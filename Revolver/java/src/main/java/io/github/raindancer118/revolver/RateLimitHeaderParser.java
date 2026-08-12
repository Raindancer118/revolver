package io.github.raindancer118.revolver;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Extracts the lock-until instant from common HTTP response headers.
 *
 * <p>Headers are checked in this order:
 * <ul>
 *   <li>{@code Retry-After} (seconds or HTTP date)</li>
 *   <li>{@code X-RateLimit-Reset} (Unix timestamp)</li>
 *   <li>{@code RateLimit-Reset} (Unix timestamp, IETF draft)</li>
 * </ul>
 */
public final class RateLimitHeaderParser {

    private static final List<String> RESET_HEADERS = List.of("X-RateLimit-Reset", "RateLimit-Reset");

    private RateLimitHeaderParser() {
    }

    /**
     * @return the lock-until instant if a recognized header was found, otherwise empty
     */
    public static Optional<Instant> parse(HttpHeaders headers) {
        Optional<String> retryAfter = headers.firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            Optional<Instant> parsed = parseRetryAfter(retryAfter.get());
            if (parsed.isPresent()) {
                return parsed;
            }
        }

        for (String header : RESET_HEADERS) {
            Optional<String> value = headers.firstValue(header);
            if (value.isPresent()) {
                Optional<Instant> parsed = parseUnixTimestamp(value.get());
                if (parsed.isPresent()) {
                    return parsed;
                }
            }
        }

        return Optional.empty();
    }

    private static Optional<Instant> parseRetryAfter(String value) {
        try {
            long seconds = Long.parseLong(value.trim());
            return Optional.of(Instant.now().plusSeconds(seconds));
        } catch (NumberFormatException ignored) {
            // fall through to HTTP-date parsing
        }
        try {
            return Optional.of(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Instant> parseUnixTimestamp(String value) {
        try {
            return Optional.of(Instant.ofEpochSecond(Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
