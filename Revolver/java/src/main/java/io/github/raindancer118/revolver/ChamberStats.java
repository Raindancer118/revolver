package io.github.raindancer118.revolver;

import java.time.Instant;

/**
 * Snapshot of a single chamber's statistics.
 *
 * @param key             the API key held by this chamber
 * @param available       whether the chamber is currently usable
 * @param lockedUntil      when the rate-limit lock expires, or {@code null} if not locked
 * @param usages          total number of shots fired from this chamber
 * @param successes       number of explicitly reported successful requests
 * @param errors          number of reported non-rate-limit errors
 * @param rateLimitHits   number of rate-limit locks observed so far
 * @param capacityEma     estimated requests per rate-limit window (0 if not yet observed)
 * @param confidence      how reliable the capacity estimate is, in [0, 1]
 */
public record ChamberStats(
        String key,
        boolean available,
        Instant lockedUntil,
        long usages,
        long successes,
        long errors,
        long rateLimitHits,
        double capacityEma,
        double confidence
) {
}
