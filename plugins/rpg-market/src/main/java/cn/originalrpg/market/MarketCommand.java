package cn.originalrpg.market;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.EquipmentOperationMarker;
import cn.originalrpg.api.model.MarketDelivery;
import cn.originalrpg.api.model.MarketListing;
import cn.originalrpg.api.model.MarketOperationResult;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.EquipmentProgressService;
import cn.originalrpg.api.service.MarketService;
import cn.originalrpg.api.service.MessageService;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.HashSet;

final class MarketCommand implements CommandExecutor, TabCompleter, Listener {
    private static final int PAGE_SIZE = 45;
    private final Plugin plugin;
    private final MarketService market;
    private final EquipmentService equipment;
    private final EquipmentProgressService equipmentProgress;
    private final MessageService messages;
    private final NamespacedKey listingMarker;
    private final NamespacedKey deliveryMarker;
    private final NamespacedKey escrowReceipts;
    private final NamespacedKey stallOwner;
    private final NamespacedKey stallOwnerName;
    private final NamespacedKey refineCount = NamespacedKey.fromString("rpgenhancement:refine_count");
    private final NamespacedKey limitLevel = NamespacedKey.fromString("rpgenhancement:limit_level");
    private final NamespacedKey commerceOwner = NamespacedKey.fromString("rpgcommerce:owner");
    private final NamespacedKey materialOwner = NamespacedKey.fromString("rpgitems:material_owner");
    private final NamespacedKey sulfurOwner = NamespacedKey.fromString("rpgsulfur:owner");
    private final Set<UUID> listingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> preparingListings = new ConcurrentHashMap<>();
    private final Map<UUID, Player> authenticatedPlayers = new ConcurrentHashMap<>();
    private final MailboxClaimGate mailboxClaims = new MailboxClaimGate();
    private final Map<UUID, ClaimSession> mailboxClaimSessions = new ConcurrentHashMap<>();

