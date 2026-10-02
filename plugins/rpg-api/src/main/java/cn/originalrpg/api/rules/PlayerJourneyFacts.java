package cn.originalrpg.api.rules;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;

/**
 * Cross-plugin, player-owned facts that are observed by more than one gameplay
 * module. These are evidence only; the owning systems remain authoritative for
 * journey rewards and land claims.
 */
public final class PlayerJourneyFacts {
    public static final NamespacedKey WILDERNESS_ARRIVED = Objects.requireNonNull(
            NamespacedKey.fromString("rpghub:wilderness_arrived"));

    private PlayerJourneyFacts() {
    }

    public static boolean hasWildernessArrival(Player player) {
        return player != null && player.getPersistentDataContainer().has(
                WILDERNESS_ARRIVED, PersistentDataType.BYTE);
    }

    /** @return true only when this call recorded the player's first arrival. */
    public static boolean recordWildernessArrival(Player player) {
        if (hasWildernessArrival(player)) return false;
        player.getPersistentDataContainer().set(
                WILDERNESS_ARRIVED, PersistentDataType.BYTE, (byte) 1);
        return true;
    }
}
