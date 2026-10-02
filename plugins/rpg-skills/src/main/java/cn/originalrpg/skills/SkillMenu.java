package cn.originalrpg.skills;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import cn.originalrpg.api.ui.TrustedMenuHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

final class SkillMenu implements TrustedMenuHolder {
    private final Inventory inventory;
    private final int page;
    private final Map<Integer, String> actions = new HashMap<>();

    SkillMenu(String title, int page) {
        this.page = page;
        this.inventory = Bukkit.createInventory(this, 54, Component.text(title));
    }

    void set(int slot, ItemStack item, String action) {
        inventory.setItem(slot, item);
        if (action == null) actions.remove(slot);
        else actions.put(slot, action);
    }

    void clear() {
        inventory.clear();
        actions.clear();
    }

    String actionAt(int slot) {
        return actions.get(slot);
    }

    int page() {
        return page;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
