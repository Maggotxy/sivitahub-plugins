package cn.originalrpg.classes;

import cn.originalrpg.api.model.AscensionResult;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import cn.originalrpg.api.ui.TrustedMenuHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

final class ClassMenu implements TrustedMenuHolder {
    private final Inventory inventory;
    private final Map<Integer, String> actions = new HashMap<>();
    private Map<Integer, String> selectedBranches = Map.of();
    private AscensionResult ascension;

    ClassMenu(String title) {
        inventory = Bukkit.createInventory(this, 54, Component.text(title));
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

    void selectedBranches(Map<Integer, String> selectedBranches) {
        this.selectedBranches = Map.copyOf(selectedBranches);
    }

    Map<Integer, String> selectedBranches() {
        return selectedBranches;
    }

    void ascension(AscensionResult ascension) {
        this.ascension = ascension;
    }

    AscensionResult ascension() {
        return ascension;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
