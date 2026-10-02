package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonSessionRulesTest {
    @Test
    void releasesARegisteredMemberAfterLeavingItsInstance() {
        assertTrue(DungeonSessionRules.departedRegisteredInstance(
                "rpg_instance_ruins_1", "rpg_instance_ruins_1", "minecraft_overworld"));
    }

    @Test
    void ignoresEnteringAndUnrelatedWorldChanges() {
        assertFalse(DungeonSessionRules.departedRegisteredInstance(
                null, "minecraft_overworld", "rpg_instance_ruins_1"));
        assertFalse(DungeonSessionRules.departedRegisteredInstance(
                "rpg_instance_ruins_1", "minecraft_overworld", "minecraft_the_nether"));
    }

    @Test
    void ignoresAChangeThatStillReportsTheRegisteredWorld() {
        assertFalse(DungeonSessionRules.departedRegisteredInstance(
                "rpg_instance_ruins_1", "rpg_instance_ruins_1", "rpg_instance_ruins_1"));
    }

    @Test
    void aTimeoutOnlyOwnsTheExactAssignmentGeneration() {
        UUID oldSession = UUID.randomUUID();
        UUID currentSession = UUID.randomUUID();

        assertTrue(DungeonSessionRules.isCurrentSession(currentSession, currentSession));
        assertFalse(DungeonSessionRules.isCurrentSession(currentSession, oldSession));
        assertFalse(DungeonSessionRules.isCurrentSession(null, oldSession));
        assertFalse(DungeonSessionRules.isCurrentSession(currentSession, null));
    }
}
