package cn.originalrpg.economy;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import cn.originalrpg.api.ui.TrustedMenuHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

final class CommissionMenu implements TrustedMenuHolder {
    private final Inventory inventory;
    private final Map<Integer, String> commissionSlots;

    CommissionMenu(Map<Integer, String> commissionSlots, String title) {
        this.commissionSlots = Map.copyOf(commissionSlots);
        inventory = Bukkit.createInventory(this, 27, Component.text(title));
    }

    String commissionAt(int slot) {
        return commissionSlots.get(slot);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
