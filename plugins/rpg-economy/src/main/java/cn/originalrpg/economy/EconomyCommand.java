package cn.originalrpg.economy;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.ActivityQuota;
import cn.originalrpg.api.model.ActivityRewardResult;
import cn.originalrpg.api.model.EconomyActivity;
import cn.originalrpg.api.model.EconomyProgress;
import cn.originalrpg.api.model.EconomyPurchaseResult;
import cn.originalrpg.api.model.CurrencyExchangeQuote;
import cn.originalrpg.api.model.CurrencyExchangeResult;
import cn.originalrpg.api.model.WealthBalance;
import cn.originalrpg.api.service.ActivityEconomyService;
import cn.originalrpg.api.service.CurrencyExchangeService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.MarketService;
import cn.originalrpg.api.service.MerchantService;
import cn.originalrpg.api.model.MerchantTransactionResult;
import cn.originalrpg.api.model.MerchantUsage;
import cn.originalrpg.api.model.CommissionEvidenceClaimResult;
import cn.originalrpg.api.model.CommissionEvidenceSnapshot;
import cn.originalrpg.api.model.SupplyPurchaseResult;
import cn.originalrpg.api.service.CommerceService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDate;
import java.time.ZoneId;

final class EconomyCommand implements CommandExecutor, TabCompleter, Listener {
    private static final List<Integer> CONTENT_SLOTS = List.of(10, 11, 12, 13, 14, 15, 16);
    private static final List<Integer> COMMISSION_SLOTS = List.of(11, 13, 15);

    private final Plugin plugin;
    private final ActivityEconomyService economy;
    private final CurrencyExchangeService currencyExchange;
    private final CommerceService commerce;
    private final MarketService market;
    private final MerchantService merchant;
    private final MessageService messages;
    private final CommissionEvidenceTracker commissionEvidence;
    private final ShopIntegrityValidator.Report shopIntegrity;
    /**
     * Connection-scoped operation owner.  A UUID-only latch lets a callback
     * captured by an old login remove the latch acquired by a newer login.
     * Retaining the exact Player handle makes release a compare-and-remove.
     */
    private final Map<UUID, OperationLease> pending = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSession> authenticatedPlayers = new ConcurrentHashMap<>();
    private final NamespacedKey rewardMarker = NamespacedKey.fromString("rpgcommerce:reward_marker");
    private final NamespacedKey owner = NamespacedKey.fromString("rpgcommerce:owner");
    private final NamespacedKey source = NamespacedKey.fromString("rpgcommerce:source");
    private final NamespacedKey adaptiveMaterial = NamespacedKey.fromString("rpgmobs:adaptive_material");
    private final NamespacedKey bossMaterial = NamespacedKey.fromString("rpgdungeons:boss_material");
    private final NamespacedKey blackMarketOffer = NamespacedKey.fromString("rpgeconomy:black_market_offer");
    private final NamespacedKey blackMarketIssue = NamespacedKey.fromString("rpgeconomy:black_market_issue");
    private final NamespacedKey receiptKey;

