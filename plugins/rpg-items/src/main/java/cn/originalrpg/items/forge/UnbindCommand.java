package cn.originalrpg.items.forge;

import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.EquipmentOperationEnvelope;
import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.model.EquipmentOperationRequest;
import cn.originalrpg.api.model.EquipmentOperationResult;
import cn.originalrpg.api.model.EquipmentSnapshot;
import cn.originalrpg.api.service.EquipmentOperationSessionService;
import cn.originalrpg.api.service.EquipmentProgressService;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Converts one bound item into an unbound mailbox result through the durable equipment outbox. */
public final class UnbindCommand implements CommandExecutor, Listener {
    private final Plugin plugin;
    private final EquipmentService equipment;
    private final MessageService messages;
    private final EquipmentProgressService equipmentProgress;
    private final EquipmentOperationSessionService operations;
    private final Map<UUID, PendingUnbind> confirmations = new ConcurrentHashMap<>();
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public UnbindCommand(
            Plugin plugin, EquipmentService equipment, MessageService messages,
            EquipmentProgressService equipmentProgress, EquipmentOperationSessionService operations
    ) {
        this.plugin = plugin;
        this.equipment = equipment;
        this.messages = messages;
        this.equipmentProgress = equipmentProgress;
        this.operations = operations;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        if (operations.hasPending(player)) {
            operations.recover(player);
            player.sendMessage(Component.text(messages.text(player, "items.unbind-pending"), NamedTextColor.YELLOW));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        EquipmentSnapshot snapshot = equipment.inspect(held).orElse(null);
        if (held.getAmount() != 1 || snapshot == null || !equipment.isAuthentic(held)) {
            player.sendMessage(Component.text(messages.text(player, "items.unbind-item-required"), NamedTextColor.RED));
            return true;
        }
        if (snapshot.boundOwner().isEmpty()) {
            player.sendMessage(Component.text(messages.text(player, "items.unbind-not-bound"), NamedTextColor.YELLOW));
            return true;
        }
        if (!snapshot.boundOwner().orElseThrow().equals(player.getUniqueId())) {
            player.sendMessage(Component.text(messages.text(player, "items.unbind-owner-mismatch"), NamedTextColor.RED));
            return true;
        }
        BigDecimal price = price(snapshot);
        PendingUnbind pending = confirmations.get(player.getUniqueId());
        if (args.length != 1 || !args[0].equalsIgnoreCase("confirm") || pending == null
                || !pending.itemId().equals(snapshot.itemId()) || pending.expiresAt() < System.currentTimeMillis()
                || pending.price().compareTo(price) != 0) {
            confirmations.put(player.getUniqueId(), new PendingUnbind(
                    snapshot.itemId(), price, System.currentTimeMillis() + 15_000L));
            player.sendMessage(Component.text(messages.text(player, "items.unbind-confirm", Map.of(
                    "cost", price.toPlainString())), NamedTextColor.YELLOW));
            return true;
        }
        if (!active.add(player.getUniqueId())) {
            player.sendMessage(Component.text(messages.text(player, "items.unbind-pending"), NamedTextColor.YELLOW));
            return true;
        }
        confirmations.remove(player.getUniqueId());
        settle(player, held, snapshot, price);
        return true;
    }

    private void settle(Player player, ItemStack item, EquipmentSnapshot expected, BigDecimal price) {
        EquipmentSnapshot current = equipment.inspect(item).orElse(null);
        if (item.getAmount() != 1 || current == null || !current.itemId().equals(expected.itemId())
                || current.boundOwner().filter(player.getUniqueId()::equals).isEmpty()) {
            active.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "items.unbind-item-changed"), NamedTextColor.RED));
            return;
        }
        UUID operationId = UUID.randomUUID();
        ItemStack original = item.clone();
        ItemStack resultItem = original.clone();
        if (!equipment.unlockForTrade(resultItem, player.getUniqueId())
                || !equipmentProgress.refreshDisplay(player, resultItem)) {
            active.remove(player.getUniqueId());
            plugin.getLogger().severe("Could not construct a signed unbinding result for " + player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "items.unbind-settlement-failed"), NamedTextColor.RED));
            return;
        }
        // C-008 / C-009：UNBIND 是唯一交易通道的入口，产物必须落在状态机认可的
        // 「可交易」态。若 unlockForTrade 没能写出解绑签名，就不能把装备交回去
        // 当成可交易装备继续流通。
        if (!EquipmentExitPathGate.unbindResultIsTradeable(equipment.isTradeUnlocked(resultItem))) {
            active.remove(player.getUniqueId());
            plugin.getLogger().severe("Unbinding result is not in a tradeable lifecycle state for "
                    + player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "items.unbind-settlement-failed"), NamedTextColor.RED));
            return;
        }
        List<String> rejected = List.of(operations.itemPayload(original));
        List<String> committed = List.of(operations.itemPayload(resultItem));
        String resultHash = EquipmentOperationEnvelope.fingerprint("unbind-result", committed);
        String requestPayload = "item=" + current.itemId() + ";result=" + resultHash;
        EquipmentOperationRequest request = new EquipmentOperationRequest(
                operationId, player.getUniqueId(), EquipmentOperationKind.UNBIND,
                Optional.of(current.itemId()), EquipmentOperationEnvelope.fingerprint(requestPayload, rejected),
                requestPayload, price, "unbind:" + operationId, committed, rejected);
        try {
            operations.stage(player, request);
        } catch (RuntimeException exception) {
            operations.clearStaged(player, operationId);
            active.remove(player.getUniqueId());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not escrow equipment for unbinding", exception);
            player.sendMessage(Component.text(messages.text(player, "items.unbind-settlement-failed"), NamedTextColor.RED));
            return;
        }
        item.setAmount(0);
        player.saveData();
        operations.submit(player, request, settlement -> {
            active.remove(player.getUniqueId());
            if (settlement.status() == EquipmentOperationResult.Status.COMMITTED && currentSession(player)) {
                player.sendMessage(Component.text(messages.text(player, "items.unbind-success"), NamedTextColor.GREEN));
            }
        });
    }

    private BigDecimal price(EquipmentSnapshot snapshot) {
        BigDecimal base = configured("unbinding.base-cost", "5000.00");
        BigDecimal slots = configured("unbinding.per-slot-cost", "350.00")
                .multiply(BigDecimal.valueOf(snapshot.totalSlots()));
        BigDecimal limits = configured("unbinding.per-limit-level-cost", "1000.00")
                .multiply(BigDecimal.valueOf(snapshot.limitBreakLevel()));
        BigDecimal multiplier = configured("unbinding.rarity-multipliers."
                + snapshot.rarity().toLowerCase(java.util.Locale.ROOT), "1.00");
        return base.add(slots).add(limits).multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal configured(String path, String fallback) {
        try {
            BigDecimal value = new BigDecimal(plugin.getConfig().getString(path, fallback));
            return value.signum() < 0 ? new BigDecimal(fallback) : value;
        } catch (NumberFormatException exception) {
            return new BigDecimal(fallback);
        }
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        confirmations.remove(event.player().getUniqueId());
        // Durable operations retain their local gate until the callback; the PDC envelope survives a crash.
    }

    private static boolean currentSession(Player player) {
        return player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    private record PendingUnbind(UUID itemId, BigDecimal price, long expiresAt) {
    }
}
