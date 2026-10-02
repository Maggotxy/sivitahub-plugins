package cn.originalrpg.economy;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import cn.originalrpg.api.ui.TrustedMenuHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

final class BlackMarketMenu implements TrustedMenuHolder {
    private final Inventory inventory;
    private final Map<Integer, String> offers = new HashMap<>();

    BlackMarketMenu(String title) {
        inventory = Bukkit.createInventory(this, 45, Component.text(title));
    }

    void set(int slot, String offerId, ItemStack item) {
        inventory.setItem(slot, item);
        offers.put(slot, offerId);
    }

    String offerAt(int slot) {
        return offers.get(slot);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
