package cn.originalrpg.items.forge;

import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.EquipmentOperationEnvelope;
import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.model.EquipmentOperationRequest;
import cn.originalrpg.api.model.EquipmentOperationResult;
import cn.originalrpg.api.model.EquipmentSnapshot;
import cn.originalrpg.api.service.EquipmentOperationSessionService;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Converts authentic equipment into bound essence through the durable equipment outbox. */
public final class SalvageCommand implements CommandExecutor, Listener {
    private final Plugin plugin;
    private final EquipmentService equipment;
    private final MessageService messages;
    private final EquipmentOperationSessionService operations;
    private final NamespacedKey essence;
    private final NamespacedKey owner;
    private final Map<UUID, PendingSalvage> confirmations = new ConcurrentHashMap<>();
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public SalvageCommand(
            Plugin plugin,
            EquipmentService equipment,
            MessageService messages,
            EquipmentOperationSessionService operations
    ) {
        this.plugin = plugin;
        this.equipment = equipment;
        this.messages = messages;
        this.operations = operations;
        essence = new NamespacedKey(plugin, "forge_essence");
        owner = new NamespacedKey(plugin, "material_owner");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        if (operations.hasPending(player)) {
            operations.recover(player);
            player.sendMessage(Component.text(messages.text(player, "items.operation-recovery-pending"),
                    NamedTextColor.YELLOW));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        EquipmentSnapshot snapshot = equipment.inspect(held).orElse(null);
        if (held.getAmount() != 1 || snapshot == null || !equipment.isAuthentic(held)) {
            player.sendMessage(Component.text(messages.text(player, "items.salvage-item-required"), NamedTextColor.RED));
            return true;
        }
        if (snapshot.boundOwner().isPresent() && !snapshot.boundOwner().orElseThrow().equals(player.getUniqueId())) {
            player.sendMessage(Component.text(messages.text(player, "items.salvage-owner-mismatch"), NamedTextColor.RED));
            return true;
        }
        // C-008：已付费解绑的装备必须先重新绑定才能销毁，否则玩家可绕过唯一
        // 市场通道（C-009）把市场价值直接换成销毁产物。
        if (!EquipmentExitPathGate.allowsExit(equipment.isCustomEquipment(held),
                equipment.isTradeUnlocked(held), EquipmentOperationKind.SALVAGE)) {
            player.sendMessage(Component.text(messages.text(player, "market.refined-bound"),
                    NamedTextColor.RED));
            return true;
        }
        PendingSalvage confirmation = confirmations.get(player.getUniqueId());
        if (args.length != 1 || !args[0].equalsIgnoreCase("confirm") || confirmation == null
                || !confirmation.itemId().equals(snapshot.itemId())
                || confirmation.expiresAt() < System.currentTimeMillis()) {
            confirmations.put(player.getUniqueId(), new PendingSalvage(
                    snapshot.itemId(), System.currentTimeMillis() + 15_000L));
            player.sendMessage(Component.text(messages.text(player, "items.salvage-confirm"), NamedTextColor.YELLOW));
            return true;
        }
        if (!active.add(player.getUniqueId())) {
            player.sendMessage(Component.text(messages.text(player, "items.operation-recovery-pending"),
                    NamedTextColor.YELLOW));
            return true;
        }
        confirmations.remove(player.getUniqueId());
        settle(player, held, snapshot);
        return true;
    }

    private void settle(Player player, ItemStack held, EquipmentSnapshot expected) {
        EquipmentSnapshot current = equipment.inspect(held).orElse(null);
        if (held.getAmount() != 1 || current == null || !equipment.isAuthentic(held)
                || !current.itemId().equals(expected.itemId())
                || current.boundOwner().filter(owner -> !owner.equals(player.getUniqueId())).isPresent()) {
            active.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "items.salvage-item-required"), NamedTextColor.RED));
            return;
        }
        // 确认步再判一次：两次点击之间装备可能被解绑（C-008 失败关闭）。
        if (!EquipmentExitPathGate.allowsExit(equipment.isCustomEquipment(held),
                equipment.isTradeUnlocked(held), EquipmentOperationKind.SALVAGE)) {
            active.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "market.refined-bound"), NamedTextColor.RED));
            return;
        }
        int amount = Math.clamp(rarityValue(current.rarity()) + current.totalSlots() / 2
                + current.limitBreakLevel() * 2, 1, 64);
        UUID operationId = UUID.randomUUID();
        ItemStack original = held.clone();
        ItemStack result = essence(player, current, amount);
        // 交付副本保留本操作的标记，防止托管资产被复制成两份可再次操作的物品。
        List<String> rejected = List.of(EquipmentOperationCoordinator.payload(original, operationId));
        List<String> committed = List.of(EquipmentOperationCoordinator.payload(result, operationId));
        String resultHash = EquipmentOperationEnvelope.fingerprint("salvage-result", committed);
        String requestPayload = "item=" + current.itemId() + ";amount=" + amount + ";result=" + resultHash;
        EquipmentOperationRequest request = new EquipmentOperationRequest(
                operationId, player.getUniqueId(), EquipmentOperationKind.SALVAGE,
                Optional.of(current.itemId()), EquipmentOperationEnvelope.fingerprint(requestPayload, rejected),
                requestPayload, BigDecimal.ZERO.setScale(2), "salvage:" + operationId, committed, rejected);
        try {
            operations.stage(player, request);
        } catch (RuntimeException exception) {
            operations.clearStaged(player, operationId);
            active.remove(player.getUniqueId());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not escrow equipment for salvage", exception);
            player.sendMessage(Component.text(messages.text(player, "items.operation-recovery-invalid"),
                    NamedTextColor.RED));
            return;
        }
        held.setAmount(0);
        // Force the complete original-equipment recovery envelope and its removal
        // into playerdata before the database/mailbox transaction may begin.
        player.saveData();
        operations.submit(player, request, settlement -> {
            active.remove(player.getUniqueId());
            if (settlement.status() == EquipmentOperationResult.Status.COMMITTED && currentSession(player)) {
                player.sendMessage(Component.text(messages.text(player, "items.salvage-success", Map.of(
                        "amount", amount)), NamedTextColor.GREEN));
            }
        });
    }

    private ItemStack essence(Player player, EquipmentSnapshot snapshot, int amount) {
        ItemStack result = new ItemStack(Material.AMETHYST_SHARD, amount);
        ItemMeta meta = result.getItemMeta();
        meta.displayName(Component.text(messages.text(player, "items.salvage-essence"), NamedTextColor.LIGHT_PURPLE));
        meta.lore(List.of(
                Component.text(messages.text(player, "items.salvage-source", Map.of(
                        "rarity", messages.text(player, "item-rarities."
                                + snapshot.rarity().toLowerCase(Locale.ROOT)))), NamedTextColor.DARK_GRAY),
                Component.text(messages.text(player, "items.salvage-bound"), NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(essence, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(owner, PersistentDataType.STRING, player.getUniqueId().toString());
        result.setItemMeta(meta);
        return result;
    }

    private static int rarityValue(String rarity) {
        return switch (rarity) {
            case "LEGENDARY" -> 12;
            case "EPIC" -> 7;
            case "RARE" -> 3;
            default -> 1;
        };
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        confirmations.remove(event.player().getUniqueId());
        // Durable operations keep their click gate until the settlement callback.
    }

    private static boolean currentSession(Player player) {
        return player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    private record PendingSalvage(UUID itemId, long expiresAt) {
    }
}
