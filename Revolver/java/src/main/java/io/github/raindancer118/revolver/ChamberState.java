package io.github.raindancer118.revolver;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Serializable snapshot of a chamber's state, used by {@link Persister} implementations.
 */
public final class ChamberState {

    private final String key;
    private final Instant lockedUntil;
    private final long usages;
    private final long successes;
    private final long errors;
    private final long rateLimitHits;
    private final long sessionUsages;
    private final double capacityEma;
    private final int observations;

    @JsonCreator
    public ChamberState(
            @JsonProperty("key") String key,
            @JsonProperty("lockedUntil") Instant lockedUntil,
            @JsonProperty("usages") long usages,
            @JsonProperty("successes") long successes,
            @JsonProperty("errors") long errors,
            @JsonProperty("rateLimitHits") long rateLimitHits,
            @JsonProperty("sessionUsages") long sessionUsages,
            @JsonProperty("capacityEma") double capacityEma,
            @JsonProperty("observations") int observations
    ) {
        this.key = key;
        this.lockedUntil = lockedUntil;
        this.usages = usages;
        this.successes = successes;
        this.errors = errors;
        this.rateLimitHits = rateLimitHits;
        this.sessionUsages = sessionUsages;
        this.capacityEma = capacityEma;
        this.observations = observations;
    }

    public String getKey() {
        return key;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public long getUsages() {
        return usages;
    }

    public long getSuccesses() {
        return successes;
    }

    public long getErrors() {
        return errors;
    }

    public long getRateLimitHits() {
        return rateLimitHits;
    }

    public long getSessionUsages() {
        return sessionUsages;
    }

    public double getCapacityEma() {
        return capacityEma;
    }

    public int getObservations() {
        return observations;
    }
}
