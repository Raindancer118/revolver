package io.github.raindancer118.revolver;

import java.net.http.HttpHeaders;
import java.time.Instant;

/**
 * A key drawn from the drum. After using it, the outcome must be reported via
 * {@link #success()}, {@link #rateLimit(Instant)} or {@link #error()} so the drum can learn.
 */
public final class Cartridge {

    private final String key;
    private final Revolver revolver;

    Cartridge(String key, Revolver revolver) {
        this.key = key;
        this.revolver = revolver;
    }

    /**
     * @return the API key value held by this cartridge
     */
    public String key() {
        return key;
    }

    /**
     * Reports a successful API request, improving this chamber's capacity estimate.
     */
    public void success() {
        revolver.reportSuccess(key);
    }

    /**
     * Reports that this chamber has hit its rate limit, locking it until the given instant.
     * The chamber is skipped by {@link Revolver#fire()} until then.
     */
    public void rateLimit(Instant until) {
        revolver.reportRateLimit(key, until);
    }

    /**
     * Reads the lock-until instant from the given HTTP response headers and reports the
     * rate limit accordingly. Falls back to a 60-second lock if no usable header is present.
     */
    public void rateLimitFromHeaders(HttpHeaders headers) {
        Instant until = RateLimitHeaderParser.parse(headers).orElse(Instant.now().plusSeconds(60));
        rateLimit(until);
    }

    /**
     * Reports a non-rate-limit error for this chamber.
     */
    public void error() {
        revolver.reportError(key);
    }
}
