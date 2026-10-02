package cn.originalrpg.classes;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.model.HiddenPath;
import cn.originalrpg.api.service.EconomyService;
import cn.originalrpg.api.service.HiddenPathService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import fr.xephi.authme.events.LogoutEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime effects for the first two scarce routes. State is deliberately
 * bounded to online players; ownership remains in RpgCore. Talisman upkeep is
 * paid once per UTC minute with a stable ledger key, so a restart or duplicate
 * heartbeat cannot charge twice.
 */
final class HiddenPathEffects implements Listener {
    private static final Set<Material> RAW_MEAT = Set.of(
            Material.BEEF, Material.CHICKEN, Material.COD, Material.SALMON,
            Material.MUTTON, Material.PORKCHOP, Material.RABBIT
    );
    private static final BigDecimal TALISMAN_MINUTE_COST = new BigDecimal("15.00");
    private static final DateTimeFormatter MINUTE_KEY = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
            .withZone(ZoneOffset.UTC);
    private final Plugin plugin;
    private final HiddenPathService paths;
    private final ProfileService profiles;
    private final EconomyService economy;
    private final MessageService messages;
    private final Map<UUID, HiddenPath> owned = new ConcurrentHashMap<>();
    private final Set<UUID> talismanEnabled = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> talismanChargedMinute = new ConcurrentHashMap<>();
    private final Set<UUID> pathLoads = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> authenticatedGenerations = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong generationSequence =
            new java.util.concurrent.atomic.AtomicLong();
    private final ZoneId bloodpactZone;

    HiddenPathEffects(
            Plugin plugin, HiddenPathService paths, ProfileService profiles,
            EconomyService economy, MessageService messages
    ) {
        this.plugin = plugin;
        this.paths = paths;
        this.profiles = profiles;
        this.economy = economy;
        this.messages = messages;
        bloodpactZone = configuredZone(plugin.getConfig().getString(
                "hidden-path.bloodpact.energy.timezone", "Asia/Shanghai"));
    }

