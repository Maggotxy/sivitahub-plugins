package cn.originalrpg.sulfur;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.service.MessageService;
import io.papermc.paper.event.entity.SulfurCubeSwallowItemEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

final class SulfurResonanceListener implements Listener, CommandExecutor {
    private final Plugin plugin;
    private final SignedImprintService imprints;
    private final MessageService messages;
    private final SulfurVisuals visuals;
    private final NamespacedKey recipeKey;
    private final NamespacedKey resonanceOwner;
    private final NamespacedKey resonanceBias;
    private final NamespacedKey resonanceSource;
    private final NamespacedKey resonanceStrength;
    private final NamespacedKey lastExtraction;
    private final NamespacedKey quotaDay;
    private final NamespacedKey quotaCount;
    private final ZoneId zoneId;

    SulfurResonanceListener(
            Plugin plugin,
            SignedImprintService imprints,
            MessageService messages,
            NamespacedKey recipeKey
    ) {
        this.plugin = plugin;
        this.imprints = imprints;
        this.messages = messages;
        this.recipeKey = recipeKey;
        visuals = new SulfurVisuals(plugin);
        resonanceOwner = new NamespacedKey(plugin, "resonance_owner");
        resonanceBias = new NamespacedKey(plugin, "resonance_bias");
        resonanceSource = new NamespacedKey(plugin, "resonance_source");
        resonanceStrength = new NamespacedKey(plugin, "resonance_strength");
        lastExtraction = new NamespacedKey(plugin, "last_extraction");
        quotaDay = new NamespacedKey(plugin, "quota_day");
        quotaCount = new NamespacedKey(plugin, "quota_count");
        zoneId = configuredZone(plugin.getConfig().getString("extraction.zone-id", "Asia/Shanghai"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwallow(SulfurCubeSwallowItemEvent event) {
        Player player = event.getPlayer();
        PersistentDataContainer data = event.getEntity().getPersistentDataContainer();
        clearResonance(data);
        ResonanceRules.classify(event.getNewItem().getType().name()).ifPresent(resonance -> {
            data.set(resonanceOwner, PersistentDataType.STRING, player.getUniqueId().toString());
            data.set(resonanceBias, PersistentDataType.STRING, resonance.bias());
            data.set(resonanceSource, PersistentDataType.STRING, event.getNewItem().getType().name());
            data.set(resonanceStrength, PersistentDataType.INTEGER, resonance.strength());
            player.sendActionBar(Component.text(messages.text(player, "sulfur.tuned", Map.of(
                    "stat", messages.text(player, "item-stats." + resonance.bias()))), NamedTextColor.YELLOW));
            visuals.tuned(player, event.getEntity().getLocation(), resonance.strength());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (!(event.getEntity() instanceof SulfurCube cube)) return;
        PersistentDataContainer data = cube.getPersistentDataContainer();
        String ownerValue = data.get(resonanceOwner, PersistentDataType.STRING);
        String bias = data.get(resonanceBias, PersistentDataType.STRING);
        String source = data.get(resonanceSource, PersistentDataType.STRING);
        int strength = data.getOrDefault(resonanceStrength, PersistentDataType.INTEGER, 0);
        if (ownerValue == null || bias == null || source == null || strength < 1) return;

        Player player = event.getPlayer();
        ItemStack blank = oppositeHand(player, event.getHand());
        if (SulfurExtractionRules.cancelForMissingBlank(true, imprints.isBlank(blank))) {
            event.setCancelled(true);
            player.sendActionBar(Component.text(messages.text(player, "sulfur.blank-required"), NamedTextColor.YELLOW));
            return;
        }
        UUID resonancePlayer;
        try {
            resonancePlayer = UUID.fromString(ownerValue);
        } catch (IllegalArgumentException exception) {
            clearResonance(data);
            return;
        }
        if (!resonancePlayer.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(Component.text(messages.text(player, "sulfur.owner-mismatch"), NamedTextColor.RED));
            return;
        }
        long now = Instant.now().getEpochSecond();
        long last = data.getOrDefault(lastExtraction, PersistentDataType.LONG, 0L);
        long cooldown = Math.max(0L, plugin.getConfig().getLong("extraction.cube-cooldown-seconds", 300L));
        if (now - last < cooldown) {
            event.setCancelled(true);
            player.sendMessage(Component.text(messages.text(player, "sulfur.cooldown", Map.of(
                    "seconds", cooldown - (now - last))), NamedTextColor.RED));
            return;
        }
        int limit = Math.clamp(plugin.getConfig().getInt("extraction.daily-limit", 6), 1, 64);
        Quota quota = quota(player);
        if (quota.used() >= limit) {
            event.setCancelled(true);
            player.sendMessage(Component.text(messages.text(player, "sulfur.daily-limit", Map.of(
                    "limit", limit)), NamedTextColor.RED));
            return;
        }

        blank.setAmount(blank.getAmount() - 1);
        ArrayList<ItemStack> drops = new ArrayList<>(event.getDrops());
        drops.add(imprints.createImprint(player, bias, source, strength));
        event.setDrops(drops);
        data.set(lastExtraction, PersistentDataType.LONG, now);
        saveQuota(player, quota.used() + 1);
        player.sendMessage(Component.text(messages.text(player, "sulfur.extracted", Map.of(
                "used", quota.used() + 1, "limit", limit,
                "stat", messages.text(player, "item-stats." + bias))), NamedTextColor.GREEN));
        visuals.extracted(player, cube.getLocation(), strength);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShearCleanup(PlayerShearEntityEvent event) {
        if (event.getEntity() instanceof SulfurCube cube) clearResonance(cube.getPersistentDataContainer());
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        if (plugin.getConfig().getBoolean("recipe.enabled", true)) event.player().discoverRecipe(recipeKey);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        int limit = Math.clamp(plugin.getConfig().getInt("extraction.daily-limit", 6), 1, 64);
        player.sendMessage(Component.text(messages.text(player, "sulfur.help-title"), NamedTextColor.GOLD));
        player.sendMessage(Component.text(messages.text(player, "sulfur.status", Map.of(
                "used", quota(player).used(), "limit", limit)), NamedTextColor.AQUA));
        for (String line : messages.lines(player, "sulfur.help-lines")) {
            player.sendMessage(Component.text("- " + line, NamedTextColor.GRAY));
        }
        return true;
    }

    private Quota quota(Player player) {
        String today = LocalDate.now(zoneId).toString();
        PersistentDataContainer data = player.getPersistentDataContainer();
        String storedDay = data.get(quotaDay, PersistentDataType.STRING);
        if (!today.equals(storedDay)) return new Quota(today, 0);
        return new Quota(today, Math.max(0, data.getOrDefault(quotaCount, PersistentDataType.INTEGER, 0)));
    }

    private void saveQuota(Player player, int used) {
        Quota current = quota(player);
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(quotaDay, PersistentDataType.STRING, current.day());
        data.set(quotaCount, PersistentDataType.INTEGER, used);
        player.saveData();
    }

    private static ItemStack oppositeHand(Player player, EquipmentSlot shearsHand) {
        return shearsHand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInMainHand() : player.getInventory().getItemInOffHand();
    }

    private void clearResonance(PersistentDataContainer data) {
        data.remove(resonanceOwner);
        data.remove(resonanceBias);
        data.remove(resonanceSource);
        data.remove(resonanceStrength);
    }

    private ZoneId configuredZone(String value) {
        try {
            return ZoneId.of(value == null ? "Asia/Shanghai" : value);
        } catch (DateTimeException exception) {
            plugin.getLogger().warning("Invalid extraction.zone-id; using Asia/Shanghai");
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private record Quota(String day, int used) {
    }
}
