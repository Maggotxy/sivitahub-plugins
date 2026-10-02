package cn.originalrpg.guide;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * Keeps optional UI decoration behind the client's confirmed resource-pack
 * state. A menu can be opened while the required pack is still downloading,
 * so merely having sent a pack is not sufficient.
 */
final class ResourcePackUiPolicy {
    private ResourcePackUiPolicy() {
    }

    static boolean customVisuals(boolean enabled, Player player) {
        return enabled && customVisuals(player.getResourcePackStatus());
    }

    static boolean customVisuals(PlayerResourcePackStatusEvent.Status status) {
        return status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
    }
}
