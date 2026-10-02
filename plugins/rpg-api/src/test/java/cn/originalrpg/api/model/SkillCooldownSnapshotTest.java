package cn.originalrpg.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCooldownSnapshotTest {
    @Test
    void roundsPositiveCooldownUpToWholeSeconds() {
        SkillCooldownSnapshot snapshot = new SkillCooldownSnapshot("fireball", 2_001L);

        assertEquals(2L, snapshot.remainingSeconds(1_000L));
        assertFalse(snapshot.ready(1_000L));
        assertEquals(0L, snapshot.remainingSeconds(2_001L));
        assertTrue(snapshot.ready(2_001L));
    }

    @Test
    void rejectsInvalidState() {
        assertThrows(IllegalArgumentException.class, () -> new SkillCooldownSnapshot("", 0L));
        assertThrows(IllegalArgumentException.class, () -> new SkillCooldownSnapshot("heal", -1L));
        assertThrows(IllegalArgumentException.class,
                () -> new SkillHudSnapshot(java.util.List.of(), 11, 10, 0L));
    }

    @Test
    void providesAnExplicitNonCastableHudSlotSentinel() {
        SkillCooldownSnapshot empty = SkillCooldownSnapshot.empty();

        assertTrue(empty.emptySlot());
        assertEquals(SkillCooldownSnapshot.EMPTY_SLOT_ID, empty.skillId());
        assertFalse(new SkillCooldownSnapshot("heal", 0L).emptySlot());
    }
}
