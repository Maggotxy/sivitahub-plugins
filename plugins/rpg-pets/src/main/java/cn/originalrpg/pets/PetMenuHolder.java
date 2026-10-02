package cn.originalrpg.pets;

import org.bukkit.inventory.Inventory;
import cn.originalrpg.api.ui.TrustedMenuHolder;

import java.util.UUID;

final class PetMenuHolder implements TrustedMenuHolder {
    enum View { MAIN, CODEX, SKINS }

    private final UUID owner;
    private final View view;
    private final int page;
    private Inventory inventory;

    PetMenuHolder(UUID owner) {
        this(owner, View.MAIN, 0);
    }

    PetMenuHolder(UUID owner, View view, int page) {
        this.owner = owner;
        this.view = view;
        this.page = Math.max(0, page);
    }

    UUID owner() { return owner; }
    View view() { return view; }
    int page() { return page; }
    void inventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
