package cn.originalrpg.api.service;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Main-thread bridge for progressing and projecting authenticated forged equipment. */
public interface EquipmentProgressService {
    /**
     * Binds an unbound item to the player and grants experience when the item is authentic and usable by them.
     *
     * @return true when experience was applied
     */
    boolean grantUseExperience(Player player, ItemStack item, int amount);

    /**
     * Rebuilds every player-facing equipment field from its signed persistent data.
     * Mutating plugins must call this after updating and signing equipment instead of
     * editing individual lore lines themselves.
     *
     * @return true when an authentic equipment item was refreshed
     */
    boolean refreshDisplay(Player player, ItemStack item);
}
