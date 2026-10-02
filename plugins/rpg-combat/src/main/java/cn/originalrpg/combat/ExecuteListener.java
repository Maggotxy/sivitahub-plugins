package cn.originalrpg.combat;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.EquipmentSnapshot;
import cn.originalrpg.api.model.EquipmentTraitCatalog;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.rules.CombatRules;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.PartyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Boss;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Optional;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

final class ExecuteListener implements Listener {
    private final Plugin plugin;
    private final ProfileService profiles;
    private final EquipmentService equipment;
    private final MessageService messages;
    private final CombatVisuals visuals;
    private final NamespacedKey dungeonBoss = NamespacedKey.fromString("rpgdungeons:boss");
    private final SkillDamageBridge skillDamage;
    private final NamespacedKey projectileSnapshot;
    private final NamespacedKey projectileInvalid;
    private final NamespacedKey projectileArchetype;
    private final NamespacedKey projectileLimitBreak;
    private final NamespacedKey projectileStrength;
    private final NamespacedKey projectileAgility;
    private final NamespacedKey projectileIntelligence;
    private final NamespacedKey projectileCritical;
    private final NamespacedKey projectileRareTrait;
    private final NamespacedKey projectileRareTraitActive;
    private final DefenseTag defenseTag;

    ExecuteListener(
            Plugin plugin, ProfileService profiles, EquipmentService equipment,
            MessageService messages, SkillDamageBridge skillDamage
    ) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.equipment = equipment;
        this.messages = messages;
        this.skillDamage = skillDamage;
        this.visuals = new CombatVisuals(plugin, messages);
        projectileSnapshot = new NamespacedKey(plugin, "projectile_weapon_snapshot");
        projectileInvalid = new NamespacedKey(plugin, "projectile_weapon_invalid");
        projectileArchetype = new NamespacedKey(plugin, "projectile_weapon_archetype");
        projectileLimitBreak = new NamespacedKey(plugin, "projectile_weapon_limit_break");
        projectileStrength = new NamespacedKey(plugin, "projectile_weapon_strength");
        projectileAgility = new NamespacedKey(plugin, "projectile_weapon_agility");
        projectileIntelligence = new NamespacedKey(plugin, "projectile_weapon_intelligence");
        projectileCritical = new NamespacedKey(plugin, "projectile_weapon_critical");
        projectileRareTrait = new NamespacedKey(plugin, "projectile_weapon_rare_trait");
        projectileRareTraitActive = new NamespacedKey(plugin, "projectile_weapon_rare_trait_active");
        defenseTag = new DefenseTag();
    }

    /**
     * Freeze combat-affecting weapon state when a projectile is created. Without
     * this boundary, a player can shoot and swap to an execute weapon before impact.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player shooter)) return;
        Projectile projectile = event.getEntity();
        if (!(projectile instanceof org.bukkit.entity.AbstractArrow)
                && !(projectile instanceof org.bukkit.entity.Fireball)) return;
        var data = projectile.getPersistentDataContainer();
        data.set(projectileSnapshot, PersistentDataType.BYTE, (byte) 1);
        ItemStack launchedWeapon = projectile instanceof org.bukkit.entity.AbstractArrow arrow
                && equipment.isCustomEquipment(arrow.getWeapon())
                ? arrow.getWeapon() : shooter.getInventory().getItemInMainHand();
        ItemStack held = launchedWeapon == null ? shooter.getInventory().getItemInMainHand() : launchedWeapon;
        Optional<EquipmentSnapshot> inspected = equipment.inspect(held);
        if (equipment.isCustomEquipment(held) && inspected.isEmpty()) {
            data.set(projectileInvalid, PersistentDataType.BYTE, (byte) 1);
            event.setCancelled(true);
            shooter.sendActionBar(Component.text(messages.text(shooter, "combat.signature-invalid"), NamedTextColor.RED));
            return;
        }
        if (inspected.isEmpty()) return;
        EquipmentSnapshot snapshot = inspected.orElseThrow();
        if (snapshot.boundOwner().isPresent() && !snapshot.boundOwner().orElseThrow().equals(shooter.getUniqueId())) {
            data.set(projectileInvalid, PersistentDataType.BYTE, (byte) 1);
            event.setCancelled(true);
            shooter.sendActionBar(Component.text(messages.text(shooter, "combat.owner-mismatch"), NamedTextColor.RED));
            return;
        }
        data.set(projectileArchetype, PersistentDataType.STRING, snapshot.archetype());
        data.set(projectileLimitBreak, PersistentDataType.INTEGER, snapshot.limitBreakLevel());
        data.set(projectileStrength, PersistentDataType.INTEGER, snapshot.stat("strength"));
        data.set(projectileAgility, PersistentDataType.INTEGER, snapshot.stat("agility"));
        data.set(projectileIntelligence, PersistentDataType.INTEGER, snapshot.stat("intelligence"));
        data.set(projectileCritical, PersistentDataType.INTEGER, snapshot.stat("critical"));
        snapshot.rareTrait().ifPresent(value -> data.set(projectileRareTrait, PersistentDataType.STRING, value));
        if (EquipmentTraitCatalog.activeFor(snapshot, shooter.getUniqueId())) {
            data.set(projectileRareTraitActive, PersistentDataType.BYTE, (byte) 1);
        }
    }

    /**
     * 非实体来源伤害（摔落 / 岩浆 / 火焰 …）的防御结算。
     *
     * 玩家与实体互砍走的是 {@link EntityDamageByEntityEvent}，Bukkit 为它维护独立的
     * HandlerList，父类监听器收不到，因此那里的防御必须由 {@link #onDamage} 亲自施加。
     * 两个监听器共用 {@link #applyDefenseMultiplier} 的幂等标记：同一伤害只被减一次。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDefensiveDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player target)) return;
        applyDefenseMultiplier(event, target);
    }

    /**
     * 对可防御伤害施加五维「防御」与装备 defense 词条，且保证同一伤害只施加一次。
     *
     * 幂等有两层，任何一层判定为「本 tick 已施加」就直接返回，绝不重复相乘：
     * 1. {@link #onDamage} 互砍路径自己施加，不依赖父类监听器；标记随 {@code event} 存活；
     * 2. {@link DefenseTag} 守卫 —— Bukkit 目前不会把子事件派发给父类监听器
     *    （两者 HandlerList 独立），但若将来两个监听器都收到同一事件、或监听器被重复注册，
     *    第二次进入会在同一游戏刻被拦下。换一刻才允许重新结算，这与守卫字段语义一致。
     * 标记只覆盖「同一个事件对象」；一次伤害只会在一个游戏刻内派发一次，因此足够。
     *
     * 注意：实体互砍时调用链是「先结算防御、再结算攻击加成」，因此攻击方倍率作用于已经
     * 被减伤后的伤害，与非实体伤害（摔落 / 岩浆）的口径一致。
     */
    void applyDefenseMultiplier(EntityDamageEvent event, Player target) {
        if (event.getDamage() <= 0.0 || !DefensePolicy.isDefendable(event.getCause().name())) return;
        int currentTick = plugin.getServer().getCurrentTick();
        if (defenseTag.alreadyApplied(event, currentTick)) return;
        defenseTag.mark(event, currentTick);
        event.setDamage(Math.max(0.0, event.getDamage() * defenseMultiplier(target)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event);
        if (attacker == null || !(event.getEntity() instanceof LivingEntity target)) return;
        if (target instanceof Player playerTarget && CombatBalance.blocksFriendlyFire(
                true, verifiedAllies(attacker, playerTarget))) {
            event.setCancelled(true);
            attacker.sendActionBar(Component.text(
                    messages.text(attacker, "combat.party-friendly-fire"), NamedTextColor.YELLOW));
            return;
        }

        WeaponResolution resolution = resolveWeapon(event, attacker);
        if (!resolution.valid()) {
            event.setCancelled(true);
            attacker.sendActionBar(Component.text(messages.text(attacker, "combat.signature-invalid"), NamedTextColor.RED));
            return;
        }
        if (resolution.ownerMismatch()) {
            event.setCancelled(true);
            attacker.sendActionBar(Component.text(messages.text(attacker, "combat.owner-mismatch"), NamedTextColor.RED));
            return;
        }

        Optional<PlayerProfile> attackerProfile = profiles.cached(attacker.getUniqueId());
        boolean ranged = event.getDamager() instanceof Projectile;
        String skillId = skillId(event, attacker);
        WeaponContext weapon = resolution.weapon();
        AttackResult attack = attackMultiplier(attackerProfile.orElse(null), weapon, ranged, skillId);
        double authoredPower = event.getDamager() instanceof Projectile projectile && skillId != null
                ? skillDamage.projectilePower(projectile) : 1.0;
        double targetMultiplier = CombatBalance.skillDamageMultiplier(skillId != null, target instanceof Player,
                protectedTarget(target), authoredPower,
                plugin.getConfig().getDouble("damage.skill-pvp-multiplier", 0.50),
                plugin.getConfig().getDouble("damage.skill-boss-multiplier", 0.75));
        double forgedBasePower = skillId == null && weapon != null
                ? Math.max(0, weapon.stat("base-power")) : 0.0;
        // 防御结算必须在这里补上：玩家互砍只派发本事件，父类的 onDefensiveDamage 收不到。
        // 先施加一次防御减伤，再叠攻击倍率，与非实体伤害的「先减伤后结算」口径一致。
        if (target instanceof Player playerTarget) applyDefenseMultiplier(event, playerTarget);
        event.setDamage(Math.max(0.0,
                (event.getDamage() + forgedBasePower) * attack.multiplier() * targetMultiplier));
        boolean executed = applyRareTraits(event, attacker, target, weapon, attackerProfile.orElse(null));
        visuals.hit(attacker, target, event.getFinalDamage(), attack.critical(), executed);
    }

    private AttackResult attackMultiplier(
            PlayerProfile profile, WeaponContext weapon, boolean ranged, String skillId
    ) {
        if (profile == null) return new AttackResult(1.0, false);
        int strength = profile.attribute(CoreAttribute.STRENGTH) + stat(weapon, "strength");
        int agility = profile.attribute(CoreAttribute.AGILITY) + stat(weapon, "agility");
        int intelligence = profile.attribute(CoreAttribute.INTELLIGENCE) + stat(weapon, "intelligence");
        double multiplier = 1.0;
        if (skillId != null) {
            int primary = CombatBalance.primaryOffense(profile.archetype(), strength, agility, intelligence);
            multiplier += Math.max(-0.50, primary * plugin.getConfig().getDouble(
                    "damage.skill-per-primary-attribute", 0.007));
        } else if (ranged) {
            multiplier += Math.max(-0.50, agility * plugin.getConfig().getDouble("damage.ranged-per-agility", 0.007));
        } else {
            multiplier += Math.max(-0.50, strength * plugin.getConfig().getDouble("damage.melee-per-strength", 0.007));
        }
        if (skillId == null && profile.archetype() == Archetype.MAGE) {
            multiplier += Math.max(-0.30, intelligence * plugin.getConfig().getDouble("damage.mage-per-intelligence", 0.004));
        }
        boolean criticalHit = false;
        if (weapon != null) {
            if (weapon.archetype().equals(profile.archetype().name())) {
                multiplier += plugin.getConfig().getDouble("damage.class-weapon-bonus", 0.15);
            } else if (profile.archetype() == Archetype.FREEFORM && !weapon.archetype().equals("UNIVERSAL")) {
                multiplier += plugin.getConfig().getDouble("damage.freeform-weapon-bonus", 0.06);
            }
            multiplier += weapon.limitBreakLevel() * plugin.getConfig().getDouble("damage.per-limit-break", 0.02);
            int critical = Math.max(0, weapon.stat("critical"));
            double chance = Math.min(plugin.getConfig().getDouble("damage.maximum-critical-chance", 0.25),
                    critical * plugin.getConfig().getDouble("damage.critical-chance-per-point", 0.004));
            if (ThreadLocalRandom.current().nextDouble() < chance) {
                multiplier *= plugin.getConfig().getDouble("damage.critical-multiplier", 1.50);
                criticalHit = true;
            }
        }
        return new AttackResult(Math.clamp(multiplier, 0.25,
                plugin.getConfig().getDouble("damage.maximum-attacker-multiplier", 4.0)), criticalHit);
    }

    private double defenseMultiplier(Player player) {
        int defense = profiles.cached(player.getUniqueId())
                .map(profile -> profile.attribute(CoreAttribute.DEFENSE)).orElse(0);
        for (ItemStack item : player.getInventory().getArmorContents()) defense += armorDefense(player, item);
        defense += armorDefense(player, player.getInventory().getItemInOffHand());
        return DefensePolicy.multiplier(
                defense,
                plugin.getConfig().getDouble("defense.reduction-coefficient", 0.009),
                plugin.getConfig().getDouble("defense.maximum-reduction", 0.60),
                plugin.getConfig().getDouble("defense.maximum-vulnerability", 1.50));
    }

    private int armorDefense(Player player, ItemStack item) {
        Optional<EquipmentSnapshot> snapshot = equipment.inspect(item);
        if (snapshot.isEmpty() || !snapshot.get().equipmentType().equals("ARMOR")) return 0;
        EquipmentSnapshot armor = snapshot.get();
        if (armor.boundOwner().isPresent() && !armor.boundOwner().get().equals(player.getUniqueId())) return 0;
        return armor.stat("base-guard") + armor.stat("defense") + armor.limitBreakLevel()
                * plugin.getConfig().getInt("defense.per-armor-limit-break", 2);
    }

    private boolean applyRareTraits(
            EntityDamageByEntityEvent event,
            Player attacker,
            LivingEntity target,
            WeaponContext weapon,
            PlayerProfile profile
    ) {
        if (weapon == null || !weapon.rareTraitActive() || weapon.rareTrait().isEmpty()) return false;
        return switch (weapon.rareTrait().get()) {
            case "life-steal" -> {
                double ratio = plugin.getConfig().getDouble("traits.life-steal-ratio", 0.04);
                double healing = Math.min(target.getHealth(), Math.max(0.0, event.getFinalDamage())) * ratio;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    AttributeInstance maximum = attacker.getAttribute(Attribute.MAX_HEALTH);
                    if (maximum != null && !attacker.isDead() && healing > 0.0) {
                        attacker.setHealth(Math.min(maximum.getValue(), attacker.getHealth() + healing));
                    }
                });
                yield false;
            }
            case "fire-mastery" -> {
                target.setFireTicks(Math.max(target.getFireTicks(), plugin.getConfig().getInt("traits.fire-ticks", 60)));
                yield false;
            }
            case "execute-resonance" -> applyExecute(event, target, profile);
            default -> {
                // Other traits are consumed by their owning skill modules.
                yield false;
            }
        };
    }

    private boolean applyExecute(EntityDamageByEntityEvent event, LivingEntity target, PlayerProfile profile) {
        if (profile == null) return false;
        AttributeInstance maximum = target.getAttribute(Attribute.MAX_HEALTH);
        if (maximum == null) return false;
        boolean protectedTarget = protectedTarget(target);
        double maxHealth = maximum.getValue();
        int primary = CombatBalance.primaryOffense(profile.archetype(),
                profile.attribute(CoreAttribute.STRENGTH), profile.attribute(CoreAttribute.AGILITY),
                profile.attribute(CoreAttribute.INTELLIGENCE));
        double power = event.getFinalDamage() + primary * plugin.getConfig().getDouble(
                "execute.power-per-primary-attribute",
                plugin.getConfig().getDouble("execute.power-per-strength", 12.0));
        double threshold = CombatRules.executeThreshold(
                plugin.getConfig().getDouble("execute.base-threshold", 0.10), protectedTarget, power, maxHealth);
        double remaining = target.getHealth() - event.getFinalDamage();
        if (remaining > 0.0 && remaining <= maxHealth * threshold) {
            event.setDamage(target.getHealth() + 1.0);
            return true;
        }
        return false;
    }

    private static int stat(WeaponContext weapon, String name) {
        return weapon == null ? 0 : weapon.stat(name);
    }

    private boolean protectedTarget(LivingEntity target) {
        return target instanceof Player || target instanceof Boss
                || dungeonBoss != null && target.getPersistentDataContainer().has(dungeonBoss, PersistentDataType.BYTE);
    }

    private boolean verifiedAllies(Player first, Player second) {
        PartyService parties = plugin.getServer().getServicesManager().load(PartyService.class);
        if (parties == null) return false;
        try {
            return parties.areAllies(first.getUniqueId(), second.getUniqueId());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String skillId(EntityDamageByEntityEvent event, Player attacker) {
        if (event.getDamager() instanceof Projectile projectile) {
            String id = skillDamage.projectileSkill(projectile);
            if (id != null && !id.isBlank()) return id;
        }
        return skillDamage.activeSkill();
    }

    private WeaponResolution resolveWeapon(EntityDamageByEntityEvent event, Player attacker) {
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getPersistentDataContainer().has(projectileSnapshot, PersistentDataType.BYTE)) {
            var data = projectile.getPersistentDataContainer();
            if (data.has(projectileInvalid, PersistentDataType.BYTE)) return WeaponResolution.invalid();
            String archetype = data.get(projectileArchetype, PersistentDataType.STRING);
            if (archetype == null) return WeaponResolution.empty();
            Map<String, Integer> stats = Map.of(
                    "strength", data.getOrDefault(projectileStrength, PersistentDataType.INTEGER, 0),
                    "agility", data.getOrDefault(projectileAgility, PersistentDataType.INTEGER, 0),
                    "intelligence", data.getOrDefault(projectileIntelligence, PersistentDataType.INTEGER, 0),
                    "critical", data.getOrDefault(projectileCritical, PersistentDataType.INTEGER, 0));
            return WeaponResolution.valid(new WeaponContext(archetype,
                    data.getOrDefault(projectileLimitBreak, PersistentDataType.INTEGER, 0),
                    Optional.ofNullable(data.get(projectileRareTrait, PersistentDataType.STRING)),
                    data.getOrDefault(projectileRareTraitActive, PersistentDataType.BYTE, (byte) 0) == (byte) 1,
                    stats));
        }
        ItemStack held = attacker.getInventory().getItemInMainHand();
        Optional<EquipmentSnapshot> inspected = equipment.inspect(held);
        if (equipment.isCustomEquipment(held) && inspected.isEmpty()) return WeaponResolution.invalid();
        if (inspected.isEmpty()) return WeaponResolution.empty();
        EquipmentSnapshot snapshot = inspected.orElseThrow();
        if (snapshot.boundOwner().isPresent() && !snapshot.boundOwner().orElseThrow().equals(attacker.getUniqueId())) {
            return WeaponResolution.mismatched();
        }
        return WeaponResolution.valid(new WeaponContext(snapshot.archetype(), snapshot.limitBreakLevel(),
                snapshot.rareTrait(), EquipmentTraitCatalog.activeFor(snapshot, attacker.getUniqueId()), snapshot.stats()));
    }

    private static Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) return player;
        }
        return null;
    }

    private record AttackResult(double multiplier, boolean critical) {
    }

    private record WeaponContext(
            String archetype, int limitBreakLevel, Optional<String> rareTrait,
            boolean rareTraitActive, Map<String, Integer> stats
    ) {
        private WeaponContext {
            Objects.requireNonNull(archetype, "archetype");
            rareTrait = Objects.requireNonNull(rareTrait, "rareTrait");
            stats = Map.copyOf(stats);
        }

        int stat(String name) {
            return stats.getOrDefault(name, 0);
        }
    }

    private record WeaponResolution(boolean valid, boolean ownerMismatch, WeaponContext weapon) {
        static WeaponResolution invalid() {
            return new WeaponResolution(false, false, null);
        }

        static WeaponResolution mismatched() {
            return new WeaponResolution(true, true, null);
        }

        static WeaponResolution empty() {
            return new WeaponResolution(true, false, null);
        }

        static WeaponResolution valid(WeaponContext weapon) {
            return new WeaponResolution(true, false, weapon);
        }
    }
}
