package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationEnvelope;
import cn.originalrpg.api.model.EquipmentOperationMarker;
import cn.originalrpg.api.model.EquipmentOperationRequest;
import cn.originalrpg.api.model.EquipmentOperationResult;
import cn.originalrpg.api.service.EquipmentOperationService;
import cn.originalrpg.api.service.EquipmentOperationSessionService;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Base64;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Main-thread escrow and login recovery around the database equipment outbox. */
public final class EquipmentOperationCoordinator implements EquipmentOperationSessionService {
    private static final Set<EquipmentOperationResult.Status> TERMINAL = Set.of(
            EquipmentOperationResult.Status.COMMITTED,
            EquipmentOperationResult.Status.REJECTED_INSUFFICIENT_FUNDS,
            // The lifecycle gate writes a durable refusal receipt and returns the
            // escrow through the recovery mailbox lane, so it resolves exactly like
            // an insufficient-funds rejection: envelope cleared, nothing charged.
            EquipmentOperationResult.Status.OPERATION_REFUSED);
    private final Plugin plugin;
    private final EquipmentOperationService operations;
    private final MessageService messages;
    private final NamespacedKey pendingEnvelope;

    public EquipmentOperationCoordinator(Plugin plugin, EquipmentOperationService operations, MessageService messages) {
        this.plugin = plugin;
        this.operations = operations;
        this.messages = messages;
        this.pendingEnvelope = new NamespacedKey(plugin, "pending_equipment_operation");
    }

    @Override
    public boolean hasPending(Player player) {
        requirePrimaryThread();
        String existing = player.getPersistentDataContainer().get(pendingEnvelope, PersistentDataType.STRING);
        return existing != null && !existing.isBlank();
    }

    /**
     * Writes the complete replay envelope to player data before removing any
     * input item. Caller must save the player after successfully removing its
     * exact escrow assets and before invoking {@link #submit}.
     *
     * <p>The request shape is verified here as well as in the ledger because a
     * request the outbox would answer {@code INVALID} or {@code CONFLICT} never
     * reaches a terminal status: the envelope would stay staged, every later
     * operation for that account would be refused, and the escrowed items would
     * only be reachable through operator intervention. Refusing before staging
     * costs the player nothing but the click.</p>
     */
    @Override
    public void stage(Player player, EquipmentOperationRequest request) {
        requirePrimaryThread();
        if (!player.getUniqueId().equals(request.playerId())) throw new IllegalArgumentException("wrong operation owner");
        if (!request.settleable()) throw new IllegalArgumentException("request is not settleable by the durable outbox");
        String existing = player.getPersistentDataContainer().get(pendingEnvelope, PersistentDataType.STRING);
        if (existing != null && !existing.isBlank()) throw new IllegalStateException("an equipment operation is already staged");
        player.getPersistentDataContainer().set(pendingEnvelope, PersistentDataType.STRING,
                EquipmentOperationEnvelope.encode(request));
    }

    @Override
    public void clearStaged(Player player, UUID operationId) {
        requirePrimaryThread();
        clearIfMatching(player, operationId);
    }

    @Override
    public void submit(Player player, EquipmentOperationRequest request, Consumer<EquipmentOperationResult> completion) {
        CompletionStage<EquipmentOperationResult> stage = operations.settle(request);
        stage.whenComplete((result, error) -> sync(() -> {
            if (error != null) {
                plugin.getLogger().severe("Equipment operation remains staged for recovery player="
                        + request.playerId() + " operation=" + request.operationId() + " cause=" + rootMessage(error));
                if (currentSession(player)) player.sendMessage(Component.text(
                        messages.text(player, "items.operation-recovery-pending"), NamedTextColor.YELLOW));
                // The PDC envelope remains the authority and prevents a new
                // operation. Notify the owning feature only so it can release
                // its process-local click gate; retry replays this exact id.
                completion.accept(new EquipmentOperationResult(
                        EquipmentOperationResult.Status.RECOVERY_PENDING,
                        request.operationId(), false, 0, BigDecimal.ZERO));
                return;
            }
            if (!TERMINAL.contains(result.status())) {
                plugin.getLogger().severe("Equipment operation quarantined player=" + request.playerId()
                        + " operation=" + request.operationId() + " status=" + result.status());
                if (currentSession(player)) player.sendMessage(Component.text(
                        messages.text(player, "items.operation-recovery-invalid"), NamedTextColor.RED));
                completion.accept(result);
                return;
            }
            // A database result can arrive after a disconnect and fast reconnect.
            // The captured Player object then belongs to the old login and must
            // never clear/save over the replacement session's durable envelope.
            if (currentSession(player)) {
                clearIfMatching(player, request.operationId());
                player.saveData();
                if (result.status() == EquipmentOperationResult.Status.REJECTED_INSUFFICIENT_FUNDS) {
                    player.sendMessage(Component.text(messages.text(player,
                            "items.operation-insufficient-recovered"), NamedTextColor.YELLOW));
                } else if (result.status() == EquipmentOperationResult.Status.OPERATION_REFUSED) {
                    player.sendMessage(Component.text(messages.text(player,
                            "items.operation-lifecycle-refused"), NamedTextColor.YELLOW));
                } else if (result.queuedDeliveries() > 0) {
                    player.sendMessage(Component.text(messages.text(player,
                            "items.operation-mailbox-ready"), NamedTextColor.GREEN));
                }
            }
            completion.accept(result);
        }));
    }

