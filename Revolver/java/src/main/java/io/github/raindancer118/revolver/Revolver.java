package io.github.raindancer118.revolver;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages a drum of API-key chambers, revolver-style: each chamber holds a key, and the
 * drum rotates to the next chamber after every shot. Locked (rate-limited) chambers are
 * skipped. The drum learns each chamber's capacity over time. Thread-safe.
 */
public final class Revolver {

    private final Object lock = new Object();
    private final List<Chamber> chambers;
    private final boolean waitWhenEmpty;
    private final Persister persister;
    private int position;

    private Revolver(List<Chamber> chambers, boolean waitWhenEmpty, Persister persister) {
        this.chambers = chambers;
        this.waitWhenEmpty = waitWhenEmpty;
        this.persister = persister;
    }

    public static Builder builder(List<String> keys) {
        return new Builder(keys);
    }

    /**
     * Draws the next available cartridge from the drum, rotating to the next chamber.
     *
     * <p>If every chamber is locked and the drum was not built with {@link Builder#waitWhenEmpty()},
     * throws {@link RevolverEmptyException} immediately, carrying the next release instant.
     * Otherwise blocks until a chamber frees up.
     */
    public Cartridge fire() throws RevolverEmptyException, InterruptedException {
        return fire(null);
    }

    /**
     * Like {@link #fire()}, but when waiting, gives up and throws {@link RevolverEmptyException}
     * once the given timeout elapses.
     */
    public Cartridge fire(Duration timeout) throws RevolverEmptyException, InterruptedException {
        Instant deadline = timeout == null ? null : Instant.now().plus(timeout);

        while (true) {
            Chamber drawn = null;
            Instant nextRelease = null;
            synchronized (lock) {
                FindResult result = findNextChamber();
                if (result.chamber() != null) {
                    drawn = result.chamber();
                    drawn.recordUsage();
                } else {
                    nextRelease = result.nextRelease();
                }
            }

            if (drawn != null) {
                persistState();
                return new Cartridge(drawn.getKey(), this);
            }

            if (!waitWhenEmpty) {
                throw new RevolverEmptyException(nextRelease);
            }

            Instant now = Instant.now();
            if (deadline != null && !now.isBefore(deadline)) {
                throw new RevolverEmptyException(nextRelease);
            }

            Duration waitFor = Duration.between(now, nextRelease);
            if (deadline != null) {
                Duration remaining = Duration.between(now, deadline);
                if (waitFor.compareTo(remaining) > 0) {
                    waitFor = remaining;
                }
            }
            if (!waitFor.isNegative() && !waitFor.isZero()) {
                Thread.sleep(waitFor.toMillis());
            }
        }
    }

    /**
     * @return the fraction of chambers currently usable, in [0.0, 1.0]
     */
    public double availability() {
        synchronized (lock) {
            Instant now = Instant.now();
            long free = chambers.stream().filter(c -> c.isAvailable(now)).count();
            return (double) free / chambers.size();
        }
    }

    /**
     * @return a snapshot of every chamber's statistics
     */
    public List<ChamberStats> drumStatus() {
        synchronized (lock) {
            Instant now = Instant.now();
            return chambers.stream().map(c -> c.toStats(now)).toList();
        }
    }

    void reportSuccess(String key) {
        synchronized (lock) {
            findChamber(key).ifPresent(Chamber::recordSuccess);
        }
        persistState();
    }

    void reportRateLimit(String key, Instant until) {
        synchronized (lock) {
            findChamber(key).ifPresent(c -> c.recordRateLimit(until));
        }
        persistState();
    }

    void reportError(String key) {
        synchronized (lock) {
            findChamber(key).ifPresent(Chamber::recordError);
        }
        persistState();
    }

    /** Finds the next free chamber starting at the current position. Must hold {@link #lock}. */
    private FindResult findNextChamber() {
        Instant now = Instant.now();
        Instant earliest = null;

        for (int i = 0; i < chambers.size(); i++) {
            int index = (position + i) % chambers.size();
            Chamber chamber = chambers.get(index);
            if (chamber.isAvailable(now)) {
                position = (index + 1) % chambers.size();
                return new FindResult(chamber, null);
            }
            Instant lockedUntil = chamber.getLockedUntil();
            if (earliest == null || lockedUntil.isBefore(earliest)) {
                earliest = lockedUntil;
            }
        }
        return new FindResult(null, earliest);
    }

    private java.util.Optional<Chamber> findChamber(String key) {
        return chambers.stream().filter(c -> c.getKey().equals(key)).findFirst();
    }

    private void persistState() {
        if (persister == null) {
            return;
        }
        List<ChamberState> states;
        synchronized (lock) {
            states = chambers.stream().map(Chamber::toState).toList();
        }
        try {
            persister.save(states);
        } catch (IOException ignored) {
            // best-effort persistence, mirrors the reference implementation
        }
    }

    private void restoreFromPersister() throws IOException {
        if (persister == null) {
            return;
        }
        List<ChamberState> states = persister.load();
        Map<String, ChamberState> byKey = states.stream()
                .collect(Collectors.toMap(ChamberState::getKey, s -> s));
        for (Chamber chamber : chambers) {
            ChamberState state = byKey.get(chamber.getKey());
            if (state != null) {
                chamber.restoreFrom(state);
            }
        }
    }

    private record FindResult(Chamber chamber, Instant nextRelease) {
    }

    /**
     * Builds and loads a {@link Revolver} with the given API keys.
     */
    public static final class Builder {

        private final List<String> keys;
        private boolean waitWhenEmpty;
        private Persister persister;

        private Builder(List<String> keys) {
            this.keys = keys;
        }

        /**
         * Makes {@link Revolver#fire()} block instead of throwing when every chamber is locked.
         */
        public Builder waitWhenEmpty() {
            this.waitWhenEmpty = true;
            return this;
        }

        /**
         * Enables file-based state persistence at the given path.
         */
        public Builder persistTo(Path path) {
            this.persister = new FilePersister(path);
            return this;
        }

        /**
         * Sets a custom storage backend.
         */
        public Builder persister(Persister persister) {
            this.persister = persister;
            return this;
        }

        /**
         * Creates the revolver and loads the drum with the configured keys, restoring any
         * previously persisted state.
         */
        public Revolver load() throws IOException {
            if (keys == null || keys.isEmpty()) {
                throw new IllegalArgumentException("revolver: at least one key is required");
            }
            List<Chamber> chambers = keys.stream().map(Chamber::new).toList();
            Revolver revolver = new Revolver(chambers, waitWhenEmpty, persister);
            revolver.restoreFromPersister();
            return revolver;
        }
    }
}
