package cn.originalrpg.api.model;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/** Transient PDC marker preventing an escrow copy from being used while its operation settles. */
public final class EquipmentOperationMarker {
    private static final NamespacedKey KEY = NamespacedKey.fromString("rpgcore:equipment_operation");

    private EquipmentOperationMarker() {
    }

    public static ItemStack markedCopy(ItemStack item, UUID operationId) {
        ItemStack marked = item.clone();
        ItemMeta meta = marked.getItemMeta();
        if (meta == null || KEY == null) throw new IllegalArgumentException("Item cannot carry an operation marker");
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, operationId.toString());
        marked.setItemMeta(meta);
        return marked;
    }

    public static void clear(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null || KEY == null) return;
        meta.getPersistentDataContainer().remove(KEY);
        item.setItemMeta(meta);
    }

    public static boolean isMarked(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && KEY != null
                && meta.getPersistentDataContainer().has(KEY, PersistentDataType.STRING);
    }
}
