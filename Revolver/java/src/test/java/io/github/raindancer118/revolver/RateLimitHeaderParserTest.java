package io.github.raindancer118.revolver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitHeaderParserTest {

    private static HttpHeaders headersOf(Map<String, List<String>> map) {
        return HttpHeaders.of(map, (name, value) -> true);
    }

    @Test
    @DisplayName("should parse a Retry-After header given in seconds")
    void should_parseRetryAfterSeconds_when_headerIsNumeric() {
        HttpHeaders headers = headersOf(Map.of("Retry-After", List.of("60")));

        Optional<Instant> result = RateLimitHeaderParser.parse(headers);

        assertThat(result).isPresent();
        assertThat(result.get()).isBetween(
                Instant.now().plus(Duration.ofSeconds(59)),
                Instant.now().plus(Duration.ofSeconds(61)));
    }

    @Test
    @DisplayName("should parse an X-RateLimit-Reset header as a Unix timestamp")
    void should_parseXRateLimitReset_when_headerIsUnixTimestamp() {
        Instant future = Instant.now().plus(Duration.ofMinutes(5));
        HttpHeaders headers = headersOf(Map.of("X-RateLimit-Reset", List.of(String.valueOf(future.getEpochSecond()))));

        Optional<Instant> result = RateLimitHeaderParser.parse(headers);

        assertThat(result).isPresent();
        assertThat(result.get().getEpochSecond()).isEqualTo(future.getEpochSecond());
    }

    @Test
    @DisplayName("should parse a RateLimit-Reset header as a Unix timestamp")
    void should_parseRateLimitReset_when_headerIsUnixTimestamp() {
        Instant future = Instant.now().plus(Duration.ofMinutes(2));
        HttpHeaders headers = headersOf(Map.of("RateLimit-Reset", List.of(String.valueOf(future.getEpochSecond()))));

        Optional<Instant> result = RateLimitHeaderParser.parse(headers);

        assertThat(result).isPresent();
        assertThat(result.get().getEpochSecond()).isEqualTo(future.getEpochSecond());
    }

    @Test
    @DisplayName("should return empty when no rate-limit header is present")
    void should_returnEmpty_when_noRateLimitHeaderPresent() {
        HttpHeaders headers = headersOf(Map.of());

        Optional<Instant> result = RateLimitHeaderParser.parse(headers);

        assertThat(result).isEmpty();
    }
}
