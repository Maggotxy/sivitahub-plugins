package cn.originalrpg.mobs.listener;

import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.rules.CombatRules;
import cn.originalrpg.api.rules.PityRules;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.MobAppearanceService;
import cn.originalrpg.api.service.ChapterService;
import cn.originalrpg.mobs.ExperienceWriteBuffer;
import cn.originalrpg.mobs.DelayedMobRewardQueue;
import cn.originalrpg.mobs.AdaptiveMobSafetyRules;
import cn.originalrpg.mobs.AdaptiveAssignmentPolicy;
import cn.originalrpg.mobs.MobVisuals;
import cn.originalrpg.mobs.MobScalingRules;
import cn.originalrpg.mobs.MobExperienceRules;
import cn.originalrpg.mobs.ability.MobAbilityController;
import cn.originalrpg.mobs.ability.MobBehavior;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class AdaptiveMobListener implements Listener, DelayedMobRewardQueue.Feedback {
    private final Plugin plugin;
    private final ProfileService profiles;
    private final ChapterService chapters;
    private final MessageService messages;
    private final ExperienceWriteBuffer experienceBuffer;
    private final NamespacedKey mobLevel;
    private final NamespacedKey eliteAffix;
    private final NamespacedKey materialMarker;
    private final NamespacedKey assignmentCandidate;
    private final NamespacedKey assignmentRetryAfter;
    private final NamespacedKey pickupOwner = key("rpgmarket:pickup_owner");
    private final NamespacedKey skillBook = key("rpgskills:skill_book");
    private final NamespacedKey skillBookInstance = key("rpgskills:book_instance");
    private final NamespacedKey dungeonBoss = key("rpgdungeons:boss");
    private final double playerRadius;
    private final int newcomerLevelThreshold;
    private final int newcomerPositiveOffset;
    private final int maximumPositiveOffset;
    private final double mixedGroupSafetyRadius;
    private final int mixedGroupMaximumAboveLowest;
    private final int newcomerSpecialAbilityLevel;
    private final String scalingSourceExclusionPermission;
    private final double eliteChance;
    private final double eliteMaterialChance;
    private final double normalMaterialChance;
    private final double eliteSkillBookChance;
    private final int eliteMaterialPity;
    private final int normalMaterialPity;
    private final int eliteSkillBookPity;
    private final double minimumHealthMultiplier;
    private final double maximumHealthMultiplier;
    private final double minimumDamageMultiplier;
    private final double maximumDamageMultiplier;
    private final double maximumArmorBonus;
    private final double eliteDamageMultiplier;
    private final long affixCooldownMillis;
    private final long assignmentRetryMillis;
    private final long minimumExperiencePerKill;
    private final long normalExperienceMultiplier;
    private final long eliteExperienceMultiplier;
    private final NamespacedKey affixCooldown;
    private final NamespacedKey berserkTriggered;
    private final NamespacedKey eliteMaterialFailures;
    private final NamespacedKey normalMaterialFailures;
    private final NamespacedKey skillBookFailures;
    private final MobVisuals visuals;
    private final MobAppearanceService appearances;
    private final MobAbilityController abilities;
    private DelayedMobRewardQueue delayedRewards;

    public AdaptiveMobListener(
            Plugin plugin, ProfileService profiles, ChapterService chapters,
            MessageService messages, ExperienceWriteBuffer experienceBuffer,
            MobAppearanceService appearances, MobAbilityController abilities
    ) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.chapters = chapters;
        this.messages = messages;
        this.experienceBuffer = experienceBuffer;
        this.appearances = appearances;
        this.abilities = abilities;
        this.visuals = new MobVisuals(plugin, messages);
        mobLevel = new NamespacedKey(plugin, "level");
        eliteAffix = new NamespacedKey(plugin, "elite_affix");
        materialMarker = new NamespacedKey(plugin, "adaptive_material");
        assignmentCandidate = new NamespacedKey(plugin, "assignment_candidate");
        assignmentRetryAfter = new NamespacedKey(plugin, "assignment_retry_after");
        playerRadius = plugin.getConfig().getDouble("adaptive.player-radius", 48.0);
        newcomerLevelThreshold = Math.clamp(plugin.getConfig().getInt(
                "adaptive.newcomer-level-threshold", 10), 1, 100);
        newcomerPositiveOffset = Math.clamp(plugin.getConfig().getInt(
                "adaptive.newcomer-positive-offset", 3), 0, 6);
        maximumPositiveOffset = Math.clamp(plugin.getConfig().getInt(
                "adaptive.maximum-positive-offset", 6), 0, 100);
        mixedGroupSafetyRadius = Math.clamp(plugin.getConfig().getDouble(
                "adaptive.mixed-group-safety-radius", 48.0), 8.0, 96.0);
        mixedGroupMaximumAboveLowest = Math.clamp(plugin.getConfig().getInt(
                "adaptive.mixed-group-maximum-above-lowest", 6), 0, 20);
        newcomerSpecialAbilityLevel = Math.clamp(plugin.getConfig().getInt(
                "adaptive.special-ability-minimum-player-level", 5), 1, 30);
        scalingSourceExclusionPermission = plugin.getConfig().getString(
                "adaptive.scaling-source-exclusion-permission", "rpg.mobs.scaling-excluded");
        eliteChance = plugin.getConfig().getDouble("elite.base-chance", 0.06);
        eliteMaterialChance = plugin.getConfig().getDouble("elite.material-drop-chance", 0.30);
        normalMaterialChance = plugin.getConfig().getDouble("normal.material-drop-chance", 0.08);
        eliteSkillBookChance = Math.clamp(plugin.getConfig().getDouble("elite.common-skill-book-chance", 0.01), 0.0, 1.0);
        eliteMaterialPity = Math.clamp(plugin.getConfig().getInt("elite.material-drop-pity", 4), 1, 10_000);
        normalMaterialPity = Math.clamp(plugin.getConfig().getInt("normal.material-drop-pity", 40), 1, 10_000);
        eliteSkillBookPity = Math.clamp(plugin.getConfig().getInt("elite.common-skill-book-pity", 100), 1, 10_000);
        minimumHealthMultiplier = plugin.getConfig().getDouble("adaptive.minimum-health-multiplier", 1.0);
        maximumHealthMultiplier = plugin.getConfig().getDouble("adaptive.maximum-health-multiplier", 8.0);
        minimumDamageMultiplier = plugin.getConfig().getDouble("adaptive.minimum-damage-multiplier", 1.0);
        maximumDamageMultiplier = plugin.getConfig().getDouble("adaptive.maximum-damage-multiplier", 4.0);
        maximumArmorBonus = plugin.getConfig().getDouble("adaptive.maximum-armor-bonus", 12.0);
        eliteDamageMultiplier = plugin.getConfig().getDouble("elite.damage-multiplier", 1.25);
        affixCooldownMillis = Math.clamp(plugin.getConfig().getLong("elite.trigger-cooldown-millis", 8000L),
                2000L, 30000L);
        assignmentRetryMillis = Math.clamp(plugin.getConfig().getLong(
                "adaptive.assignment-retry-millis", 1500L), 1000L, 2000L);
        minimumExperiencePerKill = plugin.getConfig().getLong("experience.minimum-per-kill", 10L);
        normalExperienceMultiplier = plugin.getConfig().getLong("experience.normal-level-multiplier", 10L);
        eliteExperienceMultiplier = plugin.getConfig().getLong("experience.elite-level-multiplier", 40L);
        affixCooldown = new NamespacedKey(plugin, "affix_cooldown");
        berserkTriggered = new NamespacedKey(plugin, "berserk_triggered");
        eliteMaterialFailures = new NamespacedKey(plugin, "elite_material_failures");
        normalMaterialFailures = new NamespacedKey(plugin, "normal_material_failures");
        skillBookFailures = new NamespacedKey(plugin, "skill_book_failures");
    }

    public void setDelayedRewards(DelayedMobRewardQueue delayedRewards) {
        if (this.delayedRewards != null) throw new IllegalStateException("Delayed rewards already configured");
        this.delayedRewards = java.util.Objects.requireNonNull(delayedRewards, "delayedRewards");
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity monster = event.getEntity();
        if (!(monster instanceof Enemy)
                || !AdaptiveAssignmentPolicy.isCandidateSpawn(event.getSpawnReason().name())) return;
        monster.getPersistentDataContainer().set(
                assignmentCandidate, PersistentDataType.BYTE, (byte) 1);
        tryAssignAdaptive(monster, System.currentTimeMillis());
    }

    /**
     * A natural mob can spawn while nearby authenticated profiles are still
     * entering the cache. First player combat retries that one entity only;
     * there is no world/entity scan and cached() never starts database I/O.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFirstPlayerCombat(EntityDamageByEntityEvent event) {
        LivingEntity monster;
        if (event.getEntity() instanceof LivingEntity target
                && target instanceof Enemy && playerSource(event) != null) {
            monster = target;
        } else if (event.getEntity() instanceof Player) {
            monster = monsterSource(event);
        } else {
            return;
        }
        if (monster != null) tryAssignAdaptive(monster, System.currentTimeMillis());
    }

    private boolean tryAssignAdaptive(LivingEntity monster, long nowMillis) {
        PersistentDataContainer data = monster.getPersistentDataContainer();
        boolean assigned = data.has(mobLevel, PersistentDataType.INTEGER);
        boolean candidate = data.has(assignmentCandidate, PersistentDataType.BYTE);
        Long retryAfter = data.get(assignmentRetryAfter, PersistentDataType.LONG);
        if (!AdaptiveAssignmentPolicy.shouldAttempt(assigned, candidate, retryAfter, nowMillis)) {
            if (assigned && candidate) clearAssignmentRecovery(data);
            return false;
        }
        // Claim this retry before the bounded nearby-player pass. A busy combat
        // event stream therefore cannot repeat the scan more than once per 1-2s.
        data.set(assignmentRetryAfter, PersistentDataType.LONG,
                nowMillis + assignmentRetryMillis);
        org.bukkit.Location monsterLocation = monster.getLocation();
        org.bukkit.Location playerLocation = new org.bukkit.Location(monster.getWorld(), 0.0, 0.0, 0.0);
        Player nearestPlayer = null;
        PlayerProfile nearestProfile = null;
        double nearestDistance = Double.MAX_VALUE;
        int lowestNearbyLevel = Integer.MAX_VALUE;
        double nearestRadiusSquared = playerRadius * playerRadius;
        double safetyRadiusSquared = mixedGroupSafetyRadius * mixedGroupSafetyRadius;
        double searchRadius = Math.max(playerRadius, mixedGroupSafetyRadius);
        int liveMaximum = AdaptiveMobSafetyRules.maximumPlayerLevel(
                chapters.current().maximumOpenAscension());
        for (Player player : monster.getWorld().getNearbyPlayers(monsterLocation, searchRadius)) {
            PlayerProfile profile = profiles.cached(player.getUniqueId()).orElse(null);
            if (profile == null) continue;
            // OP-only test profiles deliberately exceed the live chapter. They
            // can exercise skills and force a behavior through /mobguide test,
            // but must never seed lethal natural mobs for ordinary players.
            if (!AdaptiveMobSafetyRules.eligibleScalingSource(
                    player.hasPermission(scalingSourceExclusionPermission),
                    profile.effectiveLevel(), liveMaximum)) continue;
            player.getLocation(playerLocation);
            double distance = playerLocation.distanceSquared(monsterLocation);
            if (distance <= safetyRadiusSquared) {
                lowestNearbyLevel = Math.min(lowestNearbyLevel, profile.effectiveLevel());
            }
            if (distance <= nearestRadiusSquared && distance < nearestDistance) {
                nearestDistance = distance;
                nearestPlayer = player;
                nearestProfile = profile;
            }
        }
        if (nearestProfile == null || nearestPlayer == null) return false;
        int positiveOffset = MobScalingRules.positiveOffsetUpperBound(nearestProfile.effectiveLevel(),
                newcomerLevelThreshold, newcomerPositiveOffset, maximumPositiveOffset);
        int offset = ThreadLocalRandom.current().nextInt(-3, positiveOffset + 1);
        int level = CombatRules.adaptiveMonsterLevel(nearestProfile.effectiveLevel(), offset);
        if (lowestNearbyLevel != Integer.MAX_VALUE) {
            level = MobScalingRules.mixedGroupLevel(
                    level, lowestNearbyLevel, mixedGroupMaximumAboveLowest);
        }
        data.set(mobLevel, PersistentDataType.INTEGER, level);

        int lowestRelevantLevel = lowestNearbyLevel == Integer.MAX_VALUE
                ? nearestProfile.effectiveLevel() : lowestNearbyLevel;
        boolean specialsAllowed = MobScalingRules.specialAbilitiesAllowed(
                lowestRelevantLevel, newcomerSpecialAbilityLevel);
        boolean elite = specialsAllowed && ThreadLocalRandom.current().nextDouble() < eliteChance;
        String affix = elite ? applyRandomAffix(monster) : null;
        MobBehavior behavior = elite || !specialsAllowed ? null : abilities.assign(monster);
        if (affix != null) {
            data.set(eliteAffix, PersistentDataType.STRING, affix);
            // The reviewed full-body shell replaces the old carved-pumpkin head.
            // Other affixes keep an intact vanilla body until they receive their own model.
            if ("berserk".equals(affix)) appearances.applyBloodMoonHusk(monster);
        }
        scaleCombatAttributes(monster, level, elite);
        Component name = Component.text("Lv." + level + " ", elite ? NamedTextColor.RED : NamedTextColor.GRAY);
        if (elite) {
            name = Component.text(messages.text(nearestPlayer, "mobs.elite-name", java.util.Map.of(
                            "affix", messages.text(nearestPlayer, "mobs.affixes." + affix))) + " ",
                    NamedTextColor.RED).append(name);
        } else if (behavior != null) {
            name = name.append(Component.text("[" + abilities.localizedName(nearestPlayer, behavior) + "] ",
                    NamedTextColor.AQUA));
        }
        monster.customName(name.append(Component.translatable(monster.getType().translationKey())));
        monster.setCustomNameVisible(elite || behavior != null);
        if (elite) visuals.eliteSpawn(monster);
        clearAssignmentRecovery(data);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEliteDamagesPlayer(EntityDamageByEntityEvent event) {
        LivingEntity monster = monsterSource(event);
        if (monster == null || !(event.getEntity() instanceof Player player)) return;
        String affix = monster.getPersistentDataContainer().get(eliteAffix, PersistentDataType.STRING);
        if (affix == null) return;
        if ("berserk".equals(affix) && healthRatio(monster) <= 0.50) {
            event.setDamage(event.getDamage() * 1.25);
        }
        if ("shadow".equals(affix) && claimAffixTrigger(monster)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 30, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 40, 0));
            triggerFeedback(monster, player, affix);
        } else if ("swift".equals(affix) && claimAffixTrigger(monster)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, 0));
            triggerFeedback(monster, player, affix);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamagesElite(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Enemy)) return;
        LivingEntity monster = (LivingEntity) event.getEntity();
        Player attacker = playerSource(event);
        if (attacker == null) return;
        String affix = monster.getPersistentDataContainer().get(eliteAffix, PersistentDataType.STRING);
        if (affix == null) return;
        if ("armored".equals(affix)) {
            event.setDamage(event.getDamage() * 0.75);
            if (claimAffixTrigger(monster)) triggerFeedback(monster, attacker, affix);
        }
        if ("berserk".equals(affix) && !monster.getPersistentDataContainer().has(
                berserkTriggered, PersistentDataType.BYTE)) {
            double remaining = Math.max(0.0, monster.getHealth() - event.getFinalDamage());
            AttributeInstance maximum = monster.getAttribute(Attribute.MAX_HEALTH);
            if (maximum != null && remaining / maximum.getValue() <= 0.40) {
                monster.getPersistentDataContainer().set(berserkTriggered, PersistentDataType.BYTE, (byte) 1);
                monster.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                        PotionEffect.INFINITE_DURATION, 1));
                monster.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,
                        PotionEffect.INFINITE_DURATION, 1));
                triggerFeedback(monster, attacker, affix);
            }
        }
        if ("shadow".equals(affix) && ThreadLocalRandom.current().nextDouble() < 0.18
                && claimAffixTrigger(monster)) {
            evadeBehind(monster, attacker);
            triggerFeedback(monster, attacker, affix);
        } else if ("swift".equals(affix) && ThreadLocalRandom.current().nextDouble() < 0.24
                && claimAffixTrigger(monster)) {
            sidestep(monster, attacker);
            triggerFeedback(monster, attacker, affix);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        // DungeonManager starts the model's authored death animation during the
        // same event. BetterModel's bounded reconcile pass closes the tracker
        // after the dead entity becomes invalid, so do not cut that animation
        // off here. Natural and event mobs are still released immediately.
        if (!entity.getPersistentDataContainer().has(dungeonBoss, PersistentDataType.BYTE)) {
            appearances.clear(entity);
        }
        Integer level = entity.getPersistentDataContainer().get(mobLevel, PersistentDataType.INTEGER);
        Player killer = entity.getKiller();
        if (level == null || killer == null) {
            return;
        }
        java.util.UUID killerId = killer.getUniqueId();
        Optional<PlayerProfile> profile = profiles.cached(killerId);
        if (profile.isEmpty() || delayedRewards.hasPending(killerId)
                || !experienceBuffer.hasCapacity(killerId)) {
            queueDelayedReward(killer, entity.getUniqueId(), level,
                    entity.getPersistentDataContainer().has(eliteAffix, PersistentDataType.STRING));
            return;
        }
        if (!CombatRules.allowsCustomDrops(level, profile.get().effectiveLevel())) {
            killer.sendActionBar(Component.text(messages.text(killer, "mobs.level-gap-drop"), NamedTextColor.DARK_GRAY));
            return;
        }
        boolean elite = entity.getPersistentDataContainer().has(eliteAffix, PersistentDataType.STRING);
        double chance = elite ? eliteMaterialChance : normalMaterialChance;
        NamespacedKey materialPityKey = elite ? eliteMaterialFailures : normalMaterialFailures;
        int materialPity = elite ? eliteMaterialPity : normalMaterialPity;
        PityRules.Outcome materialOutcome = rollPity(killer, materialPityKey, materialPity, chance);
        if (materialOutcome.dropped()) {
            ItemStack material = mutationMaterial(killer, elite, true);
            event.getDrops().add(material);
            visuals.rareDrop(killer, elite ? MobVisuals.DropKind.CORE : MobVisuals.DropKind.SHARD);
            if (materialOutcome.guaranteed()) notifyPity(killer, elite
                    ? "mobs.items.mutation-core" : "mobs.items.mutation-shard");
        }
        if (elite) {
            PityRules.Outcome bookOutcome = rollPity(
                    killer, skillBookFailures, eliteSkillBookPity, eliteSkillBookChance);
            if (bookOutcome.dropped()) {
                event.getDrops().add(commonSkillBook(killer, true));
                visuals.rareDrop(killer, MobVisuals.DropKind.SKILL_BOOK);
                if (bookOutcome.guaranteed()) notifyPity(killer, "mobs.items.common-insight-book");
            }
        }
        long experience = MobExperienceRules.reward(level, elite,
                minimumExperiencePerKill, normalExperienceMultiplier, eliteExperienceMultiplier);
        if (!experienceBuffer.add(killer, experience)) {
            plugin.getLogger().severe("Mob experience buffer is full; reward was not accepted player=" + killerId);
        }
    }

    private void queueDelayedReward(Player killer, UUID entityId, int level, boolean elite) {
        if (delayedRewards == null || !delayedRewards.hasCapacity(killer.getUniqueId())) {
            plugin.getLogger().severe("Delayed mob reward queue is full; refusing unbounded growth player="
                    + killer.getUniqueId());
            return;
        }
        double chance = elite ? eliteMaterialChance : normalMaterialChance;
        NamespacedKey materialPityKey = elite ? eliteMaterialFailures : normalMaterialFailures;
        int materialPity = elite ? eliteMaterialPity : normalMaterialPity;
        PityRules.Outcome materialOutcome = rollPity(killer, materialPityKey, materialPity, chance);
        long deathMillis = System.currentTimeMillis();
        UUID killId = cn.originalrpg.mobs.MobRewardReliabilityRules.killId(
                killer.getUniqueId(), entityId, deathMillis);
        List<DelayedMobRewardQueue.FrozenDelivery> deliveries = new ArrayList<>(2);
        if (materialOutcome.dropped()) {
            String kind = "material";
            deliveries.add(new DelayedMobRewardQueue.FrozenDelivery(
                    cn.originalrpg.mobs.MobRewardReliabilityRules.deliveryId(killId, kind),
                    kind,
                    serialize(mutationMaterial(killer, elite, false)),
                    elite ? MobVisuals.DropKind.CORE : MobVisuals.DropKind.SHARD,
                    elite ? "mobs.items.mutation-core" : "mobs.items.mutation-shard",
                    materialOutcome.guaranteed()));
        }
        if (elite) {
            PityRules.Outcome bookOutcome = rollPity(
                    killer, skillBookFailures, eliteSkillBookPity, eliteSkillBookChance);
            if (bookOutcome.dropped()) {
                String kind = "skill-book";
                deliveries.add(new DelayedMobRewardQueue.FrozenDelivery(
                        cn.originalrpg.mobs.MobRewardReliabilityRules.deliveryId(killId, kind),
                        kind,
                        serialize(commonSkillBook(killer, false)),
                        MobVisuals.DropKind.SKILL_BOOK,
                        "mobs.items.common-insight-book",
                        bookOutcome.guaranteed()));
            }
        }
        long experience = MobExperienceRules.reward(level, elite,
                minimumExperiencePerKill, normalExperienceMultiplier, eliteExperienceMultiplier);
        DelayedMobRewardQueue.PendingReward reward = new DelayedMobRewardQueue.PendingReward(
                killId, killer, level, experience, deathMillis, deliveries);
        if (!delayedRewards.offer(reward)) {
            plugin.getLogger().severe("Delayed mob reward reservation unexpectedly failed kill=" + killId);
        }
    }

    private ItemStack mutationMaterial(Player audience, boolean elite, boolean groundPickup) {
        ItemStack material = new ItemStack(elite ? Material.ECHO_SHARD : Material.AMETHYST_SHARD);
        ItemMeta meta = material.getItemMeta();
        meta.displayName(Component.text(messages.text(audience, elite
                        ? "mobs.items.mutation-core" : "mobs.items.mutation-shard"),
                elite ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.AQUA));
        meta.setItemModel(new NamespacedKey("originalrpg", elite ? "mutation_core" : "mutation_shard"));
        meta.getPersistentDataContainer().set(materialMarker, PersistentDataType.BYTE, (byte) 1);
        if (groundPickup) {
            meta.getPersistentDataContainer().set(pickupOwner, PersistentDataType.STRING,
                    audience.getUniqueId().toString());
        }
        material.setItemMeta(meta);
        return material;
    }

    private ItemStack commonSkillBook(Player audience, boolean groundPickup) {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(messages.text(audience, "mobs.items.common-insight-book"), NamedTextColor.AQUA));
        meta.setItemModel(new NamespacedKey("originalrpg", "common_insight_skill_book"));
        meta.getPersistentDataContainer().set(skillBook, PersistentDataType.STRING, "common-insight");
        meta.getPersistentDataContainer().set(skillBookInstance, PersistentDataType.STRING, UUID.randomUUID().toString());
        if (groundPickup) {
            meta.getPersistentDataContainer().set(pickupOwner, PersistentDataType.STRING,
                    audience.getUniqueId().toString());
        }
        item.setItemMeta(meta);
        return item;
    }

    private String serialize(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    @Override
    public void levelGap(DelayedMobRewardQueue.PendingReward reward) {
        Player player = reward.activeSession();
        if (player != null) player.sendActionBar(Component.text(
                messages.text(player, "mobs.level-gap-drop"), NamedTextColor.DARK_GRAY));
    }

    @Override
    public void delivered(DelayedMobRewardQueue.PendingReward reward) {
        Player player = reward.activeSession();
        if (player == null) return;
        for (DelayedMobRewardQueue.FrozenDelivery delivery : reward.deliveries()) {
            visuals.rareDrop(player, delivery.visualKind());
            if (delivery.pityGuaranteed()) notifyPity(player, delivery.itemMessageKey());
        }
    }

    private void clearAssignmentRecovery(PersistentDataContainer data) {
        data.remove(assignmentCandidate);
        data.remove(assignmentRetryAfter);
    }

    private void scaleCombatAttributes(LivingEntity monster, int level, boolean elite) {
        AttributeInstance maxHealth = monster.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            double multiplier = MobScalingRules.healthMultiplier(level, elite,
                    minimumHealthMultiplier, maximumHealthMultiplier, 1.8);
            maxHealth.setBaseValue(Math.min(2048.0, maxHealth.getBaseValue() * multiplier));
            monster.setHealth(maxHealth.getValue());
        }
        AttributeInstance damage = monster.getAttribute(Attribute.ATTACK_DAMAGE);
        if (damage != null) {
            double multiplier = MobScalingRules.damageMultiplier(level, elite,
                    minimumDamageMultiplier, maximumDamageMultiplier, eliteDamageMultiplier);
            damage.setBaseValue(Math.min(100.0, damage.getBaseValue() * multiplier));
        }
        AttributeInstance armor = monster.getAttribute(Attribute.ARMOR);
        if (armor != null) armor.setBaseValue(Math.min(30.0,
                armor.getBaseValue() + MobScalingRules.armorBonus(level, elite, maximumArmorBonus)));
    }

    private String applyRandomAffix(LivingEntity monster) {
        return switch (ThreadLocalRandom.current().nextInt(4)) {
            case 0 -> {
                monster.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1));
                yield "swift";
            }
            case 1 -> {
                yield "berserk";
            }
            case 2 -> "armored";
            default -> {
                monster.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0));
                yield "shadow";
            }
        };
    }

    private LivingEntity monsterSource(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof LivingEntity monster && monster instanceof Enemy) return monster;
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof LivingEntity monster && monster instanceof Enemy) return monster;
        }
        return null;
    }

    private Player playerSource(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }

    private boolean claimAffixTrigger(LivingEntity monster) {
        long now = System.currentTimeMillis();
        Long next = monster.getPersistentDataContainer().get(affixCooldown, PersistentDataType.LONG);
        if (next != null && next > now) return false;
        monster.getPersistentDataContainer().set(affixCooldown, PersistentDataType.LONG, now + affixCooldownMillis);
        return true;
    }

    private double healthRatio(LivingEntity monster) {
        AttributeInstance maximum = monster.getAttribute(Attribute.MAX_HEALTH);
        return maximum == null || maximum.getValue() <= 0.0 ? 1.0 : monster.getHealth() / maximum.getValue();
    }

    private void evadeBehind(LivingEntity monster, Player attacker) {
        Vector direction = attacker.getLocation().getDirection().setY(0.0);
        if (direction.lengthSquared() < 0.001) return;
        org.bukkit.Location destination = attacker.getLocation().subtract(direction.normalize().multiply(2.0));
        safeTeleport(monster, destination);
    }

    private void sidestep(LivingEntity monster, Player attacker) {
        Vector away = monster.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0.0);
        if (away.lengthSquared() < 0.001) away = new Vector(1.0, 0.0, 0.0);
        Vector side = new Vector(-away.getZ(), 0.0, away.getX()).normalize()
                .multiply(ThreadLocalRandom.current().nextBoolean() ? 1.8 : -1.8);
        safeTeleport(monster, monster.getLocation().add(side));
    }

    private void safeTeleport(LivingEntity monster, org.bukkit.Location destination) {
        if (destination.getWorld() != monster.getWorld()) return;
        destination.setY(monster.getLocation().getY());
        if (!destination.getBlock().isPassable() || !destination.clone().add(0.0, 1.0, 0.0).getBlock().isPassable()
                || destination.clone().subtract(0.0, 1.0, 0.0).getBlock().isPassable()) return;
        monster.teleport(destination);
    }

    private void triggerFeedback(LivingEntity monster, Player player, String affix) {
        player.sendActionBar(Component.text(messages.text(player, "mobs.affix-trigger." + affix),
                NamedTextColor.RED));
        visuals.affixTrigger(monster, player, affix);
    }

    private PityRules.Outcome rollPity(Player player, NamespacedKey key, int guarantee, double chance) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        int failures = Math.max(0, data.getOrDefault(key, PersistentDataType.INTEGER, 0));
        PityRules.Outcome outcome = PityRules.attempt(
                failures, guarantee, chance, ThreadLocalRandom.current().nextDouble());
        data.set(key, PersistentDataType.INTEGER, outcome.failures());
        return outcome;
    }

    private void notifyPity(Player player, String itemKey) {
        player.sendMessage(Component.text(messages.text(player, "mobs.pity-drop", java.util.Map.of(
                "item", messages.text(player, itemKey))), NamedTextColor.GOLD));
    }

    private static NamespacedKey key(String value) {
        return java.util.Objects.requireNonNull(NamespacedKey.fromString(value), value);
    }
}
