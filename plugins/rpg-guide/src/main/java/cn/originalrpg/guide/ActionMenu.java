package cn.originalrpg.guide;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import cn.originalrpg.api.ui.TrustedMenuHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

final class ActionMenu implements TrustedMenuHolder {
    private final Inventory inventory;
    private final Map<Integer, Actions> actions = new HashMap<>();

    ActionMenu(int size, String title) {
        inventory = Bukkit.createInventory(this, size, Component.text(title));
        decorateFrame(size);
    }

    /**
     * A restrained one-colour frame gives every guide surface the same visual
     * hierarchy without relying on GUI-scale-sensitive negative-space fonts.
     * Real actions overwrite these slots, so semantic layout remains the owner.
     */
    private void decorateFrame(int size) {
        if (size < 18 || size % 9 != 0) return;
        ItemStack frame = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        var meta = frame.getItemMeta();
        meta.displayName(Component.text(" ", NamedTextColor.BLACK)
                .decoration(TextDecoration.ITALIC, false));
        frame.setItemMeta(meta);
        int rows = size / 9;
        for (int slot = 0; slot < size; slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row == rows - 1 || column == 0 || column == 8) {
                inventory.setItem(slot, frame);
            }
        }
    }

    void set(int slot, ItemStack item, String action) {
        set(slot, item, action, null);
    }

    void set(int slot, ItemStack item, String primaryAction, String alternateAction) {
        inventory.setItem(slot, item);
        if (primaryAction != null) actions.put(slot, new Actions(primaryAction,
                primaryAction.equals(alternateAction) ? null : alternateAction));
    }

    String actionAt(int slot, boolean alternate) {
        Actions pair = actions.get(slot);
        if (pair == null) return null;
        return alternate && pair.alternate() != null ? pair.alternate() : pair.primary();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private record Actions(String primary, String alternate) {
    }
}
