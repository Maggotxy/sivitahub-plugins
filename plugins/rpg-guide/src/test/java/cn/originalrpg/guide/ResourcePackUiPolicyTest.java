package cn.originalrpg.guide;

import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackUiPolicyTest {
    @Test
    void enablesCustomVisualsOnlyAfterSuccessfulLoad() {
        assertTrue(ResourcePackUiPolicy.customVisuals(
                PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
        for (PlayerResourcePackStatusEvent.Status status : PlayerResourcePackStatusEvent.Status.values()) {
            if (status != PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
                assertFalse(ResourcePackUiPolicy.customVisuals(status), () -> "unexpected visuals for " + status);
            }
        }
        assertFalse(ResourcePackUiPolicy.customVisuals(null));
    }
}