    void initialize() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 100L);
    }

    void refresh(Player player) {
        load(player, true);
    }

    ToggleResult toggleTalisman(Player player) {
        if (!authenticated(player.getUniqueId())
                || owned.get(player.getUniqueId()) != HiddenPath.TALISMAN) return ToggleResult.NOT_TALISMAN;
        boolean enabled;
        if (talismanEnabled.remove(player.getUniqueId())) enabled = false;
        else {
            talismanEnabled.add(player.getUniqueId());
            enabled = true;
        }
        return enabled ? ToggleResult.ENABLED : ToggleResult.DISABLED;
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        authenticatedGenerations.put(
                event.player().getUniqueId(), generationSequence.incrementAndGet());
        load(event.player());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onLogout(LogoutEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    private void clear(UUID playerId) {
        // Advance globally before dropping the player's marker. A completion
        // issued by this session can never match a later login generation.
        generationSequence.incrementAndGet();
        authenticatedGenerations.remove(playerId);
        owned.remove(playerId);
        talismanEnabled.remove(playerId);
        talismanChargedMinute.remove(playerId);
        pathLoads.remove(playerId);
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (owned.get(player.getUniqueId()) != HiddenPath.BLOODPACT
                || !RAW_MEAT.contains(event.getItem().getType())) return;
        int base = Math.clamp(plugin.getConfig().getInt(
                "hidden-path.bloodpact.energy.base-restore", 8), 1, 100);
        int decayEvery = Math.clamp(plugin.getConfig().getInt(
                "hidden-path.bloodpact.energy.decay-every-uses", 2), 1, 100);
        int decay = Math.clamp(plugin.getConfig().getInt(
                "hidden-path.bloodpact.energy.decay-amount", 2), 0, base);
        int minimum = Math.clamp(plugin.getConfig().getInt(
                "hidden-path.bloodpact.energy.minimum-restore", 2), 1, base);
        int dailyLimit = Math.clamp(plugin.getConfig().getInt(
                "hidden-path.bloodpact.energy.daily-limit", 40), 1, 1_000);
        UUID playerId = player.getUniqueId();
        long generation = generation(playerId);
        if (!valid(player, generation)) return;
        profiles.restoreEnergyWithQuota(playerId, "bloodpact-raw-meat",
                        LocalDate.now(bloodpactZone).toString(), base, decayEvery, decay, minimum, dailyLimit)
                .whenComplete((result, failure) -> sync(() -> {
                    if (!valid(player, generation) || owned.get(playerId) != HiddenPath.BLOODPACT) return;
                    if (failure != null) {
                        plugin.getLogger().warning("Bloodpact recovery failed for " + playerId + ": "
                                + failure.getMessage());
                        player.sendActionBar(Component.text(messages.text(
                                player, "hidden-path.bloodpact-failed"), NamedTextColor.RED));
                        return;
                    }
                    if (result.status() == cn.originalrpg.api.model.EnergyQuotaResult.Status.ENERGY_FULL) {
                        player.sendActionBar(Component.text(messages.text(
                                player, "hidden-path.bloodpact-full"), NamedTextColor.YELLOW));
                        return;
                    }
                    if (result.status() == cn.originalrpg.api.model.EnergyQuotaResult.Status.PERIOD_LIMIT) {
                        player.sendActionBar(Component.text(messages.text(
                                player, "hidden-path.bloodpact-limit"), NamedTextColor.YELLOW));
                        return;
                    }
                    double maximum = java.util.Objects.requireNonNull(player.getAttribute(
                            org.bukkit.attribute.Attribute.MAX_HEALTH)).getValue();
                    double health = Math.max(0.5, 3.0 * result.energyGranted() / (double) base);
                    player.setHealth(Math.min(maximum, player.getHealth() + health));
                    player.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,
                            player.getLocation().add(0.0, 1.0, 0.0), 6, 0.25, 0.35, 0.25, 0.01);
                    player.sendActionBar(Component.text(messages.text(player,
                            "hidden-path.bloodpact-fed", Map.of(
                                    "health", String.format(java.util.Locale.ROOT, "%.1f", health),
                                    "energy", result.energyGranted(),
                                    "used", result.grantedThisPeriod(),
                                    "limit", result.periodLimit())), NamedTextColor.RED));
                }));
    }

    private void tick() {
        for (UUID playerId : Set.copyOf(owned.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !valid(player, generation(playerId))) {
                owned.remove(playerId);
                talismanEnabled.remove(playerId);
                talismanChargedMinute.remove(playerId);
                continue;
            }
            HiddenPath path = owned.get(playerId);
            if (path == HiddenPath.BLOODPACT) bloodpact(player);
            else if (path == HiddenPath.TALISMAN && talismanEnabled.contains(player.getUniqueId())) talisman(player);
        }
    }

    private void bloodpact(Player player) {
        if (player.getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL) return;
        long time = player.getWorld().getTime();
        if (time >= 13_000L && time < 23_000L) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, 1, true, false, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 120, 0, true, false, true));
            player.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,
                    player.getLocation().add(0.0, 1.0, 0.0), 4, 0.25, 0.35, 0.25, 0.0);
            return;
        }
        if (time < 12_000L && isExposed(player)) {
            player.setHealth(Math.max(1.0, player.getHealth() - 1.0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 120, 1, true, false, true));
            player.getWorld().spawnParticle(Particle.ASH,
                    player.getLocation().add(0.0, 1.0, 0.0), 4, 0.2, 0.35, 0.2, 0.0);
        }
    }

    private void talisman(Player player) {
        UUID playerId = player.getUniqueId();
        long generation = generation(playerId);
        if (!valid(player, generation)) return;
        String minute = MINUTE_KEY.format(Instant.now());
        if (minute.equals(talismanChargedMinute.get(playerId))) {
            applyTalismanWard(player);
            return;
        }
        String key = "hidden-talisman:" + playerId + ":" + minute;
        economy.burn(playerId, TALISMAN_MINUTE_COST, key, "hidden-talisman-upkeep")
                .whenComplete((result, failure) -> sync(() -> {
                    if (!valid(player, generation) || !talismanEnabled.contains(playerId)) return;
                    if (failure != null || (result.status() != cn.originalrpg.api.model.CurrencyOperationResult.Status.SUCCESS
                            && result.status() != cn.originalrpg.api.model.CurrencyOperationResult.Status.DUPLICATE)) {
                        talismanEnabled.remove(playerId);
                        talismanChargedMinute.remove(playerId);
                        player.sendActionBar(Component.text(messages.text(player, "hidden-path.talisman-insufficient"), NamedTextColor.RED));
                        return;
                    }
                    talismanChargedMinute.put(playerId, minute);
                    applyTalismanWard(player);
                }));
    }

    private void applyTalismanWard(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 120, 0, true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 0, true, false, true));
        player.getWorld().getNearbyEntitiesByType(Monster.class, player.getLocation(), 10.0).stream()
                .limit(4).forEach(monster -> monster.addPotionEffect(
                        new PotionEffect(PotionEffectType.GLOWING, 100, 0, true, false, true)));
        player.getWorld().spawnParticle(Particle.ENCHANT,
                player.getLocation().add(0.0, 1.0, 0.0), 12, 0.6, 0.45, 0.6, 0.0);
    }

    private boolean isExposed(Player player) {
        return player.getLocation().getBlockY() >= player.getWorld().getHighestBlockYAt(player.getLocation()) - 1;
    }

    private ZoneId configuredZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Invalid Bloodpact Energy timezone; using Asia/Shanghai");
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private void load(Player player) {
        load(player, false);
    }

    private void load(Player player, boolean force) {
        UUID playerId = player.getUniqueId();
        long generation = generation(playerId);
        if (!valid(player, generation)) return;
        if ((!force && owned.containsKey(playerId)) || !pathLoads.add(playerId)) return;
        paths.path(playerId).whenComplete((path, failure) -> sync(() -> {
            if (!valid(player, generation)) return;
            pathLoads.remove(playerId);
            if (failure != null) return;
            path.ifPresentOrElse(value -> owned.put(playerId, value.path()), () -> owned.remove(playerId));
        }));
    }

    private void sync(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // Hidden-route ownership and minute charges are durable. A late
            // visual callback can be reconstructed on the next login/tick.
        }
    }

    private long generation(UUID playerId) {
        return authenticatedGenerations.getOrDefault(playerId, -1L);
    }

    private boolean authenticated(UUID playerId) {
        return generation(playerId) >= 0L;
    }

    private boolean valid(Player player, long generation) {
        return generation >= 0L && player.isOnline()
                && authenticatedGenerations.getOrDefault(player.getUniqueId(), -1L) == generation;
    }

    enum ToggleResult { ENABLED, DISABLED, NOT_TALISMAN }
}
