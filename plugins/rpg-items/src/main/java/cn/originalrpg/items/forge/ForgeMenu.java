package cn.originalrpg.items.forge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import cn.originalrpg.api.ui.TrustedMenuHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Set;
import java.util.UUID;

final class ForgeMenu implements TrustedMenuHolder {
    static final int BASE_SLOT = 10;
    static final int CORE_SLOT = 12;
    static final int CATALYST_SLOT = 14;
    static final int IMPRINT_SLOT = 22;
    static final int BLUEPRINT_SLOT = 24;
    static final int RESULT_SLOT = 16;
    static final Set<Integer> INPUT_SLOTS = Set.of(
            BASE_SLOT, CORE_SLOT, CATALYST_SLOT, IMPRINT_SLOT, BLUEPRINT_SLOT);

    private final Inventory inventory;
    private final UUID ownerId;

    ForgeMenu(UUID ownerId, String title, String instruction) {
        this.ownerId = ownerId;
        inventory = Bukkit.createInventory(this, 27, Component.text(title, NamedTextColor.DARK_GRAY));
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!INPUT_SLOTS.contains(slot) && slot != RESULT_SLOT) {
                inventory.setItem(slot, filler);
            }
        }
        inventory.setItem(RESULT_SLOT, named(Material.ANVIL, instruction));
    }

    UUID ownerId() {
        return ownerId;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private static ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GRAY));
        item.setItemMeta(meta);
        return item;
    }
}
