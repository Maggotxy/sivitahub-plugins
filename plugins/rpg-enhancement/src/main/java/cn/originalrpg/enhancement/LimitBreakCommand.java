package cn.originalrpg.enhancement;

import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;

import cn.originalrpg.api.model.EquipmentOperationEnvelope;
import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.model.EquipmentOperationRequest;
import cn.originalrpg.api.model.EquipmentOperationResult;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.EquipmentProgressService;
import cn.originalrpg.api.service.EquipmentOperationSessionService;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.List;
import java.util.Locale;

final class LimitBreakCommand implements CommandExecutor, TabCompleter, Listener {

    private final Plugin plugin;
    private final EquipmentService equipment;
    private final EquipmentProgressService equipmentProgress;
    private final MessageService messages;
    private final EnhancementVisuals visuals;
    private final EquipmentOperationSessionService operations;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final NamespacedKey itemId = NamespacedKey.fromString("rpgitems:item_id");
    private final NamespacedKey commerceReward = NamespacedKey.fromString("rpgcommerce:reward_marker");
    private final NamespacedKey commerceOwner = NamespacedKey.fromString("rpgcommerce:owner");
    private final NamespacedKey equipmentOwner = NamespacedKey.fromString("rpgitems:owner");
    private final NamespacedKey tradeUnlocked = NamespacedKey.fromString("rpgitems:trade_unlocked");
    private final NamespacedKey limitLevel;

