package cn.originalrpg.enhancement;

import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;

import cn.originalrpg.api.model.EquipmentSnapshot;
import cn.originalrpg.api.model.EquipmentOperationEnvelope;
import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.model.EquipmentOperationRequest;
import cn.originalrpg.api.model.EquipmentOperationResult;
import cn.originalrpg.api.service.EquipmentProgressService;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.EquipmentOperationSessionService;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

final class RefineCommand implements CommandExecutor, TabCompleter, Listener {
    private static final String[] STATS = {
            "strength", "defense", "agility", "intelligence", "stamina", "critical", "healing", "skill-haste"
    };

    private final Plugin plugin;
    private final EquipmentService equipment;
    private final EquipmentProgressService equipmentProgress;
    private final MessageService messages;
    private final EnhancementVisuals visuals;
    private final EquipmentOperationSessionService operations;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final NamespacedKey itemId = NamespacedKey.fromString("rpgitems:item_id");
    private final NamespacedKey revealedSlots = NamespacedKey.fromString("rpgitems:revealed_slots");
    private final NamespacedKey commerceReward = NamespacedKey.fromString("rpgcommerce:reward_marker");
    private final NamespacedKey commerceOwner = NamespacedKey.fromString("rpgcommerce:owner");
    private final NamespacedKey equipmentOwner = NamespacedKey.fromString("rpgitems:owner");
    private final NamespacedKey tradeUnlocked = NamespacedKey.fromString("rpgitems:trade_unlocked");
    private final NamespacedKey forgeBias = NamespacedKey.fromString("rpgitems:forge_bias");
    private final NamespacedKey forgeStrength = NamespacedKey.fromString("rpgitems:forge_strength");
    private final NamespacedKey refineCount;

