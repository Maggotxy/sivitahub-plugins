package cn.originalrpg.commerce;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.CommerceResult;
import cn.originalrpg.api.model.CommercePurchaseResult;
import cn.originalrpg.api.model.CommerceStatus;
import cn.originalrpg.api.model.CreditResult;
import cn.originalrpg.api.model.RewardClaimResult;
import cn.originalrpg.api.model.SupplyPurchaseResult;
import cn.originalrpg.api.service.CommerceService;
import cn.originalrpg.api.service.EconomyService;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RpgCommercePlugin extends JavaPlugin implements CommandExecutor, Listener {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private CommerceService commerce;
    private EconomyService economy;
    private MessageService messages;
    private NamespacedKey rewardMarker;
    private NamespacedKey owner;
    private NamespacedKey source;
    private List<Product> productCatalog = List.of();
    private List<Reward> rewardCatalog = List.of();
    private List<SupplyProduct> supplyCatalog = List.of();
    /** Exact connection handles prevent an old callback from unlocking a new login. */
    private final Map<UUID, PlayerSession> authenticatedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, OperationLease> purchaseInFlight = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int configVersion = getConfig().getInt("config-version", 0);
        if (configVersion < 2) {
            getConfig().set("rewards.first-support.amount", 1);
            getConfig().set("rewards.veteran-support.amount", 2);
            getConfig().set("config-version", 2);
        }
        getConfig().options().copyDefaults(true);
        saveConfig();
        productCatalog = loadProducts();
        rewardCatalog = loadRewards();
        supplyCatalog = loadSupplyProducts();
        getLogger().info("Loaded " + productCatalog.size() + " Starlight products, "
                + rewardCatalog.size() + " cumulative rewards, and " + supplyCatalog.size()
                + " vanilla supplies from code-reviewed catalogs; unknown or unsafe entries fail closed.");
        commerce = Objects.requireNonNull(getServer().getServicesManager().load(CommerceService.class), "CommerceService");
        economy = Objects.requireNonNull(getServer().getServicesManager().load(EconomyService.class), "EconomyService");
        messages = Objects.requireNonNull(getServer().getServicesManager().load(MessageService.class), "MessageService");
        rewardMarker = new NamespacedKey(this, "reward_marker");
        owner = new NamespacedKey(this, "owner");
        source = new NamespacedKey(this, "source");
        command("store").setExecutor(this);
        command("rpgcommerce").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        authenticatedPlayers.clear();
        purchaseInFlight.clear();
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        authenticatedPlayers.put(event.player().getUniqueId(),
                new PlayerSession(event.player(), UUID.randomUUID()));
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        revokeSession(event.player());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        revokeSession(event.getPlayer());
    }

    private void revokeSession(Player player) {
        PlayerSession session = authenticatedPlayers.get(player.getUniqueId());
        if (session != null && session.player() == player) {
            authenticatedPlayers.remove(player.getUniqueId(), session);
        }
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof SupplyMenu) {
            player.closeInventory();
        }
        // purchaseInFlight deliberately remains owned by its submitted
        // transaction. A quick reconnect cannot start a second purchase until
        // the first callback releases the exact old connection handle.
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("store")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(messages.text(sender, "common.player-only"));
                return true;
            }
            // AuthMe may leave the Bukkit Player online while the account is
            // still locked (or after /logout). Never start profile/ledger work
            // until this exact connection received the ready event.
            if (!currentSession(player)) return true;
            if (args.length == 0) {
                openStore(player);
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
                showStatus(player);
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("claim")) {
                claimRewards(player);
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("supplies")) {
                openSupplies(player);
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
                buy(player, args[1]);
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("buy-supply")) {
                buySupply(player, args[1]);
                return true;
            }
            return false;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("starlight") || args[0].equalsIgnoreCase("points"))) {
            return adjustCredits(sender, args);
        }
        return recordPayment(sender, args);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SupplyMenu menu)
                || !(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);
        if (!currentSession(player)) return;
        String action = menu.actionAt(event.getRawSlot());
        if (action == null) return;
        if (action.equals("supplies")) {
            openSupplies(player);
            return;
        }
        if (action.equals("back")) {
            openStore(player);
            return;
        }
        if (event.getClick() != ClickType.SHIFT_LEFT) {
            player.sendActionBar(Component.text(messages.text(player, "commerce.confirm-required"), NamedTextColor.YELLOW));
            return;
        }
        if (action.equals("claim")) claimRewards(player);
        else if (action.startsWith("buy:")) buy(player, action.substring("buy:".length()));
        else if (action.startsWith("supply:")) buySupply(player, action.substring("supply:".length()));
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SupplyMenu) event.setCancelled(true);
    }

    private void openStore(Player player) {
        commerce.status(player.getUniqueId()).whenComplete((status, error) -> sync(() -> {
            if (!currentSession(player)) return;
            if (error != null) {
                player.sendMessage(Component.text(messages.text(player, "commerce.store-load-error"), NamedTextColor.RED));
                return;
            }
            SupplyMenu menu = new SupplyMenu(Component.text(messages.text(player, "commerce.menu-title")));
            ItemStack account = display(Material.NETHER_STAR, messages.text(player, "commerce.menu-account"),
                    List.of(messages.text(player, "commerce.starlight-balance", Map.of("amount", status.credits())),
                            membershipText(player, status)));
            menu.set(4, account, null);
            int[] productSlots = {10, 11, 12, 14, 15, 16};
            List<Product> products = products();
            for (int index = 0; index < Math.min(products.size(), productSlots.length); index++) {
                Product product = products.get(index);
                boolean owned = cosmeticAlreadyUnlocked(player, product.marker());
                List<String> lore = new ArrayList<>();
                lore.add(messages.text(player, "commerce.menu-product-cost", Map.of(
                        "cost", product.points(), "limit", product.weeklyLimit())));
                if (product.membershipDays() > 0) lore.add(messages.text(player,
                        "commerce.menu-membership-duration", Map.of("days", product.membershipDays())));
                lore.add(messages.text(player, owned ? "commerce.menu-owned" : "commerce.menu-buy"));
                menu.set(productSlots[index], display(product.material(), productName(player, product.id(), product.name()),
                        lore),
                        owned ? null : "buy:" + product.id());
            }
            int[] rewardSlots = {29, 31, 33};
            List<Reward> rewards = rewards();
            for (int index = 0; index < Math.min(rewards.size(), rewardSlots.length); index++) {
                Reward reward = rewards.get(index);
                boolean claimed = status.claimedRewards().contains(reward.id());
                boolean ready = !claimed && status.lifetimePaid().compareTo(reward.required()) >= 0;
                String state = messages.text(player, claimed ? "commerce.state-claimed"
                        : ready ? "commerce.state-ready" : "commerce.state-locked");
                menu.set(rewardSlots[index], display(reward.material(), rewardName(player, reward.id(), reward.name()),
                        List.of(messages.text(player, "commerce.menu-reward-state", Map.of(
                                "state", state, "required", reward.required().toPlainString())))), null);
            }
            menu.set(49, display(Material.CHEST, messages.text(player, "commerce.menu-claim"),
                    List.of(messages.text(player, "commerce.menu-claim-lore"))), "claim");
            menu.set(47, display(Material.BARREL, messages.text(player, "commerce.menu-supplies"),
                    List.of(messages.text(player, "commerce.menu-supplies-lore"))), "supplies");
            player.openInventory(menu.getInventory());
        }));
    }

    private void openSupplies(Player player) {
        commerce.status(player.getUniqueId()).thenCombine(economy.balance(player.getUniqueId()), SupplyView::new)
                .whenComplete((view, error) -> sync(() -> {
                    if (!currentSession(player)) return;
                    if (error != null) {
                        player.sendMessage(Component.text(messages.text(player, "commerce.store-load-error"), NamedTextColor.RED));
                        return;
                    }
                    boolean member = view.status().memberActive(Instant.now());
                    SupplyMenu menu = new SupplyMenu(Component.text(messages.text(player, "commerce.supply-title")));
                    menu.set(4, display(Material.GOLD_INGOT, messages.text(player, "commerce.supply-account"),
                            List.of(messages.text(player, "commerce.supply-balance", Map.of(
                                            "gold", view.gold().toPlainString())),
                                    membershipText(player, view.status()))), null);
                    int[] slots = {10, 11, 12, 14, 15, 16, 21, 23};
                    List<SupplyProduct> products = supplyProducts();
                    for (int index = 0; index < Math.min(products.size(), slots.length); index++) {
                        SupplyProduct product = products.get(index);
                        BigDecimal shown = member ? product.memberPrice() : product.publicPrice();
                        List<String> lore = new ArrayList<>();
                        lore.add(messages.text(player, "commerce.supply-amount", Map.of("amount", product.amount())));
                        lore.add(messages.text(player, "commerce.supply-price", Map.of(
                                "public", product.publicPrice().toPlainString(),
                                "member", product.memberPrice().toPlainString(),
                                "current", shown.toPlainString())));
                        lore.add(messages.text(player, "commerce.supply-limit", Map.of(
                                "limit", product.dailyLimit())));
                        lore.add(messages.text(player, member
                                ? "commerce.supply-member-active" : "commerce.supply-member-inactive"));
                        lore.add(messages.text(player, "commerce.supply-buy"));
                        menu.set(slots[index], display(product.material(), supplyName(player, product.id(), product.name()), lore),
                                "supply:" + product.id());
                    }
                    menu.set(40, display(Material.EMERALD, messages.text(player, "commerce.supply-market-title"),
                            List.of(messages.text(player, "commerce.supply-market-note"))), null);
                    menu.set(49, display(Material.COMPASS, messages.text(player, "common.back"),
                            List.of(messages.text(player, "common.click-back"))), "back");
                    player.openInventory(menu.getInventory());
                }));
    }

    private ItemStack display(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.LIGHT_PURPLE));
        meta.lore(loreLines.stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private boolean adjustCredits(CommandSender sender, String[] args) {
        if (args.length != 5) return false;
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        long amount;
        try {
            amount = Long.parseLong(args[3]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text(messages.text(sender, "commerce.starlight-number-error"), NamedTextColor.RED));
            return true;
        }
        boolean grant = args[2].equalsIgnoreCase("grant");
        boolean revoke = args[2].equalsIgnoreCase("revoke");
        if (!grant && !revoke) return false;
        if (amount <= 0) {
            sender.sendMessage(Component.text(messages.text(sender, "commerce.starlight-number-error"), NamedTextColor.RED));
            return true;
        }
        String permission = grant ? "rpg.commerce.starlight.grant" : "rpg.commerce.starlight.revoke";
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(Component.text(messages.text(sender, "commerce.operation-no-permission"), NamedTextColor.RED));
            return true;
        }
        if (revoke) amount = -amount;
        long delta = amount;
        String reference = args[4];
        getLogger().warning("AUDIT_PENDING actor=" + sender.getName() + " action=credits target="
                + target.getUniqueId() + " delta=" + delta + " reference=" + reference);
        commerce.adjustCredits(target.getUniqueId(), delta, reference, sender.getName())
                .whenComplete((result, error) -> sync(() -> {
                    if (error != null) {
                        sender.sendMessage(Component.text(messages.text(sender, "commerce.starlight-adjust-failed"), NamedTextColor.RED));
                    } else if (result.status() == CreditResult.Status.SUCCESS) {
                        getLogger().warning("AUDIT_COMMITTED actor=" + sender.getName() + " action=credits target="
                                + target.getUniqueId() + " delta=" + delta + " reference=" + reference);
                        String key = delta > 0 ? "commerce.starlight-grant-success"
                                : "commerce.starlight-revoke-success";
                        sender.sendMessage(Component.text(messages.text(sender, key, Map.of(
                                "balance", result.balance(), "daily", result.dailyGranted(),
                                "dailyLimit", result.dailyLimit(), "weekly", result.weeklyGranted(),
                                "weeklyLimit", result.weeklyLimit())), NamedTextColor.GREEN));
                    } else if (result.status() == CreditResult.Status.DUPLICATE) {
                        sender.sendMessage(Component.text(messages.text(sender, "commerce.duplicate-reference"), NamedTextColor.YELLOW));
                    } else if (result.status() == CreditResult.Status.INSUFFICIENT_FUNDS) {
                        sender.sendMessage(Component.text(messages.text(sender, "commerce.starlight-insufficient"), NamedTextColor.RED));
                    } else if (result.status() == CreditResult.Status.PER_GRANT_LIMIT_REACHED) {
                        sender.sendMessage(Component.text(messages.text(sender, "commerce.starlight-per-grant-limit", Map.of(
                                "limit", result.maximumPerGrant())), NamedTextColor.RED));
                    } else if (result.status() == CreditResult.Status.DAILY_LIMIT_REACHED) {
                        sender.sendMessage(Component.text(messages.text(sender, "commerce.starlight-daily-limit", Map.of(
                                "used", result.dailyGranted(), "limit", result.dailyLimit())), NamedTextColor.RED));
                    } else if (result.status() == CreditResult.Status.WEEKLY_LIMIT_REACHED) {
                        sender.sendMessage(Component.text(messages.text(sender, "commerce.starlight-weekly-limit", Map.of(
                                "used", result.weeklyGranted(), "limit", result.weeklyLimit())), NamedTextColor.RED));
                    } else if (result.status() == CreditResult.Status.BALANCE_LIMIT) {
                        sender.sendMessage(Component.text(messages.text(sender,
                                "commerce.starlight-balance-limit", Map.of("balance", result.balance())),
                                NamedTextColor.RED));
                    } else {
                        sender.sendMessage(Component.text(messages.text(sender, "commerce.starlight-invalid"), NamedTextColor.RED));
                    }
                }));
        return true;
    }

    private boolean recordPayment(CommandSender sender, String[] args) {
        if (args.length < 4 || args.length > 5 || !args[0].equalsIgnoreCase("payment")) {
            return false;
        }
        if (!sender.hasPermission("rpg.commerce.payment")) {
            sender.sendMessage(Component.text(messages.text(sender, "commerce.operation-no-permission"), NamedTextColor.RED));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        BigDecimal amount;
        int membershipDays = 0;
        try {
            amount = new BigDecimal(args[2]);
            if (args.length == 5) {
                membershipDays = Integer.parseInt(args[4]);
            }
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text(messages.text(sender, "commerce.payment-number-error"), NamedTextColor.RED));
            return true;
        }
        String reference = args[3];
        getLogger().warning("AUDIT_PENDING actor=" + sender.getName() + " action=payment target="
                + target.getUniqueId() + " amount=" + amount + " reference=" + reference + " membershipDays=" + membershipDays);
        commerce.recordPayment(target.getUniqueId(), amount, reference, membershipDays, sender.getName())
                .whenComplete((result, error) -> sync(() -> {
                    if (error != null) {
                        getLogger().severe("Payment ledger failed for reference=" + reference + ": " + error.getMessage());
                        sender.sendMessage(Component.text(messages.text(sender, "commerce.payment-failed"), NamedTextColor.RED));
                        return;
                    }
                    if (result.status() == CommerceResult.Status.SUCCESS) {
                        getLogger().warning("AUDIT_COMMITTED actor=" + sender.getName() + " action=payment target="
                                + target.getUniqueId() + " amount=" + amount + " reference=" + reference);
                        sender.sendMessage(Component.text(messages.text(sender, "commerce.payment-success", Map.of(
                                "amount", result.account().lifetimePaid().toPlainString())), NamedTextColor.GREEN));
                    } else if (result.status() == CommerceResult.Status.DUPLICATE) {
                        sender.sendMessage(Component.text(messages.text(sender, "commerce.payment-duplicate"), NamedTextColor.YELLOW));
                    } else if (result.status() == CommerceResult.Status.MEMBERSHIP_LIMIT_REACHED) {
                        sender.sendMessage(Component.text(messages.text(sender, "commerce.membership-limit-reached"), NamedTextColor.RED));
                    } else if (result.status() == CommerceResult.Status.LIFETIME_LIMIT_REACHED) {
                        sender.sendMessage(Component.text(messages.text(sender,
                                "commerce.payment-lifetime-limit"), NamedTextColor.RED));
                    } else {
                        sender.sendMessage(Component.text(messages.text(sender, "commerce.payment-invalid"), NamedTextColor.RED));
                    }
                }));
        return true;
    }

    private void showStatus(Player player) {
        commerce.status(player.getUniqueId()).whenComplete((status, error) -> sync(() -> {
            if (!currentSession(player)) return;
            if (error != null) {
                player.sendMessage(Component.text(messages.text(player, "commerce.store-load-error"), NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text(messages.text(player, "commerce.store-title"), NamedTextColor.GOLD));
            player.sendMessage(Component.text(messages.text(player, "commerce.lifetime-support", Map.of(
                    "amount", status.lifetimePaid().toPlainString())), NamedTextColor.YELLOW));
            player.sendMessage(Component.text(messages.text(player, "commerce.starlight-balance", Map.of(
                    "amount", status.credits())), NamedTextColor.AQUA));
            player.sendMessage(Component.text(membershipText(player, status), status.memberActive(Instant.now())
                    ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            for (Product product : products()) {
                player.sendMessage(Component.text(messages.text(player, "commerce.product", Map.of(
                        "id", product.id(), "name", productName(player, product.id(), product.name()),
                        "cost", product.points(), "limit", product.weeklyLimit())), NamedTextColor.LIGHT_PURPLE));
            }
            for (Reward reward : rewards()) {
                boolean ready = !status.claimedRewards().contains(reward.id())
                        && status.lifetimePaid().compareTo(reward.required()) >= 0;
                String stateKey = status.claimedRewards().contains(reward.id()) ? "commerce.state-claimed"
                        : ready ? "commerce.state-ready" : "commerce.state-locked";
                player.sendMessage(Component.text(messages.text(player, "commerce.reward", Map.of(
                        "state", messages.text(player, stateKey),
                        "name", rewardName(player, reward.id(), reward.name()),
                        "required", reward.required().toPlainString())),
                        ready ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            }
        }));
    }

    private void claimRewards(Player player) {
        if (!acquire(player)) {
            player.sendMessage(Component.text(messages.text(player, "commerce.purchase-pending"), NamedTextColor.YELLOW));
            return;
        }
        List<Reward> rewards = rewards();
        commerce.status(player.getUniqueId()).whenComplete((status, error) -> sync(() -> {
            if (!currentSession(player)) {
                release(player);
                return;
            }
            if (error != null) {
                release(player);
                player.sendMessage(Component.text(messages.text(player, "commerce.store-load-error"), NamedTextColor.RED));
                return;
            }
            List<Reward> eligible = rewards.stream()
                    .filter(reward -> status.lifetimePaid().compareTo(reward.required()) >= 0)
                    .filter(reward -> !status.claimedRewards().contains(reward.id()))
                    .toList();
            if (eligible.isEmpty()) {
                release(player);
                player.sendMessage(Component.text(messages.text(player, "commerce.no-rewards"), NamedTextColor.YELLOW));
                return;
            }
            claimNext(player, eligible, 0);
        }));
    }

    private void claimNext(Player player, List<Reward> rewards, int index) {
        if (!currentSession(player)) {
            release(player);
            return;
        }
        if (index >= rewards.size()) {
            release(player);
            player.sendMessage(Component.text(messages.text(player, "commerce.rewards-complete"), NamedTextColor.GREEN));
            return;
        }
        Reward reward = rewards.get(index);
        String payload = Base64.getEncoder().encodeToString(
                rewardItem(player, reward.material(), reward.amount(), rewardName(player, reward.id(), reward.name()),
                        reward.marker(), player.getUniqueId(), "CUMULATIVE")
                        .serializeAsBytes());
        commerce.claimReward(player.getUniqueId(), reward.id(), reward.required(), payload).whenComplete((result, error) -> sync(() -> {
            if (!currentSession(player)) {
                release(player);
                return;
            }
            if (error != null || result.status() != RewardClaimResult.Status.SUCCESS) {
                player.sendMessage(Component.text(messages.text(player, "commerce.reward-failed", Map.of(
                        "name", rewardName(player, reward.id(), reward.name()))), NamedTextColor.RED));
            } else {
                player.sendMessage(Component.text(messages.text(player, "commerce.reward-delivered", Map.of(
                        "name", rewardName(player, reward.id(), reward.name()))), NamedTextColor.GREEN));
            }
            claimNext(player, rewards, index + 1);
        }));
    }

    private void buy(Player player, String id) {
        Product product = products().stream().filter(candidate -> candidate.id().equalsIgnoreCase(id)).findFirst().orElse(null);
        if (product == null) {
            player.sendMessage(Component.text(messages.text(player, "commerce.product-missing"), NamedTextColor.RED));
            return;
        }
        if (cosmeticAlreadyUnlocked(player, product.marker())) {
            player.sendMessage(Component.text(messages.text(player, "commerce.cosmetic-owned"), NamedTextColor.YELLOW));
            return;
        }
        if (!acquire(player)) {
            player.sendMessage(Component.text(messages.text(player, "commerce.purchase-pending"), NamedTextColor.YELLOW));
            return;
        }
        String key = "store:" + player.getUniqueId() + ":" + java.util.UUID.randomUUID();
        java.util.concurrent.CompletionStage<CommercePurchaseResult> purchase;
        if (product.membershipDays() > 0) {
            purchase = commerce.purchaseMembershipWithCredits(player.getUniqueId(), product.id(), product.points(),
                    product.membershipDays(), key, weeklyPeriodKey(), product.weeklyLimit());
        } else {
            ItemStack item = rewardItem(player, product.material(), product.amount(),
                    productName(player, product.id(), product.name()), product.marker(), player.getUniqueId(), "STARLIGHT");
            String payload = Base64.getEncoder().encodeToString(item.serializeAsBytes());
            purchase = commerce.purchaseWithCredits(player.getUniqueId(), product.id(), product.points(), payload, key,
                    weeklyPeriodKey(), product.weeklyLimit());
        }
        purchase
                .whenComplete((result, error) -> sync(() -> {
                    release(player);
                    if (!currentSession(player)) return;
                    if (error != null) {
                        player.sendMessage(Component.text(messages.text(player, "commerce.purchase-failed"), NamedTextColor.RED));
                    } else if (result.status() == CommercePurchaseResult.Status.SUCCESS) {
                        String success = product.membershipDays() > 0
                                ? messages.text(player, "commerce.membership-purchase-success", Map.of(
                                        "until", DATE_TIME.format(result.account().memberUntil())))
                                : messages.text(player, "commerce.purchase-success");
                        player.sendMessage(Component.text(success, NamedTextColor.GREEN));
                    } else if (result.status() == CommercePurchaseResult.Status.INSUFFICIENT_CREDITS) {
                        player.sendMessage(Component.text(messages.text(player, "commerce.starlight-purchase-insufficient"), NamedTextColor.RED));
                    } else if (result.status() == CommercePurchaseResult.Status.LIMIT_REACHED) {
                        player.sendMessage(Component.text(messages.text(player, "commerce.purchase-limited", Map.of(
                                "used", result.usedPurchases(), "limit", result.maximumPurchases())), NamedTextColor.YELLOW));
                    } else if (result.status() == CommercePurchaseResult.Status.MEMBERSHIP_LIMIT_REACHED) {
                        player.sendMessage(Component.text(messages.text(player,
                                "commerce.membership-limit-reached"), NamedTextColor.YELLOW));
                    } else {
                        player.sendMessage(Component.text(messages.text(player, "commerce.purchase-no-change"), NamedTextColor.YELLOW));
                    }
                }));
    }

    private void buySupply(Player player, String id) {
        SupplyProduct product = supplyProducts().stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(id)).findFirst().orElse(null);
        if (product == null) {
            player.sendMessage(Component.text(messages.text(player, "commerce.supply-missing"), NamedTextColor.RED));
            return;
        }
        if (!acquire(player)) {
            player.sendMessage(Component.text(messages.text(player, "commerce.purchase-pending"), NamedTextColor.YELLOW));
            return;
        }
        // Supply items intentionally retain vanilla metadata. They stack, can be shared,
        // and never become a second class of account-bound commodity.
        ItemStack item = new ItemStack(product.material(), product.amount());
        String payload = Base64.getEncoder().encodeToString(item.serializeAsBytes());
        String key = "supply:" + player.getUniqueId() + ":" + java.util.UUID.randomUUID();
        commerce.purchaseSupplyWithGold(player.getUniqueId(), product.id(), product.publicPrice(),
                        product.memberPrice(), payload, key, dailyPeriodKey(), product.dailyLimit())
                .whenComplete((result, error) -> sync(() -> {
                    release(player);
                    if (!currentSession(player)) return;
                    if (error != null) {
                        player.sendMessage(Component.text(messages.text(player, "commerce.supply-failed"), NamedTextColor.RED));
                    } else if (result.status() == SupplyPurchaseResult.Status.SUCCESS) {
                        player.sendMessage(Component.text(messages.text(player, "commerce.supply-success", Map.of(
                                "price", result.chargedGold().toPlainString(),
                                "balance", result.goldBalance().toPlainString(),
                                "used", result.usedPurchases(), "limit", result.maximumPurchases())), NamedTextColor.GREEN));
                    } else if (result.status() == SupplyPurchaseResult.Status.INSUFFICIENT_FUNDS) {
                        player.sendMessage(Component.text(messages.text(player, "commerce.supply-insufficient", Map.of(
                                "price", result.chargedGold().toPlainString(),
                                "balance", result.goldBalance().toPlainString())), NamedTextColor.RED));
                    } else if (result.status() == SupplyPurchaseResult.Status.LIMIT_REACHED) {
                        player.sendMessage(Component.text(messages.text(player, "commerce.supply-limited", Map.of(
                                "used", result.usedPurchases(), "limit", result.maximumPurchases())), NamedTextColor.YELLOW));
                    } else {
                        player.sendMessage(Component.text(messages.text(player, "commerce.supply-no-change"), NamedTextColor.YELLOW));
                    }
                }));
    }

    private ItemStack rewardItem(
            Player audience, Material material, int amount, String name, String marker,
            java.util.UUID playerId, String itemSource
    ) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.LIGHT_PURPLE));
        String sourceName = messages.text(audience, itemSource.equals("STARLIGHT")
                ? "commerce.source-starlight" : "commerce.source-cumulative");
        meta.lore(List.of(Component.text(messages.text(audience, "commerce.bound-source", Map.of(
                "source", sourceName)), NamedTextColor.DARK_GRAY)));
        NamespacedKey model = rewardModel(marker);
        if (model != null) meta.setItemModel(model);
        meta.getPersistentDataContainer().set(rewardMarker, PersistentDataType.STRING, marker);
        meta.getPersistentDataContainer().set(owner, PersistentDataType.STRING, playerId.toString());
        meta.getPersistentDataContainer().set(source, PersistentDataType.STRING, itemSource);
        item.setItemMeta(meta);
        return item;
    }

    private boolean currentSession(Player player) {
        PlayerSession session = player == null ? null : authenticatedPlayers.get(player.getUniqueId());
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player
                && session != null && session.player() == player;
    }

    private boolean acquire(Player player) {
        PlayerSession session = authenticatedPlayers.get(player.getUniqueId());
        return currentSession(player) && session != null
                && purchaseInFlight.putIfAbsent(player.getUniqueId(),
                new OperationLease(player, session.token())) == null;
    }

    private void release(Player player) {
        UUID playerId = player.getUniqueId();
        OperationLease lease = purchaseInFlight.get(playerId);
        if (lease != null && lease.player() == player) purchaseInFlight.remove(playerId, lease);
    }

    private record PlayerSession(Player player, UUID token) {
    }

    private record OperationLease(Player player, UUID sessionToken) {
    }

    private NamespacedKey rewardModel(String marker) {
        return switch (marker) {
            case "protection_stone" -> new NamespacedKey("originalrpg", "protection_stone");
            case "lucky_charm" -> new NamespacedKey("originalrpg", "lucky_charm");
            default -> null;
        };
    }

    private List<Product> products() {
        return productCatalog;
    }

    private List<Product> loadProducts() {
        ConfigurationSection section = getConfig().getConfigurationSection("products");
        if (section == null) return List.of();
        List<Product> products = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection product = section.getConfigurationSection(id);
            if (product == null) continue;
            Material material = Material.matchMaterial(product.getString("material", "PAPER"));
            if (material == null) continue;
            long points = product.getLong("points", 0L);
            if (points <= 0) continue;
            String normalizedId = id.toLowerCase(Locale.ROOT);
            int amount = product.getInt("amount", 1);
            String marker = product.getString("marker", id);
            int weeklyLimit = product.getInt("weekly-limit", 1);
            int membershipDays = product.getInt("membership-days", 0);
            CommerceCatalogPolicy.Verdict verdict = CommerceCatalogPolicy.validateProduct(
                    normalizedId, material.name(), amount, marker, points, weeklyLimit, membershipDays);
            if (!verdict.accepted()) {
                getLogger().severe("Disabling Starlight product " + id + ": " + verdict.reason());
                continue;
            }
            products.add(new Product(normalizedId, points, material, amount,
                    product.getString("name", id), marker, weeklyLimit, membershipDays));
        }
        products.sort(Comparator.comparingLong(Product::points));
        return List.copyOf(products);
    }

    private List<Reward> rewards() {
        return rewardCatalog;
    }

    private List<Reward> loadRewards() {
        ConfigurationSection section = getConfig().getConfigurationSection("rewards");
        if (section == null) {
            return List.of();
        }
        List<Reward> rewards = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection reward = section.getConfigurationSection(id);
            if (reward == null) continue;
            Material material = Material.matchMaterial(reward.getString("material", "PAPER"));
            if (material == null) {
                getLogger().warning("Ignoring reward " + id + ": invalid material");
                continue;
            }
            try {
                String normalizedId = id.toLowerCase(Locale.ROOT);
                BigDecimal required = new BigDecimal(reward.getString("required-lifetime", "0.00"));
                int amount = reward.getInt("amount", 1);
                String marker = reward.getString("marker", id);
                CommerceCatalogPolicy.Verdict verdict = CommerceCatalogPolicy.validateReward(
                        normalizedId, material.name(), amount, marker, required);
                if (!verdict.accepted()) {
                    getLogger().severe("Disabling cumulative reward " + id + ": " + verdict.reason());
                    continue;
                }
                rewards.add(new Reward(normalizedId, required, material, amount,
                        reward.getString("name", id), marker));
            } catch (NumberFormatException exception) {
                getLogger().warning("Ignoring reward " + id + ": invalid required-lifetime");
            }
        }
        rewards.sort(Comparator.comparing(Reward::required));
        return List.copyOf(rewards);
    }

    private List<SupplyProduct> supplyProducts() {
        return supplyCatalog;
    }

    private List<SupplyProduct> loadSupplyProducts() {
        ConfigurationSection section = getConfig().getConfigurationSection("supply-shop.products");
        if (section == null) return List.of();
        List<SupplyProduct> products = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection product = section.getConfigurationSection(id);
            if (product == null) continue;
            Material material = Material.matchMaterial(product.getString("material", "PAPER"));
            if (material == null) {
                getLogger().severe("Disabling supply " + id + ": invalid material");
                continue;
            }
            try {
                BigDecimal publicPrice = new BigDecimal(product.getString("public-price", "0.00"))
                        .setScale(2, java.math.RoundingMode.HALF_UP);
                BigDecimal memberPrice = new BigDecimal(product.getString("member-price", "0.00"))
                        .setScale(2, java.math.RoundingMode.HALF_UP);
                int amount = product.getInt("amount", 0);
                int dailyLimit = product.getInt("daily-limit", 0);
                String normalizedId = id.toLowerCase(Locale.ROOT);
                SupplyCatalogPolicy.Verdict verdict = SupplyCatalogPolicy.validate(
                        normalizedId, material.name(), amount, publicPrice, memberPrice, dailyLimit);
                if (!verdict.accepted()) {
                    getLogger().severe("Disabling supply " + id + ": " + verdict.reason());
                    continue;
                }
                products.add(new SupplyProduct(normalizedId, material, amount,
                        product.getString("name", id), publicPrice, memberPrice,
                        dailyLimit));
            } catch (NumberFormatException exception) {
                getLogger().severe("Disabling supply " + id + ": invalid price");
            }
        }
        products.sort(Comparator.comparing(SupplyProduct::publicPrice));
        return List.copyOf(products);
    }

    private String membershipText(Player player, CommerceStatus status) {
        return status.memberActive(Instant.now())
                ? messages.text(player, "commerce.member-active", Map.of("until", DATE_TIME.format(status.memberUntil())))
                : messages.text(player, "commerce.member-inactive");
    }

    private String productName(Player player, String id, String fallback) {
        String key = "commerce.products." + id;
        String translated = messages.text(player, key);
        return translated.equals(key) ? fallback : translated;
    }

    private String rewardName(Player player, String id, String fallback) {
        String key = "commerce.rewards." + id;
        String translated = messages.text(player, key);
        return translated.equals(key) ? fallback : translated;
    }

    private String supplyName(Player player, String id, String fallback) {
        String key = "commerce.supplies." + id;
        String translated = messages.text(player, key);
        return translated.equals(key) ? fallback : translated;
    }

    private boolean cosmeticAlreadyUnlocked(Player player, String marker) {
        String key = switch (marker) {
            case "founder_cosmetic" -> "prismatic_unlocked";
            case "elytra_wings" -> "trail_unlocked_wings";
            case "elytra_comet" -> "trail_unlocked_comet";
            default -> null;
        };
        if (key == null) return false;
        return player.getPersistentDataContainer().getOrDefault(
                new NamespacedKey("rpgcosmetics", key), PersistentDataType.BYTE, (byte) 0) == (byte) 1;
    }

    private String weeklyPeriodKey() {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        WeekFields fields = WeekFields.ISO;
        return date.get(fields.weekBasedYear()) + "-W" + String.format(Locale.ROOT, "%02d",
                date.get(fields.weekOfWeekBasedYear()));
    }

    private String dailyPeriodKey() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
    }

    private PluginCommand command(String name) {
        return Objects.requireNonNull(getCommand(name), name + " command");
    }

    private void sync(Runnable action) {
        if (!isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(this, () -> {
                if (isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // Database operations remain authoritative when this plugin is stopping.
        }
    }

    private record Reward(
            String id,
            BigDecimal required,
            Material material,
            int amount,
            String name,
            String marker
    ) {
    }

    private record Product(
            String id, long points, Material material, int amount, String name, String marker,
            int weeklyLimit, int membershipDays
    ) {
    }

    private record SupplyProduct(
            String id,
            Material material,
            int amount,
            String name,
            BigDecimal publicPrice,
            BigDecimal memberPrice,
            int dailyLimit
    ) {
    }

    private record SupplyView(CommerceStatus status, BigDecimal gold) {
    }
}
