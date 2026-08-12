package io.github.raindancer118.revolver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RevolverTest {

    @Nested
    @DisplayName("Loading the drum")
    class Loading {

        @Test
        @DisplayName("should reject a null key list")
        void should_rejectNullKeys_when_loadingWithoutKeys() {
            assertThatThrownBy(() -> Revolver.builder(null).load())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject an empty key list")
        void should_rejectEmptyKeys_when_loadingWithoutKeys() {
            assertThatThrownBy(() -> Revolver.builder(List.of()).load())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Firing the drum")
    class Firing {

        @Test
        @DisplayName("should rotate through chambers in order")
        void should_rotateThroughChambers_when_firedRepeatedly() throws Exception {
            Revolver revolver = Revolver.builder(List.of("key1", "key2", "key3")).load();

            List<String> expected = List.of("key1", "key2", "key3", "key1", "key2", "key3");
            for (String key : expected) {
                Cartridge cartridge = revolver.fire();
                assertThat(cartridge.key()).isEqualTo(key);
            }
        }

        @Test
        @DisplayName("should skip a locked chamber")
        void should_skipLockedChamber_when_chamberIsRateLimited() throws Exception {
            Revolver revolver = Revolver.builder(List.of("key1", "key2", "key3")).load();

            Cartridge first = revolver.fire();
            assertThat(first.key()).isEqualTo("key1");
            first.rateLimit(Instant.now().plus(Duration.ofHours(1)));

            Cartridge next = revolver.fire();

            assertThat(next.key()).isNotEqualTo("key1");
        }

        @Test
        @DisplayName("should throw when all chambers are locked")
        void should_throwRevolverEmptyException_when_allChambersLocked() throws Exception {
            Revolver revolver = Revolver.builder(List.of("key1", "key2")).load();

            Cartridge first = revolver.fire();
            Cartridge second = revolver.fire();
            Instant lockUntil = Instant.now().plus(Duration.ofMinutes(5));
            first.rateLimit(lockUntil);
            second.rateLimit(lockUntil);

            assertThatThrownBy(revolver::fire)
                    .isInstanceOf(RevolverEmptyException.class)
                    .satisfies(exception -> assertThat(((RevolverEmptyException) exception).getNextRelease())
                            .isNotNull());
        }

        @Test
        @DisplayName("should wait for a bounded timeout and then throw when configured to wait")
        void should_throwAfterTimeout_when_waitingForLockedChamber() throws Exception {
            Revolver revolver = Revolver.builder(List.of("key1")).waitWhenEmpty().load();

            Cartridge cartridge = revolver.fire();
            cartridge.rateLimit(Instant.now().plus(Duration.ofMinutes(10)));

            long start = System.nanoTime();
            assertThatThrownBy(() -> revolver.fire(Duration.ofMillis(50)))
                    .isInstanceOf(RevolverEmptyException.class);
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

            assertThat(elapsedMillis).isGreaterThanOrEqualTo(40);
        }
    }

    @Nested
    @DisplayName("Availability")
    class Availability {

        @Test
        @DisplayName("should report full availability with no locked chambers")
        void should_reportFullAvailability_when_noChamberLocked() throws Exception {
            Revolver revolver = Revolver.builder(List.of("key1", "key2", "key3", "key4")).load();

            assertThat(revolver.availability()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should report half availability with half chambers locked")
        void should_reportHalfAvailability_when_halfChambersLocked() throws Exception {
            Revolver revolver = Revolver.builder(List.of("key1", "key2", "key3", "key4")).load();

            Cartridge first = revolver.fire();
            Cartridge second = revolver.fire();
            first.rateLimit(Instant.now().plus(Duration.ofHours(1)));
            second.rateLimit(Instant.now().plus(Duration.ofHours(1)));

            assertThat(revolver.availability()).isEqualTo(0.5);
        }
    }

    @Nested
    @DisplayName("Capacity estimation")
    class CapacityEstimation {

        @Test
        @DisplayName("should build a positive capacity estimate after repeated rate-limit observations")
        void should_estimatePositiveCapacity_when_rateLimitedRepeatedly() throws Exception {
            Revolver revolver = Revolver.builder(List.of("key1")).load();

            Cartridge last = null;
            for (int round = 0; round < 5; round++) {
                for (int shot = 0; shot < 5; shot++) {
                    last = revolver.fire();
                }
                last.rateLimit(Instant.now().minusNanos(1));
            }

            List<ChamberStats> status = revolver.drumStatus();
            assertThat(status).hasSize(1);
            ChamberStats stats = status.get(0);
            assertThat(stats.capacityEma()).isPositive();
            assertThat(stats.confidence()).isPositive();
        }
    }

    @Nested
    @DisplayName("Drum status")
    class DrumStatus {

        @Test
        @DisplayName("should track usages, successes and errors per chamber")
        void should_trackUsagesSuccessesAndErrors_when_cartridgesAreReported() throws Exception {
            Revolver revolver = Revolver.builder(List.of("key1", "key2")).load();

            Cartridge first = revolver.fire();
            first.success();
            Cartridge second = revolver.fire();
            second.error();

            List<ChamberStats> stats = revolver.drumStatus();

            assertThat(stats).hasSize(2);
            assertThat(stats.get(0).usages()).isEqualTo(1);
            assertThat(stats.get(0).successes()).isEqualTo(1);
            assertThat(stats.get(1).usages()).isEqualTo(1);
            assertThat(stats.get(1).errors()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Persistence")
    class Persistence {

        @Test
        @DisplayName("should restore a locked chamber's state after reloading from the same file")
        void should_restoreLockedState_when_reloadingFromSameFile(@TempDir Path tempDir) throws Exception {
            Path stateFile = tempDir.resolve("drum.json");
            Revolver first = Revolver.builder(List.of("key1", "key2")).persistTo(stateFile).load();

            Cartridge cartridge = first.fire();
            Instant lockUntil = Instant.now().plus(Duration.ofMinutes(10));
            cartridge.rateLimit(lockUntil);

            Revolver second = Revolver.builder(List.of("key1", "key2")).persistTo(stateFile).load();

            ChamberStats key1Stats = second.drumStatus().stream()
                    .filter(s -> s.key().equals("key1"))
                    .findFirst()
                    .orElseThrow();

            assertThat(key1Stats.available()).isFalse();
        }

        @Test
        @DisplayName("should not fail to load when the persistence file does not yet exist")
        void should_notFail_when_persistenceFileDoesNotExist(@TempDir Path tempDir) {
            Path stateFile = tempDir.resolve("does-not-exist.json");

            assertThatCode(() -> Revolver.builder(List.of("key1")).persistTo(stateFile).load())
                    .doesNotThrowAnyException();
        }
    }
}
