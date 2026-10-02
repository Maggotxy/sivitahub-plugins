package cn.originalrpg.enhancement;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class EnhancementFallbackContractTest {
    @Test
    void missingConfigurationCannotSilentlyRestoreLegacyDiscounts() throws Exception {
        Path sourceRoot = repositoryRoot().resolve(
                "plugins/rpg-enhancement/src/main/java/cn/originalrpg/enhancement");
        String refine = Files.readString(sourceRoot.resolve("RefineCommand.java"));
        String limitBreak = Files.readString(sourceRoot.resolve("LimitBreakCommand.java"));
        String mutation = Files.readString(sourceRoot.resolve("MutationCommand.java"));

        assertTrue(refine.contains("getString(\"refinement.price\", \"400.00\")"));
        assertTrue(limitBreak.contains("getString(\"limit-break.base-price\", \"1200.00\")"));
        assertTrue(mutation.contains("configuredPrice(skill, \"price\", \"3500.00\")"));
        assertTrue(mutation.contains("configuredPrice(skill, \"restore-price\", \"900.00\")"));
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