    MarketCommand(
            Plugin plugin,
            MarketService market,
            EquipmentService equipment,
            EquipmentProgressService equipmentProgress,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.market = market;
        this.equipment = equipment;
        this.equipmentProgress = equipmentProgress;
        this.messages = messages;
        listingMarker = new NamespacedKey(plugin, "listing_id");
        deliveryMarker = new NamespacedKey(plugin, "delivery_id");
        escrowReceipts = new NamespacedKey(plugin, "escrow_receipts");
        stallOwner = new NamespacedKey(plugin, "stall_owner");
        stallOwnerName = new NamespacedKey(plugin, "stall_owner_name");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        if (args.length == 0) return list(player, 0);
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sell" -> args.length == 1 ? showSellDialog(player) : sell(player, args);
            case "list" -> list(player, page(player, args));
            case "mine" -> mine(player);
            case "buy" -> confirmPurchase(player, parseId(player, args), 0, null);
            case "cancel" -> confirmCancellation(player, parseId(player, args));
            case "claim" -> claim(player);
            case "stall" -> stall(player, args);
            default -> false;
        };
    }

    private boolean stall(Player player, String[] args) {
        if (!plugin.getConfig().getBoolean("stalls.enabled", true)) {
            player.sendMessage(Component.text(messages.text(player, "market.stall-disabled"), NamedTextColor.RED));
            return true;
        }
        if (args.length != 2 || !(args[1].equalsIgnoreCase("create") || args[1].equalsIgnoreCase("remove"))) {
            player.sendMessage(Component.text(messages.text(player, "market.stall-usage"), NamedTextColor.YELLOW));
            return true;
        }
        Block block = player.getTargetBlockExact(Math.clamp(
                plugin.getConfig().getInt("stalls.target-distance", 5), 3, 8));
        if (block == null || !stallMaterial(block.getType()) || !(block.getState() instanceof TileState state)) {
            player.sendMessage(Component.text(messages.text(player, "market.stall-target"), NamedTextColor.RED));
            return true;
        }
        if (!plugin.getConfig().getStringList("stalls.worlds").contains(block.getWorld().getName())) {
            player.sendMessage(Component.text(messages.text(player, "market.stall-world"), NamedTextColor.RED));
            return true;
        }
        String existing = state.getPersistentDataContainer().get(stallOwner, PersistentDataType.STRING);
        if (args[1].equalsIgnoreCase("remove")) {
            if (existing == null) {
                player.sendMessage(Component.text(messages.text(player, "market.stall-not-found"), NamedTextColor.YELLOW));
                return true;
            }
            if (!existing.equals(player.getUniqueId().toString()) && !player.hasPermission("rpg.market.admin")) {
                player.sendMessage(Component.text(messages.text(player, "market.stall-not-owner"), NamedTextColor.RED));
                return true;
            }
            state.getPersistentDataContainer().remove(stallOwner);
            state.getPersistentDataContainer().remove(stallOwnerName);
            state.update(true, false);
            player.sendMessage(Component.text(messages.text(player, "market.stall-removed"), NamedTextColor.GREEN));
            return true;
        }
        StallClaimPolicy.Decision claimDecision = claimDecision(player, block);
        if (claimDecision != StallClaimPolicy.Decision.ALLOWED) {
            String key = claimDecision == StallClaimPolicy.Decision.INTEGRATION_UNAVAILABLE
                    ? "market.stall-claim-verification-unavailable" : "market.stall-claim-required";
            player.sendMessage(Component.text(messages.text(player, key), NamedTextColor.RED));
            return true;
        }
        if (existing != null && !existing.equals(player.getUniqueId().toString())) {
            player.sendMessage(Component.text(messages.text(player, "market.stall-not-owner"), NamedTextColor.RED));
            return true;
        }
        state.getPersistentDataContainer().set(stallOwner, PersistentDataType.STRING, player.getUniqueId().toString());
        state.getPersistentDataContainer().set(stallOwnerName, PersistentDataType.STRING, player.getName());
        state.update(true, false);
        player.sendMessage(Component.text(messages.text(player, "market.stall-created"), NamedTextColor.GREEN));
        return true;
    }

    private StallClaimPolicy.Decision claimDecision(Player player, Block block) {
        org.bukkit.plugin.Plugin griefPrevention = plugin.getServer().getPluginManager().getPlugin("GriefPrevention");
        if (griefPrevention == null || !griefPrevention.isEnabled()) {
            return StallClaimPolicy.decide(false, false, false, false);
        }
        try {
            Class<?> claimType = Class.forName("me.ryanhamshire.GriefPrevention.Claim");
            Object dataStore = griefPrevention.getClass().getField("dataStore").get(griefPrevention);
            Object claim = dataStore.getClass()
                    .getMethod("getClaimAt", org.bukkit.Location.class, boolean.class, claimType)
                    .invoke(dataStore, block.getLocation(), false, null);
            if (claim == null) return StallClaimPolicy.decide(true, true, false, false);
            Object denial = claimType.getMethod("allowEdit", Player.class).invoke(claim, player);
            return StallClaimPolicy.decide(true, true, true, denial == null);
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Could not verify GriefPrevention ownership for a market stall: "
                    + exception.getClass().getSimpleName());
            return StallClaimPolicy.decide(true, false, false, false);
        }
    }

    private boolean openStall(Player viewer, UUID ownerId, String ownerName) {
        int maximum = Math.clamp(plugin.getConfig().getInt("stalls.maximum-visible-listings", 45), 1, PAGE_SIZE);
        market.activeListings(ownerId, maximum).whenComplete((listings, error) -> sync(() -> {
            if (!ready(viewer)) return;
            if (error != null) {
                viewer.sendMessage(Component.text(messages.text(viewer, "market.load-failed"), NamedTextColor.RED));
                return;
            }
            MarketBrowserMenu menu = new MarketBrowserMenu(messages.text(viewer, "market.stall-title", Map.of(
                    "owner", ownerName, "count", listings.size())), false, 0, ownerId);
            int slot = 0;
            for (MarketListing listing : listings) {
                ItemStack display = browserItem(viewer, listing);
                if (display == null) menu.setDisplay(slot++, invalidBrowserItem(viewer));
                else menu.setListing(slot++, listing.id(), display);
            }
            if (listings.isEmpty()) menu.setDisplay(22, control(viewer, Material.GRAY_STAINED_GLASS_PANE,
                    "market.stall-empty", "market.stall-empty-lore"));
            menu.setAction(47, "mine", control(viewer, Material.ENDER_CHEST,
                    "market.browser-mine", "market.browser-mine-lore"));
            menu.setAction(48, "public", control(viewer, Material.SPYGLASS,
                    "market.browser-public", "market.browser-public-lore"));
            menu.setDisplay(49, control(viewer, Material.BARREL,
                    "market.stall-help", "market.stall-help-lore"));
            menu.setAction(50, "claim", control(viewer, Material.CHEST,
                    "market.browser-claim", "market.browser-claim-lore"));
            viewer.openInventory(menu.getInventory());
        }));
        return true;
    }

    private boolean showSellDialog(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            player.sendMessage(Component.text(messages.text(player, "market.hold-item"), NamedTextColor.RED));
            return true;
        }
        ItemStack preview = held.clone();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(messages.text(player, "market.sell-dialog-title"), NamedTextColor.GOLD))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(
                                DialogBody.item(preview).showDecorations(true).showTooltip(true)
                                        .width(48).height(48).build(),
                                DialogBody.plainMessage(Component.text(
                                        messages.text(player, "market.sell-dialog-body"), NamedTextColor.GRAY))))
                        .inputs(List.of(DialogInput.text("price", Component.text(
                                        messages.text(player, "market.sell-dialog-price")))
                                .width(240).maxLength(20).build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text(messages.text(player, "market.sell-dialog-confirm"),
                                        NamedTextColor.GREEN))
                                .tooltip(Component.text(messages.text(player, "market.sell-dialog-confirm-tooltip")))
                                .action(DialogAction.customClick((response, audience) -> {
                                    if (!(audience instanceof Player responder)) return;
                                    String price = response.getText("price");
                                    sync(() -> {
                                        if (ready(responder)) sell(responder,
                                                new String[]{"sell", price == null ? "" : price.strip()});
                                    });
                                }, callbackOptions()))
                                .build(),
                        ActionButton.create(Component.text(messages.text(player, "common.cancel"), NamedTextColor.RED),
                                Component.empty(), 120, null))));
        player.showDialog(dialog);
        return true;
    }

    private boolean sell(Player player, String[] args) {
        if (args.length != 2) return false;
        if (!listingPlayers.add(player.getUniqueId())) {
            player.sendMessage(Component.text(messages.text(player, "market.listing-pending"), NamedTextColor.YELLOW));
            return true;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (NestedItemPolicy.isContainerWithContents(item)) {
            listingPlayers.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "market.system-item"), NamedTextColor.RED));
            return true;
        }
        boolean custom = equipment.isCustomEquipment(item);
        if (item.getType().isAir() || custom && item.getAmount() != 1) {
            listingPlayers.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "market.single-item-required"), NamedTextColor.RED));
            return true;
        }
        if (custom && (!equipment.isAuthentic(item) || equipment.isBound(item))) {
            listingPlayers.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "market.custom-item-invalid"), NamedTextColor.RED));
            return true;
        }
        ItemMeta meta = item.getItemMeta();
        if (hasNonTransferableSystemMarker(meta)) {
            listingPlayers.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "market.system-item"), NamedTextColor.RED));
            return true;
        }
        if (meta != null && (commerceOwner != null
                && meta.getPersistentDataContainer().has(commerceOwner, PersistentDataType.STRING)
                || materialOwner != null && meta.getPersistentDataContainer().has(materialOwner, PersistentDataType.STRING)
                || sulfurOwner != null && meta.getPersistentDataContainer().has(sulfurOwner, PersistentDataType.STRING))) {
            listingPlayers.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "market.account-bound"), NamedTextColor.RED));
            return true;
        }
        if (custom && meta != null && !equipment.isTradeUnlocked(item)
                && (refineCount != null && meta.getPersistentDataContainer()
                        .getOrDefault(refineCount, PersistentDataType.INTEGER, 0) > 0
                || limitLevel != null && meta.getPersistentDataContainer()
                        .getOrDefault(limitLevel, PersistentDataType.INTEGER, 0) > 0)) {
            listingPlayers.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "market.refined-bound"), NamedTextColor.RED));
            return true;
        }
        BigDecimal price;
        try {
            BigDecimal minimum = new BigDecimal(plugin.getConfig().getString("listing.minimum-price", "1.00"));
            BigDecimal maximum = new BigDecimal(plugin.getConfig().getString("listing.maximum-price", "1000000000000.00"));
            price = MarketPricePolicy.normalize(args[1], minimum, maximum).orElseThrow(NumberFormatException::new);
        } catch (NumberFormatException exception) {
            listingPlayers.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "market.price-invalid"), NamedTextColor.RED));
            return true;
        }

        UUID listingId = UUID.randomUUID();
        preparingListings.put(player.getUniqueId(), listingId);
        meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(listingMarker, PersistentDataType.STRING, listingId.toString());
        item.setItemMeta(meta);
        player.saveData();
        String payload = Base64.getEncoder().encodeToString(item.serializeAsBytes());
        BigDecimal feeRate = new BigDecimal(plugin.getConfig().getString(
                custom ? "fees.custom-equipment" : "fees.normal", custom ? "0.10" : "0.05"));
        int maximumOpen = Math.clamp(plugin.getConfig().getInt("listing.maximum-open-per-player", 12), 1, 100);
        market.prepare(listingId, player.getUniqueId(), payload, price, feeRate, maximumOpen,
                        custom ? equipment.itemId(item) : java.util.Optional.empty())
                .whenComplete((result, error) -> sync(() -> completePreparation(player, listingId, result, error)));
        return true;
    }

    private static boolean hasNonTransferableSystemMarker(ItemMeta meta) {
        return meta != null && meta.getPersistentDataContainer().getKeys().stream()
                .map(NamespacedKey::toString)
                .anyMatch(SystemItemPolicy::isNonTransferableMarker);
    }

    private void completePreparation(
            Player player, UUID listingId, MarketOperationResult result, Throwable error
    ) {
        preparingListings.remove(player.getUniqueId(), listingId);
        listingPlayers.remove(player.getUniqueId());
        Player current = Bukkit.getPlayer(player.getUniqueId());
        if (!ready(current)) return;
        if (error != null || result.status() != MarketOperationResult.Status.SUCCESS) {
            // The callback may belong to a disconnected Player handle. Clear
            // the failed marker only through the current authenticated login;
            // an offline orphan is reconciled against PREPARED rows on login.
            clearListingMarker(current, listingId);
            current.saveData();
            String key = error == null && result.status() == MarketOperationResult.Status.LISTING_LIMIT
                    ? "market.listing-limit" : error == null
                    && result.status() == MarketOperationResult.Status.DUPLICATE_ITEM
                    ? "market.duplicate-item" : error == null
                    && result.status() == MarketOperationResult.Status.ITEM_NOT_TRADEABLE
                    ? "market.item-not-tradeable" : "market.prepare-failed";
            current.sendMessage(Component.text(messages.text(current, key), NamedTextColor.RED));
            return;
        }
        escrowPrepared(current, listingId, false);
    }

    private boolean list(Player player, int requestedPage) {
        int page = Math.clamp(requestedPage, 0, 22_222);
        market.activeListings(page * PAGE_SIZE, PAGE_SIZE + 1).whenComplete((loaded, error) -> sync(() -> {
            if (!ready(player)) return;
            if (error != null) {
                player.sendMessage(Component.text(messages.text(player, "market.load-failed"), NamedTextColor.RED));
                return;
            }
            boolean hasNext = loaded.size() > PAGE_SIZE;
            List<MarketListing> listings = loaded.stream().limit(PAGE_SIZE).toList();
            if (listings.isEmpty() && page > 0) {
                list(player, page - 1);
                return;
            }
            MarketBrowserMenu menu = new MarketBrowserMenu(messages.text(player, "market.browser-title", Map.of(
                    "count", listings.size(), "page", page + 1)), false, page);
            int slot = 0;
            for (MarketListing listing : listings) {
                ItemStack display = browserItem(player, listing);
                if (display == null) menu.setDisplay(slot++, invalidBrowserItem(player));
                else menu.setListing(slot++, listing.id(), display);
            }
            if (page > 0) menu.setAction(45, "previous", control(player, Material.ARROW,
                    "market.browser-previous", "market.browser-previous-lore"));
            menu.setAction(47, "mine", control(player, Material.ENDER_CHEST,
                    "market.browser-mine", "market.browser-mine-lore"));
            menu.setAction(48, "sell", control(player, Material.EMERALD,
                    "market.browser-sell", "market.browser-sell-lore"));
            menu.setDisplay(49, control(player, Material.WRITABLE_BOOK,
                    "market.browser-help-title", "market.browser-help-lore"));
            menu.setAction(50, "claim", control(player, Material.CHEST,
                    "market.browser-claim", "market.browser-claim-lore"));
            if (hasNext) menu.setAction(53, "next", control(player, Material.ARROW,
                    "market.browser-next", "market.browser-next-lore"));
            player.openInventory(menu.getInventory());
        }));
        return true;
    }

    private boolean mine(Player player) {
        market.activeListings(player.getUniqueId(), 100).whenComplete((listings, error) -> sync(() -> {
            if (!ready(player)) return;
            if (error != null) {
                player.sendMessage(Component.text(messages.text(player, "market.load-failed"), NamedTextColor.RED));
                return;
            }
            MarketBrowserMenu menu = new MarketBrowserMenu(messages.text(player, "market.mine-title", Map.of(
                    "count", listings.size())), true, 0);
            int slot = 0;
            for (MarketListing listing : listings) {
                ItemStack display = browserItem(player, listing);
                if (display == null) menu.setDisplay(slot++, invalidBrowserItem(player));
                else menu.setListing(slot++, listing.id(), display);
            }
            menu.setAction(48, "public", control(player, Material.SPYGLASS,
                    "market.browser-public", "market.browser-public-lore"));
            menu.setDisplay(49, control(player, Material.WRITABLE_BOOK,
                    "market.mine-help-title", "market.mine-help-lore"));
            menu.setAction(50, "claim", control(player, Material.CHEST,
                    "market.browser-claim", "market.browser-claim-lore"));
            player.openInventory(menu.getInventory());
        }));
        return true;
    }

    private ItemStack browserItem(Player player, MarketListing listing) {
        ItemStack item;
        try {
            item = ItemStack.deserializeBytes(Base64.getDecoder().decode(listing.itemPayload()));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().severe("Invalid active market payload id=" + listing.id());
            return null;
        }
        equipmentProgress.refreshDisplay(player, item);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(Component.text(messages.text(player, "market.browser-price", Map.of(
                "price", listing.price().toPlainString())), NamedTextColor.GOLD));
        lore.add(Component.text(messages.text(player, "market.browser-fee", Map.of(
                "fee", listing.feeRate().movePointRight(2).stripTrailingZeros().toPlainString())), NamedTextColor.GRAY));
        lore.add(Component.text(messages.text(player, listing.sellerId().equals(player.getUniqueId())
                ? "market.browser-cancel" : "market.browser-buy"), NamedTextColor.GREEN));
        lore.add(Component.text(listing.id().toString(), NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack invalidBrowserItem(Player player) {
        ItemStack item = new ItemStack(org.bukkit.Material.BARRIER);
        ItemMeta invalid = item.getItemMeta();
        invalid.displayName(Component.text(messages.text(player, "market.browser-invalid-item"), NamedTextColor.RED));
        item.setItemMeta(invalid);
        return item;
    }

    private boolean confirmPurchase(Player player, UUID listingId, int returnPage, UUID sellerFilter) {
        if (listingId == null) return true;
        market.listing(listingId).whenComplete((loaded, error) -> sync(() -> {
            if (!ready(player)) return;
            MarketListing listing = error == null ? loaded.orElse(null) : null;
            if (listing == null || listing.state() != MarketListing.State.ACTIVE) {
                player.sendMessage(Component.text(messages.text(player, "market.purchase-unavailable"), NamedTextColor.RED));
                return;
            }
            if (listing.sellerId().equals(player.getUniqueId())) {
                confirmCancellation(player, listingId);
                return;
            }
            ItemStack preview = browserItem(player, listing);
            if (preview == null) {
                player.sendMessage(Component.text(messages.text(player, "market.payload-invalid"), NamedTextColor.RED));
                return;
            }
            BigDecimal fee = listing.price().multiply(listing.feeRate()).setScale(2, java.math.RoundingMode.HALF_UP);
            Dialog dialog = Dialog.create(builder -> builder.empty()
                    .base(DialogBase.builder(Component.text(messages.text(player, "market.purchase-dialog-title"),
                                    NamedTextColor.GOLD))
                            .canCloseWithEscape(true).pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(List.of(
                                    DialogBody.item(preview).showDecorations(true).showTooltip(true)
                                            .width(48).height(48).build(),
                                    DialogBody.plainMessage(Component.text(messages.text(player,
                                            "market.purchase-dialog-body", Map.of(
                                                    "price", listing.price().toPlainString(),
                                                    "fee", fee.toPlainString(),
                                                    "total", listing.price().add(fee).toPlainString())),
                                            NamedTextColor.GRAY))))
                            .build())
                    .type(DialogType.confirmation(
                            ActionButton.builder(Component.text(messages.text(player, "market.purchase-dialog-confirm"),
                                            NamedTextColor.GREEN))
                                    .tooltip(Component.text(messages.text(player, "market.purchase-dialog-tooltip")))
                                    .action(DialogAction.customClick((response, audience) -> {
                                        if (audience instanceof Player buyer) sync(() -> {
                                            if (ready(buyer)) executePurchase(
                                                    buyer, listingId, returnPage, sellerFilter);
                                        });
                                    }, callbackOptions())).build(),
                            ActionButton.create(Component.text(messages.text(player, "common.cancel"), NamedTextColor.RED),
                                    Component.empty(), 120, null))));
            player.showDialog(dialog);
        }));
        return true;
    }

    private void executePurchase(Player player, UUID listingId, int returnPage, UUID sellerFilter) {
        String idempotencyKey = "market-buy:" + listingId + ":" + player.getUniqueId();
        market.purchase(listingId, player.getUniqueId(), idempotencyKey)
                .whenComplete((result, error) -> sync(() -> {
                    if (!ready(player)) return;
                    if (error != null) {
                        player.sendMessage(Component.text(messages.text(player, "market.purchase-failed"), NamedTextColor.RED));
                        return;
                    }
                    if (result.status() == MarketOperationResult.Status.SUCCESS) {
                        player.sendMessage(Component.text(messages.text(player, "market.purchase-success", Map.of(
                                "fee", result.fee().toPlainString())), NamedTextColor.GREEN));
                        if (sellerFilter == null) list(player, returnPage);
                        else openStall(player, sellerFilter, messages.text(player, "market.stall-owner"));
                    } else if (result.status() == MarketOperationResult.Status.DUPLICATE) {
                        player.sendMessage(Component.text(messages.text(player, "market.purchase-duplicate"), NamedTextColor.YELLOW));
                    } else if (result.status() == MarketOperationResult.Status.BALANCE_LIMIT) {
                        player.sendMessage(Component.text(
                                messages.text(player, "market.purchase-seller-balance-limit"), NamedTextColor.RED));
                    } else {
                        player.sendMessage(Component.text(messages.text(player, "market.purchase-rejected", Map.of(
                                "status", result.status())), NamedTextColor.RED));
                    }
                }));
    }

    private boolean confirmCancellation(Player player, UUID listingId) {
        if (listingId == null) return true;
        market.listing(listingId).whenComplete((loaded, error) -> sync(() -> {
            if (!ready(player)) return;
            MarketListing listing = error == null ? loaded.orElse(null) : null;
            if (listing == null || listing.state() != MarketListing.State.ACTIVE
                    || !listing.sellerId().equals(player.getUniqueId())) {
                player.sendMessage(Component.text(messages.text(player, "market.cancel-failed", Map.of(
                        "status", "NOT_OWNER")), NamedTextColor.RED));
                return;
            }
            ItemStack preview = browserItem(player, listing);
            Dialog dialog = Dialog.create(builder -> builder.empty()
                    .base(DialogBase.builder(Component.text(messages.text(player, "market.cancel-dialog-title"),
                                    NamedTextColor.GOLD))
                            .canCloseWithEscape(true).pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(preview == null ? List.of() : List.of(DialogBody.item(preview)
                                    .showDecorations(true).showTooltip(true).width(48).height(48).build()))
                            .build())
                    .type(DialogType.confirmation(
                            ActionButton.builder(Component.text(messages.text(player, "market.cancel-dialog-confirm"),
                                            NamedTextColor.RED))
                                    .tooltip(Component.text(messages.text(player, "market.cancel-dialog-tooltip")))
                                    .action(DialogAction.customClick((response, audience) -> {
                                        if (audience instanceof Player seller) sync(() -> {
                                            if (ready(seller)) executeCancellation(seller, listingId);
                                        });
                                    }, callbackOptions())).build(),
                            ActionButton.create(Component.text(messages.text(player, "common.cancel"),
                                    NamedTextColor.GRAY), Component.empty(), 120, null))));
            player.showDialog(dialog);
        }));
        return true;
    }

    private void executeCancellation(Player player, UUID listingId) {
        market.cancel(listingId, player.getUniqueId()).whenComplete((result, error) -> sync(() -> {
            if (!ready(player)) return;
            if (error != null || result.status() != MarketOperationResult.Status.SUCCESS) {
                player.sendMessage(Component.text(messages.text(player, "market.cancel-failed", Map.of(
                        "status", error == null ? result.status() : "DATABASE")), NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text(messages.text(player, "market.cancel-success"), NamedTextColor.GREEN));
            mine(player);
        }));
    }

    private boolean claim(Player player) {
        MailboxClaimGate.Lease lease = mailboxClaims.begin(player.getUniqueId());
        if (lease == null) {
            player.sendMessage(Component.text(messages.text(player, "market.claim-in-progress"), NamedTextColor.YELLOW));
            return true;
        }
        mailboxClaimSessions.put(player.getUniqueId(), new ClaimSession(player, lease));
        market.pendingDeliveries(player.getUniqueId(), 50).whenComplete((deliveries, error) -> sync(() -> {
            if (error != null) {
                finishClaim(player, lease);
                if (ready(player)) player.sendMessage(Component.text(
                        messages.text(player, "market.mailbox-load-failed"), NamedTextColor.RED));
                return;
            }
            if (deliveries.isEmpty()) {
                finishClaim(player, lease);
                if (ready(player)) player.sendMessage(Component.text(
                        messages.text(player, "market.mailbox-empty"), NamedTextColor.YELLOW));
                return;
            }
            deliverNext(player, lease, deliveries, 0, 0);
        }));
        return true;
    }

    private void deliverNext(
            Player player, MailboxClaimGate.Lease lease,
            List<MarketDelivery> deliveries, int index, int delivered
    ) {
        if (!ready(player) || index >= deliveries.size()) {
            finishClaim(player, lease);
            if (ready(player)) player.sendMessage(Component.text(messages.text(player, "market.claim-complete", Map.of(
                    "count", delivered)), NamedTextColor.GREEN));
            return;
        }
        MarketDelivery delivery = deliveries.get(index);
        ItemStack existing = findByMarker(player, deliveryMarker, delivery.id());
        if (existing != null) {
            markAndContinue(player, lease, deliveries, index, delivered, delivery);
            return;
        }
        if (player.getInventory().firstEmpty() < 0) {
            finishClaim(player, lease);
            player.sendMessage(Component.text(messages.text(player, "market.inventory-full"), NamedTextColor.YELLOW));
            return;
        }
        ItemStack item;
        try {
            item = ItemStack.deserializeBytes(Base64.getDecoder().decode(delivery.itemPayload()));
        } catch (IllegalArgumentException exception) {
            finishClaim(player, lease);
            plugin.getLogger().severe("Invalid market delivery payload id=" + delivery.id());
            player.sendMessage(Component.text(messages.text(player, "market.payload-invalid"), NamedTextColor.RED));
            return;
        }
        // The equipment-operation marker is an in-process escrow lock, never
        // durable item state. Current producers strip it before writing the
        // outbox, but deliveries queued by older builds may still contain it.
        // This is the only trusted deserialization boundary for the mailbox,
        // so clear the obsolete lock here before adding our own short-lived
        // delivery receipt. Never scan arbitrary inventory items for it: an
        // unresolved current operation must remain quarantined and replayable.
        EquipmentOperationMarker.clear(item);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(listingMarker);
            meta.getPersistentDataContainer().set(deliveryMarker, PersistentDataType.STRING, delivery.id().toString());
            item.setItemMeta(meta);
        }
        if (!player.getInventory().addItem(item).isEmpty()) {
            finishClaim(player, lease);
            player.sendMessage(Component.text(messages.text(player, "market.inventory-full"), NamedTextColor.YELLOW));
            return;
        }
        player.saveData();
        markAndContinue(player, lease, deliveries, index, delivered, delivery);
    }

    private void markAndContinue(
            Player player, MailboxClaimGate.Lease lease,
            List<MarketDelivery> deliveries, int index, int delivered,
            MarketDelivery delivery
    ) {
        market.markDelivered(delivery.id(), player.getUniqueId()).whenComplete((marked, error) -> sync(() -> {
            if (error != null) {
                finishClaim(player, lease);
                if (ready(player)) player.sendMessage(Component.text(
                        messages.text(player, "market.delivery-confirm-failed"), NamedTextColor.YELLOW));
                return;
            }
            if (!ready(player)) {
                finishClaim(player, lease);
                return;
            }
            if (!clearInventoryMarker(player, deliveryMarker, delivery.id())) {
                finishClaim(player, lease);
                plugin.getLogger().warning("Committed market delivery receipt was not found in inventory player="
                        + player.getUniqueId() + " delivery=" + delivery.id());
                player.sendMessage(Component.text(
                        messages.text(player, "market.delivery-confirm-failed"), NamedTextColor.YELLOW));
                return;
            }
            player.saveData();
            deliverNext(player, lease, deliveries, index + 1, delivered + 1);
        }));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (hasTransientMarker(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().getTopInventory().getHolder() instanceof MarketBrowserMenu menu) {
            event.setCancelled(true);
            UUID listingId = menu.listingAt(event.getRawSlot());
            if (listingId != null) {
                if (menu.ownerView()) confirmCancellation(player, listingId);
                else confirmPurchase(player, listingId, menu.page(), menu.sellerFilter());
                return;
            }
            String action = menu.actionAt(event.getRawSlot());
            if (action == null) return;
            switch (action) {
                case "previous" -> list(player, Math.max(0, menu.page() - 1));
                case "next" -> list(player, menu.page() + 1);
                case "mine" -> mine(player);
                case "public" -> list(player, 0);
                case "sell" -> showSellDialog(player);
                case "claim" -> {
                    player.closeInventory();
                    claim(player);
                }
                default -> { }
            }
            return;
        }
        ItemStack hotbar = event.getHotbarButton() >= 0
                ? player.getInventory().getItem(event.getHotbarButton()) : null;
        ItemStack offhand = event.getClick() == ClickType.SWAP_OFFHAND
                ? player.getInventory().getItemInOffHand() : null;
        if (hasTransientMarker(event.getCurrentItem()) || hasTransientMarker(event.getCursor())
                || hasTransientMarker(hotbar) || hasTransientMarker(offhand)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MarketBrowserMenu) {
            event.setCancelled(true);
            return;
        }
        if (hasTransientMarker(event.getOldCursor())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (hasTransientMarker(event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (hasTransientMarker(event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (hasTransientMarker(event.getItem())) event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        var iterator = event.getDrops().iterator();
        while (iterator.hasNext()) {
            ItemStack item = iterator.next();
            if (!hasTransientMarker(item)) continue;
            iterator.remove();
            event.getItemsToKeep().add(item);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (hasTransientMarker(event.getItem())) {
            event.setCancelled(true);
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null || !stallMaterial(event.getClickedBlock().getType())) return;
        if (!(event.getClickedBlock().getState() instanceof TileState state)) return;
        String owner = state.getPersistentDataContainer().get(stallOwner, PersistentDataType.STRING);
        if (owner == null) return;
        UUID ownerId;
        try {
            ownerId = UUID.fromString(owner);
        } catch (IllegalArgumentException exception) {
            state.getPersistentDataContainer().remove(stallOwner);
            state.getPersistentDataContainer().remove(stallOwnerName);
            state.update(true, false);
            return;
        }
        if (ownerId.equals(event.getPlayer().getUniqueId()) && event.getPlayer().isSneaking()) return;
        event.setCancelled(true);
        String ownerName = state.getPersistentDataContainer().getOrDefault(
                stallOwnerName, PersistentDataType.STRING, messages.text(event.getPlayer(), "market.stall-owner"));
        openStall(event.getPlayer(), ownerId, ownerName);
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (hasTransientMarker(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (hasTransientMarker(event.getItemInHand())) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearSession(event.getPlayer());
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        clearSession(event.player());
    }

    private void clearSession(Player player) {
        authenticatedPlayers.remove(player.getUniqueId(), player);
        // A database preparation keeps its process-local gate until its
        // callback settles. Releasing it here would allow the next login to
        // start a second listing while the first transaction is unresolved.
        if (!preparingListings.containsKey(player.getUniqueId())) {
            listingPlayers.remove(player.getUniqueId());
        }
        // The in-flight lease belongs to its exact asynchronous claim chain.
        // Disconnecting an old Player handle must not release a newer login's lease.
        ClaimSession claim = mailboxClaimSessions.get(player.getUniqueId());
        if (claim != null && claim.player() == player
                && mailboxClaimSessions.remove(player.getUniqueId(), claim)) {
            mailboxClaims.finish(claim.lease());
        }
        Object holder = player.getOpenInventory().getTopInventory().getHolder();
        if (holder instanceof MarketBrowserMenu) {
            player.closeInventory();
        }
        player.closeDialog();
    }

    void close() {
        authenticatedPlayers.clear();
        listingPlayers.clear();
        preparingListings.clear();
        mailboxClaimSessions.clear();
        mailboxClaims.clear();
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        Player player = event.player();
        UUID playerId = player.getUniqueId();
        authenticatedPlayers.put(playerId, player);
        recoverDeliveryReceipts(player);
        recoverPreparedListings(player);
    }

    private void recoverPreparedListings(Player player) {
        if (!ready(player)) return;
        UUID playerId = player.getUniqueId();
        // A preparation started by the previous connection still owns the
        // transition. Querying PREPARED concurrently can observe the old DB
        // state and misclassify its inventory marker as an orphan. Wait for
        // that callback, then reconcile from a committed database snapshot.
        if (preparingListings.containsKey(playerId)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> recoverPreparedListings(player), 20L);
            return;
        }
        market.preparedListings(playerId).whenComplete((listings, error) -> sync(() -> {
            if (error != null) {
                plugin.getLogger().warning("Market recovery lookup failed for " + player.getUniqueId() + ": " + error.getMessage());
                return;
            }
            if (!ready(player)) return;
            Set<UUID> preparedIds = listings.stream().map(MarketListing::id)
                    .collect(java.util.stream.Collectors.toSet());
            UUID inFlight = preparingListings.get(playerId);
            clearOrphanListingMarkers(player, preparedIds, inFlight);
            retainEscrowReceipts(player, preparedIds);
            player.saveData();
            for (MarketListing listing : listings) {
                escrowPrepared(player, listing.id(), true);
            }
            market.pendingDeliveries(player.getUniqueId(), 1).whenComplete((pending, pendingError) -> sync(() -> {
                if (pendingError != null) {
                    plugin.getLogger().warning("Market delivery recovery lookup failed for "
                            + player.getUniqueId() + ": " + pendingError.getMessage());
                    return;
                }
                if (ready(player) && !pending.isEmpty()) player.sendMessage(Component.text(
                        messages.text(player, "market.mailbox-notice"), NamedTextColor.GOLD));
            }));
        }));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("sell", "list", "mine", "buy", "cancel", "claim", "stall").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stall")) {
            return List.of("create", "remove").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    private boolean stallMaterial(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST || material == Material.BARREL;
    }

    private int page(Player player, String[] args) {
        if (args.length < 2) return 0;
        try {
            int value = Integer.parseInt(args[1]);
            if (value < 1) throw new NumberFormatException();
            return value - 1;
        } catch (NumberFormatException exception) {
            player.sendMessage(Component.text(messages.text(player, "market.page-invalid"), NamedTextColor.RED));
            return 0;
        }
    }

    private UUID parseId(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(Component.text(messages.text(player, "market.id-required"), NamedTextColor.RED));
            return null;
        }
        try {
            return UUID.fromString(args[1]);
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.text(messages.text(player, "market.id-invalid"), NamedTextColor.RED));
            return null;
        }
    }

    private boolean removeListingItem(Player player, UUID listingId) {
        int slot = findSlotByMarker(player, listingMarker, listingId);
        if (slot < 0) return false;
        player.getInventory().setItem(slot, null);
        return true;
    }

    private void escrowPrepared(Player player, UUID listingId, boolean recovery) {
        ItemStack marked = findByMarker(player, listingMarker, listingId);
        boolean receipt = escrowReceiptIds(player).contains(listingId);
        if (marked == null && !receipt) {
            quarantinePrepared(player.getUniqueId(), listingId);
            return;
        }
        if (marked != null) {
            addEscrowReceipt(player, listingId);
            player.saveData();
            removeListingItem(player, listingId);
            player.saveData();
        }
        market.activate(listingId, player.getUniqueId()).whenComplete((activated, activationError) -> sync(() -> {
            if (activationError != null || activated.status() != MarketOperationResult.Status.SUCCESS) {
                if (ready(player) && !recovery) player.sendMessage(Component.text(
                        messages.text(player, "market.activation-recovery"), NamedTextColor.YELLOW));
                plugin.getLogger().warning("Prepared market listing activation will retry for player="
                        + player.getUniqueId() + " listing=" + listingId + " status="
                        + (activationError == null ? activated.status() : activationError.getMessage()));
                return;
            }
            if (ready(player)) {
                removeEscrowReceipt(player, listingId);
                player.saveData();
                if (!recovery) player.sendMessage(Component.text(messages.text(player, "market.listed", Map.of(
                        "id", listingId)), NamedTextColor.GREEN));
            }
        }));
    }

    private void quarantinePrepared(UUID playerId, UUID listingId) {
        market.quarantinePrepared(listingId, playerId).whenComplete((result, error) -> {
            if (error != null || result.status() != MarketOperationResult.Status.SUCCESS) {
                plugin.getLogger().severe("Could not quarantine ambiguous prepared listing player=" + playerId
                        + " listing=" + listingId + " status="
                        + (error == null ? result.status() : error.getMessage()));
                return;
            }
            plugin.getLogger().severe("Quarantined prepared listing without inventory item or escrow receipt player="
                    + playerId + " listing=" + listingId + "; payload was not activated or returned");
        });
    }

    private Set<UUID> escrowReceiptIds(Player player) {
        String stored = player.getPersistentDataContainer().get(escrowReceipts, PersistentDataType.STRING);
        if (stored == null || stored.isBlank()) return new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        for (String value : stored.split(",")) {
            try {
                ids.add(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
                // Invalid legacy receipt fragments are discarded on the next mutation.
            }
        }
        return ids;
    }

    private void addEscrowReceipt(Player player, UUID listingId) {
        Set<UUID> ids = escrowReceiptIds(player);
        ids.add(listingId);
        storeEscrowReceipts(player, ids);
    }

    private void removeEscrowReceipt(Player player, UUID listingId) {
        Set<UUID> ids = escrowReceiptIds(player);
        ids.remove(listingId);
        storeEscrowReceipts(player, ids);
    }

    private void retainEscrowReceipts(Player player, Set<UUID> preparedIds) {
        Set<UUID> ids = escrowReceiptIds(player);
        ids.retainAll(preparedIds);
        storeEscrowReceipts(player, ids);
    }

    private void storeEscrowReceipts(Player player, Set<UUID> ids) {
        if (ids.isEmpty()) {
            player.getPersistentDataContainer().remove(escrowReceipts);
            return;
        }
        String stored = ids.stream().sorted().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
        player.getPersistentDataContainer().set(escrowReceipts, PersistentDataType.STRING, stored);
    }

    private void clearListingMarker(Player player, UUID listingId) {
        clearInventoryMarker(player, listingMarker, listingId);
    }

    private boolean clearOrphanListingMarkers(Player player, Set<UUID> preparedIds, UUID inFlight) {
        boolean changed = false;
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getItemMeta() == null) continue;
            String encoded = item.getItemMeta().getPersistentDataContainer()
                    .get(listingMarker, PersistentDataType.STRING);
            if (encoded == null) continue;
            UUID listingId;
            try {
                listingId = UUID.fromString(encoded);
            } catch (IllegalArgumentException exception) {
                listingId = null;
            }
            if (listingId != null && (preparedIds.contains(listingId) || listingId.equals(inFlight))) continue;
            if (clearInventoryMarkerAt(player, slot, listingMarker)) {
                changed = true;
                plugin.getLogger().warning("Removed orphan market preparation marker player="
                        + player.getUniqueId() + " slot=" + slot + " listing=" + encoded);
            }
        }
        return changed;
    }

    private ItemStack findByMarker(Player player, NamespacedKey key, UUID value) {
        int slot = findSlotByMarker(player, key, value);
        return slot < 0 ? null : player.getInventory().getItem(slot);
    }

    private int findSlotByMarker(Player player, NamespacedKey key, UUID value) {
        String expected = value.toString();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getItemMeta() == null) continue;
            String marker = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if (expected.equals(marker)) return slot;
        }
        return -1;
    }

    private boolean clearInventoryMarker(Player player, NamespacedKey key, UUID value) {
        int slot = findSlotByMarker(player, key, value);
        return slot >= 0 && clearInventoryMarkerAt(player, slot, key);
    }

    private boolean clearInventoryMarkerAt(Player player, int slot, NamespacedKey key) {
        ItemStack current = player.getInventory().getItem(slot);
        if (current == null || current.getItemMeta() == null) return false;
        ItemStack updated = current.clone();
        ItemMeta meta = updated.getItemMeta();
        if (!meta.getPersistentDataContainer().has(key, PersistentDataType.STRING)) return false;
        meta.getPersistentDataContainer().remove(key);
        updated.setItemMeta(meta);
        equipmentProgress.refreshDisplay(player, updated);
        player.getInventory().setItem(slot, updated);
        return true;
    }

    private void recoverDeliveryReceipts(Player player) {
        List<String> markers = new ArrayList<>(player.getInventory().getSize());
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            markers.add(item == null || item.getItemMeta() == null ? null
                    : item.getItemMeta().getPersistentDataContainer()
                    .get(deliveryMarker, PersistentDataType.STRING));
        }
        DeliveryReceiptRules.Scan scan = DeliveryReceiptRules.scan(markers);
        for (int slot : scan.invalidSlots()) {
            if (clearInventoryMarkerAt(player, slot, deliveryMarker)) {
                plugin.getLogger().warning("Removed malformed transient delivery receipt player="
                        + player.getUniqueId() + " slot=" + slot);
            }
        }
        if (!scan.duplicateIds().isEmpty()) {
            plugin.getLogger().severe("Duplicate transient delivery receipts remain quarantined player="
                    + player.getUniqueId() + " deliveries=" + scan.duplicateIds());
        }
        if (!scan.invalidSlots().isEmpty()) player.saveData();
        for (UUID deliveryId : scan.uniqueReceipts().keySet()) {
            market.markDelivered(deliveryId, player.getUniqueId()).whenComplete((marked, error) -> sync(() -> {
                if (error != null) {
                    plugin.getLogger().warning("Transient delivery receipt recovery failed player="
                            + player.getUniqueId() + " delivery=" + deliveryId + ": " + error.getMessage());
                    return;
                }
                if (!ready(player) || !clearInventoryMarker(player, deliveryMarker, deliveryId)) return;
                player.saveData();
                plugin.getLogger().info("Recovered transient delivery receipt player="
                        + player.getUniqueId() + " delivery=" + deliveryId + " newlyCommitted=" + marked);
            }));
        }
    }

    private boolean hasMarker(ItemStack item, NamespacedKey key) {
        return item != null && item.getItemMeta() != null
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    private boolean hasTransientMarker(ItemStack item) {
        return hasMarker(item, listingMarker) || hasMarker(item, deliveryMarker);
    }

    private void finishClaim(Player player, MailboxClaimGate.Lease lease) {
        ClaimSession claim = mailboxClaimSessions.get(lease.playerId());
        if (claim != null && claim.player() == player && claim.lease().equals(lease)) {
            mailboxClaimSessions.remove(lease.playerId(), claim);
        }
        mailboxClaims.finish(lease);
    }

    private record ClaimSession(Player player, MailboxClaimGate.Lease lease) { }

    private ItemStack control(Player player, Material material, String nameKey, String loreKey) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(messages.text(player, nameKey), NamedTextColor.GOLD));
        meta.lore(List.of(Component.text(messages.text(player, loreKey), NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private ClickCallback.Options callbackOptions() {
        return ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build();
    }

    private void sync(Runnable action) {
        Bukkit.getScheduler().runTask(plugin, action);
    }

    private boolean ready(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player
                && authenticatedPlayers.get(player.getUniqueId()) == player;
    }
}
