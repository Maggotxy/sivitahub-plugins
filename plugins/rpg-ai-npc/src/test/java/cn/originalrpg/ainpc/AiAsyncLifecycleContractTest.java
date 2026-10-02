package cn.originalrpg.ainpc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class AiAsyncLifecycleContractTest {
    @Test
    void clientCloseCancelsQueuedAndOutstandingRequests() throws IOException {
        String source = Files.readString(source("AiProviderClient.java"));
        assertTrue(source.contains("closed.compareAndSet(false, true)"));
        assertTrue(source.contains("executor.shutdownNow()"));
        assertTrue(source.contains("httpClient.shutdownNow()"));
        assertTrue(source.contains("for (CompletableFuture<String> request : pending)"));
    }

    @Test
    void callbackRequiresRuntimeLoginRequestAndPlayerIdentity() throws IOException {
        String source = Files.readString(source("AiNpcController.java"));
        assertTrue(source.contains("plugin.current(runtime)"));
        assertTrue(source.contains("sessions.valid(playerId, request.loginGeneration())"));
        assertTrue(source.contains("inFlight.get(playerId) == request"));
        assertTrue(source.contains("online != request.playerSession()"));
        assertTrue(source.contains("if (!scheduled) inFlight.remove(playerId, request)"));
    }

    @Test
    void optionalGameplayStateServicesAreResolvedForEachSnapshot() throws IOException {
        String source = Files.readString(source("AiNpcController.java"));
        for (String service : java.util.List.of(
                "JourneyService", "SkillService", "EquipmentService", "CombatStateService")) {
            assertTrue(source.contains("OptionalServices.enabled(plugin, " + service + ".class)"));
            assertTrue(!source.contains("private final " + service));
        }
    }

    private static Path source(String file) {
        return Path.of("src/main/java/cn/originalrpg/ainpc", file);
    }
}
