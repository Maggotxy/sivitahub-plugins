package cn.originalrpg.commerce;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import cn.originalrpg.api.ui.TrustedMenuHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

final class SupplyMenu implements TrustedMenuHolder {
    private final Inventory inventory;
    private final Map<Integer, String> actions = new HashMap<>();

    SupplyMenu(Component title) {
        inventory = Bukkit.createInventory(this, 54, title);
    }

    void set(int slot, ItemStack item, String action) {
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
    }

    String actionAt(int slot) {
        return actions.get(slot);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
