package cn.originalrpg.enhancement;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import cn.originalrpg.api.ui.TrustedMenuHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

final class RefineMenu implements TrustedMenuHolder {
    static final int INFO_SLOT = 22;
    static final int SAFE_SLOT = 29;
    static final int CONFIRM_SLOT = 31;
    static final int LUCKY_SLOT = 33;
    static final int CLOSE_SLOT = 40;

    private final Inventory inventory;
    private final UUID ownerId;
    private final String itemId;
    private int selectedSlot;
    private boolean safe;
    private boolean lucky;

    RefineMenu(UUID ownerId, String itemId, String title, int selectedSlot, boolean safe, boolean lucky) {
        this.ownerId = ownerId;
        this.itemId = itemId;
        this.selectedSlot = selectedSlot;
        this.safe = safe;
        this.lucky = lucky;
        inventory = Bukkit.createInventory(this, 45, Component.text(title));
    }

    UUID ownerId() {
        return ownerId;
    }

    String itemId() {
        return itemId;
    }

    int selectedSlot() {
        return selectedSlot;
    }

    void selectedSlot(int selectedSlot) {
        this.selectedSlot = selectedSlot;
    }

    boolean safe() {
        return safe;
    }

    void toggleSafe() {
        safe = !safe;
    }

    boolean lucky() {
        return lucky;
    }

    void toggleLucky() {
        lucky = !lucky;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