    RefineCommand(Plugin plugin, EquipmentService equipment,
                  EquipmentProgressService equipmentProgress, MessageService messages,
                  EnhancementVisuals visuals, EquipmentOperationSessionService operations) {
        this.plugin = plugin;
        this.equipment = equipment;
        this.equipmentProgress = equipmentProgress;
        this.messages = messages;
        this.visuals = visuals;
        this.operations = operations;
        refineCount = new NamespacedKey(plugin, "refine_count");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        EnhancementOptions options = parseOptions(args);
        if (options == null) {
            player.sendMessage(Component.text(messages.text(player, "enhancement.option-help"), NamedTextColor.YELLOW));
            return true;
        }
        if (active.contains(player.getUniqueId())) {
            player.sendMessage(Component.text(messages.text(player, "enhancement.refine-pending"), NamedTextColor.YELLOW));
            return true;
        }
        HeldEquipment held = inspectHeld(player, true);
        if (held == null) return true;
        openMenu(player, held, -1, options.safe(), options.lucky());
        return true;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RefineMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !menu.ownerId().equals(player.getUniqueId())) return;
        if (active.contains(player.getUniqueId())) {
            player.sendActionBar(Component.text(messages.text(player, "enhancement.refine-pending"),
                    NamedTextColor.YELLOW));
            return;
        }
        HeldEquipment held = inspectHeld(player, false);
        if (held == null || !menu.itemId().equals(held.itemId())) {
            player.closeInventory();
            player.sendMessage(Component.text(messages.text(player, "enhancement.refine-item-changed"),
                    NamedTextColor.RED));
            return;
        }
        int rawSlot = event.getRawSlot();
        int selected = RefinementRules.slotIndexForButton(rawSlot, held.revealedSlots());
        if (selected >= 0) {
            menu.selectedSlot(selected);
            renderMenu(player, menu, held);
            return;
        }
        if (rawSlot == RefineMenu.SAFE_SLOT) {
            if (!menu.safe() && findReward(player, "protection_stone") == null) {
                player.sendActionBar(Component.text(messages.text(player, "enhancement.protection-required"),
                        NamedTextColor.RED));
                return;
            }
            menu.toggleSafe();
            renderMenu(player, menu, held);
            return;
        }
        if (rawSlot == RefineMenu.LUCKY_SLOT) {
            if (!menu.lucky() && findReward(player, "lucky_charm") == null) {
                player.sendActionBar(Component.text(messages.text(player, "enhancement.luck-required"),
                        NamedTextColor.RED));
                return;
            }
            menu.toggleLucky();
            renderMenu(player, menu, held);
            return;
        }
        if (rawSlot == RefineMenu.CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (rawSlot == RefineMenu.CONFIRM_SLOT) {
            if (!RefinementRules.validSelection(menu.selectedSlot(), held.revealedSlots())) {
                player.sendActionBar(Component.text(messages.text(player, "enhancement.refine-select-required"),
                        NamedTextColor.YELLOW));
                return;
            }
            beginRefinement(player, held, menu.selectedSlot(),
                    new EnhancementOptions(menu.safe(), menu.lucky()));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof RefineMenu) event.setCancelled(true);
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        Player player = event.player();
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof RefineMenu) player.closeInventory();
        // Submitted durable operations keep active until their database callback.
    }

    private void openMenu(Player player, HeldEquipment held, int selectedSlot, boolean safe, boolean lucky) {
        boolean availableSafe = safe && findReward(player, "protection_stone") != null;
        boolean availableLucky = lucky && findReward(player, "lucky_charm") != null;
        RefineMenu menu = new RefineMenu(player.getUniqueId(), held.itemId(),
                messages.text(player, "enhancement.refine-menu-title"), selectedSlot,
                availableSafe, availableLucky);
        renderMenu(player, menu, held);
        player.openInventory(menu.getInventory());
    }

    private void renderMenu(Player player, RefineMenu menu, HeldEquipment held) {
        ItemStack filler = menuItem(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.BLACK, List.of());
        for (int slot = 0; slot < menu.getInventory().getSize(); slot++) {
            menu.getInventory().setItem(slot, filler);
        }
        menu.getInventory().setItem(4, held.item().clone());
        PersistentDataContainer data = held.item().getItemMeta().getPersistentDataContainer();
        for (int index = 0; index < RefinementRules.SLOT_BUTTONS.size(); index++) {
            int inventorySlot = RefinementRules.SLOT_BUTTONS.get(index);
            if (index >= held.revealedSlots()) {
                menu.getInventory().setItem(inventorySlot, menuItem(Material.GRAY_DYE,
                        messages.text(player, "enhancement.refine-slot-locked", Map.of("slot", index + 1)),
                        NamedTextColor.DARK_GRAY, List.of(messages.text(player, "enhancement.refine-slot-locked-lore"))));
                continue;
            }
            String value = data.get(new NamespacedKey(plugin, "slot_" + index), PersistentDataType.STRING);
            String current = describeSlot(player, value);
            boolean selected = index == menu.selectedSlot();
            menu.getInventory().setItem(inventorySlot, menuItem(
                    selected ? Material.LIME_DYE : Material.AMETHYST_SHARD,
                    messages.text(player, selected ? "enhancement.refine-slot-selected" : "enhancement.refine-slot-title",
                            Map.of("slot", index + 1)), selected ? NamedTextColor.GREEN : NamedTextColor.AQUA,
                    List.of(messages.text(player, "enhancement.refine-slot-current", Map.of("value", current)),
                            messages.text(player, selected ? "enhancement.refine-slot-selected-lore"
                                    : "enhancement.refine-slot-select-lore"))));
        }

        double successRate = successRate(menu.lucky());
        int minimum = plugin.getConfig().getInt("refinement.minimum-roll", -5);
        int maximum = plugin.getConfig().getInt("refinement.maximum-roll", 18);
        String selectedName = RefinementRules.validSelection(menu.selectedSlot(), held.revealedSlots())
                ? messages.text(player, "enhancement.refine-selected-value", Map.of("slot", menu.selectedSlot() + 1))
                : messages.text(player, "enhancement.refine-selected-none");
        String failure = menu.safe()
                ? messages.text(player, "enhancement.refine-preview-failure-safe")
                : messages.text(player, "enhancement.refine-preview-failure-risk", Map.of("minimum", -5, "maximum", -1));
        menu.getInventory().setItem(RefineMenu.INFO_SLOT, menuItem(Material.WRITABLE_BOOK,
                messages.text(player, "enhancement.refine-preview-title"), NamedTextColor.GOLD, List.of(
                        messages.text(player, "enhancement.refine-preview-target", Map.of("target", selectedName)),
                        messages.text(player, "enhancement.refine-preview-rate", Map.of("rate", percent(successRate))),
                        messages.text(player, "enhancement.refine-preview-roll", Map.of("minimum", signed(minimum),
                                "maximum", signed(maximum))),
                        failure,
                        messages.text(player, "enhancement.refine-preview-price", Map.of("price", refinementPrice().toPlainString())))));

        boolean hasProtection = findReward(player, "protection_stone") != null;
        boolean hasLuck = findReward(player, "lucky_charm") != null;
        menu.getInventory().setItem(RefineMenu.SAFE_SLOT, toggleItem(Material.SHIELD, player,
                "enhancement.refine-safe", menu.safe(), hasProtection));
        menu.getInventory().setItem(RefineMenu.LUCKY_SLOT, toggleItem(Material.RABBIT_FOOT, player,
                "enhancement.refine-lucky", menu.lucky(), hasLuck));
        boolean ready = RefinementRules.validSelection(menu.selectedSlot(), held.revealedSlots());
        menu.getInventory().setItem(RefineMenu.CONFIRM_SLOT, menuItem(
                ready ? Material.ANVIL : Material.BARRIER,
                messages.text(player, ready ? "enhancement.refine-confirm" : "enhancement.refine-confirm-disabled"),
                ready ? NamedTextColor.GREEN : NamedTextColor.RED,
                List.of(messages.text(player, ready ? "enhancement.refine-confirm-lore"
                        : "enhancement.refine-select-required"))));
        menu.getInventory().setItem(RefineMenu.CLOSE_SLOT, menuItem(Material.OAK_DOOR,
                messages.text(player, "enhancement.refine-close"), NamedTextColor.GRAY, List.of()));
    }

    private ItemStack toggleItem(Material material, Player player, String key, boolean enabled, boolean available) {
        return menuItem(material,
                messages.text(player, key, Map.of("state", messages.text(player,
                        enabled ? "enhancement.refine-toggle-on" : "enhancement.refine-toggle-off"))),
                enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                List.of(messages.text(player, available ? key + "-available" : key + "-missing")));
    }

    private void beginRefinement(Player player, HeldEquipment held, int selectedSlot, EnhancementOptions options) {
        if (operations.hasPending(player)) {
            operations.recover(player);
            player.sendMessage(Component.text(messages.text(player, "enhancement.refine-pending"), NamedTextColor.YELLOW));
            return;
        }
        if (!active.add(player.getUniqueId())) {
            player.sendMessage(Component.text(messages.text(player, "enhancement.refine-pending"), NamedTextColor.YELLOW));
            return;
        }
        if ((options.safe() && findReward(player, "protection_stone") == null)
                || (options.lucky() && findReward(player, "lucky_charm") == null)) {
            active.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, options.safe()
                    ? "enhancement.protection-required" : "enhancement.luck-required"), NamedTextColor.RED));
            return;
        }
        BigDecimal price = refinementPrice();
        UUID operationId = UUID.randomUUID();
        player.sendActionBar(Component.text(messages.text(player, "enhancement.refine-settling"),
                NamedTextColor.YELLOW));
        HeldEquipment current = inspectHeld(player, false);
        if (current == null || !held.itemId().equals(current.itemId())
                || !RefinementRules.validSelection(selectedSlot, current.revealedSlots())) {
            active.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "enhancement.item-changed-refunded"), NamedTextColor.RED));
            return;
        }
        ItemStack protection = options.safe() ? findReward(player, "protection_stone") : null;
        ItemStack luck = options.lucky() ? findReward(player, "lucky_charm") : null;
        if ((options.safe() && protection == null) || (options.lucky() && luck == null)) {
            active.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "enhancement.aid-changed-refunded"), NamedTextColor.RED));
            return;
        }
        ItemStack original = current.item().clone();
        ItemStack resultItem = original.clone();
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom")
                .create(EquipmentOperationEnvelope.deterministicSeed(operationId, "refinement"));
        RollOutcome outcome;
        try {
            outcome = applyRoll(player, resultItem, selectedSlot, protection != null,
                    luck == null ? 0.0 : plugin.getConfig().getDouble("refinement.lucky-charm-bonus", 0.12), random);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Refinement result construction failed for " + player.getUniqueId(), exception);
            active.remove(player.getUniqueId());
            return;
        }
        List<ItemStack> escrow = new ArrayList<>();
        escrow.add(original);
        if (protection != null) escrow.add(amountCopy(protection, 1));
        if (luck != null) escrow.add(amountCopy(luck, 1));
        List<String> rejected = escrow.stream().map(operations::itemPayload).toList();
        String requestPayload = "item=" + held.itemId() + ";slot=" + selectedSlot + ";safe=" + options.safe()
                + ";lucky=" + options.lucky() + ";outcome=" + outcome.encoded();
        EquipmentOperationRequest request = new EquipmentOperationRequest(operationId, player.getUniqueId(),
                EquipmentOperationKind.REFINEMENT, Optional.of(UUID.fromString(held.itemId())),
                EquipmentOperationEnvelope.fingerprint(requestPayload, rejected), requestPayload, price,
                "refine:" + operationId, List.of(operations.itemPayload(resultItem)), rejected);
        try {
            operations.stage(player, request);
        } catch (RuntimeException exception) {
            operations.clearStaged(player, operationId);
            active.remove(player.getUniqueId());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not escrow refinement inputs", exception);
            player.sendMessage(Component.text(messages.text(player, "enhancement.settlement-failed"), NamedTextColor.RED));
            return;
        }
        current.item().setAmount(0);
        if (protection != null) consumeOne(protection);
        if (luck != null) consumeOne(luck);
        player.closeInventory();
        player.saveData();
        operations.submit(player, request, settlement -> {
            active.remove(player.getUniqueId());
            if (!currentSession(player)
                    || settlement.status() != EquipmentOperationResult.Status.COMMITTED) return;
            notifyOutcome(player, selectedSlot, outcome);
        });
    }

    private RollOutcome applyRoll(Player player, ItemStack item, int selectedSlot, boolean protectedRoll,
                                  double luckBonus, RandomGenerator random) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) throw new IllegalStateException("refinement item has no metadata");
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (equipmentOwner != null && !data.has(equipmentOwner, PersistentDataType.STRING)) {
            data.set(equipmentOwner, PersistentDataType.STRING, player.getUniqueId().toString());
        }
        if (tradeUnlocked != null) data.remove(tradeUnlocked);
        int count = data.getOrDefault(refineCount, PersistentDataType.INTEGER, 0) + 1;
        data.set(refineCount, PersistentDataType.INTEGER, count);
        double successRate = Math.clamp(plugin.getConfig().getDouble("refinement.success-rate", 0.65)
                + luckBonus, 0.0, 1.0);
        NamespacedKey statKey = new NamespacedKey(plugin, "slot_" + selectedSlot);
        if (random.nextDouble() > successRate) {
            if (!protectedRoll) {
                String stat = STATS[random.nextInt(STATS.length)];
                int penalty = -random.nextInt(1, 6);
                data.set(statKey, PersistentDataType.STRING, stat + ":" + penalty);
                commitAndRefresh(player, item, meta);
                return new RollOutcome(RollStatus.FAILURE, stat, penalty);
            } else {
                commitAndRefresh(player, item, meta);
                return new RollOutcome(RollStatus.PROTECTED, "", 0);
            }
        }
        String stat = rollStat(data, random);
        int minimum = plugin.getConfig().getInt("refinement.minimum-roll", -5);
        int maximum = plugin.getConfig().getInt("refinement.maximum-roll", 18);
        int value = random.nextInt(minimum, maximum + 1);
        data.set(statKey, PersistentDataType.STRING, stat + ":" + value);
        commitAndRefresh(player, item, meta);
        return new RollOutcome(RollStatus.SUCCESS, stat, value);
    }

    private void notifyOutcome(Player player, int selectedSlot, RollOutcome outcome) {
        switch (outcome.status()) {
            case PROTECTED -> {
                player.sendMessage(Component.text(messages.text(player, "enhancement.refine-protected"), NamedTextColor.YELLOW));
                visuals.refineFailure(player, true);
            }
            case FAILURE -> {
                player.sendMessage(Component.text(messages.text(player, "enhancement.refine-failed", Map.of(
                        "slot", selectedSlot + 1, "stat", statName(player, outcome.stat()),
                        "value", signed(outcome.value()))), NamedTextColor.RED));
                visuals.refineFailure(player, false);
            }
            case SUCCESS -> {
                player.sendMessage(Component.text(messages.text(player, "enhancement.refine-success", Map.of(
                                "slot", selectedSlot + 1, "stat", statName(player, outcome.stat()),
                                "value", signed(outcome.value()))),
                        outcome.value() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
                visuals.refineSuccess(player, outcome.value());
            }
        }
    }

    private HeldEquipment inspectHeld(Player player, boolean notify) {
        ItemStack item = player.getInventory().getItemInMainHand();
        ItemMeta meta = item.getItemMeta();
        if (meta == null || itemId == null || revealedSlots == null
                || !meta.getPersistentDataContainer().has(itemId, PersistentDataType.STRING)) {
            if (notify) player.sendMessage(Component.text(messages.text(player, "enhancement.refine-item-required"),
                    NamedTextColor.RED));
            return null;
        }
        if (item.getAmount() != 1 || !equipment.isAuthentic(item)) {
            if (notify) player.sendMessage(Component.text(messages.text(player, "enhancement.signature-invalid"),
                    NamedTextColor.RED));
            return null;
        }
        if (equipment.inspect(item).flatMap(EquipmentSnapshot::boundOwner)
                .filter(owner -> !owner.equals(player.getUniqueId())).isPresent()) {
            if (notify) player.sendMessage(Component.text(messages.text(player, "enhancement.owner-mismatch"),
                    NamedTextColor.RED));
            return null;
        }
        int slots = meta.getPersistentDataContainer().getOrDefault(revealedSlots, PersistentDataType.INTEGER, 0);
        if (slots <= 0) {
            if (notify) player.sendMessage(Component.text(messages.text(player, "enhancement.slot-required"),
                    NamedTextColor.RED));
            return null;
        }
        String id = meta.getPersistentDataContainer().get(itemId, PersistentDataType.STRING);
        if (id == null || id.isBlank()) return null;
        return new HeldEquipment(id, Math.min(slots, RefinementRules.SLOT_BUTTONS.size()), item);
    }

    private String describeSlot(Player player, String value) {
        if (value == null || value.isBlank()) return messages.text(player, "enhancement.refine-slot-empty");
        String[] parts = value.split(":", 2);
        if (parts.length != 2) return messages.text(player, "enhancement.refine-slot-invalid");
        try {
            int amount = Integer.parseInt(parts[1]);
            return messages.text(player, "enhancement.refine-slot-value", Map.of(
                    "stat", statName(player, parts[0]), "value", signed(amount)));
        } catch (NumberFormatException exception) {
            return messages.text(player, "enhancement.refine-slot-invalid");
        }
    }

    private double successRate(boolean lucky) {
        return RefinementRules.successRate(plugin.getConfig().getDouble("refinement.success-rate", 0.65),
                plugin.getConfig().getDouble("refinement.lucky-charm-bonus", 0.12), lucky);
    }

    private BigDecimal refinementPrice() {
        return new BigDecimal(plugin.getConfig().getString("refinement.price", "400.00"));
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100.0);
    }

    private static String signed(int value) {
        return value >= 0 ? "+" + value : Integer.toString(value);
    }

    private String rollStat(PersistentDataContainer data, RandomGenerator random) {
        String bias = forgeBias == null ? null : data.get(forgeBias, PersistentDataType.STRING);
        int strength = forgeStrength == null ? 0 : data.getOrDefault(forgeStrength, PersistentDataType.INTEGER, 0);
        int bound = RefinementWeights.totalWeight(STATS, bias, strength);
        return RefinementWeights.select(STATS, bias, strength, random.nextInt(bound));
    }

    private ItemStack findReward(Player player, String marker) {
        if (commerceReward == null) return null;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getItemMeta() == null) continue;
            String value = item.getItemMeta().getPersistentDataContainer().get(commerceReward, PersistentDataType.STRING);
            String ownerId = commerceOwner == null ? null
                    : item.getItemMeta().getPersistentDataContainer().get(commerceOwner, PersistentDataType.STRING);
            if (marker.equals(value) && player.getUniqueId().toString().equals(ownerId)) return item;
        }
        return null;
    }

    private static void consumeOne(ItemStack item) {
        item.setAmount(item.getAmount() - 1);
    }

    private static ItemStack amountCopy(ItemStack item, int amount) {
        ItemStack copy = item.clone();
        copy.setAmount(amount);
        return copy;
    }

    private void commitAndRefresh(Player player, ItemStack item, ItemMeta meta) {
        item.setItemMeta(meta);
        equipment.sign(item);
        if (player != null && !equipmentProgress.refreshDisplay(player, item)) {
            plugin.getLogger().severe("Failed to refresh signed equipment display after refinement for "
                    + player.getUniqueId());
        }
    }

    private static boolean currentSession(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    private String statName(Player player, String stat) {
        String translated = messages.text(player, "item-stats." + stat);
        return translated.equals("item-stats." + stat) ? stat : translated;
    }

    private static ItemStack menuItem(Material material, String name, NamedTextColor color, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        ArrayList<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length > 2) return List.of();
        boolean hasSafe = java.util.Arrays.stream(args).limit(Math.max(0, args.length - 1))
                .anyMatch("safe"::equalsIgnoreCase);
        boolean hasLucky = java.util.Arrays.stream(args).limit(Math.max(0, args.length - 1))
                .anyMatch("lucky"::equalsIgnoreCase);
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return java.util.stream.Stream.of(hasSafe ? null : "safe", hasLucky ? null : "lucky")
                .filter(java.util.Objects::nonNull).filter(value -> value.startsWith(prefix)).toList();
    }

    private static EnhancementOptions parseOptions(String[] args) {
        if (args.length > 2) return null;
        boolean safe = false;
        boolean lucky = false;
        for (String value : args) {
            if (value.equalsIgnoreCase("safe") && !safe) safe = true;
            else if (value.equalsIgnoreCase("lucky") && !lucky) lucky = true;
            else return null;
        }
        return new EnhancementOptions(safe, lucky);
    }

    private record EnhancementOptions(boolean safe, boolean lucky) {
    }

    private record HeldEquipment(String itemId, int revealedSlots, ItemStack item) {
    }

    private enum RollStatus { SUCCESS, FAILURE, PROTECTED }

    private record RollOutcome(RollStatus status, String stat, int value) {
        String encoded() {
            return status + ":" + stat + ":" + value;
        }
    }
}
