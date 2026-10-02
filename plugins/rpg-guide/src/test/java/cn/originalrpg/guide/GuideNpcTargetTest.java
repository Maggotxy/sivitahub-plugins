package cn.originalrpg.guide;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GuideNpcTargetTest {
    @Test
    void everyVisibleNpcHasUniqueTrustedVisitAndWalkingActions() {
        assertEquals(11, GuideNpcTarget.ordered().size());
        assertEquals(GuideNpcTarget.ordered().size(), new HashSet<>(
                GuideNpcTarget.ordered().stream().map(GuideNpcTarget::id).toList()).size());
        for (GuideNpcTarget target : GuideNpcTarget.ordered()) {
            assertEquals("command:hubfind " + target.id(), target.visitAction());
            assertEquals("/hubfind " + target.id(), target.navigationCommand());
            assertNotEquals(org.bukkit.Material.AIR, target.icon());
        }
    }
}
