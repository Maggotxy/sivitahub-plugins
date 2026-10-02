package cn.originalrpg.economy;

import cn.originalrpg.api.model.ActivityRewardRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomicActivityAsyncLifecycleContractTest {
    @Test
    void asynchronousFeedbackUsesSafeSchedulingAndTheExactLoginSession() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-economy/src/main/java/cn/originalrpg/economy/EconomicActivityListener.java"),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("Bukkit.getPlayer(player.getUniqueId()) == player"));
        assertTrue(source.contains("commissionEvidence.grant(player, source).whenComplete((result, error) ->\n"
                + "                sync("));
        assertTrue(source.contains(".whenComplete((result, error) -> sync("));
        assertFalse(source.contains("whenComplete((result, error) -> Bukkit.getScheduler().runTask"));
        assertTrue(source.contains("if (shuttingDown || !plugin.isEnabled()) return;"));
        assertTrue(source.contains("catch (IllegalStateException ignored)"));
    }

    @Test
    void survivalEssenceUsesTheAtomicDailyMailboxSettlementInsteadOfDirectInventoryDelivery()
            throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-economy/src/main/java/cn/originalrpg/economy/EconomicActivityListener.java"),
                StandardCharsets.UTF_8);
        int method = source.indexOf("private void grantEssence");
        int nextMethod = source.indexOf("private void completeEssenceGrant", method);
        String body = source.substring(method, nextMethod);

        assertTrue(body.contains("item.serializeAsBytes()"));
        assertTrue(body.contains("economy.rewardDailyItem("));
        assertTrue(body.contains("whenComplete((result, error) -> sync("));
        assertFalse(body.contains("player.getInventory().addItem"));
        assertFalse(body.contains("dropItemNaturally"));
        assertFalse(body.contains("playerData.set(essenceCount"));
    }

    @Test
    void shutdownPlanReplaysInFlightAndPendingRewardsWithOriginalIdempotencyKeys() {
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        ActivityRewardRequest inFlight = reward("hunt:in-flight");
        ActivityRewardRequest pendingOne = reward("hunt:pending-1");
        ActivityRewardRequest pendingTwo = reward("hunt:pending-2");
        ActivityRewardRequest pendingThree = reward("hunt:pending-3");
        Map<UUID, List<ActivityRewardRequest>> inFlightBatches = Map.of(firstPlayer, List.of(inFlight));
        Map<UUID, ArrayDeque<ActivityRewardRequest>> pending = new HashMap<>();
        pending.put(firstPlayer, new ArrayDeque<>(List.of(pendingOne, pendingTwo, pendingThree)));
        pending.put(secondPlayer, new ArrayDeque<>());

        List<EconomicActivityListener.ShutdownBatch> plan = EconomicActivityListener.shutdownPlan(
                pending, inFlightBatches, 2);

        assertEquals(3, plan.size());
        assertEquals(List.of("hunt:in-flight", "hunt:pending-1", "hunt:pending-2", "hunt:pending-3"),
                plan.stream().filter(batch -> batch.playerId().equals(firstPlayer))
                        .flatMap(batch -> batch.rewards().stream())
                        .map(ActivityRewardRequest::idempotencyKey).toList());
        assertTrue(plan.stream().noneMatch(batch -> batch.playerId().equals(secondPlayer)));
        assertEquals(3, pending.get(firstPlayer).size(), "planning must not mutate the live queue");
    }

    @Test
    void shutdownPlanFreezesBatchesAndRejectsAnInvalidBound() {
        UUID playerId = UUID.randomUUID();
        ArrayDeque<ActivityRewardRequest> queue = new ArrayDeque<>(List.of(reward("hunt:one")));
        List<EconomicActivityListener.ShutdownBatch> plan = EconomicActivityListener.shutdownPlan(
                Map.of(playerId, queue), Map.of(), 64);

        queue.add(reward("hunt:late"));
        assertEquals(List.of("hunt:one"), plan.getFirst().rewards().stream()
                .map(ActivityRewardRequest::idempotencyKey).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getFirst().rewards().add(reward("hunt:mutation")));
        assertThrows(IllegalArgumentException.class,
                () -> EconomicActivityListener.shutdownPlan(Map.of(), Map.of(), 0));
    }

    private static ActivityRewardRequest reward(String key) {
        return new ActivityRewardRequest(new BigDecimal("10.00"), 1L, key, "test");
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
