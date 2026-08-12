package io.github.raindancer118.revolver;

import java.time.Duration;
import java.time.Instant;

/**
 * Thrown when every chamber in the drum is currently locked by a rate limit.
 */
public final class RevolverEmptyException extends Exception {

    private final Instant nextRelease;

    public RevolverEmptyException(Instant nextRelease) {
        super(buildMessage(nextRelease));
        this.nextRelease = nextRelease;
    }

    /**
     * @return the point in time at which the next chamber becomes available again
     */
    public Instant getNextRelease() {
        return nextRelease;
    }

    private static String buildMessage(Instant nextRelease) {
        Duration remaining = Duration.between(Instant.now(), nextRelease);
        return "revolver: all chambers exhausted — next release at " + nextRelease
                + " (in " + remaining.toSeconds() + "s)";
    }
}