    @Override
    public void recover(Player player) {
        requirePrimaryThread();
        if (!currentSession(player)) return;
        String encoded = player.getPersistentDataContainer().get(pendingEnvelope, PersistentDataType.STRING);
        if (encoded == null || encoded.isBlank()) return;
        EquipmentOperationRequest request = EquipmentOperationEnvelope.decode(encoded)
                .filter(value -> value.playerId().equals(player.getUniqueId())).orElse(null);
        if (request == null) {
            plugin.getLogger().severe("Invalid staged equipment operation for " + player.getUniqueId()
                    + "; envelope retained for operator recovery");
            player.sendMessage(Component.text(messages.text(player, "items.operation-recovery-invalid"),
                    NamedTextColor.RED));
            return;
        }
        submit(player, request, ignored -> { });
    }

    @Override
    public String itemPayload(ItemStack item) {
        requirePrimaryThread();
        return payload(item);
    }

    /**
     * 交付副本：把入参物品复制一份，并在副本上打上「本操作正在进行」的标记后再序列化。
     *
     * <p>交付出去的那份物品必须保留标记。若在这里 {@code clear} 掉标记，出站的
     * payload 就是一份干净、可再次操作的权威物品 —— 同一份托管资产被复制成
     * 两份可用的物品，形成重复交付窗口。标记随副本一起进入邮箱/outbox，
     * 由交付侧（{@code rpg-market} 反序列化边界）在确认收货时才清除。</p>
     *
     * <p>{@code operationId} 为 {@code null} 时不打标记（仅用于确实没有归属操作的
     * 预览/展示类 payload）；真实结算路径必须传入 operationId。</p>
     */
    public static String payload(ItemStack item, UUID operationId) {
        ItemStack delivery = deliveryCopy(item, operationId);
        return Base64.getEncoder().encodeToString(delivery.serializeAsBytes());
    }

    public static String payload(ItemStack item) {
        return payload(item, null);
    }

    /**
     * 交付标记拷贝的公开入口。交付路径统一使用它生成出站副本，保证
     * 「交付中物品不可被再次操作」这条幂等保护在副本上依然成立。
     */
    public static ItemStack markedDeliveryCopy(ItemStack item, UUID operationId) {
        return EquipmentOperationMarker.markedCopy(item, operationId);
    }

    /**
     * 生成带交付标记的副本。打标记的决策见 {@link DeliveryCopyRules}：
     * 已标记的入参沿用其原有标记，避免覆盖成另一个操作；无归属操作时不打标记。
     */
    private static ItemStack deliveryCopy(ItemStack item, UUID operationId) {
        return switch (DeliveryCopyRules.markerSource(
                EquipmentOperationMarker.isMarked(item), operationId)) {
            case PRESERVE, NONE -> item.clone();
            case ASSIGN -> markedDeliveryCopy(item, operationId);
        };
    }

    private void clearIfMatching(Player player, UUID operationId) {
        String encoded = player.getPersistentDataContainer().get(pendingEnvelope, PersistentDataType.STRING);
        boolean same = EquipmentOperationEnvelope.decode(encoded)
                .map(EquipmentOperationRequest::operationId).filter(operationId::equals).isPresent();
        if (same) player.getPersistentDataContainer().remove(pendingEnvelope);
    }

    private void requirePrimaryThread() {
        if (!plugin.getServer().isPrimaryThread()) throw new IllegalStateException("Bukkit state must stay on main thread");
    }

    private static boolean currentSession(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = Objects.requireNonNull(throwable, "throwable");
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private void sync(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // The staged PDC envelope remains durable for next-login replay.
        }
    }
}
