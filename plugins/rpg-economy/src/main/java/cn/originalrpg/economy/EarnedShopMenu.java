package cn.originalrpg.economy;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import cn.originalrpg.api.ui.TrustedMenuHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

final class EarnedShopMenu implements TrustedMenuHolder {
    private final Inventory inventory;
    private final Map<Integer, String> productSlots;

    EarnedShopMenu(Map<Integer, String> productSlots, String title) {
        this.productSlots = Map.copyOf(productSlots);
        inventory = Bukkit.createInventory(this, 27, Component.text(title));
    }

    String productAt(int slot) {
        return productSlots.get(slot);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
