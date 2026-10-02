package cn.originalrpg.mobs;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MobRewardReliabilityRulesTest {
    @Test
    void capacityIsBoundedGloballyAndPerPlayer() {
        assertTrue(MobRewardReliabilityRules.hasCapacity(31, 3, 32, 4));
        assertFalse(MobRewardReliabilityRules.hasCapacity(32, 3, 32, 4));
        assertFalse(MobRewardReliabilityRules.hasCapacity(10, 4, 32, 4));
    }

    @Test
    void retryStopsAtAttemptAndLifetimeBoundaries() {
        assertTrue(MobRewardReliabilityRules.mayRetry(2, 3, 1_000L, 1_999L, 1_000L));
        assertFalse(MobRewardReliabilityRules.mayRetry(3, 3, 1_000L, 1_999L, 1_000L));
        assertFalse(MobRewardReliabilityRules.mayRetry(1, 3, 1_000L, 2_000L, 1_000L));
        assertTrue(MobRewardReliabilityRules.expired(2_000L, 1_999L, 1_000L));
    }

    @Test
    void retryBackoffIsExponentialAndSaturatesWithoutOverflow() {
        assertEquals(1_000L, MobRewardReliabilityRules.retryDelayMillis(1, 1_000L, 10_000L));
        assertEquals(2_000L, MobRewardReliabilityRules.retryDelayMillis(2, 1_000L, 10_000L));
        assertEquals(8_000L, MobRewardReliabilityRules.retryDelayMillis(4, 1_000L, 10_000L));
        assertEquals(10_000L, MobRewardReliabilityRules.retryDelayMillis(5, 1_000L, 10_000L));
        assertEquals(Long.MAX_VALUE, MobRewardReliabilityRules.retryDelayMillis(
                Integer.MAX_VALUE, 1_000L, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> MobRewardReliabilityRules.retryDelayMillis(0, 1_000L, 10_000L));
        assertThrows(IllegalArgumentException.class,
                () -> MobRewardReliabilityRules.retryDelayMillis(1, 10_000L, 1_000L));
    }

    @Test
    void mailboxIdsAreStablePerKillAndDistinctPerRewardKind() {
        UUID killId = UUID.fromString("637f1977-2702-457c-88a6-2f2c03b06c0d");
        UUID material = MobRewardReliabilityRules.deliveryId(killId, "material");
        assertEquals(material, MobRewardReliabilityRules.deliveryId(killId, "material"));
        assertNotEquals(material, MobRewardReliabilityRules.deliveryId(killId, "skill-book"));
        assertNotEquals(material, MobRewardReliabilityRules.deliveryId(UUID.randomUUID(), "material"));
    }

    @Test
    void killIdFreezesPlayerEntityAndDeathInstant() {
        UUID player = UUID.fromString("38400000-8cf0-11bd-b23e-10b96e4ef00d");
        UUID entity = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");
        UUID first = MobRewardReliabilityRules.killId(player, entity, 1_723_456_789L);
        assertEquals(first, MobRewardReliabilityRules.killId(player, entity, 1_723_456_789L));
        assertNotEquals(first, MobRewardReliabilityRules.killId(player, entity, 1_723_456_790L));
        assertNotEquals(first, MobRewardReliabilityRules.killId(player, UUID.randomUUID(), 1_723_456_789L));
    }
}
