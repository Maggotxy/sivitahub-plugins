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
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Sacrifices one finished item into a blueprint through the durable equipment outbox. */
public final class LegacyCommand implements CommandExecutor, Listener {
    private final Plugin plugin;
    private final EquipmentService equipment;
    private final MessageService messages;
    private final LegacyBlueprintService blueprints;
    private final EquipmentOperationSessionService operations;
    private final Map<UUID, PendingLegacy> confirmations = new ConcurrentHashMap<>();
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public LegacyCommand(
            Plugin plugin, EquipmentService equipment, MessageService messages,
            LegacyBlueprintService blueprints, EquipmentOperationSessionService operations
    ) {
        this.plugin = plugin;
        this.equipment = equipment;
        this.messages = messages;
        this.blueprints = blueprints;
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
            player.sendMessage(Component.text(messages.text(player, "items.legacy.pending"), NamedTextColor.YELLOW));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        EquipmentSnapshot snapshot = equipment.inspect(held).orElse(null);
        int minimumLimit = Math.max(0, plugin.getConfig().getInt("legacy.minimum-limit-break", 2));
        LegacyBlueprintRules.Eligibility eligibility = LegacyBlueprintRules.eligibility(
                snapshot, player.getUniqueId(), minimumLimit);
        if (held.getAmount() != 1 || eligibility != LegacyBlueprintRules.Eligibility.VALID) {
            player.sendMessage(Component.text(messages.text(player, eligibilityKey(eligibility), Map.of(
                    "level", minimumLimit)), NamedTextColor.RED));
            return true;
        }
        PendingLegacy confirmation = confirmations.get(player.getUniqueId());
        if (args.length != 1 || !args[0].equalsIgnoreCase("confirm") || confirmation == null
                || !confirmation.itemId().equals(snapshot.itemId())
                || confirmation.expiresAt() < System.currentTimeMillis()) {
            confirmations.put(player.getUniqueId(), new PendingLegacy(
                    snapshot.itemId(), System.currentTimeMillis() + 15_000L));
            player.sendMessage(Component.text(messages.text(player, "items.legacy.confirm", Map.of(
                    "price", legacyPrice().toPlainString(),
                    "trait", messages.text(player, "item-traits." + snapshot.rareTrait().orElseThrow()))),
                    NamedTextColor.YELLOW));
            return true;
        }
        confirmations.remove(player.getUniqueId());
        if (!active.add(player.getUniqueId())) {
            player.sendMessage(Component.text(messages.text(player, "items.legacy.pending"), NamedTextColor.YELLOW));
            return true;
        }
        settle(player, held, snapshot, legacyPrice(), minimumLimit);
        return true;
    }

    private void settle(
            Player player, ItemStack item, EquipmentSnapshot expected, BigDecimal price, int minimumLimit
    ) {
        EquipmentSnapshot current = equipment.inspect(item).orElse(null);
        LegacyBlueprintRules.Eligibility eligibility = LegacyBlueprintRules.eligibility(
                current, player.getUniqueId(), minimumLimit);
        if (item.getAmount() != 1 || current == null || !current.itemId().equals(expected.itemId())
                || eligibility != LegacyBlueprintRules.Eligibility.VALID) {
            active.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "items.legacy.item-changed"), NamedTextColor.RED));
            return;
        }
        UUID operationId = UUID.randomUUID();
        ItemStack original = item.clone();
        ItemStack blueprint = blueprints.create(player, current, messages, operationId);
        List<String> rejected = List.of(operations.itemPayload(original));
        List<String> committed = List.of(operations.itemPayload(blueprint));
        String resultHash = EquipmentOperationEnvelope.fingerprint("legacy-result", committed);
        String requestPayload = "item=" + current.itemId() + ";trait=" + current.rareTrait().orElseThrow()
                + ";result=" + resultHash;
        EquipmentOperationRequest request = new EquipmentOperationRequest(
                operationId, player.getUniqueId(), EquipmentOperationKind.LEGACY,
                Optional.of(current.itemId()), EquipmentOperationEnvelope.fingerprint(requestPayload, rejected),
                requestPayload, price, "legacy:" + operationId, committed, rejected);
        try {
            operations.stage(player, request);
        } catch (RuntimeException exception) {
            operations.clearStaged(player, operationId);
            active.remove(player.getUniqueId());
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Could not escrow equipment for legacy extraction", exception);
            player.sendMessage(Component.text(messages.text(player, "items.legacy.settlement-failed"), NamedTextColor.RED));
            return;
        }
        item.setAmount(0);
        player.saveData();
        operations.submit(player, request, settlement -> {
            active.remove(player.getUniqueId());
            if (settlement.status() == EquipmentOperationResult.Status.COMMITTED && currentSession(player)) {
                player.sendMessage(Component.text(messages.text(player, "items.legacy.success", Map.of(
                        "trait", messages.text(player, "item-traits." + current.rareTrait().orElseThrow()))),
                        NamedTextColor.LIGHT_PURPLE));
            }
        });
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        confirmations.remove(event.player().getUniqueId());
        // Durable operations retain their local gate until the callback; the PDC envelope survives a crash.
    }

    private BigDecimal legacyPrice() {
        try {
            BigDecimal value = new BigDecimal(plugin.getConfig().getString("legacy.extraction-price", "6000.00"));
            return value.signum() > 0 ? value : new BigDecimal("6000.00");
        } catch (NumberFormatException exception) {
            return new BigDecimal("6000.00");
        }
    }

    private static String eligibilityKey(LegacyBlueprintRules.Eligibility eligibility) {
        return switch (eligibility) {
            case OWNER_REQUIRED -> "items.legacy.owner-required";
            case RARE_TRAIT_REQUIRED -> "items.legacy.trait-required";
            case FULL_REVELATION_REQUIRED -> "items.legacy.revelation-required";
            case LIMIT_BREAK_REQUIRED -> "items.legacy.limit-required";
            default -> "items.legacy.item-required";
        };
    }

    private static boolean currentSession(Player player) {
        return player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    private record PendingLegacy(UUID itemId, long expiresAt) {
    }
}
