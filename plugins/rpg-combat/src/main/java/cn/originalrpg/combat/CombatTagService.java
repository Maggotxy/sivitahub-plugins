package cn.originalrpg.combat;

import cn.originalrpg.api.service.CombatStateService;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class CombatTagService implements CombatStateService, Listener {
    private final Plugin plugin;
    private final NamespacedKey persistedDeadline;
    private final Map<UUID, Long> deadlines = new ConcurrentHashMap<>();

    CombatTagService(Plugin plugin) {
        this.plugin = plugin;
        persistedDeadline = new NamespacedKey(plugin, "combat_deadline");
    }

    @Override
    public boolean inCombat(UUID playerId) {
        return remainingSeconds(playerId) > 0L;
    }

    @Override
    public long remainingSeconds(UUID playerId) {
        if (playerId == null) return 0L;
        Long deadline = deadlines.get(playerId);
        if (deadline == null) return 0L;
        long remaining = CombatTagRules.remainingSeconds(System.currentTimeMillis(), deadline);
        if (remaining == 0L) deadlines.remove(playerId, deadline);
        return remaining;
    }

    @Override
    public void markCombat(UUID playerId) {
        if (playerId == null) return;
        long duration = Math.clamp(plugin.getConfig().getLong("combat-tag.duration-seconds", 12L), 3L, 120L);
        deadlines.compute(playerId, (ignored, current) -> Math.max(current == null ? 0L : current,
                CombatTagRules.deadline(System.currentTimeMillis(), duration)));
    }

    @Override
    public void clear(UUID playerId) {
        if (playerId == null) return;
        deadlines.remove(playerId);
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null) player.getPersistentDataContainer().remove(persistedDeadline);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity) || event.getDamage() <= 0.0) return;
        Player attacker = attacker(event);
        if (attacker != null && !attacker.equals(event.getEntity())) markCombat(attacker.getUniqueId());
        if (event.getEntity() instanceof Player target) markCombat(target.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        clear(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Long deadline = deadlines.remove(player.getUniqueId());
        if (deadline == null || deadline <= System.currentTimeMillis()) {
            player.getPersistentDataContainer().remove(persistedDeadline);
        } else {
            player.getPersistentDataContainer().set(persistedDeadline, PersistentDataType.LONG, deadline);
        }
        player.saveData();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Long deadline = player.getPersistentDataContainer().get(persistedDeadline, PersistentDataType.LONG);
        player.getPersistentDataContainer().remove(persistedDeadline);
        if (deadline != null && deadline > System.currentTimeMillis()) {
            deadlines.put(player.getUniqueId(), deadline);
        }
    }

    void shutdown() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Long deadline = deadlines.get(player.getUniqueId());
            if (deadline != null && deadline > System.currentTimeMillis()) {
                player.getPersistentDataContainer().set(persistedDeadline, PersistentDataType.LONG, deadline);
                player.saveData();
            }
        }
        deadlines.clear();
    }

    private Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }
}
