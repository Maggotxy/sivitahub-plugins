package cn.originalrpg.hub;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class HubMapLifecycleContractTest {
    @Test
    void delayedSquaremapWorldReadinessUsesABoundedCancellableRetry() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-hub/src/main/java/cn/originalrpg/hub/RpgHubPlugin.java"),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("squaremapRegistrationAttempts >= 30"));
        assertTrue(source.contains("cancelSquaremapRegistration();"));
        assertTrue(source.contains("SquaremapHubIntegration.worldReady(current)"));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
