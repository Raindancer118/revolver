package io.github.raindancer118.revolver.example;

import io.github.raindancer118.revolver.Cartridge;
import io.github.raindancer118.revolver.ChamberStats;
import io.github.raindancer118.revolver.Revolver;
import io.github.raindancer118.revolver.RevolverEmptyException;

import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Full usage example, mirroring the reference Go implementation's example.
 */
public final class RevolverExample {

    public static void main(String[] args) throws Exception {
        Revolver revolver = Revolver.builder(
                        List.of("sk-key1xxxxxxxx", "sk-key2xxxxxxxx", "sk-key3xxxxxxxx"))
                .persistTo(Path.of(".drum-state.json"))
                .load();

        for (int request = 1; request <= 15; request++) {
            Cartridge cartridge;
            try {
                cartridge = revolver.fire();
            } catch (RevolverEmptyException e) {
                System.out.printf("Drum empty! Next release: %s%n",
                        DateTimeFormatter.ISO_INSTANT.format(e.getNextRelease()));
                return;
            }

            String key = cartridge.key();
            System.out.printf("Request #%d with key ...%s | availability: %.0f%%%n",
                    request, key.substring(key.length() - 4), revolver.availability() * 100);

            ExampleApiResult result = exampleApiCall(key);

            switch (result) {
                case RATE_LIMITED -> {
                    cartridge.rateLimit(java.time.Instant.now().plusSeconds(60));
                    request--; // retry this request
                    System.out.println("  -> Rate limited! Chamber locked.");
                }
                case ERROR -> {
                    cartridge.error();
                    System.out.println("  -> Error calling API.");
                }
                case SUCCESS -> cartridge.success();
            }
        }

        System.out.println("\nDrum status:");
        for (ChamberStats stats : revolver.drumStatus()) {
            String availability = stats.available()
                    ? "OK"
                    : "LOCKED (free at " + stats.lockedUntil() + ")";
            String key = stats.key();
            System.out.printf("  Key ...%-8s %s | %d shots | capacity ~%.0f (confidence %.0f%%)%n",
                    key.substring(key.length() - 4), availability, stats.usages(),
                    stats.capacityEma(), stats.confidence() * 100);
        }
    }

    private static ExampleApiResult exampleApiCall(String key) {
        // Placeholder — the real API call would go here.
        return ExampleApiResult.SUCCESS;
    }

    private enum ExampleApiResult {
        SUCCESS, RATE_LIMITED, ERROR
    }
}
