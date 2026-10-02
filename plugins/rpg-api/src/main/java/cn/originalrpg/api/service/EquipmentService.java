package cn.originalrpg.api.service;

import cn.originalrpg.api.model.EquipmentSnapshot;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;

public interface EquipmentService {
    boolean isCustomEquipment(ItemStack item);

    boolean isAuthentic(ItemStack item);

    boolean isBound(ItemStack item);

    boolean isTradeUnlocked(ItemStack item);

    Optional<UUID> itemId(ItemStack item);

    Optional<EquipmentSnapshot> inspect(ItemStack item);

    boolean unlockForTrade(ItemStack item, UUID expectedOwner);

    void sign(ItemStack item);
}
