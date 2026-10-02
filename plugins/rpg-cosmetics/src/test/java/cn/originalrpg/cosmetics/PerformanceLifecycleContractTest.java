package cn.originalrpg.cosmetics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PerformanceLifecycleContractTest {
    @Test
    void optionalLoadMonitorIsResolvedAtRenderTimeAndFailsClosed() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/cn/originalrpg/cosmetics/RpgCosmeticsPlugin.java"));

        assertTrue(source.contains(
                "OptionalServices.enabled(this, PerformanceService.class)"));
        assertTrue(source.contains(
                "performance == null ? ServerLoadState.CRITICAL : performance.state()"));
        assertFalse(source.contains("private PerformanceService"));
        assertFalse(source.contains("load(PerformanceService.class)"));
    }
}
