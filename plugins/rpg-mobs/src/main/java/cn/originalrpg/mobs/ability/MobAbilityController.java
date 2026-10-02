package cn.originalrpg.mobs.ability;

import cn.originalrpg.api.model.ServerLoadState;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Event-fed, bounded natural-mob abilities. The heartbeat visits only marked mobs;
 * short sequences own their exact lifetime and never load chunks or scan worlds.
 */
public final class MobAbilityController implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final MessageService messages;
    private final MobAbilityVisuals visuals;
    private final NamespacedKey behaviorKey;
    private final NamespacedKey nextUseKey;
    private final NamespacedKey shieldKey;
    private final NamespacedKey projectileKey;
    private final NamespacedKey mobLevelKey;
    private final Set<UUID> tracked = new LinkedHashSet<>();
    private final Set<UUID> abilityDamageVictims = new HashSet<>();
    private final Set<AbilitySequence> sequences = new HashSet<>();
    private final double chance;
    private final double activationRadius;
    private final int scanTicks;
    private final int maximumTracked;
    private final int maximumActiveNormal;
    private final int maximumActivePressure;
    private final int maximumActiveCritical;
    private int activeSequences;
    private int cursor;
    private BukkitTask heartbeat;

    public MobAbilityController(Plugin plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
        visuals = new MobAbilityVisuals(plugin);
        behaviorKey = new NamespacedKey(plugin, "normal_behavior");
        nextUseKey = new NamespacedKey(plugin, "normal_behavior_next_use");
        shieldKey = new NamespacedKey(plugin, "normal_behavior_shield");
        projectileKey = new NamespacedKey(plugin, "normal_behavior_projectile");
        mobLevelKey = new NamespacedKey(plugin, "level");
        chance = Math.clamp(plugin.getConfig().getDouble("normal-behaviors.base-chance", 0.10), 0.0, 0.35);
        activationRadius = Math.clamp(plugin.getConfig().getDouble(
                "normal-behaviors.activation-radius", 18.0), 6.0, 32.0);
        scanTicks = Math.clamp(plugin.getConfig().getInt("normal-behaviors.scan-ticks", 10), 5, 40);
        maximumTracked = Math.clamp(plugin.getConfig().getInt(
                "normal-behaviors.maximum-tracked", 96), 8, 256);
        maximumActiveNormal = Math.clamp(plugin.getConfig().getInt(
                "normal-behaviors.maximum-active.normal", 24), 2, 64);
        maximumActivePressure = Math.clamp(plugin.getConfig().getInt(
                "normal-behaviors.maximum-active.pressure", 10), 1, maximumActiveNormal);
        maximumActiveCritical = Math.clamp(plugin.getConfig().getInt(
                "normal-behaviors.maximum-active.critical", 3), 0, maximumActivePressure);
    }

    public void start() {
        if (heartbeat != null) return;
        heartbeat = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, scanTicks, scanTicks);
    }

    /** Assign at most one trait; elites retain their separate affix language. */
    public MobBehavior assign(LivingEntity monster) {
        MobBehavior existing = behavior(monster);
        if (existing != null) {
            register(monster);
            return existing;
        }
        List<MobBehavior> candidates = MobAbilityRules.candidates(monster.getType().name());
        if (candidates.isEmpty() || tracked.size() >= maximumTracked
                || ThreadLocalRandom.current().nextDouble() >= chance) return null;
        MobBehavior selected = MobAbilityRules.choose(candidates, ThreadLocalRandom.current().nextInt());
        if (selected == null) return null;
        PersistentDataContainer data = monster.getPersistentDataContainer();
        data.set(behaviorKey, PersistentDataType.STRING, selected.id());
        if (selected == MobBehavior.WARDEN) {
            data.set(shieldKey, PersistentDataType.INTEGER,
                    Math.clamp(plugin.getConfig().getInt("normal-behaviors.warden.shield-hits", 3), 1, 8));
        }
        register(monster);
        return selected;
    }

    public String localizedName(Player audience, MobBehavior behavior) {
        return messages.text(audience, "mobs.behaviors." + behavior.id() + ".name");
    }

    public boolean force(LivingEntity monster, MobBehavior behavior) {
        if (MobAbilityRules.candidates(monster.getType().name()).isEmpty()) return false;
        PersistentDataContainer data = monster.getPersistentDataContainer();
        data.set(behaviorKey, PersistentDataType.STRING, behavior.id());
        data.set(nextUseKey, PersistentDataType.LONG, 0L);
        if (behavior == MobBehavior.WARDEN) {
            data.set(shieldKey, PersistentDataType.INTEGER,
                    Math.clamp(plugin.getConfig().getInt("normal-behaviors.warden.shield-hits", 3), 1, 8));
        } else {
            data.remove(shieldKey);
        }
        register(monster);
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (tracked.size() >= maximumTracked) return;
        int remaining = maximumTracked - tracked.size();
        for (Entity entity : event.getChunk().getEntities()) {
            if (remaining <= 0) break;
            if (entity instanceof LivingEntity living && behavior(living) != null && register(living)) remaining--;
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        tracked.remove(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerHitsGuard(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity monster)) return;
        Player attacker = playerSource(event.getDamager());
        if (attacker == null || behavior(monster) != MobBehavior.WARDEN) return;
        PersistentDataContainer data = monster.getPersistentDataContainer();
        int shield = data.getOrDefault(shieldKey, PersistentDataType.INTEGER, 0);
        if (shield <= 0 || !inFront(monster, attacker)) return;
        event.setDamage(event.getDamage() * Math.clamp(plugin.getConfig().getDouble(
                "normal-behaviors.warden.damage-taken-multiplier", 0.30), 0.1, 0.8));
        shield--;
        data.set(shieldKey, PersistentDataType.INTEGER, shield);
        visuals.impact(monster.getLocation().add(0.0, monster.getHeight() * 0.55, 0.0), MobBehavior.WARDEN);
        attacker.sendActionBar(Component.text(messages.text(attacker, shield > 0
                ? "mobs.behaviors.warden.blocked" : "mobs.behaviors.warden.broken",
                Map.of("hits", shield)), shield > 0 ? NamedTextColor.GOLD : NamedTextColor.GREEN));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAbilityProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        String kind = projectile.getPersistentDataContainer().get(projectileKey, PersistentDataType.STRING);
        if (kind == null) return;
        Location impact = projectile.getLocation();
        LivingEntity owner = projectile.getShooter() instanceof LivingEntity living ? living : null;
        projectile.remove();
        if (owner == null || !owner.isValid()) return;
        if ("corrosive".equals(kind)) {
            corrosivePool(owner, impact);
        } else if ("seeker".equals(kind)) {
            if (event.getHitEntity() instanceof Player player) {
                dealDamage(owner, player, damage(owner, 2.2));
            }
            visuals.impact(impact, MobBehavior.ORBITER);
        }
    }

    private void tick() {
        if (tracked.isEmpty()) return;
        List<UUID> snapshot = new ArrayList<>(tracked);
        int budget = switch (visuals.load()) {
            case NORMAL -> maximumTracked;
            case PRESSURE -> Math.max(8, maximumTracked / 3);
            case CRITICAL -> Math.max(4, maximumTracked / 8);
        };
        int visits = Math.min(budget, snapshot.size());
        for (int index = 0; index < visits; index++) {
            UUID id = snapshot.get((cursor + index) % snapshot.size());
            Entity candidate = Bukkit.getEntity(id);
            if (!(candidate instanceof Mob monster) || !monster.isValid() || monster.isDead()) {
                tracked.remove(id);
                continue;
            }
            MobBehavior behavior = behavior(monster);
            if (behavior == null) {
                tracked.remove(id);
                continue;
            }
            if (behavior == MobBehavior.WARDEN) continue;
            LivingEntity target = monster.getTarget();
            if (!(target instanceof Player player) || !combatTarget(player)
                    || player.getWorld() != monster.getWorld()
                    || player.getLocation().distanceSquared(monster.getLocation())
                    > activationRadius * activationRadius || !monster.hasLineOfSight(player)) continue;
            PersistentDataContainer data = monster.getPersistentDataContainer();
            long now = System.currentTimeMillis();
            long nextUse = data.getOrDefault(nextUseKey, PersistentDataType.LONG, 0L);
            if (nextUse > now || activeSequences >= activeLimit()) continue;
            data.set(nextUseKey, PersistentDataType.LONG, now + cooldownMillis(behavior));
            execute(monster, player, behavior);
        }
        cursor = snapshot.isEmpty() ? 0 : (cursor + visits) % snapshot.size();
    }

    private void execute(Mob monster, Player target, MobBehavior behavior) {
        announce(monster, target, behavior);
        visuals.cue(monster, behavior);
        switch (behavior) {
            case SKIRMISHER -> skirmisher(monster, target);
            case CHARGER -> charger(monster, target);
            case SPITTER -> spitter(monster, target);
            case CONDUCTOR -> conductor(monster, target);
            case BOOMERANG -> boomerang(monster, target);
            case BURROWER -> burrower(monster, target);
            case TRAPPER -> trapper(monster, target);
            case ORBITER -> orbiter(monster, target);
            case WARDEN -> { }
        }
    }

    private void skirmisher(Mob monster, Player target) {
        new AbilitySequence() {
            private int age;

            @Override
            public void run() {
                if (!valid(monster, target)) {
                    complete();
                    return;
                }
                if (age < 8) {
                    if ((age & 1) == 0) visuals.telegraph(monster, target.getEyeLocation(), MobBehavior.SKIRMISHER);
                    age++;
                    return;
                }
                Vector away = monster.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0.0);
                if (away.lengthSquared() < 0.01) away = new Vector(1.0, 0.0, 0.0);
                monster.setVelocity(away.normalize().multiply(0.52).setY(0.32));
                Vector shot = target.getEyeLocation().toVector().subtract(monster.getEyeLocation().toVector());
                if (shot.lengthSquared() > 0.01) {
                    Arrow arrow = monster.launchProjectile(Arrow.class, shot.normalize().multiply(1.65));
                    arrow.setPickupStatus(Arrow.PickupStatus.DISALLOWED);
                    arrow.setCritical(false);
                    arrow.setDamage(damage(monster, 2.1) * 0.75);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (arrow.isValid()) arrow.remove();
                    }, 80L);
                }
                complete();
            }
        }.schedule();
    }

    private void charger(Mob monster, Player target) {
        new AbilitySequence() {
            private final Set<UUID> hit = new HashSet<>();
            private int age;
            private Vector direction;

            @Override
            public void run() {
                if (!valid(monster, target)) {
                    complete();
                    return;
                }
                if (age < 12) {
                    monster.setVelocity(new Vector());
                    if ((age & 1) == 0) visuals.telegraph(monster, target.getLocation().add(0, 0.5, 0),
                            MobBehavior.CHARGER);
                    age++;
                    return;
                }
                if (direction == null) {
                    direction = target.getLocation().toVector().subtract(monster.getLocation().toVector()).setY(0.0);
                    if (direction.lengthSquared() < 0.01) direction = new Vector(1.0, 0.0, 0.0);
                    direction.normalize();
                }
                Location next = monster.getLocation().add(direction.clone().multiply(0.85));
                if (!next.getBlock().isPassable() || age >= 26) {
                    complete();
                    return;
                }
                monster.setVelocity(direction.clone().multiply(0.92).setY(0.08));
                visuals.trail(monster.getLocation().add(0.0, 0.55, 0.0), MobBehavior.CHARGER);
                for (Player player : monster.getWorld().getNearbyPlayers(monster.getLocation(), 1.25)) {
                    if (!combatTarget(player) || !hit.add(player.getUniqueId())) continue;
                    dealDamage(monster, player, damage(monster, 3.2));
                    Vector knockback = direction.clone().multiply(1.15).setY(0.42);
                    player.setVelocity(player.getVelocity().add(knockback));
                    visuals.impact(player.getLocation().add(0.0, 0.9, 0.0), MobBehavior.CHARGER);
                }
                age++;
            }
        }.schedule();
    }

    private void spitter(Mob monster, Player target) {
        new AbilitySequence() {
            private int age;

            @Override
            public void run() {
                if (!valid(monster, target)) {
                    complete();
                    return;
                }
                if (age < 10) {
                    if ((age & 1) == 0) visuals.telegraph(monster, target.getLocation().add(0, 0.4, 0),
                            MobBehavior.SPITTER);
                    age++;
                    return;
                }
                Snowball orb = monster.launchProjectile(Snowball.class);
                ItemStack visual = new ItemStack(Material.SLIME_BALL);
                ItemMeta meta = visual.getItemMeta();
                meta.setItemModel(new NamespacedKey("originalrpg", "mob_corrosive_orb"));
                visual.setItemMeta(meta);
                orb.setItem(visual);
                orb.setPersistent(false);
                orb.getPersistentDataContainer().set(projectileKey, PersistentDataType.STRING, "corrosive");
                Vector velocity = target.getLocation().add(0.0, 0.45, 0.0).toVector()
                        .subtract(monster.getEyeLocation().toVector());
                if (velocity.lengthSquared() > 0.01) {
                    Vector normalized = velocity.normalize();
                    orb.setVelocity(new Vector(normalized.getX() * 0.92,
                            normalized.getY() * 0.92 + 0.14, normalized.getZ() * 0.92));
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!orb.isValid()) return;
                    Location expired = orb.getLocation();
                    orb.remove();
                    if (monster.isValid()) corrosivePool(monster, expired);
                }, 60L);
                complete();
            }
        }.schedule();
    }

    private void conductor(Mob monster, Player target) {
        new AbilitySequence() {
            private int age;

            @Override
            public void run() {
                if (!valid(monster, target)) {
                    complete();
                    return;
                }
                if (age < 12) {
                    if ((age & 1) == 0) visuals.telegraph(monster, target.getEyeLocation(), MobBehavior.CONDUCTOR);
                    age++;
                    return;
                }
                List<Player> victims = new ArrayList<>();
                victims.add(target);
                Location cursorLocation = target.getLocation().add(0.0, 1.0, 0.0);
                int maximum = visuals.load() == ServerLoadState.NORMAL ? 3 : 2;
                while (victims.size() < maximum) {
                    Player next = nearestUnhit(monster, cursorLocation, victims, 6.0);
                    if (next == null) break;
                    victims.add(next);
                    cursorLocation = next.getLocation().add(0.0, 1.0, 0.0);
                }
                Location from = monster.getEyeLocation();
                double amount = damage(monster, 2.7);
                for (int index = 0; index < victims.size(); index++) {
                    Player victim = victims.get(index);
                    Location to = victim.getLocation().add(0.0, 1.0, 0.0);
                    visuals.chain(from, to, index);
                    dealDamage(monster, victim, amount * Math.pow(0.78, index));
                    from = to;
                }
                visuals.impact(target.getLocation().add(0.0, 1.0, 0.0), MobBehavior.CONDUCTOR);
                complete();
            }
        }.schedule();
    }

    private void boomerang(Mob monster, Player target) {
        new AbilitySequence() {
            private int age;
            private Location origin;
            private Vector direction;
            private ItemDisplay display;
            private final Set<UUID> outwardHits = new HashSet<>();
            private final Set<UUID> returnHits = new HashSet<>();

            @Override
            public void run() {
                if (!valid(monster, target) && display == null) {
                    complete();
                    return;
                }
                if (age < 8) {
                    if (!valid(monster, target)) {
                        complete();
                        return;
                    }
                    if ((age & 1) == 0) visuals.telegraph(monster, target.getEyeLocation(), MobBehavior.BOOMERANG);
                    age++;
                    return;
                }
                if (display == null) {
                    origin = monster.getEyeLocation().clone();
                    direction = target.getEyeLocation().toVector().subtract(origin.toVector());
                    if (direction.lengthSquared() < 0.01) {
                        complete();
                        return;
                    }
                    direction.normalize();
                    ItemStack item = new ItemStack(Material.IRON_AXE);
                    ItemMeta meta = item.getItemMeta();
                    meta.setItemModel(new NamespacedKey("originalrpg", "mob_boomerang"));
                    item.setItemMeta(meta);
                    display = monster.getWorld().spawn(origin, ItemDisplay.class, spawned -> {
                        spawned.setItemStack(item);
                        spawned.setInvulnerable(true);
                        spawned.setPersistent(false);
                        spawned.setTeleportDuration(1);
                        spawned.setViewRange(0.6F);
                    });
                }
                int flight = age - 8;
                double distance = flight <= 11 ? flight * 0.72 : Math.max(0.0, (22 - flight) * 0.72);
                Location point;
                if (flight <= 11 || !monster.isValid()) point = origin.clone().add(direction.clone().multiply(distance));
                else {
                    Location currentOrigin = monster.getEyeLocation();
                    double returnProgress = Math.clamp((flight - 11) / 11.0, 0.0, 1.0);
                    Location apex = origin.clone().add(direction.clone().multiply(11 * 0.72));
                    point = apex.clone().add(currentOrigin.toVector().subtract(apex.toVector()).multiply(returnProgress));
                }
                if (!point.getBlock().isPassable() || flight > 22) {
                    complete();
                    return;
                }
                display.teleport(point);
                display.setRotation((float) ((flight * 48.0) % 360.0), 0.0F);
                visuals.trail(point, MobBehavior.BOOMERANG);
                Set<UUID> phaseHits = flight <= 11 ? outwardHits : returnHits;
                for (Player player : point.getWorld().getNearbyPlayers(point, 0.9)) {
                    if (!combatTarget(player) || !phaseHits.add(player.getUniqueId())) continue;
                    dealDamage(monster, player, damage(monster, 2.4));
                    visuals.impact(player.getLocation().add(0.0, 0.9, 0.0), MobBehavior.BOOMERANG);
                }
                age++;
            }

            @Override
            void cleanUp() {
                if (display != null && display.isValid()) display.remove();
            }
        }.schedule();
    }

    private void corrosivePool(LivingEntity owner, Location center) {
        if (activeSequences >= activeLimit()) return;
        final double radius = Math.clamp(plugin.getConfig().getDouble(
                "normal-behaviors.spitter.pool-radius", 2.25), 1.0, 3.5);
        new AbilitySequence() {
            private int age;

            @Override
            public void run() {
                if (age >= 70 || center.getWorld() == null || !owner.isValid()) {
                    complete();
                    return;
                }
                if (age % 5 == 0) visuals.pool(center, radius);
                if (age % 20 == 0 && owner.isValid()) {
                    for (Player player : center.getWorld().getNearbyPlayers(center, radius)) {
                        if (!combatTarget(player)) continue;
                        dealDamage(owner, player, damage(owner, 1.25));
                        Vector pull = center.toVector().subtract(player.getLocation().toVector()).setY(0.0);
                        if (pull.lengthSquared() > 0.01) player.setVelocity(
                                player.getVelocity().add(pull.normalize().multiply(0.12)));
                    }
                }
                age++;
            }
        }.schedule();
    }

    private void burrower(Mob monster, Player target) {
        new AbilitySequence() {
            private final boolean wasInvisible = monster.isInvisible();
            private final boolean wasInvulnerable = monster.isInvulnerable();
            private int age;

            @Override
            public void run() {
                if (!valid(monster, target)) {
                    complete();
                    return;
                }
                if (age < 14) {
                    monster.setInvisible(true);
                    monster.setInvulnerable(true);
                    monster.setVelocity(new Vector());
                    if ((age & 1) == 0) visuals.ring(monster.getLocation(), 1.15, MobBehavior.BURROWER);
                    age++;
                    return;
                }
                Location destination = safeFlank(target, monster);
                if (destination != null) monster.teleport(destination);
                visuals.impact(monster.getLocation().add(0.0, 0.55, 0.0), MobBehavior.BURROWER);
                complete();
            }

            @Override
            void cleanUp() {
                if (!monster.isValid()) return;
                monster.setInvisible(wasInvisible);
                monster.setInvulnerable(wasInvulnerable);
            }
        }.schedule();
    }

    private void trapper(Mob monster, Player target) {
        Location center = target.getLocation().clone();
        double radius = Math.clamp(plugin.getConfig().getDouble(
                "normal-behaviors.trapper.radius", 2.4), 1.5, 3.5);
        new AbilitySequence() {
            private int age;

            @Override
            public void run() {
                if (!valid(monster, target) || age >= 62) {
                    complete();
                    return;
                }
                if (age < 16) {
                    if ((age & 1) == 0) visuals.ring(center, radius, MobBehavior.TRAPPER);
                    age++;
                    return;
                }
                if (age % 5 == 0) visuals.ring(center, radius, MobBehavior.TRAPPER);
                if ((age - 16) % 20 == 0) {
                    for (Player player : center.getWorld().getNearbyPlayers(center, radius)) {
                        if (!combatTarget(player)) continue;
                        Vector inward = center.toVector().subtract(player.getLocation().toVector()).setY(0.0);
                        if (inward.lengthSquared() > 0.01) {
                            player.setVelocity(player.getVelocity().multiply(0.35)
                                    .add(inward.normalize().multiply(0.28)).setY(0.10));
                        }
                        dealDamage(monster, player, damage(monster, 1.15));
                    }
                    visuals.impact(center.clone().add(0.0, 0.15, 0.0), MobBehavior.TRAPPER);
                }
                age++;
            }
        }.schedule();
    }

    private void orbiter(Mob monster, Player target) {
        new AbilitySequence() {
            private final List<Snowball> orbs = new ArrayList<>();
            private int age;

            @Override
            public void run() {
                if (!valid(monster, target) || age >= 68) {
                    complete();
                    return;
                }
                if (orbs.isEmpty()) spawnOrbs();
                for (int index = 0; index < orbs.size(); index++) {
                    Snowball orb = orbs.get(index);
                    if (!orb.isValid()) continue;
                    int releaseTick = 22 + index * 7;
                    if (age < releaseTick) {
                        double angle = age * 0.32 + Math.PI * 2.0 * index / orbs.size();
                        Location orbit = monster.getLocation().add(Math.cos(angle) * 1.35,
                                1.0 + Math.sin(angle * 1.7) * 0.28, Math.sin(angle) * 1.35);
                        orb.teleport(orbit);
                        orb.setVelocity(new Vector());
                    } else {
                        Vector desired = target.getEyeLocation().toVector().subtract(orb.getLocation().toVector());
                        if (desired.lengthSquared() > 0.01) {
                            Vector steered = orb.getVelocity().multiply(0.42)
                                    .add(desired.normalize().multiply(0.58));
                            if (steered.lengthSquared() > 0.01) orb.setVelocity(steered.normalize().multiply(0.82));
                        }
                    }
                    if ((age & 1) == 0) visuals.trail(orb.getLocation(), MobBehavior.ORBITER);
                }
                age++;
            }

            private void spawnOrbs() {
                int count = switch (visuals.load()) {
                    case NORMAL -> 3;
                    case PRESSURE -> 2;
                    case CRITICAL -> 1;
                };
                ItemStack visual = new ItemStack(Material.ENDER_PEARL);
                ItemMeta meta = visual.getItemMeta();
                meta.setItemModel(new NamespacedKey("originalrpg", "mob_seeker_orb"));
                visual.setItemMeta(meta);
                for (int index = 0; index < count; index++) {
                    Snowball orb = monster.launchProjectile(Snowball.class);
                    orb.setItem(visual);
                    orb.setGravity(false);
                    orb.setPersistent(false);
                    orb.setSilent(true);
                    orb.getPersistentDataContainer().set(projectileKey, PersistentDataType.STRING, "seeker");
                    orbs.add(orb);
                }
            }

            @Override
            void cleanUp() {
                for (Snowball orb : orbs) if (orb.isValid()) orb.remove();
            }
        }.schedule();
    }

    private Location safeFlank(Player target, LivingEntity monster) {
        Vector facing = target.getLocation().getDirection().setY(0.0);
        if (facing.lengthSquared() < 0.01) facing = new Vector(0.0, 0.0, 1.0);
        facing.normalize();
        Vector side = new Vector(-facing.getZ(), 0.0, facing.getX());
        Vector[] offsets = {
                facing.clone().multiply(-1.9),
                side.clone().multiply(1.9),
                side.clone().multiply(-1.9),
                facing.clone().multiply(1.9)
        };
        for (Vector offset : offsets) {
            Location candidate = target.getLocation().clone().add(offset);
            candidate.setY(target.getLocation().getY());
            if (candidate.getWorld() == monster.getWorld() && candidate.getBlock().isPassable()
                    && candidate.clone().add(0.0, 1.0, 0.0).getBlock().isPassable()
                    && !candidate.clone().subtract(0.0, 1.0, 0.0).getBlock().isPassable()) return candidate;
        }
        return null;
    }

    private Player nearestUnhit(LivingEntity owner, Location center, List<Player> hit, double radius) {
        Player result = null;
        double best = radius * radius;
        for (Player player : center.getWorld().getNearbyPlayers(center, radius)) {
            if (!combatTarget(player) || hit.contains(player)) continue;
            double distance = player.getLocation().distanceSquared(center);
            if (distance < best && owner.hasLineOfSight(player)) {
                best = distance;
                result = player;
            }
        }
        return result;
    }

    private boolean register(LivingEntity monster) {
        if (tracked.contains(monster.getUniqueId())) return false;
        if (tracked.size() >= maximumTracked) return false;
        tracked.add(monster.getUniqueId());
        return true;
    }

    private MobBehavior behavior(LivingEntity monster) {
        return MobBehavior.fromId(monster.getPersistentDataContainer().get(
                behaviorKey, PersistentDataType.STRING));
    }

    private void announce(LivingEntity monster, Player target, MobBehavior behavior) {
        String name = localizedName(target, behavior);
        target.sendActionBar(Component.text(messages.text(target, "mobs.behaviors.trigger", Map.of(
                "mob", messages.text(target, "mobs.behaviors.hostile"),
                "skill", name)), NamedTextColor.RED));
        target.playSound(monster.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING,
                SoundCategory.HOSTILE, 0.35F, 1.65F);
    }

    private long cooldownMillis(MobBehavior behavior) {
        double fallback = switch (behavior) {
            case SKIRMISHER -> 9.0;
            case CHARGER -> 11.0;
            case SPITTER -> 12.0;
            case CONDUCTOR -> 13.0;
            case BOOMERANG -> 10.0;
            case BURROWER -> 12.0;
            case TRAPPER -> 14.0;
            case ORBITER -> 15.0;
            case WARDEN -> 30.0;
        };
        double configured = Math.clamp(plugin.getConfig().getDouble(
                "normal-behaviors." + behavior.id() + ".cooldown-seconds", fallback), 4.0, 60.0);
        return Math.round(configured * 1000.0);
    }

    private int activeLimit() {
        return switch (visuals.load()) {
            case NORMAL -> maximumActiveNormal;
            case PRESSURE -> maximumActivePressure;
            case CRITICAL -> maximumActiveCritical;
        };
    }

    private double damage(LivingEntity monster, double base) {
        int level = monster.getPersistentDataContainer().getOrDefault(
                mobLevelKey, PersistentDataType.INTEGER, 1);
        return MobAbilityRules.scaledDamage(level, base, 0.045, base * 2.25);
    }

    private void dealDamage(LivingEntity source, Player target, double amount) {
        if (!combatTarget(target) || amount <= 0.0 || !abilityDamageVictims.add(target.getUniqueId())) return;
        try {
            target.damage(amount, source);
        } finally {
            abilityDamageVictims.remove(target.getUniqueId());
        }
    }

    private boolean inFront(LivingEntity monster, Player attacker) {
        Vector facing = monster.getLocation().getDirection().setY(0.0);
        Vector toward = attacker.getLocation().toVector().subtract(monster.getLocation().toVector()).setY(0.0);
        if (facing.lengthSquared() < 0.01 || toward.lengthSquared() < 0.01) return true;
        return facing.normalize().dot(toward.normalize()) > -0.15;
    }

    private Player playerSource(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private boolean combatTarget(Player player) {
        return player.isOnline() && !player.isDead()
                && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR;
    }

    private boolean valid(LivingEntity source, Player target) {
        return source.isValid() && !source.isDead() && combatTarget(target)
                && source.getWorld() == target.getWorld() && source.getLocation().isChunkLoaded();
    }

    @Override
    public void close() {
        if (heartbeat != null) heartbeat.cancel();
        heartbeat = null;
        for (AbilitySequence sequence : List.copyOf(sequences)) sequence.complete();
        sequences.clear();
        tracked.clear();
        abilityDamageVictims.clear();
        activeSequences = 0;
    }

    private abstract class AbilitySequence extends BukkitRunnable {
        private boolean complete;

        final void schedule() {
            if (activeSequences >= activeLimit()) return;
            activeSequences++;
            sequences.add(this);
            runTaskTimer(plugin, 0L, 1L);
        }

        final void complete() {
            if (complete) return;
            complete = true;
            cleanUp();
            sequences.remove(this);
            activeSequences = Math.max(0, activeSequences - 1);
            cancel();
        }

        void cleanUp() {
        }
    }
}