    EconomyCommand(
            Plugin plugin,
            ActivityEconomyService economy,
            CurrencyExchangeService currencyExchange,
            CommerceService commerce,
            MarketService market,
            MerchantService merchant,
            MessageService messages,
            CommissionEvidenceTracker commissionEvidence,
            ShopIntegrityValidator.Report shopIntegrity
    ) {
        this.plugin = plugin;
        this.economy = economy;
        this.currencyExchange = currencyExchange;
        this.commerce = commerce;
        this.market = market;
        this.merchant = merchant;
        this.messages = messages;
        this.commissionEvidence = commissionEvidence;
        this.shopIntegrity = shopIntegrity;
        receiptKey = new NamespacedKey(plugin, "economy_receipts");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        if (!ready(player)) return true;
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "status" -> { showStatus(player); yield true; }
            case "vanilla" -> { showVanilla(player); yield true; }
            case "commissions" -> { openCommissions(player); yield true; }
            case "shop" -> { openShop(player); yield true; }
            case "merchant" -> { openBlackMarket(player); yield true; }
            case "exchange" -> {
                if (args.length == 1) showExchange(player);
                else if (args.length == 3) exchange(player, args[1], args[2]);
                else player.sendMessage(Component.text(messages.text(player, "exchange.invalid"), NamedTextColor.RED));
                yield true;
            }
            default -> false;
        };
    }

    private void showVanilla(Player player) {
        player.sendMessage(Component.text(messages.text(player, "economy.vanilla-title"), NamedTextColor.GOLD));
        for (String line : messages.lines(player, "economy.vanilla-lines")) {
            player.sendMessage(Component.text("- " + line, NamedTextColor.GRAY));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().getTopInventory().getHolder() instanceof CommissionMenu menu) {
            event.setCancelled(true);
            String id = menu.commissionAt(event.getRawSlot());
            if (id == null) return;
            if (event.getClick() == ClickType.SHIFT_LEFT) submitCommission(player, id);
            else player.sendActionBar(Component.text(
                    messages.text(player, "economy.confirm-required"), NamedTextColor.YELLOW));
        } else if (event.getView().getTopInventory().getHolder() instanceof EarnedShopMenu menu) {
            event.setCancelled(true);
            String id = menu.productAt(event.getRawSlot());
            if (id == null) return;
            if (event.getClick() == ClickType.SHIFT_LEFT) buyProduct(player, id);
            else player.sendActionBar(Component.text(
                    messages.text(player, "economy.confirm-required"), NamedTextColor.YELLOW));
        } else if (event.getView().getTopInventory().getHolder() instanceof BlackMarketMenu menu) {
            event.setCancelled(true);
            String id = menu.offerAt(event.getRawSlot());
            if (id == null) return;
            if (event.getClick() == ClickType.SHIFT_RIGHT) {
                sellToMerchant(player, id);
            } else if (event.getClick() == ClickType.SHIFT_LEFT) {
                buyFromMerchant(player, id);
            } else if (event.getClick().isRightClick()) {
                player.sendActionBar(Component.text(
                        messages.text(player, "merchant.confirm-sell"), NamedTextColor.YELLOW));
            } else if (event.getClick().isLeftClick()) {
                player.sendActionBar(Component.text(
                        messages.text(player, "merchant.confirm-buy"), NamedTextColor.YELLOW));
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CommissionMenu
                || event.getView().getTopInventory().getHolder() instanceof EarnedShopMenu
                || event.getView().getTopInventory().getHolder() instanceof BlackMarketMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        authenticatedPlayers.put(event.player().getUniqueId(),
                new PlayerSession(event.player(), UUID.randomUUID()));
        Bukkit.getScheduler().runTaskLater(plugin, () -> recoverNextReceipt(event.player()), 20L);
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        Player player = event.player();
        PlayerSession session = authenticatedPlayers.get(player.getUniqueId());
        if (session != null && session.player() == player) {
            authenticatedPlayers.remove(player.getUniqueId(), session);
        }
        // Do not remove durable receipts or submitted settlements. Their
        // callbacks/recovery own the pending gate. Only hide the stale menu.
        Object holder = player.getOpenInventory().getTopInventory().getHolder();
        if (holder instanceof CommissionMenu || holder instanceof EarnedShopMenu
                || holder instanceof BlackMarketMenu) player.closeInventory();
    }

    private void showStatus(Player player) {
        economy.progress(player.getUniqueId())
                .thenCombine(currencyExchange.balance(player.getUniqueId()), ProgressAndBalance::new)
                .whenComplete((data, error) -> sync(() -> {
            if (!ready(player)) return;
            if (error != null) {
                player.sendMessage(Component.text(messages.text(player, "common.data-error"), NamedTextColor.RED));
                return;
            }
            EconomyProgress progress = data.progress();
            WealthBalance balance = data.balance();
            player.sendMessage(Component.text(messages.text(player, "economy.status-title"), NamedTextColor.GOLD));
            player.sendMessage(Component.text(balanceText(player, balance), NamedTextColor.YELLOW));
            for (EconomyActivity activity : EconomyActivity.values()) {
                ActivityQuota used = progress.quota(activity);
                player.sendMessage(Component.text(messages.text(player, "economy.activity-line", Map.of(
                        "activity", activityName(player, activity),
                        "gold", used.goldEarned().toPlainString(),
                        "gold_cap", capGold(activity).toPlainString(),
                        "merit", used.meritEarned(),
                        "merit_cap", capMerit(activity)
                )), NamedTextColor.GRAY));
            }
            player.sendMessage(Component.text(messages.text(player, "economy.command-help"), NamedTextColor.AQUA));
        }));
    }

    private void showExchange(Player player) {
        currencyExchange.quotes(player.getUniqueId()).whenComplete((quotes, error) -> sync(() -> {
            if (!ready(player)) return;
            if (error != null) {
                player.sendMessage(Component.text(messages.text(player, "common.data-error"), NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text(messages.text(player, "exchange.title"), NamedTextColor.GOLD));
            player.sendMessage(Component.text(messages.text(player, "exchange.help"), NamedTextColor.AQUA));
            for (CurrencyExchangeQuote quote : quotes) {
                player.sendMessage(Component.text(messages.text(player, "exchange.quote", Map.of(
                        "route", routeName(player, quote.routeId()),
                        "cost", amounts(player, quote.goldCost(), quote.meritCost(), quote.starlightCost()),
                        "reward", amounts(player, quote.goldReward(), quote.meritReward(), quote.starlightReward()),
                        "daily", quote.dailyRemaining(),
                        "weekly", quote.weeklyRemaining()
                )), NamedTextColor.GRAY));
                player.sendMessage(Component.text("/economy exchange " + quote.routeId() + " 1", NamedTextColor.DARK_GRAY));
            }
        }));
    }

    private void exchange(Player player, String route, String amountText) {
        long units;
        try {
            units = Long.parseLong(amountText);
        } catch (NumberFormatException exception) {
            player.sendMessage(Component.text(messages.text(player, "exchange.invalid"), NamedTextColor.RED));
            return;
        }
        if (!acquire(player)) {
            player.sendMessage(Component.text(messages.text(player, "exchange.pending"), NamedTextColor.YELLOW));
            return;
        }
        String key = "exchange:" + player.getUniqueId() + ":" + UUID.randomUUID();
        currencyExchange.exchange(player.getUniqueId(), route, units, key).whenComplete((result, error) -> sync(() -> {
            release(player);
            if (!ready(player)) return;
            if (error != null) {
                player.sendMessage(Component.text(messages.text(player, "exchange.failed"), NamedTextColor.RED));
                return;
            }
            switch (result.status()) {
                case SUCCESS -> player.sendMessage(Component.text(messages.text(player, "exchange.success", Map.of(
                        "route", routeName(player, result.routeId()),
                        "units", result.units(),
                        "balance", balanceText(player, result.balance())
                )), NamedTextColor.GREEN));
                case DUPLICATE -> player.sendMessage(Component.text(messages.text(player, "exchange.duplicate"), NamedTextColor.YELLOW));
                case INSUFFICIENT_FUNDS -> player.sendMessage(Component.text(messages.text(player, "exchange.insufficient"), NamedTextColor.RED));
                case LIMIT_EXCEEDED -> player.sendMessage(Component.text(messages.text(player, "exchange.limited"), NamedTextColor.YELLOW));
                case BALANCE_LIMIT -> player.sendMessage(Component.text(messages.text(player, "exchange.balance-limit"), NamedTextColor.YELLOW));
                case INVALID -> player.sendMessage(Component.text(messages.text(player, "exchange.invalid"), NamedTextColor.RED));
            }
        }));
    }

    private String routeName(Player player, String route) {
        return messages.text(player, "exchange.routes." + route);
    }

    private String balanceText(Player player, WealthBalance balance) {
        return messages.text(player, "currency.balance", Map.of(
                "gold", balance.gold().toPlainString(),
                "merit", balance.merit(),
                "starlight", balance.starlight()
        ));
    }

    private String amounts(Player player, BigDecimal gold, long merit, long starlight) {
        List<String> values = new ArrayList<>();
        if (gold.signum() > 0) values.add(gold.toPlainString() + " " + messages.text(player, "currency.gold"));
        if (merit > 0) values.add(merit + " " + messages.text(player, "currency.merit"));
        if (starlight > 0) values.add(starlight + " " + messages.text(player, "currency.starlight"));
        return String.join(" + ", values);
    }

    private void openCommissions(Player player) {
        CommissionPeriod period = commissionPeriod();
        Map<Integer, String> slots = visibleCommissionSlots(period);
        commissionEvidence.snapshot(player).whenComplete((evidence, error) -> sync(() -> {
            if (!ready(player)) return;
            if (error != null || evidence == null) {
                plugin.getLogger().warning("Commission evidence menu failed for " + player.getUniqueId()
                        + ": " + (error == null ? "empty result" : error.getMessage()));
                player.sendMessage(Component.text(messages.text(player, "common.data-error"), NamedTextColor.RED));
                return;
            }
            CommissionMenu menu = new CommissionMenu(slots, messages.text(player, "economy.commissions-title"));
        menu.getInventory().setItem(4, named(Material.WRITABLE_BOOK,
                messages.text(player, "economy.commission-evidence-title"), List.of(
                        Component.text(messages.text(player, "economy.commission-evidence-summary", Map.of(
                                "available", evidence.available(), "earned", evidence.earned(),
                                "cap", evidence.dailyCap())), NamedTextColor.GREEN),
                        Component.text(messages.text(player, "economy.commission-total-summary", Map.of(
                                "used", evidence.submissions(), "limit", commissionTotalDailyLimit())),
                                NamedTextColor.GRAY),
                        Component.text(messages.text(player, "economy.commission-rotation", Map.of(
                                "date", period.refreshDate().toString())), NamedTextColor.DARK_GRAY)
                )));
        slots.forEach((slot, id) -> {
            Commission entry = commission(id);
            if (entry != null) menu.getInventory().setItem(slot, named(entry.material(),
                    translated(player, "economy.commission-names." + id, entry.name()), List.of(
                    Component.text(messages.text(player, "economy.commission-deliver", Map.of(
                            "amount", entry.amount(), "material", entry.material().name())), NamedTextColor.GRAY),
                    Component.text(messages.text(player, "economy.commission-reward", Map.of(
                            "gold", entry.gold().toPlainString(),
                            "merit", entry.merit() > 0 ? " + " + entry.merit() + " "
                                    + messages.text(player, "currency.merit") : "")), NamedTextColor.GOLD),
                    Component.text(messages.text(player, "economy.commission-evidence-required", Map.of(
                            "cost", entry.evidenceCost())), NamedTextColor.GREEN),
                    Component.text(messages.text(player, "economy.commission-daily-used", Map.of(
                            "used", evidence.offerClaims(id),
                            "limit", entry.dailyLimit())), NamedTextColor.GRAY),
                    Component.text(messages.text(player, "economy.commission-click"), NamedTextColor.GREEN)
            )));
        });
        player.openInventory(menu.getInventory());
        }));
    }

    private void openShop(Player player) {
        Map<Integer, String> slots = assignedIds("earned-shop");
        EarnedShopMenu menu = new EarnedShopMenu(slots, messages.text(player, "economy.shop-title"));
        slots.forEach((slot, id) -> {
            Product product = product(id, player.getUniqueId());
            if (product != null) menu.getInventory().setItem(slot, named(product.material(),
                    translated(player, "economy.product-names." + id, product.name()),
                    productLore(player, product)));
        });
        player.openInventory(menu.getInventory());
    }

    private List<Component> productLore(Player player, Product product) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(messages.text(player, "economy.shop-price", Map.of(
                "gold", product.gold().toPlainString(), "merit", product.merit())), NamedTextColor.GOLD));
        lore.add(Component.text(messages.text(player, "economy.shop-bound"), NamedTextColor.GRAY));
        if (product.lifetimeLimit() > 0) lore.add(Component.text(messages.text(player,
                "economy.shop-lifetime-limit", Map.of("limit", product.lifetimeLimit())), NamedTextColor.YELLOW));
        lore.add(Component.text(messages.text(player, "economy.shop-click"), NamedTextColor.GREEN));
        return List.copyOf(lore);
    }

    private void openBlackMarket(Player player) {
        PeriodInfo period = merchantPeriod();
        merchant.usage(player.getUniqueId(), period.key()).whenComplete((usage, error) -> sync(() -> {
            if (!ready(player)) return;
            if (error != null) {
                player.sendMessage(Component.text(messages.text(player, "merchant.usage-failed"), NamedTextColor.RED));
                return;
            }
            renderBlackMarket(player, period, usage);
        }));
    }

    private void renderBlackMarket(Player player, PeriodInfo period, Map<String, MerchantUsage> usage) {
        BlackMarketMenu menu = new BlackMarketMenu(messages.text(player, "merchant.title"));
        ConfigurationSection offers = plugin.getConfig().getConfigurationSection("black-market.offers");
        if (offers != null) {
            int[] slots = {10, 12, 14, 16, 28, 30, 32, 34};
            List<String> rotated = new ArrayList<>(offers.getKeys(false));
            rotated.sort((left, right) -> Long.compareUnsigned(
                    merchantOfferScore(period, left), merchantOfferScore(period, right)));
            int visible = Math.clamp(plugin.getConfig().getInt(
                    "black-market.visible-offers", 3), 1, slots.length);
            int index = 0;
            for (String id : rotated) {
                if (index >= visible) break;
                MerchantOffer offer = merchantOffer(id);
                if (offer == null) continue;
                MerchantPrices prices = merchantPrices(offer, period);
                MerchantUsage used = usage.getOrDefault(id, MerchantUsage.empty());
                ItemStack display = merchantItem(player, offer, period.key(), null);
                ItemMeta meta = display.getItemMeta();
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.add(Component.empty());
                lore.add(Component.text(messages.text(player, "merchant.reference-price", Map.of(
                        "price", offer.basePrice().toPlainString())), NamedTextColor.GRAY));
                lore.add(Component.text(messages.text(player, "merchant.sell-price", Map.of(
                        "price", prices.sellPrice().toPlainString(), "used", used.purchasedUnits(),
                        "limit", offer.purchaseLimit())), NamedTextColor.GOLD));
                lore.add(Component.text(messages.text(player, "merchant.buyback-price", Map.of(
                        "price", prices.buybackPrice().toPlainString(), "used", used.buybackUnits(),
                        "limit", offer.buybackLimit())), NamedTextColor.GREEN));
                lore.add(Component.text(messages.text(player, "merchant.controls"), NamedTextColor.AQUA));
                lore.add(Component.text(messages.text(player, "merchant.refresh", Map.of(
                        "date", period.refreshDate().toString())), NamedTextColor.DARK_GRAY));
                meta.lore(lore);
                display.setItemMeta(meta);
                menu.set(slots[index++], id, display);
            }
        }
        player.openInventory(menu.getInventory());
    }

    private void buyFromMerchant(Player player, String id) {
        MerchantOffer offer = merchantOffer(id);
        if (offer == null || !acquire(player)) return;
        PeriodInfo period = merchantPeriod();
        MerchantPrices prices = merchantPrices(offer, period);
        String issuanceId = UUID.randomUUID().toString();
        ItemStack item = merchantItem(player, offer, period.key(), issuanceId);
        String payload = Base64.getEncoder().encodeToString(item.serializeAsBytes());
        String key = "merchant-buy:" + UUID.randomUUID();
        merchant.purchase(player.getUniqueId(), id, period.key(), 1, offer.purchaseLimit(),
                prices.sellPrice(), payload, key).whenComplete((result, error) -> sync(() -> {
            release(player);
            if (!ready(player)) return;
            if (error != null) {
                player.sendMessage(Component.text(messages.text(player, "merchant.failed"), NamedTextColor.RED));
                return;
            }
            if (result.status() == MerchantTransactionResult.Status.SUCCESS) {
                player.sendMessage(Component.text(messages.text(player, "merchant.purchase-success", Map.of(
                        "price", result.goldDelta().abs().toPlainString(), "used", result.usedUnits(),
                        "limit", result.maximumUnits())), NamedTextColor.GREEN));
                openBlackMarket(player);
            } else {
                player.sendMessage(Component.text(messages.text(player, "merchant.rejected", Map.of(
                        "status", merchantStatus(player, result.status()))), NamedTextColor.RED));
            }
        }));
    }

    private void sellToMerchant(Player player, String id) {
        MerchantOffer offer = merchantOffer(id);
        if (offer == null || !acquire(player)) return;
        PeriodInfo period = merchantPeriod();
        MerchantPrices prices = merchantPrices(offer, period);
        String issuanceId = completeIssuance(player, offer, period.key());
        if (issuanceId == null) {
            release(player);
            player.sendMessage(Component.text(messages.text(player, "merchant.item-required", Map.of(
                    "amount", offer.amount(), "item", merchantName(player, offer.id()))), NamedTextColor.RED));
            return;
        }
        ItemStack refund = merchantItem(player, offer, period.key(), issuanceId);
        String refundPayload = Base64.getEncoder().encodeToString(refund.serializeAsBytes());
        UUID refundId = UUID.randomUUID();
        // The issuance id survives item duplication. A deterministic operation key
        // makes every copy replay the first settlement instead of minting Gold again.
        String key = "merchant-buyback:" + issuanceId;
        EconomyReceiptCodec.Receipt receipt = new EconomyReceiptCodec.Receipt(
                EconomyReceiptCodec.Kind.MERCHANT_BUYBACK, EconomyReceiptCodec.Stage.PREPARED,
                refundId, key, id, period.key(), prices.buybackPrice(), 0L,
                offer.buybackLimit(), EconomyReceiptCodec.Rejection.NONE, refundPayload);
        if (!addReceipt(player, receipt)) {
            release(player);
            player.sendMessage(Component.text(messages.text(player, "economy.receipt-cap"), NamedTextColor.RED));
            return;
        }
        if (!removeOffer(player, offer, period.key(), issuanceId)) {
            removeReceipt(player, receipt.id());
            release(player);
            player.sendMessage(Component.text(messages.text(player, "merchant.item-required", Map.of(
                    "amount", offer.amount(), "item", merchantName(player, offer.id()))), NamedTextColor.RED));
            return;
        }
        receipt = updateReceipt(player, receipt.withStage(EconomyReceiptCodec.Stage.ESCROWED));
        settleMerchantReceipt(player, receipt, false);
    }

    private void settleMerchantReceipt(
            Player player, EconomyReceiptCodec.Receipt receipt, boolean recovering
    ) {
        merchant.buyback(player.getUniqueId(), receipt.subjectId(), receipt.periodKey(), 1,
                receipt.maximumUnits(), receipt.gold(), receipt.operationKey()).whenComplete((result, error) -> sync(() -> {
            if (error != null) {
                deferReceiptAttempt(player);
                if (ready(player)) player.sendMessage(Component.text(
                        messages.text(player, "merchant.pending-recovery"), NamedTextColor.YELLOW));
                return;
            }
            if (result.status() == MerchantTransactionResult.Status.SUCCESS) {
                if (!ready(player)) {
                    finishReceiptAttempt(player, recovering);
                    return;
                }
                removeReceipt(player, receipt.id());
                finishReceiptAttempt(player, recovering);
                player.sendMessage(Component.text(messages.text(player, "merchant.buyback-success", Map.of(
                        "price", result.goldDelta().abs().toPlainString(), "used", result.usedUnits(),
                        "limit", result.maximumUnits())), NamedTextColor.GREEN));
                openBlackMarket(player);
                return;
            }
            if (result.status() == MerchantTransactionResult.Status.DUPLICATE) {
                // A recovered receipt and a copied issuance deliberately converge here.
                // The original transaction already owns the only credit operation.
                if (!ready(player)) {
                    finishReceiptAttempt(player, recovering);
                    return;
                }
                removeReceipt(player, receipt.id());
                finishReceiptAttempt(player, recovering);
                player.sendMessage(Component.text(messages.text(
                        player, "merchant.buyback-duplicate"), NamedTextColor.YELLOW));
                openBlackMarket(player);
                return;
            }
            queueMerchantRefund(player, receipt, recovering, result.status());
        }));
    }

    private void queueMerchantRefund(
            Player player, EconomyReceiptCodec.Receipt receipt, boolean recovering,
            MerchantTransactionResult.Status rejection
    ) {
        if (!ready(player)) {
            finishReceiptAttempt(player, recovering);
            return;
        }
        EconomyReceiptCodec.Receipt refundPending = receipt.stage() == EconomyReceiptCodec.Stage.REFUND_PENDING
                ? receipt
                : updateReceipt(player, receipt.withRejection(merchantRejection(rejection))
                        .withStage(EconomyReceiptCodec.Stage.REFUND_PENDING));
        market.enqueueDelivery(refundPending.id(), player.getUniqueId(), refundPending.itemPayload(),
                        "black-market-refund:" + refundPending.subjectId())
                .whenComplete((queued, error) -> sync(() -> {
                    if (error != null || !queued) {
                        plugin.getLogger().severe("Black-market refund failed for " + player.getUniqueId()
                                + " delivery " + refundPending.id());
                        if (ready(player)) player.sendMessage(Component.text(
                                messages.text(player, "merchant.refund-alert"), NamedTextColor.RED));
                        deferReceiptAttempt(player);
                    } else {
                        // A stale Player object must never save over a newer login. Keep the
                        // idempotent receipt; the current session will replay the already queued
                        // delivery and then clear it without duplicating the item.
                        if (ready(player)) {
                            removeReceipt(player, refundPending.id());
                            player.sendMessage(Component.text(messages.text(
                                    player, refundPending.rejection() == EconomyReceiptCodec.Rejection.BALANCE_LIMIT
                                            ? "merchant.buyback-balance-limit" : "merchant.refunded"),
                                    NamedTextColor.YELLOW));
                        }
                        finishReceiptAttempt(player, recovering);
                    }
                }));
    }

    private EconomyReceiptCodec.Rejection merchantRejection(MerchantTransactionResult.Status status) {
        return status == MerchantTransactionResult.Status.BALANCE_LIMIT
                ? EconomyReceiptCodec.Rejection.BALANCE_LIMIT : EconomyReceiptCodec.Rejection.INVALID;
    }

    private MerchantOffer merchantOffer(String id) {
        if (!shopIntegrity.merchantOfferEnabled(id)) return null;
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("black-market.offers." + id);
        if (section == null) return null;
        Material material = Material.matchMaterial(section.getString("material", "PAPER"));
        if (material == null) return null;
        int amount = Math.clamp(section.getInt("amount", 1), 1, material.getMaxStackSize());
        BigDecimal base = new BigDecimal(section.getString("base-price", "100.00"));
        BigDecimal minimum = new BigDecimal(section.getString("sell-multiplier-minimum", "0.80"));
        BigDecimal maximum = new BigDecimal(section.getString("sell-multiplier-maximum", "1.80"));
        if (base.signum() <= 0 || minimum.signum() <= 0 || maximum.compareTo(minimum) < 0) return null;
        BigDecimal configuredBuyback = BigDecimal.valueOf(
                Math.clamp(section.getDouble("buyback-ratio", 0.55), 0.01, 0.80));
        BigDecimal minimumRoundtripLoss = BigDecimal.valueOf(Math.clamp(plugin.getConfig().getDouble(
                "black-market.minimum-roundtrip-loss", 0.10), 0.05, 0.50));
        BigDecimal buybackRatio = MerchantPricing.safeBuybackRatio(
                configuredBuyback, minimum, maximum, minimumRoundtripLoss);
        return new MerchantOffer(id, material, amount, section.getString("marker", "none"),
                section.getString("marker-value", id), section.getString("model", ""), base, minimum, maximum,
                buybackRatio, Math.clamp(section.getInt("purchase-limit", 3), 1, 1000),
                Math.clamp(section.getInt("buyback-limit", 2), 1, 1000));
    }

    private ItemStack merchantItem(Player player, MerchantOffer offer, String periodKey, String issuanceId) {
        ItemStack item = new ItemStack(offer.material(), offer.amount());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(merchantName(player, offer.id()), NamedTextColor.LIGHT_PURPLE));
        meta.lore(List.of(Component.text(messages.text(player, "merchant.item-source"), NamedTextColor.DARK_GRAY)));
        if (!offer.model().isBlank()) {
            NamespacedKey model = NamespacedKey.fromString("originalrpg:" + offer.model());
            if (model != null) meta.setItemModel(model);
        }
        switch (offer.marker()) {
            case "adaptive" -> {
                if (adaptiveMaterial != null) meta.getPersistentDataContainer()
                        .set(adaptiveMaterial, PersistentDataType.BYTE, (byte) 1);
            }
            case "boss" -> {
                if (bossMaterial != null) meta.getPersistentDataContainer()
                        .set(bossMaterial, PersistentDataType.STRING, offer.markerValue());
            }
            case "reward" -> {
                if (rewardMarker != null) meta.getPersistentDataContainer()
                        .set(rewardMarker, PersistentDataType.STRING, offer.markerValue());
                if (owner != null) meta.getPersistentDataContainer()
                        .set(owner, PersistentDataType.STRING, player.getUniqueId().toString());
                if (source != null) meta.getPersistentDataContainer()
                        .set(source, PersistentDataType.STRING, "BLACK_MARKET");
            }
            default -> { }
        }
        if (blackMarketOffer != null) meta.getPersistentDataContainer()
                .set(blackMarketOffer, PersistentDataType.STRING, periodKey + ":" + offer.id());
        if (blackMarketIssue != null && issuanceId != null) meta.getPersistentDataContainer()
                .set(blackMarketIssue, PersistentDataType.STRING, issuanceId);
        item.setItemMeta(meta);
        return item;
    }

    private String completeIssuance(Player player, MerchantOffer offer, String periodKey) {
        Map<String, Integer> amounts = new LinkedHashMap<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            String issuanceId = issuanceOf(player, item, offer, periodKey);
            if (issuanceId != null) amounts.merge(issuanceId, item.getAmount(), Integer::sum);
        }
        return amounts.entrySet().stream()
                .filter(entry -> entry.getValue() >= offer.amount())
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    private boolean removeOffer(Player player, MerchantOffer offer, String periodKey, String issuanceId) {
        int available = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (matchesOffer(player, item, offer, periodKey, issuanceId)) available += item.getAmount();
        }
        if (available < offer.amount()) return false;
        int remaining = offer.amount();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (remaining == 0) break;
            if (!matchesOffer(player, item, offer, periodKey, issuanceId)) continue;
            int taken = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - taken);
            remaining -= taken;
        }
        return true;
    }

    private boolean matchesOffer(
            Player player, ItemStack item, MerchantOffer offer, String periodKey, String issuanceId
    ) {
        return issuanceId != null && issuanceId.equals(issuanceOf(player, item, offer, periodKey));
    }

    private String issuanceOf(Player player, ItemStack item, MerchantOffer offer, String periodKey) {
        if (item == null || item.getType() != offer.material() || item.getItemMeta() == null) return null;
        var data = item.getItemMeta().getPersistentDataContainer();
        if (blackMarketOffer == null || !ShopIntegrityValidator.matchesMerchantProvenance(
                periodKey, offer.id(), data.get(blackMarketOffer, PersistentDataType.STRING))) return null;
        boolean markerMatches = switch (offer.marker()) {
            case "adaptive" -> adaptiveMaterial != null && data.has(adaptiveMaterial, PersistentDataType.BYTE);
            case "boss" -> bossMaterial != null && offer.markerValue().equals(
                    data.get(bossMaterial, PersistentDataType.STRING));
            case "reward" -> rewardMarker != null && owner != null && offer.markerValue().equals(
                    data.get(rewardMarker, PersistentDataType.STRING)) && player.getUniqueId().toString().equals(
                    data.get(owner, PersistentDataType.STRING));
            default -> false;
        };
        if (!markerMatches || blackMarketIssue == null) return null;
        String issuanceId = data.get(blackMarketIssue, PersistentDataType.STRING);
        if (issuanceId == null) return null;
        try {
            UUID parsed = UUID.fromString(issuanceId);
            return parsed.toString().equals(issuanceId) ? issuanceId : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private MerchantPrices merchantPrices(MerchantOffer offer, PeriodInfo period) {
        long mixed = mix64(period.index() ^ ((long) offer.id().hashCode() << 32));
        MerchantPricing.Prices prices = MerchantPricing.prices(offer.basePrice(), offer.minimumMultiplier(),
                offer.maximumMultiplier(), offer.buybackRatio(), mixed);
        return new MerchantPrices(prices.sellPrice(), prices.buybackPrice());
    }

    private PeriodInfo merchantPeriod() {
        int days = Math.clamp(plugin.getConfig().getInt("black-market.refresh-days", 14), 1, 90);
        ZoneId zone;
        try {
            zone = ZoneId.of(plugin.getConfig().getString("black-market.timezone", "Asia/Shanghai"));
        } catch (RuntimeException exception) {
            zone = ZoneId.of("Asia/Shanghai");
        }
        long epochDay = LocalDate.now(zone).toEpochDay();
        long index = Math.floorDiv(epochDay, days);
        return new PeriodInfo(index, "p" + index, LocalDate.ofEpochDay((index + 1) * days));
    }

    private long merchantOfferScore(PeriodInfo period, String offerId) {
        return mix64(period.index() ^ ((long) offerId.hashCode() << 32));
    }

    private String merchantStatus(Player player, MerchantTransactionResult.Status status) {
        return messages.text(player, "merchant.status." + status.name().toLowerCase(Locale.ROOT));
    }

    private String merchantName(Player player, String id) {
        return translated(player, "merchant.offers." + id, id);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private void submitCommission(Player player, String id) {
        if (!ready(player)) return;
        Commission entry = commission(id);
        CommissionPeriod period = commissionPeriod();
        if (entry == null || !visibleCommissionSlots(period).containsValue(id)) {
            player.sendMessage(Component.text(messages.text(player, "economy.commission-unavailable"), NamedTextColor.RED));
            return;
        }
        int requiredMinutes = Math.clamp(plugin.getConfig().getInt(
                "commissions-policy.minimum-play-minutes", 60), 0, 100_000);
        int playedMinutes = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / (20 * 60);
        if (playedMinutes < requiredMinutes) {
            player.sendMessage(Component.text(messages.text(player, "economy.commission-account-age", Map.of(
                    "played", playedMinutes, "required", requiredMinutes)), NamedTextColor.YELLOW));
            return;
        }
        if (entry == null || !acquire(player)) return;
        economy.progress(player.getUniqueId()).whenComplete((progress, error) -> sync(() -> {
            if (!ready(player) || error != null) {
                release(player);
                return;
            }
            ActivityQuota used = progress.quota(EconomyActivity.GATHERING);
            if (used.goldEarned().add(entry.gold()).compareTo(capGold(EconomyActivity.GATHERING)) > 0
                    || used.meritEarned() + entry.merit() > capMerit(EconomyActivity.GATHERING)) {
                release(player);
                player.sendMessage(Component.text(messages.text(player, "economy.commission-cap"), NamedTextColor.YELLOW));
                return;
            }
            String key = "commission:" + player.getUniqueId() + ":" + UUID.randomUUID();
            UUID refundDeliveryId = UUID.randomUUID();
            String refundPayload = Base64.getEncoder().encodeToString(
                    new ItemStack(entry.material(), entry.amount()).serializeAsBytes());
            EconomyReceiptCodec.Receipt receipt = new EconomyReceiptCodec.Receipt(
                    EconomyReceiptCodec.Kind.COMMISSION, EconomyReceiptCodec.Stage.PREPARED,
                    refundDeliveryId, key, id, period.date().toString(),
                    entry.gold(), entry.merit(), 0, EconomyReceiptCodec.Rejection.NONE, refundPayload);
            if (!addReceipt(player, receipt)) {
                release(player);
                player.sendMessage(Component.text(messages.text(player, "economy.receipt-cap"), NamedTextColor.RED));
                return;
            }
            EconomyReceiptCodec.Receipt prepared = receipt;
            commissionEvidence.claim(player, prepared.id(), id, entry.evidenceCost(),
                            commissionTotalDailyLimit(), entry.dailyLimit())
                    .whenComplete((evidenceClaim, claimError) -> sync(() -> {
                        if (claimError != null || evidenceClaim == null) {
                            release(player);
                            plugin.getLogger().warning("Commission evidence claim will recover for "
                                    + player.getUniqueId() + " receipt=" + prepared.id() + ": "
                                    + (claimError == null ? "empty result" : claimError.getMessage()));
                            if (ready(player)) player.sendMessage(Component.text(
                                    messages.text(player, "economy.commission-pending-recovery"),
                                    NamedTextColor.YELLOW));
                            return;
                        }
                        if (!ready(player)) {
                            release(player);
                            return;
                        }
                        if (evidenceClaim.status() != CommissionEvidenceClaimResult.Status.SUCCESS) {
                            removeReceipt(player, prepared.id());
                            release(player);
                            player.sendMessage(Component.text(messages.text(player,
                                    commissionClaimMessage(evidenceClaim.status())), NamedTextColor.YELLOW));
                            return;
                        }
                        if (!remove(player, entry.material(), entry.amount())) {
                            EconomyReceiptCodec.Receipt releasing = updateReceipt(player,
                                    prepared.withRejection(EconomyReceiptCodec.Rejection.MATERIAL_MISSING)
                                            .withStage(EconomyReceiptCodec.Stage.RELEASE_PENDING));
                            releaseCommissionReceipt(player, releasing, false);
                            return;
                        }
                        EconomyReceiptCodec.Receipt escrowed = updateReceipt(
                                player, prepared.withStage(EconomyReceiptCodec.Stage.ESCROWED));
                        settleCommissionReceipt(player, escrowed, false);
                    }));
        }));
    }

    private void settleCommissionReceipt(
            Player player, EconomyReceiptCodec.Receipt receipt, boolean recovering
    ) {
        economy.rewardExact(player.getUniqueId(), EconomyActivity.GATHERING, receipt.gold(), receipt.merit(),
                        capGold(EconomyActivity.GATHERING), capMerit(EconomyActivity.GATHERING),
                        receipt.operationKey(), "commission:" + receipt.subjectId())
                .whenComplete((result, rewardError) -> sync(() -> {
                    if (rewardError != null) {
                        deferReceiptAttempt(player);
                        if (ready(player)) player.sendMessage(Component.text(
                                messages.text(player, "economy.commission-pending-recovery"), NamedTextColor.YELLOW));
                        return;
                    }
                    if (result.status() == ActivityRewardResult.Status.SUCCESS
                            || result.status() == ActivityRewardResult.Status.DUPLICATE) {
                        if (!ready(player)) {
                            finishReceiptAttempt(player, recovering);
                            return;
                        }
                        removeReceipt(player, receipt.id());
                        finishReceiptAttempt(player, recovering);
                        player.sendMessage(Component.text(messages.text(player, "economy.commission-success", Map.of(
                                "gold", result.goldGranted().toPlainString(),
                                "merit", result.meritGranted() > 0 ? " +" + result.meritGranted() + " "
                                        + messages.text(player, "currency.merit") : "")), NamedTextColor.GREEN));
                        openCommissions(player);
                        return;
                    }
                    queueCommissionRefund(player, receipt, recovering, result.status());
                }));
    }

    private void queueCommissionRefund(
            Player player, EconomyReceiptCodec.Receipt receipt, boolean recovering,
            ActivityRewardResult.Status rejection
    ) {
        if (!ready(player)) {
            finishReceiptAttempt(player, recovering);
            return;
        }
        EconomyReceiptCodec.Receipt refundPending = receipt.stage() == EconomyReceiptCodec.Stage.REFUND_PENDING
                ? receipt
                : updateReceipt(player, receipt.withRejection(commissionRejection(rejection))
                        .withStage(EconomyReceiptCodec.Stage.REFUND_PENDING));
        market.enqueueDelivery(refundPending.id(), player.getUniqueId(), refundPending.itemPayload(),
                        "commission-refund:" + refundPending.subjectId())
                .whenComplete((queued, error) -> sync(() -> {
                    if (error != null || !queued) {
                        plugin.getLogger().severe("Commission material refund could not be queued for "
                                + player.getUniqueId() + " delivery " + refundPending.id());
                        if (ready(player)) player.sendMessage(Component.text(
                                messages.text(player, "economy.commission-refund-alert"), NamedTextColor.RED));
                        deferReceiptAttempt(player);
                    } else if (!ready(player)) {
                        // Leave both the ACTIVE evidence claim and the receipt intact. Releasing
                        // the database claim here would make a new session see an unexplained
                        // receipt, while saving the old Player would risk overwriting new data.
                        finishReceiptAttempt(player, recovering);
                    } else {
                        EconomyReceiptCodec.Receipt releasing = updateReceipt(player,
                                refundPending.withStage(EconomyReceiptCodec.Stage.RELEASE_PENDING));
                        releaseCommissionReceipt(player, releasing, recovering);
                    }
                }));
    }

    private void buyProduct(Player player, String id) {
        Product product = product(id, player.getUniqueId());
        if (product == null || !acquire(player)) return;
        String payload = Base64.getEncoder().encodeToString(product.item().serializeAsBytes());
        if (product.lifetimeLimit() > 0) {
            buyLimitedProduct(player, id, product, payload);
            return;
        }
        String key = "eshop:" + player.getUniqueId() + ":" + UUID.randomUUID();
        economy.purchase(player.getUniqueId(), product.gold(), product.merit(), payload, key, id)
                .whenComplete((result, error) -> sync(() -> {
                    release(player);
                    if (!ready(player)) return;
                    if (error != null || result.status() != EconomyPurchaseResult.Status.SUCCESS) {
                        player.sendMessage(Component.text(messages.text(player, "economy.shop-insufficient"), NamedTextColor.RED));
                        return;
                    }
                    player.sendMessage(Component.text(messages.text(player, "economy.shop-success"), NamedTextColor.GREEN));
                    openShop(player);
                }));
    }

    private void buyLimitedProduct(Player player, String id, Product product, String payload) {
        if (product.merit() != 0L) {
            release(player);
            plugin.getLogger().severe("Lifetime product " + id + " cannot charge Merit atomically");
            player.sendMessage(Component.text(messages.text(player, "economy.shop-failed"), NamedTextColor.RED));
            return;
        }
        String key = "eshop-limited:" + player.getUniqueId() + ":" + UUID.randomUUID();
        commerce.purchaseSupplyWithGold(player.getUniqueId(), "earned-" + id,
                        product.gold(), product.gold(), payload, key,
                        product.periodKey(), product.lifetimeLimit())
                .whenComplete((result, error) -> sync(() -> {
                    release(player);
                    if (!ready(player)) return;
                    if (error != null) {
                        player.sendMessage(Component.text(messages.text(player,
                                "economy.shop-failed"), NamedTextColor.RED));
                        return;
                    }
                    if (result.status() == SupplyPurchaseResult.Status.SUCCESS) {
                        player.sendMessage(Component.text(messages.text(player,
                                "economy.shop-success"), NamedTextColor.GREEN));
                        openShop(player);
                    } else if (result.status() == SupplyPurchaseResult.Status.LIMIT_REACHED) {
                        player.sendMessage(Component.text(messages.text(player,
                                "economy.shop-limit-reached"), NamedTextColor.YELLOW));
                    } else if (result.status() == SupplyPurchaseResult.Status.INSUFFICIENT_FUNDS) {
                        player.sendMessage(Component.text(messages.text(player,
                                "economy.shop-insufficient"), NamedTextColor.RED));
                    } else {
                        player.sendMessage(Component.text(messages.text(player,
                                "economy.shop-failed"), NamedTextColor.RED));
                    }
                }));
    }

    private Commission commission(String id) {
        if (!shopIntegrity.commissionEnabled(id)) return null;
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("commissions." + id);
        if (section == null) return null;
        Material material = Material.matchMaterial(section.getString("material", "STONE"));
        if (material == null) return null;
        return new Commission(section.getString("name", id), material,
                Math.clamp(section.getInt("amount", 1), 1, material.getMaxStackSize()),
                new BigDecimal(section.getString("gold", "0.00")), Math.max(0L, section.getLong("merit", 0L)),
                Math.clamp(section.getInt("evidence-cost", plugin.getConfig().getInt(
                        "commissions-policy.default-evidence-cost", 1)), 1, 32),
                Math.clamp(section.getInt("daily-limit", plugin.getConfig().getInt(
                        "commissions-policy.default-offer-daily-limit", 1)), 1, 32));
    }

    private Product product(String id, UUID playerId) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("earned-shop." + id);
        if (section == null) return null;
        Material material = Material.matchMaterial(section.getString("material", "PAPER"));
        if (material == null || rewardMarker == null || owner == null || source == null) return null;
        int amount = Math.clamp(section.getInt("amount", 1), 1, material.getMaxStackSize());
        String name = translated(Bukkit.getPlayer(playerId), "economy.product-names." + id,
                section.getString("name", id));
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.LIGHT_PURPLE));
        Player audience = Bukkit.getPlayer(playerId);
        meta.lore(List.of(Component.text(messages.text(audience, "economy.item-source"), NamedTextColor.DARK_GRAY),
                Component.text(messages.text(audience, "economy.item-bound"), NamedTextColor.RED)));
        String marker = section.getString("marker", id);
        meta.getPersistentDataContainer().set(rewardMarker, PersistentDataType.STRING, marker);
        if ("forge_essence".equals(marker)) {
            NamespacedKey forgeEssence = NamespacedKey.fromString("rpgitems:forge_essence");
            NamespacedKey materialOwner = NamespacedKey.fromString("rpgitems:material_owner");
            NamespacedKey starterCoreOwner = NamespacedKey.fromString("rpgguide:starter_core_owner");
            if (forgeEssence == null || materialOwner == null || starterCoreOwner == null) return null;
            meta.getPersistentDataContainer().set(forgeEssence, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(materialOwner,
                    PersistentDataType.STRING, playerId.toString());
            // This one-time shop item must pair with the account's surviving
            // First-Forge Base and preserve the guaranteed starter recipe.
            meta.getPersistentDataContainer().set(starterCoreOwner,
                    PersistentDataType.STRING, playerId.toString());
        }
        NamespacedKey model = rewardModel(marker);
        if (model != null) meta.setItemModel(model);
        meta.getPersistentDataContainer().set(owner, PersistentDataType.STRING, playerId.toString());
        meta.getPersistentDataContainer().set(source, PersistentDataType.STRING, "EARNED");
        item.setItemMeta(meta);
        int lifetimeLimit = Math.clamp(section.getInt("lifetime-limit", 0), 0, 100);
        String periodKey = section.getString("period-key", "lifetime-v1");
        return new Product(name, material, new BigDecimal(section.getString("gold", "0.00")),
                Math.max(0L, section.getLong("merit", 0L)), item, lifetimeLimit, periodKey);
    }

    private NamespacedKey rewardModel(String marker) {
        return switch (marker) {
            case "protection_stone" -> NamespacedKey.fromString("originalrpg:protection_stone");
            case "lucky_charm" -> NamespacedKey.fromString("originalrpg:lucky_charm");
            case "forge_essence" -> NamespacedKey.fromString("originalrpg:mutation_shard");
            default -> null;
        };
    }

    private Map<Integer, String> visibleCommissionSlots(CommissionPeriod period) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("commissions");
        if (section == null) return Map.of();
        List<String> ids = section.getKeys(false).stream()
                .filter(shopIntegrity::commissionEnabled)
                .sorted((left, right) -> Long.compareUnsigned(
                        commissionOfferScore(period, left), commissionOfferScore(period, right)))
                .toList();
        int visible = Math.clamp(plugin.getConfig().getInt(
                "commissions-policy.visible-per-day", 3), 1, COMMISSION_SLOTS.size());
        Map<Integer, String> result = new HashMap<>();
        for (int index = 0; index < Math.min(visible, ids.size()); index++) {
            result.put(COMMISSION_SLOTS.get(index), ids.get(index));
        }
        return result;
    }

    private CommissionPeriod commissionPeriod() {
        ZoneId zone;
        try {
            zone = ZoneId.of(plugin.getConfig().getString("commissions-policy.timezone", "Asia/Shanghai"));
        } catch (RuntimeException exception) {
            zone = ZoneId.of("Asia/Shanghai");
        }
        LocalDate date = LocalDate.now(zone);
        return new CommissionPeriod(date, date.plusDays(1));
    }

    private long commissionOfferScore(CommissionPeriod period, String id) {
        return mix64(period.date().toEpochDay() ^ ((long) id.hashCode() << 32));
    }

    private int commissionTotalDailyLimit() {
        return Math.clamp(plugin.getConfig().getInt("commissions-policy.total-daily-limit", 3), 1, 32);
    }

    private String commissionClaimMessage(CommissionEvidenceClaimResult.Status status) {
        return switch (status) {
            case INSUFFICIENT_EVIDENCE -> "economy.commission-evidence-insufficient";
            case TOTAL_LIMIT -> "economy.commission-total-limit";
            case OFFER_LIMIT -> "economy.commission-offer-limit";
            case INVALID -> "economy.commission-invalid";
            case SUCCESS -> "economy.commission-success";
        };
    }

    private Map<Integer, String> assignedIds(String path) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
        if (section == null) return Map.of();
        Map<Integer, String> result = new HashMap<>();
        int index = 0;
        for (String id : section.getKeys(false)) {
            if (index >= CONTENT_SLOTS.size()) break;
            result.put(CONTENT_SLOTS.get(index++), id);
        }
        return result;
    }

    private void recoverNextReceipt(Player player) {
        if (!ready(player)) return;
        if (!acquire(player)) {
            if (!receipts(player).isEmpty()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> recoverNextReceipt(player), 20L * 5L);
            }
            return;
        }
        EconomyReceiptCodec.Receipt receipt = receipts(player).stream()
                .filter(candidate -> candidate.stage() != EconomyReceiptCodec.Stage.QUARANTINED)
                .findFirst().orElse(null);
        if (receipt == null) {
            release(player);
            return;
        }
        if (receipt.kind() == EconomyReceiptCodec.Kind.COMMISSION
                && receipt.stage() == EconomyReceiptCodec.Stage.RELEASE_PENDING) {
            releaseCommissionReceipt(player, receipt, true);
            return;
        }
        if (receipt.stage() == EconomyReceiptCodec.Stage.REFUND_PENDING) {
            if (receipt.kind() == EconomyReceiptCodec.Kind.COMMISSION) {
                queueCommissionRefund(player, receipt, true, rejectionStatus(receipt.rejection()));
            } else {
                queueMerchantRefund(player, receipt, true, merchantRejectionStatus(receipt.rejection()));
            }
            return;
        }
        if (receipt.kind() == EconomyReceiptCodec.Kind.COMMISSION) {
            Commission entry = commission(receipt.subjectId());
            if (entry == null) {
                quarantineReceipt(player, receipt, "commission disabled by shop integrity validation");
                release(player);
                return;
            }
            LocalDate receiptDate = receiptDate(receipt);
            if (receiptDate == null) {
                quarantineReceipt(player, receipt, "invalid commission evidence date");
                release(player);
                return;
            }
            commissionEvidence.claim(player, receiptDate, receipt.id(), receipt.subjectId(), entry.evidenceCost(),
                            commissionTotalDailyLimit(), entry.dailyLimit())
                    .whenComplete((claim, error) -> sync(() -> continueReceiptRecovery(
                            player, receipt, claim, error)));
            return;
        }
        continueReceiptRecovery(player, receipt, null, null);
    }

    private void releaseCommissionReceipt(
            Player player, EconomyReceiptCodec.Receipt receipt, boolean recovering
    ) {
        LocalDate date = receiptDate(receipt);
        if (date == null) {
            quarantineReceipt(player, receipt, "invalid commission evidence date during release");
            release(player);
            return;
        }
        commissionEvidence.release(player, date, receipt.id())
                .whenComplete((released, error) -> sync(() -> {
                    if (error != null || !Boolean.TRUE.equals(released)) {
                        plugin.getLogger().warning("Commission evidence release will recover receipt="
                                + receipt.id() + ": " + (error == null ? "claim missing" : error.getMessage()));
                        deferReceiptAttempt(player);
                        return;
                    }
                    if (!ready(player)) {
                        finishReceiptAttempt(player, recovering);
                        return;
                    }
                    removeReceipt(player, receipt.id());
                    Commission entry = commission(receipt.subjectId());
                    if (entry != null) player.sendMessage(Component.text(
                            commissionRefundMessage(player, receipt, entry), NamedTextColor.RED));
                    finishReceiptAttempt(player, recovering);
                }));
    }

    private ActivityRewardResult.Status rejectionStatus(EconomyReceiptCodec.Rejection rejection) {
        return switch (rejection) {
            case DAILY_LIMIT -> ActivityRewardResult.Status.DAILY_LIMIT;
            case BALANCE_LIMIT -> ActivityRewardResult.Status.BALANCE_LIMIT;
            case MERIT_LIMIT -> ActivityRewardResult.Status.MERIT_LIMIT;
            case NONE, MATERIAL_MISSING, INVALID -> ActivityRewardResult.Status.INVALID;
        };
    }

    private MerchantTransactionResult.Status merchantRejectionStatus(EconomyReceiptCodec.Rejection rejection) {
        return rejection == EconomyReceiptCodec.Rejection.BALANCE_LIMIT
                ? MerchantTransactionResult.Status.BALANCE_LIMIT : MerchantTransactionResult.Status.INVALID;
    }

    private EconomyReceiptCodec.Rejection commissionRejection(ActivityRewardResult.Status status) {
        return switch (status) {
            case DAILY_LIMIT -> EconomyReceiptCodec.Rejection.DAILY_LIMIT;
            case BALANCE_LIMIT -> EconomyReceiptCodec.Rejection.BALANCE_LIMIT;
            case MERIT_LIMIT -> EconomyReceiptCodec.Rejection.MERIT_LIMIT;
            case INVALID, PARTIAL -> EconomyReceiptCodec.Rejection.INVALID;
            case SUCCESS, DUPLICATE -> EconomyReceiptCodec.Rejection.NONE;
        };
    }

    private String commissionRefundMessage(
            Player player, EconomyReceiptCodec.Receipt receipt, Commission entry
    ) {
        return switch (receipt.rejection()) {
            case MATERIAL_MISSING -> messages.text(player, "economy.commission-material", Map.of(
                    "amount", entry.amount(), "material", entry.material().name()));
            case DAILY_LIMIT -> messages.text(player, "economy.commission-refund-daily-limit");
            case BALANCE_LIMIT -> messages.text(player, "economy.commission-refund-balance-limit");
            case MERIT_LIMIT -> messages.text(player, "economy.commission-refund-merit-limit");
            case INVALID, NONE -> messages.text(player, "economy.commission-failed");
        };
    }

    private void continueReceiptRecovery(
            Player player, EconomyReceiptCodec.Receipt receipt,
            CommissionEvidenceClaimResult claim, Throwable claimError
    ) {
        if (!ready(player)) {
            release(player);
            return;
        }
        if (receipt.kind() == EconomyReceiptCodec.Kind.COMMISSION) {
            if (claimError != null || claim == null) {
                plugin.getLogger().warning("Commission receipt recovery deferred receipt=" + receipt.id()
                        + ": " + (claimError == null ? "empty result" : claimError.getMessage()));
                deferReceiptAttempt(player);
                return;
            }
            if (claim.status() != CommissionEvidenceClaimResult.Status.SUCCESS) {
                quarantineReceipt(player, receipt, "missing or released active-play evidence claim");
                release(player);
                return;
            }
        }
        EconomyReceiptCodec.Receipt ready = ensureEscrowed(player, receipt);
        if (ready == null) {
            release(player);
            return;
        }
        plugin.getLogger().info("Recovering economy receipt player=" + player.getUniqueId()
                + " receipt=" + ready.id() + " kind=" + ready.kind());
        if (ready.kind() == EconomyReceiptCodec.Kind.COMMISSION) {
            settleCommissionReceipt(player, ready, true);
        } else {
            settleMerchantReceipt(player, ready, true);
        }
    }

    private EconomyReceiptCodec.Receipt ensureEscrowed(
            Player player, EconomyReceiptCodec.Receipt receipt
    ) {
        if (receipt.stage() == EconomyReceiptCodec.Stage.ESCROWED) return receipt;
        ItemStack expected;
        try {
            expected = ItemStack.deserializeBytes(Base64.getDecoder().decode(receipt.itemPayload()));
        } catch (RuntimeException exception) {
            quarantineReceipt(player, receipt, "invalid item payload");
            return null;
        }
        int available;
        boolean removed;
        if (receipt.kind() == EconomyReceiptCodec.Kind.COMMISSION) {
            available = countPlain(player, expected.getType());
            removed = available >= expected.getAmount()
                    && remove(player, expected.getType(), expected.getAmount());
        } else {
            MerchantOffer offer = merchantOffer(receipt.subjectId());
            if (offer == null || offer.amount() != expected.getAmount()) {
                quarantineReceipt(player, receipt, "offer configuration no longer matches receipt");
                return null;
            }
            String issuanceId = issuanceOf(player, expected, offer, receipt.periodKey());
            if (issuanceId == null || !receipt.operationKey().equals("merchant-buyback:" + issuanceId)) {
                quarantineReceipt(player, receipt, "missing or mismatched merchant issuance id");
                return null;
            }
            available = countOffer(player, offer, receipt.periodKey(), issuanceId);
            removed = available >= offer.amount()
                    && removeOffer(player, offer, receipt.periodKey(), issuanceId);
        }
        int required = expected.getAmount();
        if (!verifiesPreparedEscrow(available, required, removed)) {
            // PREPARED does not prove inventory removal. Accepting an empty inventory here lets
            // a replay mint currency without ever placing the commissioned items in escrow.
            quarantineReceipt(player, receipt, available == 0
                    ? "prepared receipt has no verifiable inventory escrow"
                    : "partial or concurrent inventory escrow");
            return null;
        }
        return updateReceipt(player, receipt.withStage(EconomyReceiptCodec.Stage.ESCROWED));
    }

    static boolean verifiesPreparedEscrow(int available, int required, boolean removed) {
        return required > 0 && available >= required && removed;
    }

    private void quarantineReceipt(Player player, EconomyReceiptCodec.Receipt receipt, String reason) {
        updateReceipt(player, receipt.withStage(EconomyReceiptCodec.Stage.QUARANTINED));
        plugin.getLogger().severe("Quarantined economy receipt player=" + player.getUniqueId()
                + " receipt=" + receipt.id() + " reason=" + reason);
        if (ready(player)) player.sendMessage(Component.text(
                messages.text(player, "economy.receipt-quarantined"), NamedTextColor.RED));
    }

    private void finishReceiptAttempt(Player player, boolean recovering) {
        release(player);
        if (recovering && ready(player)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> recoverNextReceipt(player), 2L);
        }
    }

    private void deferReceiptAttempt(Player player) {
        release(player);
        if (ready(player)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> recoverNextReceipt(player), 20L * 30L);
        }
    }

    private List<EconomyReceiptCodec.Receipt> receipts(Player player) {
        return EconomyReceiptCodec.decodeAll(player.getPersistentDataContainer()
                .get(receiptKey, PersistentDataType.STRING));
    }

    private boolean addReceipt(Player player, EconomyReceiptCodec.Receipt receipt) {
        List<EconomyReceiptCodec.Receipt> current = new ArrayList<>(receipts(player));
        if (current.size() >= 16 || current.stream().anyMatch(value -> value.id().equals(receipt.id()))) return false;
        current.add(receipt);
        persistReceipts(player, current);
        return true;
    }

    private EconomyReceiptCodec.Receipt updateReceipt(Player player, EconomyReceiptCodec.Receipt receipt) {
        List<EconomyReceiptCodec.Receipt> current = new ArrayList<>(receipts(player));
        current.removeIf(value -> value.id().equals(receipt.id()));
        current.add(receipt);
        persistReceipts(player, current);
        return receipt;
    }

    private boolean removeReceipt(Player player, UUID receiptId) {
        // Async results are marshalled to the server thread, but a quick reconnect can leave
        // their captured Player object stale. Never let that old object persist over the live
        // session's inventory/PDC state; retaining the receipt is safe because every settlement
        // and refund uses an idempotency key.
        if (!currentPlayerSession(player)) return false;
        List<EconomyReceiptCodec.Receipt> current = new ArrayList<>(receipts(player));
        if (!current.removeIf(value -> value.id().equals(receiptId))) return false;
        persistReceipts(player, current);
        return true;
    }

    private void persistReceipts(Player player, List<EconomyReceiptCodec.Receipt> receipts) {
        if (receipts.isEmpty()) player.getPersistentDataContainer().remove(receiptKey);
        else player.getPersistentDataContainer().set(
                receiptKey, PersistentDataType.STRING, EconomyReceiptCodec.encodeAll(receipts));
        player.saveData();
    }

    private LocalDate receiptDate(EconomyReceiptCodec.Receipt receipt) {
        try {
            return LocalDate.parse(receipt.periodKey());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private int countPlain(Player player, Material material) {
        int available = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material && !item.hasItemMeta()) available += item.getAmount();
        }
        return available;
    }

    private int countOffer(Player player, MerchantOffer offer, String periodKey, String issuanceId) {
        int available = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (matchesOffer(player, item, offer, periodKey, issuanceId)) available += item.getAmount();
        }
        return available;
    }

    private boolean remove(Player player, Material material, int amount) {
        if (countPlain(player, material) < amount) return false;
        int remaining = amount;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (remaining == 0) break;
            if (item == null || item.getType() != material || item.hasItemMeta()) continue;
            int taken = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - taken);
            remaining -= taken;
        }
        return true;
    }

    private ItemStack named(Material material, String name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.AQUA));
        meta.lore(new ArrayList<>(lore));
        item.setItemMeta(meta);
        return item;
    }

    private BigDecimal capGold(EconomyActivity activity) {
        return new BigDecimal(plugin.getConfig().getString("caps." + activity.name() + ".gold", "0.00"));
    }

    private long capMerit(EconomyActivity activity) {
        return plugin.getConfig().getLong("caps." + activity.name() + ".merit", 0L);
    }

    private String activityName(Player player, EconomyActivity activity) {
        return messages.text(player, "economy.activities." + activity.name().toLowerCase(Locale.ROOT));
    }

    private String translated(Player player, String key, String fallback) {
        String value = messages.text(player, key);
        return value.equals(key) ? fallback : value;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return List.of("status", "vanilla", "commissions", "shop", "merchant", "exchange").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("exchange")) {
            return List.of("gold-to-merit", "merit-to-gold", "gold-merit-to-starlight",
                            "starlight-to-gold", "starlight-to-merit").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("exchange")) {
            return List.of("1", "5", "10").stream().filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }

    private void sync(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // The database operation remains authoritative during shutdown.
        }
    }

    private boolean ready(Player player) {
        PlayerSession session = player == null ? null : authenticatedPlayers.get(player.getUniqueId());
        return currentPlayerSession(player) && session != null && session.player() == player;
    }

    private boolean acquire(Player player) {
        PlayerSession session = authenticatedPlayers.get(player.getUniqueId());
        return session != null && session.player() == player
                && pending.putIfAbsent(player.getUniqueId(),
                new OperationLease(player, session.token())) == null;
    }

    private void release(Player player) {
        UUID playerId = player.getUniqueId();
        OperationLease lease = pending.get(playerId);
        if (lease != null && lease.player() == player) pending.remove(playerId, lease);
    }

    private boolean currentPlayerSession(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    private record PlayerSession(Player player, UUID token) {
    }

    private record OperationLease(Player player, UUID sessionToken) {
    }

    private record Commission(
            String name, Material material, int amount, BigDecimal gold, long merit,
            int evidenceCost, int dailyLimit
    ) {
    }

    private record Product(
            String name, Material material, BigDecimal gold, long merit, ItemStack item,
            int lifetimeLimit, String periodKey
    ) {
    }

    private record ProgressAndBalance(EconomyProgress progress, WealthBalance balance) {
    }

    private record MerchantOffer(
            String id, Material material, int amount, String marker, String markerValue, String model,
            BigDecimal basePrice, BigDecimal minimumMultiplier, BigDecimal maximumMultiplier,
            BigDecimal buybackRatio, int purchaseLimit, int buybackLimit
    ) {
    }

    private record MerchantPrices(BigDecimal sellPrice, BigDecimal buybackPrice) {
    }

    private record PeriodInfo(long index, String key, LocalDate refreshDate) {
    }

    private record CommissionPeriod(LocalDate date, LocalDate refreshDate) {
    }
}
