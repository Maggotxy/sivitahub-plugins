package cn.originalrpg.market;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import cn.originalrpg.api.ui.TrustedMenuHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class MarketBrowserMenu implements TrustedMenuHolder {
    private final Inventory inventory;
    private final Map<Integer, UUID> listings = new HashMap<>();
    private final Map<Integer, String> actions = new HashMap<>();
    private final boolean ownerView;
    private final int page;
    private final UUID sellerFilter;

    MarketBrowserMenu(String title, boolean ownerView, int page) {
        this(title, ownerView, page, null);
    }

    MarketBrowserMenu(String title, boolean ownerView, int page, UUID sellerFilter) {
        inventory = Bukkit.createInventory(this, 54, Component.text(title));
        this.ownerView = ownerView;
        this.page = page;
        this.sellerFilter = sellerFilter;
    }

    void setListing(int slot, UUID listingId, ItemStack item) {
        inventory.setItem(slot, item);
        listings.put(slot, listingId);
    }

    void setDisplay(int slot, ItemStack item) {
        inventory.setItem(slot, item);
    }

    void setAction(int slot, String action, ItemStack item) {
        inventory.setItem(slot, item);
        actions.put(slot, action);
    }

    UUID listingAt(int slot) {
        return listings.get(slot);
    }

    String actionAt(int slot) {
        return actions.get(slot);
    }

    boolean ownerView() {
        return ownerView;
    }

    int page() {
        return page;
    }

    UUID sellerFilter() {
        return sellerFilter;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
