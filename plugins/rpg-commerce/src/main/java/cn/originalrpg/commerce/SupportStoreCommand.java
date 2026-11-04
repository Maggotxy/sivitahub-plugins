package cn.originalrpg.commerce;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.event.SupportEntitlementsChangedEvent;
import cn.originalrpg.api.model.SignedSupportReceipt;
import cn.originalrpg.api.model.SupportCosmetic;
import cn.originalrpg.api.model.SupportCreditStatus;
import cn.originalrpg.api.model.SupportPaymentResult;
import cn.originalrpg.api.model.SupportProduct;
import cn.originalrpg.api.model.SupportPurchaseResult;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.SupportCreditService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Cosmetic-only checkout and a console adapter for receipts signed by an
 * external trusted settlement bridge. No command creates an unsigned credit,
 * receives card data, chooses an exchange rate, or grants a gameplay item.
 */
final class SupportStoreCommand implements CommandExecutor, TabCompleter, Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final SupportCreditService support;
    private final MessageService messages;
    private final SupportCheckoutSessions<Player> sessions = new SupportCheckoutSessions<>(Clock.systemUTC());
    private final Set<UUID> statusPending = new HashSet<>();
    private boolean importing;
    private boolean controlling;

    SupportStoreCommand(JavaPlugin plugin, SupportCreditService support, MessageService messages) {
        this.plugin = plugin; this.support = support; this.messages = messages;
    }

    @EventHandler public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        sessions.authenticate(event.player().getUniqueId(), event.player());
    }

    @EventHandler public void onRevoked(AuthenticatedPlayerRevokedEvent event) {
        sessions.revoke(event.player().getUniqueId(), event.player());
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        sessions.revoke(event.getPlayer().getUniqueId(), event.getPlayer());
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { send(sender, "common.player-only", NamedTextColor.RED); return true; }
        if (!live(player) || sessions.generation(player.getUniqueId(), player).isEmpty()) return true;
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("status"))) { show(player); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("catalog")) { catalog(player); return true; }
        if (args.length == 2 && args[0].equalsIgnoreCase("buy")) { preview(player, args[1]); return true; }
        if (args.length == 2 && args[0].equalsIgnoreCase("confirm")) { confirm(player, args[1]); return true; }
        return false;
    }

    void show(Player player) {
        UUID generation = sessions.generation(player.getUniqueId(), player).orElse(null);
        if (generation == null || !live(player)) return;
        if (!statusPending.add(player.getUniqueId())) return;
        CompletionStage<SupportCreditStatus> read;
        try { read = support.status(player.getUniqueId()); }
        catch (RuntimeException error) {
            statusPending.remove(player.getUniqueId());
            send(player, "support.load-error", NamedTextColor.RED); return;
        }
        read.whenComplete((status, error) -> sync(() -> {
            statusPending.remove(player.getUniqueId());
            if (!live(player) || !sessions.current(player.getUniqueId(), player, generation)) return;
            if (error != null) { send(player, "support.load-error", NamedTextColor.RED); return; }
            send(player, "support.balance", NamedTextColor.AQUA, Map.of("balance", status.balance(),
                    "used", status.weekIssued(), "limit", status.weekLimit()));
            for (SupportCosmetic cosmetic : status.cosmetics()) send(player, "support.owned", NamedTextColor.GREEN,
                    Map.of("name", name(player, cosmetic)));
            catalog(player);
        }));
    }

    private void catalog(Player player) {
        List<SupportProduct> products = support.catalog();
        if (products.isEmpty()) { send(player, "support.disabled", NamedTextColor.GRAY); return; }
        send(player, "support.scope", NamedTextColor.GRAY);
        for (SupportProduct product : products) player.sendMessage(Component.text(messages.text(player,
                "support.product", Map.of("name", name(player, product.cosmetic()), "cost", product.cost(),
                        "id", product.cosmetic().id())), NamedTextColor.LIGHT_PURPLE)
                .clickEvent(ClickEvent.runCommand("/support buy " + product.cosmetic().id())));
    }

    private void preview(Player player, String id) {
        SupportProduct product = support.catalog().stream().filter(value -> value.cosmetic().id().equals(id)).findFirst().orElse(null);
        if (product == null) { send(player, "support.product-missing", NamedTextColor.RED); return; }
        // Existing voucher-era appearance ownership remains usable. A player should not
        // accidentally pay again for an appearance already unlocked; its historical source is not inferred.
        String earnedKey = switch (product.cosmetic()) {
            case PRISMATIC_AURA -> "prismatic_unlocked";
            case ELYTRA_WINGS -> "trail_unlocked_wings";
            case ELYTRA_COMET -> "trail_unlocked_comet";
        };
        if (player.getPersistentDataContainer().getOrDefault(new NamespacedKey("rpgcosmetics", earnedKey),
                PersistentDataType.BYTE, (byte) 0) == (byte) 1) {
            send(player, "support.already-owned", NamedTextColor.YELLOW); return;
        }
        var preview = sessions.preview(player.getUniqueId(), player, product.cosmetic(), product.cost()).orElse(null);
        if (preview == null) { send(player, "support.pending", NamedTextColor.YELLOW); return; }
        player.sendMessage(Component.text(messages.text(player, "support.confirm", Map.of(
                "name", name(player, product.cosmetic()), "cost", product.cost(), "token", preview.id())), NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand("/support confirm " + preview.id())));
    }

    private void confirm(Player player, String token) {
        UUID id;
        try { id = UUID.fromString(token); }
        catch (IllegalArgumentException error) { send(player, "support.preview-expired", NamedTextColor.RED); return; }
        var checkout = sessions.confirm(player.getUniqueId(), player, id).orElse(null);
        if (checkout == null) { send(player, sessions.busy(player.getUniqueId()) ? "support.pending" : "support.preview-expired", NamedTextColor.YELLOW); return; }
        var preview = checkout.preview();
        try {
            support.purchaseCosmetic(player.getUniqueId(), preview.cosmetic(), preview.cost(), preview.id())
                    .whenComplete((result, error) -> sync(() -> {
                        boolean current = sessions.complete(checkout);
                        if (error == null && (result.status() == SupportPurchaseResult.Status.SUCCESS
                                || result.status() == SupportPurchaseResult.Status.DUPLICATE
                                || result.status() == SupportPurchaseResult.Status.ALREADY_OWNED)) {
                            // A database read, not this event, grants rendering permission.
                            Bukkit.getPluginManager().callEvent(new SupportEntitlementsChangedEvent(checkout.playerId()));
                        }
                        if (!current || !live(player)) return;
                        if (error != null) {
                            send(player, "support.purchase-uncertain", NamedTextColor.YELLOW, Map.of("operation", preview.id()));
                            plugin.getLogger().warning("Support cosmetic completion uncertain; operation=" + preview.id());
                            return;
                        }
                        String key = switch (result.status()) {
                            case SUCCESS, DUPLICATE -> "support.purchase-success";
                            case ALREADY_OWNED -> "support.already-owned";
                            case INSUFFICIENT_FUNDS -> "support.insufficient";
                            case DISABLED -> "support.disabled";
                            case PRICE_CHANGED -> "support.price-changed";
                            default -> "support.purchase-rejected";
                        };
                        send(player, key, NamedTextColor.LIGHT_PURPLE, Map.of("name", name(player, preview.cosmetic()),
                                "balance", result.balance(), "cost", result.charged()));
                    }));
        } catch (RuntimeException error) {
            sessions.complete(checkout);
            send(player, "support.purchase-uncertain", NamedTextColor.YELLOW, Map.of("operation", preview.id()));
        }
    }

    /** Console-only transport; core independently verifies every signed field. */
    boolean importReceipt(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || !sender.hasPermission("rpg.commerce.support.import")) {
            send(sender, "support.import-console-only", NamedTextColor.RED); return true;
        }
        if (args.length != 3) return false;
        if (importing) { send(sender, "support.pending", NamedTextColor.YELLOW); return true; }
        SignedSupportReceipt receipt;
        try { receipt = new SignedSupportReceipt(args[1], args[2]); }
        catch (IllegalArgumentException error) { send(sender, "support.receipt-invalid", NamedTextColor.RED); return true; }
        importing = true;
        try {
            support.acceptReceipt(receipt).whenComplete((result, error) -> sync(() -> {
                importing = false;
                if (error != null) { send(sender, "support.import-uncertain", NamedTextColor.YELLOW); return; }
                boolean success = result.status() == SupportPaymentResult.Status.SUCCESS || result.status() == SupportPaymentResult.Status.DUPLICATE;
                send(sender, "support.import-result", success ? NamedTextColor.GREEN : NamedTextColor.YELLOW,
                        Map.of("status", result.status(), "order", result.orderId(), "credited", result.credited(), "balance", result.balance()));
                plugin.getLogger().info("Support receipt outcome=" + result.status() + " order=" + result.orderId());
            }));
        } catch (RuntimeException error) { importing = false; send(sender, "support.import-uncertain", NamedTextColor.YELLOW); }
        return true;
    }

    /** Uses the existing persistent mechanism row, not a second in-memory toggle. */
    boolean controlPayments(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || !sender.hasPermission("rpg.commerce.support.control")) {
            send(sender, "support.control-console-only", NamedTextColor.RED); return true;
        }
        if (args.length != 2) return false;
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!List.of("enable", "disable", "status").contains(action)) return false;
        if (controlling) { send(sender, "support.pending", NamedTextColor.YELLOW); return true; }
        controlling = true;
        try {
            CompletionStage<Boolean> operation = action.equals("status") ? support.paymentAcceptanceEnabled()
                    : support.setPaymentAcceptanceEnabled(action.equals("enable"), sender.getName());
            operation.whenComplete((enabled, error) -> sync(() -> {
                controlling = false;
                if (error != null) { send(sender, "support.control-uncertain", NamedTextColor.YELLOW); return; }
                send(sender, "support.control-result", NamedTextColor.AQUA, Map.of("enabled", enabled));
                if (!action.equals("status")) plugin.getLogger().info("Support payment control action=" + action
                        + " effective=" + enabled + " actor=" + sender.getName());
            }));
        } catch (RuntimeException error) { controlling = false; send(sender, "support.control-uncertain", NamedTextColor.YELLOW); }
        return true;
    }

    private String name(CommandSender viewer, SupportCosmetic cosmetic) { return messages.text(viewer, "support.cosmetics." + cosmetic.id()); }
    private boolean live(Player player) { return plugin.isEnabled() && player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player; }
    private void send(CommandSender sender, String key, NamedTextColor color) { send(sender, key, color, Map.of()); }
    private void send(CommandSender sender, String key, NamedTextColor color, Map<String, ?> values) {
        sender.sendMessage(Component.text(messages.text(sender, key, values), color));
    }
    private void sync(Runnable task) {
        if (!plugin.isEnabled()) return;
        try { Bukkit.getScheduler().runTask(plugin, () -> { if (plugin.isEnabled()) task.run(); }); }
        catch (IllegalStateException ignored) { /* Committed authorities survive plugin shutdown. */ }
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> candidates = args.length == 1 ? List.of("status", "catalog", "buy")
                : args.length == 2 && args[0].equalsIgnoreCase("buy") ? support.catalog().stream().map(value -> value.cosmetic().id()).toList() : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return candidates.stream().filter(value -> value.startsWith(prefix)).toList();
    }
    @Override public void close() { sessions.close(); statusPending.clear(); importing = false; controlling = false; }
}
