package cn.originalrpg.community;

import cn.originalrpg.api.model.CommunityStorageSnapshot;
import cn.originalrpg.api.model.MarketListing;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.ui.TrustedMenuHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only projection: clicks create explicit signed requests; inventory items are never withdrawn directly. */
final class CommunityStorageMenu implements TrustedMenuHolder {
    private final Inventory inventory;
    private final UUID viewer;
    private final Map<Integer, String> actions = new HashMap<>();

    CommunityStorageMenu(Server server, Player player, MessageService messages, CommunityStorageSnapshot snapshot, int requestedPage) {
        viewer = player.getUniqueId();
        int lastPage = Math.max(0, (snapshot.supplies().size() - 1) / 45);
        int page = Math.clamp(requestedPage, 0, lastPage);
        inventory = server.createInventory(this, 54, Component.text(messages.text(player, "community-storage.menu-title",
                Map.of("tag", snapshot.tag(), "page", page + 1)), NamedTextColor.DARK_GREEN));
        int start = page * 45;
        for (int index = start; index < Math.min(start + 45, snapshot.supplies().size()); index++) {
            MarketListing listing = snapshot.supplies().get(index);
            ItemStack icon;
            try { icon = ItemStack.deserializeBytes(Base64.getDecoder().decode(listing.itemPayload())); }
            catch (RuntimeException invalidPayload) { icon = new ItemStack(Material.BARRIER); }
            if (icon.getType().isAir()) icon = new ItemStack(Material.BARRIER);
            var meta = icon.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(messages.text(player, "community-storage.stock-state", Map.of("state",
                    messages.text(player, "community-storage.stock." + listing.state().name().toLowerCase(java.util.Locale.ROOT))))));
            lore.add(Component.text(messages.text(player, "community-storage.donor", Map.of("player", listing.sellerId()))));
            lore.add(Component.text(listing.id().toString(), NamedTextColor.DARK_GRAY));
            if (listing.state() == MarketListing.State.ACTIVE) {
                lore.add(Component.text(messages.text(player, "community-storage.request-self"), NamedTextColor.GOLD));
                actions.put(index - start, "communitystorage withdraw " + listing.id());
            } else {
                lore.add(Component.text(messages.text(player, "community-storage.recovery-required"), NamedTextColor.YELLOW));
            }
            meta.lore(lore);
            icon.setItemMeta(meta);
            inventory.setItem(index - start, icon);
        }
        if (page > 0) button(player, messages, 45, Material.ARROW, "previous", "communitystorage list " + page);
        button(player, messages, 46, Material.COMPASS, "back", "community");
        button(player, messages, 48, Material.BOOK, "materials", "communitystorage materials");
        button(player, messages, 49, Material.HOPPER, "deposit", "communitystorage deposit");
        button(player, messages, 50, Material.WRITABLE_BOOK, "pending", "communitystorage pending");
        button(player, messages, 51, Material.FILLED_MAP, "history", "community history " + snapshot.communityId());
        if (page < lastPage) button(player, messages, 53, Material.ARROW, "next", "communitystorage list " + (page + 2));
    }

    UUID viewer() { return viewer; }
    String actionAt(int slot) { return actions.get(slot); }
    @Override public @NotNull Inventory getInventory() { return inventory; }

    private void button(Player player, MessageService messages, int slot, Material material, String key, String command) {
        ItemStack icon = new ItemStack(material);
        var meta = icon.getItemMeta();
        meta.displayName(Component.text(messages.text(player, "community-storage.button." + key), NamedTextColor.AQUA));
        icon.setItemMeta(meta);
        inventory.setItem(slot, icon);
        actions.put(slot, command);
    }
}
