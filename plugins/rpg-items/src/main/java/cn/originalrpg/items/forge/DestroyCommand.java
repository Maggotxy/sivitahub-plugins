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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * C-008 退出路径：分解到底的明确销毁，终态 {@code DESTROYED}。
 *
 * <p>与 {@link SalvageCommand} 共用同一套托管出站（outbox）、幂等键与审计流水，
 * 只是结果态换成状态机里此前没有生产入口的 {@code DESTROYED}：物品永久离开
 * 流通，产物是账号绑定的锻造精华，不结算金币、不掉落可交易物品、不退款。</p>
 *
 * <p>交付通道与 {@code /salvage} 完全一致：产物经 outbox 进市场邮箱，由
 * {@code /market claim} 取出（见 {@link DestroyDeliveryRules}）。原实现把产物
 * 直接塞背包，背包已满就会静默丢掉玩家唯一的回报，并且绕开了唯一市场邮箱
 * 通道（C-009）；这里改为只提交出站请求，不再改玩家背包。</p>
 */
public final class DestroyCommand implements CommandExecutor, Listener {
    private final Plugin plugin;
    private final EquipmentService equipment;
    private final MessageService messages;
    private final EquipmentOperationSessionService operations;
    private final NamespacedKey essence;
    private final NamespacedKey owner;
    private final Map<UUID, PendingDestroy> confirmations = new ConcurrentHashMap<>();
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public DestroyCommand(
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
            player.sendMessage(Component.text(messages.text(player, "items.destroy-item-required"), NamedTextColor.RED));
            return true;
        }
        if (snapshot.boundOwner().isPresent() && !snapshot.boundOwner().orElseThrow().equals(player.getUniqueId())) {
            player.sendMessage(Component.text(messages.text(player, "items.destroy-owner-mismatch"), NamedTextColor.RED));
            return true;
        }
        // C-008 / C-009：已付费解绑的装备必须先重新绑定才能销毁，否则玩家可绕过
        // 唯一市场通道把市场价值直接换成销毁产物。
        if (!DestroyGate.allowsTerminalExit(equipment.isCustomEquipment(held),
                equipment.isTradeUnlocked(held), EquipmentOperationKind.DESTROY)) {
            player.sendMessage(Component.text(messages.text(player, "market.refined-bound"), NamedTextColor.RED));
            return true;
        }
        PendingDestroy confirmation = confirmations.get(player.getUniqueId());
        long window = Math.clamp(plugin.getConfig().getLong("destroy.confirmation-window-seconds", 15), 5L, 300L);
        if (args.length != 1 || !args[0].equalsIgnoreCase("confirm") || confirmation == null
                || !confirmation.itemId().equals(snapshot.itemId())
                || confirmation.expiresAt() < System.currentTimeMillis()) {
            confirmations.put(player.getUniqueId(), new PendingDestroy(
                    snapshot.itemId(), System.currentTimeMillis() + window * 1000L));
            player.sendMessage(Component.text(messages.text(player, "items.destroy-confirm"), NamedTextColor.YELLOW));
            return true;
        }
        if (!active.add(player.getUniqueId())) {
            player.sendMessage(Component.text(messages.text(player, "items.destroy-pending"), NamedTextColor.YELLOW));
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
                || current.boundOwner().filter(bound -> !bound.equals(player.getUniqueId())).isPresent()) {
            active.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "items.destroy-item-required"), NamedTextColor.RED));
            return;
        }
        // 确认步再判一次：两次点击之间装备可能被解绑（C-008 失败关闭）。
        if (!DestroyGate.allowsTerminalExit(equipment.isCustomEquipment(held),
                equipment.isTradeUnlocked(held), EquipmentOperationKind.DESTROY)) {
            active.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "market.refined-bound"), NamedTextColor.RED));
            return;
        }
        int amount = Math.clamp(rarityValue(current.rarity()) + current.totalSlots() / 2
                + current.limitBreakLevel() * 2, 1, 64);
        UUID operationId = UUID.randomUUID();
        ItemStack original = held.clone();
        ItemStack result = essence(player, current, amount, operationId);
        // 交付副本保留本操作的标记，防止托管资产被复制成两份可再次操作的物品。
        List<String> rejected = List.of(EquipmentOperationCoordinator.payload(original, operationId));
        List<String> committed = List.of(EquipmentOperationCoordinator.payload(result, operationId));
        String resultHash = EquipmentOperationEnvelope.fingerprint("destroy-result", committed);
        String requestPayload = "item=" + current.itemId() + ";amount=" + amount + ";result=" + resultHash;
        EquipmentOperationRequest request = new EquipmentOperationRequest(
                operationId, player.getUniqueId(), EquipmentOperationKind.DESTROY,
                Optional.of(current.itemId()), EquipmentOperationEnvelope.fingerprint(requestPayload, rejected),
                requestPayload, BigDecimal.ZERO.setScale(2), "destroy:" + operationId, committed, rejected);
        // C-009：产物只能经托管出站进入唯一市场邮箱，绝不直接改玩家背包。
        // 判据必须在任何「输入已被清空」的步骤之前：原实现把它写在
        // held.setAmount(0) 与 player.saveData() 之后，一旦口径被改成非邮箱通道，
        // 异常会在装备已从手上消失、恢复信封已落 playerdata 之后抛出，
        // 把一次本可回放的托管操作变成玩家物品凭空消失且没有任何结算记录。
        if (DestroyRules.deliveryLane(EquipmentOperationKind.DESTROY)
                != DestroyDeliveryRules.Lane.ESCROW_MAILBOX) {
            active.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "items.destroy-settlement-failed"),
                    NamedTextColor.RED));
            plugin.getLogger().severe("销毁退出路径的产物必须经市场邮箱交付，已拒绝本次操作 item="
                    + current.itemId());
            return;
        }
        try {
            operations.stage(player, request);
        } catch (RuntimeException exception) {
            operations.clearStaged(player, operationId);
            active.remove(player.getUniqueId());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not escrow equipment for destruction", exception);
            player.sendMessage(Component.text(messages.text(player, "items.destroy-settlement-failed"),
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
                player.sendMessage(Component.text(messages.text(player, "items.destroy-success", Map.of(
                        "amount", amount)), NamedTextColor.GREEN));
            }
        });
    }

    private ItemStack essence(Player player, EquipmentSnapshot snapshot, int amount, UUID operationId) {
        ItemStack result = new ItemStack(Material.AMETHYST_SHARD, amount);
        ItemMeta meta = result.getItemMeta();
        meta.displayName(Component.text(messages.text(player, "items.destroy-essence"), NamedTextColor.DARK_PURPLE));
        meta.lore(List.of(
                Component.text(messages.text(player, "items.destroy-source", Map.of(
                        "rarity", messages.text(player, "item-rarities."
                                + snapshot.rarity().toLowerCase(java.util.Locale.ROOT)))), NamedTextColor.DARK_GRAY),
                Component.text(messages.text(player, "items.destroy-bound"), NamedTextColor.GRAY)));
        var data = meta.getPersistentDataContainer();
        data.set(essence, PersistentDataType.BYTE, (byte) 1);
        data.set(owner, PersistentDataType.STRING, player.getUniqueId().toString());
        // 墓碑：终态装备只能进邮箱，不能再被锻造、对话或丢弃。
        data.set(terminalKey(), PersistentDataType.STRING, DestroyedTerminalTicket.stateFor(operationId));
        result.setItemMeta(meta);
        return result;
    }

    private NamespacedKey terminalKey() {
        return new NamespacedKey(plugin, DestroyRules.TERMINAL_KEY);
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

    private record PendingDestroy(UUID itemId, long expiresAt) {
    }
}
