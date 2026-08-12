package io.github.raindancer118.revolver;

import java.time.Instant;

/**
 * A single chamber of the drum, holding one API key and its usage statistics.
 * Not thread-safe on its own — callers must synchronize externally (see {@link Revolver}).
 */
final class Chamber {

    /** Weight given to a new observation in the capacity EMA (30% new, 70% history). */
    private static final double EMA_ALPHA = 0.3;

    /** Number of observations after which the capacity estimate is considered fully reliable. */
    private static final int CONFIDENCE_SATURATION = 10;

    private final String key;
    private Instant lockedUntil;

    private long usages;
    private long successes;
    private long errors;
    private long rateLimitHits;

    /** Usages since the last rate limit, used to estimate capacity per window. */
    private long sessionUsages;

    /** Exponentially weighted average of usages per rate-limit window. Zero means no observation yet. */
    private double capacityEma;

    /** Number of rate-limit observations folded into {@link #capacityEma} so far. */
    private int observations;

    Chamber(String key) {
        this.key = key;
    }

    String getKey() {
        return key;
    }

    boolean isAvailable(Instant now) {
        return lockedUntil == null || lockedUntil.isBefore(now);
    }

    Instant getLockedUntil() {
        return lockedUntil;
    }

    void recordUsage() {
        usages++;
        sessionUsages++;
    }

    void recordSuccess() {
        successes++;
    }

    void recordError() {
        errors++;
    }

    void recordRateLimit(Instant until) {
        lockedUntil = until;
        rateLimitHits++;

        if (sessionUsages > 0) {
            double sample = sessionUsages;
            capacityEma = capacityEma == 0 ? sample : EMA_ALPHA * sample + (1 - EMA_ALPHA) * capacityEma;
            observations++;
            sessionUsages = 0;
        }
    }

    /**
     * @return the reliability of the capacity estimate, in [0, 1], saturating at
     * {@link #CONFIDENCE_SATURATION} observations
     */
    double confidence() {
        if (observations == 0) {
            return 0;
        }
        return Math.min(1.0, (double) observations / CONFIDENCE_SATURATION);
    }

    ChamberStats toStats(Instant now) {
        return new ChamberStats(key, isAvailable(now), lockedUntil, usages, successes, errors,
                rateLimitHits, capacityEma, confidence());
    }

    ChamberState toState() {
        return new ChamberState(key, lockedUntil, usages, successes, errors, rateLimitHits,
                sessionUsages, capacityEma, observations);
    }

    void restoreFrom(ChamberState state) {
        this.lockedUntil = state.getLockedUntil();
        this.usages = state.getUsages();
        this.successes = state.getSuccesses();
        this.errors = state.getErrors();
        this.rateLimitHits = state.getRateLimitHits();
        this.sessionUsages = state.getSessionUsages();
        this.capacityEma = state.getCapacityEma();
        this.observations = state.getObservations();
    }
}