    LimitBreakCommand(Plugin plugin, EquipmentService equipment,
                      EquipmentProgressService equipmentProgress, MessageService messages,
                      EnhancementVisuals visuals, EquipmentOperationSessionService operations) {
        this.plugin = plugin;
        this.equipment = equipment;
        this.equipmentProgress = equipmentProgress;
        this.messages = messages;
        this.visuals = visuals;
        this.operations = operations;
        limitLevel = new NamespacedKey(plugin, "limit_level");
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
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getAmount() != 1 || !equipment.isAuthentic(item) || itemId == null || item.getItemMeta() == null) {
            player.sendMessage(Component.text(messages.text(player, "enhancement.limit-valid-item"), NamedTextColor.RED));
            return true;
        }
        ItemMeta meta = item.getItemMeta();
        if (equipment.inspect(item).flatMap(cn.originalrpg.api.model.EquipmentSnapshot::boundOwner)
                .filter(owner -> !owner.equals(player.getUniqueId())).isPresent()) {
            player.sendMessage(Component.text(messages.text(player, "enhancement.owner-mismatch"), NamedTextColor.RED));
            return true;
        }
        int current = meta.getPersistentDataContainer().getOrDefault(limitLevel, PersistentDataType.INTEGER, 0);
        int maximum = Math.max(1, plugin.getConfig().getInt("limit-break.maximum-level", 10));
        if (current >= maximum) {
            player.sendMessage(Component.text(messages.text(player, "enhancement.limit-maximum"), NamedTextColor.YELLOW));
            return true;
        }
        if (operations.hasPending(player)) {
            operations.recover(player);
            player.sendMessage(Component.text(messages.text(player, "enhancement.limit-pending"), NamedTextColor.YELLOW));
            return true;
        }
        if (!active.add(player.getUniqueId())) {
            player.sendMessage(Component.text(messages.text(player, "enhancement.limit-pending"), NamedTextColor.YELLOW));
            return true;
        }
        if ((options.safe() && findReward(player, "protection_stone") == null)
                || (options.lucky() && findReward(player, "lucky_charm") == null)) {
            active.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, options.safe()
                    ? "enhancement.protection-required" : "enhancement.luck-required"), NamedTextColor.RED));
            return true;
        }
        String expectedId = meta.getPersistentDataContainer().get(itemId, PersistentDataType.STRING);
        BigDecimal basePrice = new BigDecimal(plugin.getConfig().getString("limit-break.base-price", "1200.00"));
        BigDecimal price = basePrice.multiply(BigDecimal.valueOf(current + 1L));
        UUID operationId = UUID.randomUUID();
        settle(player, expectedId, current, options, price, operationId);
        return true;
    }

    private void settle(
            Player player, String expectedId, int previousLevel, EnhancementOptions options,
            BigDecimal price, UUID operationId
    ) {
        ItemStack item = findEquipment(player, expectedId);
        if (item == null || !equipment.isAuthentic(item) || item.getItemMeta() == null) {
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
        double baseRate = plugin.getConfig().getDouble("limit-break.base-success-rate", 0.80);
        double lossPerLevel = plugin.getConfig().getDouble("limit-break.rate-loss-per-level", 0.06);
        double luckBonus = luck == null ? 0.0 : plugin.getConfig().getDouble("limit-break.lucky-charm-bonus", 0.12);
        double rate = Math.clamp(baseRate - previousLevel * lossPerLevel + luckBonus, 0.05, 0.95);
        ItemStack original = item.clone();
        ItemStack resultItem = item.clone();
        ItemMeta meta = resultItem.getItemMeta();
        if (equipmentOwner != null && !meta.getPersistentDataContainer().has(equipmentOwner, PersistentDataType.STRING)) {
            meta.getPersistentDataContainer().set(equipmentOwner, PersistentDataType.STRING,
                    player.getUniqueId().toString());
        }
        if (tradeUnlocked != null) meta.getPersistentDataContainer().remove(tradeUnlocked);
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom")
                .create(EquipmentOperationEnvelope.deterministicSeed(operationId, "limit-break"));
        boolean success = random.nextDouble() <= rate;
        if (success) {
            meta.getPersistentDataContainer().set(limitLevel, PersistentDataType.INTEGER, previousLevel + 1);
            commitAndRefresh(player, resultItem, meta);
        } else {
            int next = protection != null ? previousLevel : Math.max(0, previousLevel - 1);
            meta.getPersistentDataContainer().set(limitLevel, PersistentDataType.INTEGER, next);
            commitAndRefresh(player, resultItem, meta);
        }
        ArrayList<ItemStack> escrow = new ArrayList<>();
        escrow.add(original);
        if (protection != null) escrow.add(amountCopy(protection, 1));
        if (luck != null) escrow.add(amountCopy(luck, 1));
        java.util.List<String> rejected = escrow.stream().map(operations::itemPayload).toList();
        int next = success ? previousLevel + 1 : protection != null ? previousLevel : Math.max(0, previousLevel - 1);
        String requestPayload = "item=" + expectedId + ";previous=" + previousLevel + ";safe=" + options.safe()
                + ";lucky=" + options.lucky() + ";rate=" + rate + ";success=" + success + ";next=" + next;
        EquipmentOperationRequest request = new EquipmentOperationRequest(operationId, player.getUniqueId(),
                EquipmentOperationKind.LIMIT_BREAK, Optional.of(UUID.fromString(expectedId)),
                EquipmentOperationEnvelope.fingerprint(requestPayload, rejected), requestPayload, price,
                "limit-break:" + operationId, java.util.List.of(operations.itemPayload(resultItem)), rejected);
        try {
            operations.stage(player, request);
        } catch (RuntimeException exception) {
            operations.clearStaged(player, operationId);
            active.remove(player.getUniqueId());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not escrow limit-break inputs", exception);
            player.sendMessage(Component.text(messages.text(player, "enhancement.settlement-failed"), NamedTextColor.RED));
            return;
        }
        item.setAmount(0);
        if (protection != null) consumeOne(protection);
        if (luck != null) consumeOne(luck);
        player.saveData();
        operations.submit(player, request, settlement -> {
            active.remove(player.getUniqueId());
            if (!currentSession(player)
                    || settlement.status() != EquipmentOperationResult.Status.COMMITTED) return;
            if (success) {
                player.sendMessage(Component.text(messages.text(player, "enhancement.limit-success", Map.of(
                        "level", previousLevel + 1)), NamedTextColor.GREEN));
                visuals.limitSuccess(player, previousLevel + 1);
            } else {
                player.sendMessage(Component.text(messages.text(player, protection != null
                                ? "enhancement.limit-protected" : "enhancement.limit-downgraded", Map.of("level", next)),
                        protection != null ? NamedTextColor.YELLOW : NamedTextColor.RED));
                visuals.limitFailure(player, protection != null);
            }
        });
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

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        // There is no preview state. Submitted durable operations deliberately remain active.
    }

    private ItemStack findEquipment(Player player, String expectedId) {
        if (expectedId == null || itemId == null) return null;
        for (ItemStack candidate : player.getInventory().getContents()) {
            if (candidate == null || candidate.getItemMeta() == null) continue;
            if (expectedId.equals(candidate.getItemMeta().getPersistentDataContainer()
                    .get(itemId, PersistentDataType.STRING))) return candidate;
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
            plugin.getLogger().severe("Failed to refresh signed equipment display after limit break for "
                    + player.getUniqueId());
        }
    }

    private static boolean currentSession(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player;
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
}
