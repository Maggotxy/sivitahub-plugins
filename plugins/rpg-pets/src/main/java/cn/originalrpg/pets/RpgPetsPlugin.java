package cn.originalrpg.pets;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.PetCollectionEntry;
import cn.originalrpg.api.model.PetProgress;
import cn.originalrpg.api.model.PetProgressResult;
import cn.originalrpg.api.model.PetRevivalIntent;
import cn.originalrpg.api.model.PetRevivalRequest;
import cn.originalrpg.api.model.PetRevivalResult;
import cn.originalrpg.api.service.CombatStateService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.PetProgressService;
import cn.originalrpg.api.service.PetRevivalService;
import de.Keyle.MyPet.MyPetApi;
import de.Keyle.MyPet.MyPetPlugin;
import de.Keyle.MyPet.api.entity.Pet;
import de.Keyle.MyPet.api.entity.PersistedPet;
import de.Keyle.MyPet.api.entity.StoredPet;
import de.Keyle.MyPet.api.event.PetActivatedEvent;
import de.Keyle.MyPet.api.event.PetCreateEvent;
import de.Keyle.MyPet.api.event.PetDamageEvent;
import de.Keyle.MyPet.api.event.PetExpEvent;
import de.Keyle.MyPet.api.event.PetFeedEvent;
import de.Keyle.MyPet.api.event.PetLevelUpEvent;
import de.Keyle.MyPet.api.event.PetPickupItemEvent;
import de.Keyle.MyPet.api.event.PetStatusEvent;
import de.Keyle.MyPet.api.skill.Upgrade;
import de.Keyle.MyPet.api.skill.modifier.UpgradeNumberModifier;
import de.Keyle.MyPet.skill.upgrades.LifeUpgrade;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Server-owned rules and UX around the MyPet engine.
 *
 * <p>MyPet owns entity AI and persistence. This layer owns safe command access,
 * world/combat policy, bounded damage, unified-currency revival and bilingual
 * onboarding. All Bukkit interaction remains on the server thread; only the
 * currency ledger runs asynchronously.</p>
 */
public final class RpgPetsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Set<Integer> MILESTONE_LEVELS = Set.of(5, 10, 15, 20, 25, 30);

    private record PlayerSession(UUID playerId, UUID token) {
    }

    private record AuthenticatedSession(Player player, UUID token) {
    }

    private final Set<UUID> internalCommands = ConcurrentHashMap.newKeySet();
    private final Map<UUID, AuthenticatedSession> authenticatedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> sessionTokens = new ConcurrentHashMap<>();
    /** One pending intent lookup/debit request per account. */
    private final Set<UUID> revivalPlayerQueries = ConcurrentHashMap.newKeySet();
    /** One MyPet persistence replay per durable operation. */
    private final Set<UUID> revivalWrites = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingProgress = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> captureNoticeAt = new HashMap<>();
    private final Map<UUID, PetProgress> progressCache = new ConcurrentHashMap<>();
    private final Map<UUID, PetAbilityState> abilityStates = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> combatBondCounters = new ConcurrentHashMap<>();
    private final Map<UUID, SkinApplication> appliedSkins = new ConcurrentHashMap<>();
    private CombatStateService combat;
    private PetProgressService petProgress;
    private PetRevivalService petRevivals;
    private PetMessages messages;
    private PetPolicy policy;
    private Set<String> engineRoots;
    private int menuSize;
    private double baseDamageCap;
    private double damagePerLevel;
    private double absoluteDamageCap;
    private double maxExpPerEvent;
    private boolean hostileTargetsOnly;
    private boolean nonLethalDirectDamage;
    private BigDecimal reviveBase;
    private BigDecimal revivePerLevel;
    private NamespacedKey signalKey;
    private NamespacedKey signalOwnerKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        // Old development builds could serialize an entire locale node as a
        // list while adding help lines.  Leaf-by-leaf migrations cannot repair
        // that shape: every lookup below messages.<locale> then silently
        // misses.  Replace only malformed locale sections with their packaged
        // defaults; valid operator-edited mappings and all balance settings are
        // left untouched.
        repairPackagedSectionIfMalformed("messages.zh_cn");
        repairPackagedSectionIfMalformed("messages.en_us");
        int configVersion = getConfig().getInt("config-version", 0);
        if (configVersion < 3) {
            getConfig().set("menu.size", 45);
        }
        if (configVersion < 4) {
            // Bukkit merges missing scalar keys but deliberately does not merge
            // existing lists. Refresh the player-facing leaves introduced with
            // bond/evolution/active abilities while preserving all policy and
            // balancing overrides made by an operator.
            copyPackagedDefaults(
                    "messages.zh_cn.help",
                    "messages.zh_cn.status-active",
                    "messages.zh_cn.capture-created",
                    "messages.zh_cn.menu.status.lore-active",
                    "messages.zh_cn.menu.skill.lore",
                    "messages.en_us.help",
                    "messages.en_us.status-active",
                    "messages.en_us.capture-created",
                    "messages.en_us.menu.status.lore-active",
                    "messages.en_us.menu.skill.lore");
        }
        if (configVersion < 5) {
            copyPackagedDefaults(
                    "revive.recovery-interval-ticks",
                    "messages.zh_cn.revive-saved",
                    "messages.zh_cn.revive-resuming",
                    "messages.zh_cn.revive-support",
                    "messages.zh_cn.revive-pet",
                    "messages.en_us.revive-saved",
                    "messages.en_us.revive-resuming",
                    "messages.en_us.revive-support",
                    "messages.en_us.revive-pet");
        }
        getConfig().set("config-version", 5);
        saveConfig();
        if (!MyPetApi.isReady()) {
            throw new IllegalStateException("MyPet API is not ready; verify the hard dependency and startup order");
        }
        combat = requireService(CombatStateService.class);
        petProgress = requireService(PetProgressService.class);
        petRevivals = requireService(PetRevivalService.class);
        signalKey = new NamespacedKey(this, "companion_signal");
        signalOwnerKey = new NamespacedKey(this, "companion_signal_owner");
        messages = new PetMessages(getConfig(), requireService(MessageService.class));
        policy = new PetPolicy(
                normalizedSet(getConfig().getStringList("dungeon-world-prefixes")),
                normalizedSet(getConfig().getStringList("blocked-worlds")),
                normalizedSet(getConfig().getStringList("capture-worlds")));
        engineRoots = normalizedSet(getConfig().getStringList("engine-command-roots"));
        menuSize = normalizeMenuSize(getConfig().getInt("menu.size", 27));
        baseDamageCap = positive(getConfig().getDouble("combat.max-damage-base", 6.0), 6.0);
        damagePerLevel = positive(getConfig().getDouble("combat.max-damage-per-level", 0.15), 0.15);
        absoluteDamageCap = positive(getConfig().getDouble("combat.absolute-max-damage", 10.0), 10.0);
        hostileTargetsOnly = getConfig().getBoolean("combat.hostile-targets-only", true);
        nonLethalDirectDamage = getConfig().getBoolean("combat.nonlethal-direct-damage", true);
        maxExpPerEvent = positive(getConfig().getDouble("progression.max-exp-per-event", 25.0), 25.0);
        reviveBase = positiveMoney("revive.base-cost", "120.00");
        revivePerLevel = positiveMoney("revive.per-level-cost", "18.00");

        PluginCommand command = Objects.requireNonNull(getCommand("companion"), "companion command");
        command.setExecutor(this);
        command.setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);

        long interval = Math.clamp(getConfig().getLong("audit-interval-ticks", 40L), 20L, 200L);
        getServer().getScheduler().runTaskTimer(this, this::auditActivePets, interval, interval);
        long recoveryInterval = Math.clamp(
                getConfig().getLong("revive.recovery-interval-ticks", 200L), 100L, 1_200L);
        getServer().getScheduler().runTaskTimer(this, this::auditPendingRevivals,
                recoveryInterval, recoveryInterval);
        getLogger().info("MyPet rules active: four level-30 paths, no pickup/gathering/trade/PvP, bounded combat damage.");
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        UUID playerId = event.player().getUniqueId();
        UUID token = UUID.randomUUID();
        authenticatedPlayers.put(playerId, new AuthenticatedSession(event.player(), token));
        sessionTokens.put(playerId, token);
        recoverRevivals(playerId, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearSession(event.getPlayer());
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        clearSession(event.player());
    }

    private void clearSession(Player player) {
        UUID playerId = player.getUniqueId();
        AuthenticatedSession session = authenticatedPlayers.get(playerId);
        if (session == null || session.player() != player
                || !authenticatedPlayers.remove(playerId, session)) return;
        sessionTokens.remove(playerId, session.token());
        // Submitted economy/progression operations own these latches until
        // their completion protocol resolves. Logout only revokes UI and live
        // pet state; it must not make a second debit or progression mutation
        // possible while the first transaction is still in flight.
        internalCommands.remove(playerId);
        captureNoticeAt.remove(playerId);
        // MyPet owns the persisted pet record. Sending the live entity away
        // removes its combat/aura footprint while the account is back in
        // AuthMe limbo; the next authenticated call/audit can restore it.
        activePet(player).ifPresent(pet -> {
            UUID petId = pet.getUUID();
            pet.sendAway();
            progressCache.remove(petId);
            abilityStates.remove(petId);
            combatBondCounters.remove(petId);
            appliedSkins.remove(petId);
        });
    }

    private void copyPackagedDefaults(String... paths) {
        if (getConfig().getDefaults() == null) return;
        for (String path : paths) {
            getConfig().set(path, getConfig().getDefaults().get(path));
        }
    }

    private void repairPackagedSectionIfMalformed(String path) {
        if (getConfig().isConfigurationSection(path) || getConfig().getDefaults() == null) return;
        var defaults = getConfig().getDefaults();
        var defaultSection = defaults.getConfigurationSection(path);
        if (defaultSection == null) {
            throw new IllegalStateException("Packaged pet configuration is missing section " + path);
        }
        getLogger().warning("Repairing malformed pet configuration section " + path + ".");
        getConfig().set(path, null);
        for (String child : defaultSection.getKeys(true)) {
            String sourcePath = path + "." + child;
            if (!defaults.isConfigurationSection(sourcePath)) {
                getConfig().set(sourcePath, defaults.get(sourcePath));
            }
        }
    }

    @Override
    public void onDisable() {
        authenticatedPlayers.clear();
        sessionTokens.clear();
        revivalPlayerQueries.clear();
        revivalWrites.clear();
        pendingProgress.clear();
        internalCommands.clear();
        captureNoticeAt.clear();
        progressCache.clear();
        abilityStates.clear();
        combatBondCounters.clear();
        appliedSkins.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is player-only. Operators can test MyPet commands in game.");
            return true;
        }
        if (args.length == 0) {
            openMenu(player);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "help", "guide" -> sendHelp(player);
            case "status", "info" -> showStatus(player);
            case "call", "summon" -> callPet(player);
            case "off", "dismiss", "sendaway" -> dismissPet(player);
            case "capture", "capturehelper" -> runEngine(player, "petcapturehelper");
            case "skill", "skills", "path" -> runEngine(player, "petchooseskilltree");
            case "inventory", "backpack" -> runEngine(player, "petinventory");
            case "storage", "switch" -> runEngine(player, "petswitch");
            case "store" -> runEngine(player, "petstore");
            case "behavior", "behaviour" -> setBehavior(player, args.length >= 2 ? args[1] : "friendly");
            case "name", "rename" -> renamePet(player, args);
            case "revive", "recover" -> revivePet(player);
            case "codex", "collection" -> openCodex(player, 0);
            case "skin", "skins", "appearance" -> openSkins(player);
            case "evolve", "evolution" -> evolvePet(player);
            case "signal", "whistle" -> giveSignal(player);
            default -> openMenu(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefix(List.of("help", "status", "call", "off", "capture", "skill", "inventory",
                    "storage", "store", "behavior", "name", "revive", "codex", "skins", "evolve", "signal"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("behavior")) {
            return prefix(List.of("friendly", "aggressive"), args[1]);
        }
        return List.of();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRawEngineCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (internalCommands.contains(player.getUniqueId()) || player.hasPermission("rpg.pets.admin")) return;
        String raw = event.getMessage().stripLeading();
        if (!raw.startsWith("/")) return;
        String root = raw.substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int namespace = root.indexOf(':');
        if (namespace >= 0) root = root.substring(namespace + 1);
        if (!engineRoots.contains(root)) return;
        event.setCancelled(true);
        player.sendMessage(messages.render(player, "engine-redirect"));
        getServer().getScheduler().runTask(this, () -> openMenu(player));
    }

    /** Prevent MyPet's attack-with-lead capture pathway outside the survival overworld. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCaptureAttempt(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (player.getInventory().getItemInMainHand().getType() != Material.LEAD) return;
        if (policy.canCapture(player.getWorld().getName())) return;
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        if (captureNoticeAt.getOrDefault(player.getUniqueId(), 0L) <= now) {
            player.sendMessage(messages.render(player, "capture-blocked"));
            captureNoticeAt.put(player.getUniqueId(), now + 3000L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPetDamage(PetDamageEvent event) {
        Player owner = event.getPlayer();
        if (owner == null || event.getTarget() instanceof Player
                || hostileTargetsOnly && !(event.getTarget() instanceof Enemy)
                || policy.petsBlocked(event.getTarget().getWorld().getName())) {
            event.setCancelled(true);
            return;
        }
        double bounded = boundedDamage(event.getPet(), event.getTarget(), event.getDamage());
        if (bounded <= 0.0) {
            event.setCancelled(true);
        } else {
            event.setDamage(bounded);
        }
    }

    /** Covers ranged projectiles and any engine attack path that reaches Bukkit directly. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPetBukkitDamage(EntityDamageByEntityEvent event) {
        Pet pet = petFromDamager(event.getDamager());
        if (pet == null) return;
        if (event.getEntity() instanceof Player
                || hostileTargetsOnly && !(event.getEntity() instanceof Enemy)
                || policy.petsBlocked(event.getEntity().getWorld().getName())) {
            event.setCancelled(true);
            return;
        }
        double bounded = boundedDamage(pet, event.getEntity(), event.getDamage());
        if (bounded <= 0.0) {
            event.setCancelled(true);
        } else {
            event.setDamage(bounded);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPetPickup(PetPickupItemEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPetDrop(EntityDropItemEvent event) {
        if (petFromEntity(event.getEntity()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPetDeath(EntityDeathEvent event) {
        if (petFromEntity(event.getEntity()) != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            return;
        }
        // Bleed or another delayed engine effect may bypass the direct
        // non-lethal clamp. Never let such a finishing blow become an AFK
        // material source; the player must land the final hit for loot.
        DamageSource source = event.getDamageSource();
        Pet killer = petFromDamager(source.getCausingEntity());
        if (killer == null) killer = petFromDamager(source.getDirectEntity());
        if (killer != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            return;
        }
        Player player = event.getEntity().getKiller();
        if (player != null && event.getEntity() instanceof Enemy) {
            activePet(player).ifPresent(pet -> recordCombatBond(player, pet, event.getEntity().getUniqueId()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPetExperience(PetExpEvent event) {
        Player owner = event.getPlayer();
        if (owner == null || policy.petsBlocked(owner.getWorld().getName())) {
            event.setCancelled(true);
            return;
        }
        if (event.getExp() > maxExpPerEvent) event.setExp(maxExpPerEvent);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPetCreated(PetCreateEvent event) {
        Player player = event.getPlayer();
        PlayerSession playerSession = player == null ? null : session(player);
        StoredPet pet = event.getPet();
        UUID ownerId = event.getOwner().getUniqueId();
        registerProgress(ownerId, pet, progress -> {
            Player currentPlayer = playerSession == null ? null : authenticatedPlayer(playerSession);
            if (currentPlayer == null) return;
            if (event.getSource() == PetCreateEvent.Source.LEASH) giveSignal(currentPlayer);
            currentPlayer.sendMessage(messages.render(currentPlayer, "capture-created",
                    progressValues(currentPlayer, progress)));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPetActivated(PetActivatedEvent event) {
        Pet pet = event.getPet();
        recoverRevivals(pet.getOwner().getUniqueId(), false);
        registerProgress(pet.getOwner().getUniqueId(), pet, progress -> {
            if (!isEnabled()) return;
            UUID playerId = pet.getOwner().getUniqueId();
            UUID petId = pet.getUUID();
            getServer().getScheduler().runTaskLater(this, () -> {
                if (!isEnabled()) return;
                Pet current = activePetByIdentity(playerId, petId);
                if (current != null) applySkinIfNeeded(current, progress.selectedSkinId());
            }, 3L);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPetStatus(PetStatusEvent event) {
        if (event.getState() == Pet.PetState.Dead || event.getState() == Pet.PetState.Despawned) {
            recoverRevivals(event.getOwner().getUniqueId(), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPetFed(PetFeedEvent event) {
        if (event.getResult() == PetFeedEvent.Result.SELF_FEED) return;
        Pet pet = event.getPet();
        Player player = pet.getOwner().getPlayer();
        if (player == null) return;
        long bucket = System.currentTimeMillis() / 300_000L;
        rewardBond(player, pet, 5, "feeding", "pet-feed:" + pet.getUUID() + ":" + bucket, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPetLevel(PetLevelUpEvent event) {
        Player player = event.getPet().getOwner().getPlayer();
        if (player == null) return;
        if (MILESTONE_LEVELS.contains(event.getLevel())) {
            rewardBond(player, event.getPet(), 15, "milestone",
                    "pet-level:" + event.getPet().getUUID() + ":" + event.getLevel(), false);
        }
        if (!MILESTONE_LEVELS.contains(event.getLevel())) return;
        player.sendMessage(messages.render(player, "level-up", Map.of(
                "name", petName(event.getPet()), "level", event.getLevel())));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignalUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK
                || !isSignal(event.getItem())) return;
        event.setCancelled(true);
        useAbility(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null || !policy.petsBlocked(event.getTo().getWorld().getName())) return;
        activePet(event.getPlayer()).ifPresent(Pet::sendAway);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PetMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner().equals(player.getUniqueId())) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        if (holder.view() == PetMenuHolder.View.CODEX) {
            if (event.getRawSlot() == 45 && holder.page() > 0) openCodex(player, holder.page() - 1);
            else if (event.getRawSlot() == 49) openMenu(player);
            else if (event.getRawSlot() == 53) openCodex(player, holder.page() + 1);
            return;
        }
        if (holder.view() == PetMenuHolder.View.SKINS) {
            switch (event.getRawSlot()) {
                case 11 -> selectSkin(player, "natural");
                case 13 -> selectSkin(player, "resonant");
                case 15 -> selectSkin(player, "ascendant");
                case 22 -> openMenu(player);
                default -> { }
            }
            return;
        }
        switch (event.getRawSlot()) {
            case 10 -> sendHelp(player);
            case 12 -> showStatus(player);
            case 14 -> openCodex(player, 0);
            case 16 -> runEngine(player, "petcapturehelper");
            case 19 -> callPet(player);
            case 20 -> dismissPet(player);
            case 21 -> setBehavior(player, isRightClick(event.getClick()) ? "aggressive" : "friendly");
            case 22 -> evolvePet(player);
            case 23 -> runEngine(player, "petchooseskilltree");
            case 24 -> runEngine(player, "petinventory");
            case 25 -> runEngine(player, isRightClick(event.getClick()) ? "petswitch" :
                    activePet(player).isPresent() ? "petstore" : "petswitch");
            case 29 -> {
                player.closeInventory();
                player.sendMessage(messages.render(player, "rename-usage"));
            }
            case 31 -> giveSignal(player);
            case 33 -> openSkins(player);
            case 40 -> revivePet(player);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PetMenuHolder) event.setCancelled(true);
    }

    private void openMenu(Player player) {
        PetMenuHolder holder = new PetMenuHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, menuSize,
                Component.text(messages.render(player, "menu-title"), NamedTextColor.DARK_AQUA));
        holder.inventory(inventory);
        ItemStack filler = item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < menuSize; slot++) inventory.setItem(slot, filler);

        Optional<Pet> active = activePet(player);
        Map<String, Object> status = active.map(pet -> statusValues(player, pet)).orElse(Map.of());
        inventory.setItem(10, menuItem(player, Material.WRITABLE_BOOK, "guide", Map.of()));
        inventory.setItem(12, menuItem(player, Material.NAME_TAG, "status", status,
                active.isPresent() ? "lore-active" : "lore-none"));
        inventory.setItem(14, menuItem(player, Material.KNOWLEDGE_BOOK, "codex", Map.of()));
        inventory.setItem(16, menuItem(player, Material.LEAD, "capture", Map.of()));
        inventory.setItem(19, menuItem(player, Material.ECHO_SHARD, "call", Map.of()));
        inventory.setItem(20, menuItem(player, Material.ENDER_PEARL, "dismiss", Map.of()));
        inventory.setItem(21, menuItem(player, Material.WOLF_SPAWN_EGG, "behavior", Map.of()));
        inventory.setItem(22, menuItem(player, Material.AMETHYST_CLUSTER, "evolve", status));
        inventory.setItem(23, menuItem(player, Material.ENCHANTED_BOOK, "skill", Map.of()));
        inventory.setItem(24, menuItem(player, Material.BUNDLE, "inventory", Map.of()));
        inventory.setItem(25, menuItem(player, Material.CHEST, "storage", Map.of()));
        inventory.setItem(29, menuItem(player, Material.OAK_SIGN, "rename", Map.of()));
        inventory.setItem(31, menuItem(player, Material.GOAT_HORN, "signal", status));
        inventory.setItem(33, menuItem(player, Material.ARMOR_STAND, "skins", status));
        BigDecimal cost = active.map(this::reviveCost).orElse(reviveBase);
        inventory.setItem(40, menuItem(player, Material.TOTEM_OF_UNDYING, "revive",
                Map.of("cost", money(cost))));
        player.openInventory(inventory);
    }

    private ItemStack menuItem(Player player, Material material, String key, Map<String, ?> values) {
        return menuItem(player, material, key, values, "lore");
    }

    private ItemStack menuItem(Player player, Material material, String key,
                               Map<String, ?> values, String loreKey) {
        return item(material, messages.render(player, "menu." + key + ".name", values),
                messages.renderLines(player, "menu." + key + "." + loreKey, values));
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        TextComponent title = Component.text(name, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false);
        meta.displayName(title);
        meta.lore(lore.stream().map(line -> Component.text(line, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private void sendHelp(Player player) {
        player.closeInventory();
        messages.renderLines(player, "help").forEach(player::sendMessage);
    }

    private void showStatus(Player player) {
        player.closeInventory();
        Optional<Pet> active = activePet(player);
        if (active.isEmpty()) {
            player.sendMessage(messages.render(player, "status-none"));
            return;
        }
        player.sendMessage(messages.render(player, "status-active", statusValues(player, active.get())));
    }

    private void callPet(Player player) {
        player.closeInventory();
        PetPolicy.Denial denial = policy.summonDenial(
                combat.inCombat(player.getUniqueId()), player.getWorld().getName());
        if (denial == PetPolicy.Denial.COMBAT) {
            player.sendMessage(messages.render(player, "combat-blocked",
                    Map.of("seconds", combat.remainingSeconds(player.getUniqueId()))));
            return;
        }
        if (denial == PetPolicy.Denial.DUNGEON) {
            player.sendMessage(messages.render(player, "dungeon-blocked"));
            return;
        }
        if (denial == PetPolicy.Denial.WORLD) {
            player.sendMessage(messages.render(player, "world-blocked"));
            return;
        }
        Optional<Pet> active = activePet(player);
        if (active.isEmpty()) {
            player.sendMessage(messages.render(player, "no-pet"));
            return;
        }
        Pet pet = active.get();
        Pet.SpawnFlags result = pet.callToOwner();
        if (result == Pet.SpawnFlags.Success || result == Pet.SpawnFlags.AlreadyHere) {
            player.sendMessage(messages.render(player, "call-success", Map.of("name", petName(pet))));
        } else {
            player.sendMessage(messages.render(player, "call-failed", Map.of("reason", result.name())));
        }
    }

    private void dismissPet(Player player) {
        player.closeInventory();
        Optional<Pet> active = activePet(player);
        if (active.isEmpty()) {
            player.sendMessage(messages.render(player, "no-pet"));
            return;
        }
        Pet pet = active.get();
        String name = petName(pet);
        pet.sendAway();
        player.sendMessage(messages.render(player, "sent-away", Map.of("name", name)));
    }

    private void setBehavior(Player player, String requested) {
        String behavior = PetBehaviorRules.publicMode(requested);
        String engineBehavior = PetBehaviorRules.engineMode(behavior);
        player.closeInventory();
        Optional<Pet> active = activePet(player);
        if (active.isEmpty()) {
            player.sendMessage(messages.render(player, "no-pet"));
            return;
        }
        if (active.get().getSkilltree() == null) {
            player.sendMessage(messages.render(player, "behavior-path-required"));
            return;
        }
        runEngine(player, "petbehavior " + engineBehavior);
        player.sendMessage(messages.render(player, "behavior-" + behavior));
    }

    private void renamePet(Player player, String[] args) {
        player.closeInventory();
        Optional<Pet> active = activePet(player);
        if (active.isEmpty()) {
            player.sendMessage(messages.render(player, "no-pet"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(messages.render(player, "rename-usage"));
            return;
        }
        String raw = String.join(" ", List.of(args).subList(1, args.length));
        String safe = raw.replaceAll("<[^>]*>|§.", "")
                .replaceAll("[^\\p{L}\\p{N}_· \\-]", "").strip();
        if (safe.isEmpty() || safe.codePointCount(0, safe.length()) > 16) {
            player.sendMessage(messages.render(player, "rename-invalid"));
            return;
        }
        Pet pet = active.get();
        pet.setPetName(safe);
        pet.updateNameTag();
        player.sendMessage(messages.render(player, "renamed", Map.of("name", safe)));
    }

    private void revivePet(Player player) {
        player.closeInventory();
        Optional<Pet> active = activePet(player);
        if (active.isEmpty() || active.get().getStatus() != Pet.PetState.Dead) {
            player.sendMessage(messages.render(player, "revive-not-dead"));
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!revivalPlayerQueries.add(playerId)) {
            player.sendMessage(messages.render(player, "revive-pending"));
            return;
        }
        Pet pet = active.get();
        UUID petId = pet.getUUID();
        BigDecimal cost = reviveCost(pet);
        UUID operationId = UUID.randomUUID();
        String debitKey = "pet-revive:" + operationId;
        petRevivals.begin(new PetRevivalRequest(operationId, playerId, petId, cost, debitKey))
                .whenComplete((result, error) -> runMain(() ->
                        finishReviveRequest(playerId, petId, cost, result, error)));
    }

    private void finishReviveRequest(UUID playerId, UUID petId, BigDecimal cost,
                                     PetRevivalResult result, Throwable error) {
        revivalPlayerQueries.remove(playerId);
        Player currentPlayer = authenticatedPlayer(playerId);
        if (error != null || result == null) {
            if (currentPlayer != null) currentPlayer.sendMessage(messages.render(currentPlayer, "revive-saved"));
            recoverRevivals(playerId, false);
            return;
        }
        if (result.status() == PetRevivalResult.Status.INSUFFICIENT_FUNDS) {
            if (currentPlayer != null) currentPlayer.sendMessage(messages.render(currentPlayer, "revive-insufficient",
                    Map.of("cost", money(cost))));
            return;
        }
        if (result.status() == PetRevivalResult.Status.BALANCE_LIMIT
                || result.status() == PetRevivalResult.Status.COMPENSATED) {
            if (currentPlayer != null) currentPlayer.sendMessage(messages.render(currentPlayer, "revive-support", Map.of(
                    "operation", result.intent().map(intent -> intent.operationId().toString()).orElse("—"))));
            return;
        }
        if (result.status() == PetRevivalResult.Status.QUARANTINED
                || result.status() == PetRevivalResult.Status.CONFLICT
                || result.status() == PetRevivalResult.Status.INVALID
                || result.status() == PetRevivalResult.Status.NOT_FOUND) {
            if (currentPlayer != null) currentPlayer.sendMessage(messages.render(currentPlayer, "revive-support", Map.of(
                    "operation", result.intent().map(intent -> intent.operationId().toString()).orElse("—"))));
            return;
        }
        if (result.status() == PetRevivalResult.Status.COMPLETED) {
            if (currentPlayer != null) currentPlayer.sendMessage(messages.render(currentPlayer, "revived", Map.of(
                    "name", petNameOrFallback(currentPlayer, petId), "cost", money(cost))));
            return;
        }
        PetRevivalIntent intent = result.intent().orElse(null);
        if (intent == null) {
            if (currentPlayer != null) currentPlayer.sendMessage(messages.render(currentPlayer, "revive-saved"));
            recoverRevivals(playerId, false);
            return;
        }
        if (currentPlayer != null) currentPlayer.sendMessage(messages.render(currentPlayer, "revive-saved"));
        persistRevival(intent, true);
    }

    private void recoverRevivals(UUID playerId, boolean announce) {
        if (!isEnabled() || !revivalPlayerQueries.add(playerId)) return;
        petRevivals.pending(playerId).whenComplete((intents, error) -> runMain(() -> {
            revivalPlayerQueries.remove(playerId);
            if (error != null || intents == null) {
                getLogger().warning("Could not load pending pet revivals for " + playerId + ": "
                        + (error == null ? "missing result" : error.getMessage()));
                return;
            }
            if (announce && !intents.isEmpty()) {
                Player player = authenticatedPlayer(playerId);
                if (player != null) player.sendMessage(messages.render(player, "revive-resuming"));
            }
            for (PetRevivalIntent intent : intents) persistRevival(intent, false);
        }));
    }

    private void persistRevival(PetRevivalIntent intent, boolean announceCompletion) {
        if (!isEnabled() || !revivalWrites.add(intent.operationId())) return;
        Pet live = activePetByIdentity(intent.playerId(), intent.petId());
        if (live != null) {
            PersistedPet snapshot = MyPetApi.getPetManager().snapshot(live)
                    .withRespawnTime(0).withHealth(Math.max(1.0, live.getMaxHealth()));
            persistMyPetSnapshot(intent, snapshot, announceCompletion);
            return;
        }
        MyPetPlugin.getInstance().getRepository().getPet(intent.petId())
                .whenComplete((stored, loadError) -> {
                    if (!isEnabled()) {
                        revivalWrites.remove(intent.operationId());
                        return;
                    }
                    if (loadError != null) {
                        revivalWrites.remove(intent.operationId());
                        getLogger().warning("MyPet revival read failed for operation " + intent.operationId()
                                + ": " + loadError.getMessage());
                        return;
                    }
                    if (stored == null) {
                        resolveMissingPet(intent, announceCompletion);
                        return;
                    }
                    if (!stored.getOwner().getUniqueId().equals(intent.playerId())) {
                        quarantineRevival(intent, "owner_mismatch");
                        return;
                    }
                    if (!(stored instanceof PersistedPet persisted)) {
                        quarantineRevival(intent, "unsupported_pet_snapshot");
                        return;
                    }
                    PersistedPet updated = persisted.withRespawnTime(0)
                            .withHealth(maximumPersistedHealth(stored));
                    persistMyPetSnapshot(intent, updated, announceCompletion);
                });
    }

    private void persistMyPetSnapshot(
            PetRevivalIntent intent,
            PersistedPet snapshot,
            boolean announceCompletion
    ) {
        MyPetPlugin.getInstance().getRepository().updatePet(snapshot)
                .whenComplete((saved, saveError) -> {
                    if (!isEnabled()) {
                        revivalWrites.remove(intent.operationId());
                        return;
                    }
                    if (saveError != null || !Boolean.TRUE.equals(saved)) {
                        revivalWrites.remove(intent.operationId());
                        getLogger().warning("MyPet revival write failed for operation "
                                + intent.operationId() + ": "
                                + (saveError == null ? "repository rejected update" : saveError.getMessage()));
                        return;
                    }
                    runMain(() -> {
                        Pet current = activePetByIdentity(intent.playerId(), intent.petId());
                        if (current != null && current.getStatus() == Pet.PetState.Dead) {
                            current.setRespawnTime(0);
                            current.tickRespawnTimer();
                        }
                        completeRevival(intent, current, announceCompletion);
                    });
                });
    }

    private void resolveMissingPet(PetRevivalIntent intent, boolean announceCompletion) {
        MyPetPlugin.getInstance().getRepository().getMyPetPlayer(intent.playerId())
                .thenCompose(owner -> owner == null
                        ? java.util.concurrent.CompletableFuture.completedFuture(null)
                        : MyPetPlugin.getInstance().getRepository().getPets(owner))
                .whenComplete((pets, error) -> {
                    if (!isEnabled()) {
                        revivalWrites.remove(intent.operationId());
                        return;
                    }
                    if (error != null) {
                        revivalWrites.remove(intent.operationId());
                        getLogger().warning("MyPet ownership audit failed for operation " + intent.operationId()
                                + ": " + error.getMessage());
                        return;
                    }
                    if (pets == null) {
                        quarantineRevival(intent, "owner_not_found");
                        return;
                    }
                    StoredPet matching = pets.stream()
                            .filter(candidate -> candidate.getUUID().equals(intent.petId())).findFirst().orElse(null);
                    if (matching == null) {
                        quarantineRevival(intent, "pet_not_found");
                        return;
                    }
                    revivalWrites.remove(intent.operationId());
                    persistRevival(intent, announceCompletion);
                });
    }

    private void completeRevival(PetRevivalIntent intent, Pet pet, boolean announce) {
        petRevivals.complete(intent.operationId(), intent.playerId(), intent.petId())
                .whenComplete((result, error) -> runMain(() -> {
                    revivalWrites.remove(intent.operationId());
                    if (error != null || result == null
                            || result.status() != PetRevivalResult.Status.COMPLETED) {
                        getLogger().warning("Could not confirm pet revival operation " + intent.operationId()
                                + ": " + (error == null ? String.valueOf(result) : error.getMessage()));
                        return;
                    }
                    if (!announce) return;
                    Player player = authenticatedPlayer(intent.playerId());
                    if (player != null) player.sendMessage(messages.render(player, "revived", Map.of(
                            "name", pet == null ? petNameOrFallback(player, intent.petId()) : petName(pet),
                            "cost", money(intent.goldCost()))));
                }));
    }

    private void quarantineRevival(PetRevivalIntent intent, String reason) {
        petRevivals.quarantine(intent.operationId(), intent.playerId(), intent.petId(), reason)
                .whenComplete((result, error) -> runMain(() -> {
                    revivalWrites.remove(intent.operationId());
                    getLogger().severe("Pet revival operation " + intent.operationId()
                            + " requires administrator review: " + reason
                            + (error == null ? "" : " (" + error.getMessage() + ")"));
                    Player player = authenticatedPlayer(intent.playerId());
                    if (player != null) player.sendMessage(messages.render(player, "revive-support", Map.of(
                            "operation", intent.operationId().toString())));
                }));
    }

    private Pet activePetByIdentity(UUID playerId, UUID petId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return null;
        Pet pet = activePet(player).orElse(null);
        return pet != null && pet.getUUID().equals(petId) ? pet : null;
    }

    private Player authenticatedPlayer(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return authenticated(player) ? player : null;
    }

    private Player authenticatedPlayer(PlayerSession session) {
        Player player = authenticatedPlayer(session.playerId());
        return player != null && session.token().equals(sessionTokens.get(session.playerId())) ? player : null;
    }

    private PlayerSession session(Player player) {
        UUID playerId = player.getUniqueId();
        AuthenticatedSession active = authenticatedPlayers.get(playerId);
        UUID token = active != null && active.player() == player
                && Bukkit.getPlayer(playerId) == player ? active.token() : null;
        return new PlayerSession(playerId, token == null ? new UUID(0L, 0L) : token);
    }

    private String petNameOrFallback(Player player, UUID petId) {
        Pet pet = activePetByIdentity(player.getUniqueId(), petId);
        return pet == null ? messages.render(player, "revive-pet") : petName(pet);
    }

    private void runMain(Runnable action) {
        if (!isEnabled()) return;
        try {
            getServer().getScheduler().runTask(this, () -> {
                if (isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // Durable repository operations are recovered after the next enable.
        }
    }

    private boolean authenticated(Player player) {
        AuthenticatedSession session = player == null ? null : authenticatedPlayers.get(player.getUniqueId());
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player
                && session != null && session.player() == player
                && session.token().equals(sessionTokens.get(player.getUniqueId()));
    }

    private void registerProgress(UUID ownerId, StoredPet pet, Consumer<PetProgress> onReady) {
        PetProgress cached = progressCache.get(pet.getUUID());
        if (cached != null && cached.ownerId().equals(ownerId)) {
            onReady.accept(cached);
            return;
        }
        PetProgressRules.Identity identity = PetProgressRules.identity(pet.getUUID());
        petProgress.registerCapture(ownerId, pet.getUUID(), pet.getPetType().name().toLowerCase(Locale.ROOT),
                        identity.personalityId(), identity.traitId())
                .whenComplete((progress, error) -> runMain(() -> {
                    if (error != null || progress == null) {
                        getLogger().warning("Could not load pet progression for " + pet.getUUID() + ": "
                                + (error == null ? "missing result" : error.getMessage()));
                        return;
                    }
                    progressCache.put(progress.petId(), progress);
                    onReady.accept(progress);
                }));
    }

    private void rewardBond(Player player, Pet pet, int baseAmount, String source,
                            String idempotencyKey, boolean announce) {
        if (!authenticated(player)) return;
        PlayerSession playerSession = session(player);
        PetProgress cached = progressCache.get(pet.getUUID());
        if (cached == null) {
            registerProgress(player.getUniqueId(), pet,
                    progress -> rewardBond(player, pet, baseAmount, source, idempotencyKey, announce));
            return;
        }
        int amount = PetProgressRules.adjustedBond(cached.personalityId(), baseAmount);
        petProgress.rewardBond(player.getUniqueId(), pet.getUUID(), amount, source, idempotencyKey)
                .whenComplete((result, error) -> runMain(() -> {
                    if (error != null || result == null) {
                        getLogger().warning("Pet bond settlement failed for " + pet.getUUID() + ": "
                                + (error == null ? "missing result" : error.getMessage()));
                        return;
                    }
                    result.progress().ifPresent(progress -> progressCache.put(progress.petId(), progress));
                    Player currentPlayer = authenticatedPlayer(playerSession);
                    if (announce && result.status() == PetProgressResult.Status.SUCCESS && currentPlayer != null) {
                        PetProgress progress = result.progress().orElse(cached);
                        currentPlayer.sendMessage(messages.render(currentPlayer, "bond-gained", Map.of(
                                "amount", amount, "bond", progress.bond())));
                    }
                }));
    }

    private void recordCombatBond(Player player, Pet pet, UUID killedEntityId) {
        int count = combatBondCounters.computeIfAbsent(pet.getUUID(), ignored -> new AtomicInteger()).incrementAndGet();
        if (count < 5) return;
        combatBondCounters.get(pet.getUUID()).addAndGet(-5);
        rewardBond(player, pet, 2, "combat",
                "pet-combat:" + pet.getUUID() + ":" + killedEntityId, false);
    }

    private void evolvePet(Player player) {
        PlayerSession playerSession = session(player);
        Pet pet = activePet(player).orElse(null);
        if (pet == null) {
            player.closeInventory();
            player.sendMessage(messages.render(player, "no-pet"));
            return;
        }
        PetProgress progress = progressCache.get(pet.getUUID());
        if (progress == null) {
            player.closeInventory();
            player.sendMessage(messages.render(player, "not-ready"));
            registerProgress(player.getUniqueId(), pet, ignored -> openMenu(player));
            return;
        }
        int stage = progress.evolutionStage();
        if (stage >= 2) {
            player.closeInventory();
            player.sendMessage(messages.render(player, "evolution-complete"));
            return;
        }
        int level = PetProgressRules.minimumLevelForNextStage(stage);
        int bond = PetProgressRules.minimumBondForNextStage(stage);
        if (pet.getLevel() < level || progress.bond() < bond) {
            player.closeInventory();
            player.sendMessage(messages.render(player, "evolution-locked", Map.of(
                    "level", level, "current_level", pet.getLevel(),
                    "bond", bond, "current_bond", progress.bond())));
            return;
        }
        player.closeInventory();
        petProgress.evolve(player.getUniqueId(), pet.getUUID(), stage, bond,
                        PetProgressRules.skinForNextStage(stage))
                .whenComplete((result, error) -> runMain(() -> {
                    Player currentPlayer = authenticatedPlayer(playerSession);
                    if (currentPlayer == null) return;
                    if (error != null || result == null || result.status() != PetProgressResult.Status.SUCCESS) {
                        currentPlayer.sendMessage(messages.render(currentPlayer, "evolution-failed"));
                        return;
                    }
                    Pet currentPet = activePetByIdentity(currentPlayer.getUniqueId(), pet.getUUID());
                    if (currentPet == null) return;
                    PetProgress updated = result.progress().orElseThrow();
                    progressCache.put(updated.petId(), updated);
                    PetSkinRenderer.apply(currentPet, PetProgressRules.skinForNextStage(stage), true);
                    currentPlayer.playSound(currentPlayer.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.75f);
                    currentPlayer.sendMessage(messages.render(currentPlayer, "evolution-success", Map.of(
                            "stage", updated.evolutionStage(),
                            "skin", messages.render(currentPlayer, "skin." + PetProgressRules.skinForNextStage(stage)))));
                }));
    }

    private void openCodex(Player player, int requestedPage) {
        PlayerSession playerSession = session(player);
        petProgress.collection(player.getUniqueId()).whenComplete((entries, error) ->
                runMain(() -> {
                    Player currentPlayer = authenticatedPlayer(playerSession);
                    if (currentPlayer == null) return;
                    if (error != null || entries == null) {
                        currentPlayer.sendMessage(messages.render(currentPlayer, "not-ready"));
                        return;
                    }
                    int pageCount = Math.max(1, (entries.size() + 44) / 45);
                    int page = Math.clamp(requestedPage, 0, pageCount - 1);
                    PetMenuHolder holder = new PetMenuHolder(player.getUniqueId(), PetMenuHolder.View.CODEX, page);
                    Inventory inventory = Bukkit.createInventory(holder, 54,
                            Component.text(messages.render(currentPlayer, "codex-title", Map.of(
                                    "page", page + 1, "pages", pageCount)), NamedTextColor.DARK_AQUA));
                    holder.inventory(inventory);
                    int start = page * 45;
                    for (int index = start; index < Math.min(entries.size(), start + 45); index++) {
                        PetCollectionEntry entry = entries.get(index);
                        Material material = Material.matchMaterial(entry.speciesId().toUpperCase(Locale.ROOT) + "_SPAWN_EGG");
                        if (material == null) material = Material.PAPER;
                        inventory.setItem(index - start, item(material,
                                messages.render(currentPlayer, "codex-entry-name", Map.of("species", speciesName(entry.speciesId()))),
                                messages.renderLines(currentPlayer, "codex-entry-lore", Map.of(
                                        "captures", entry.captureCount(), "stage", entry.highestEvolutionStage()))));
                    }
                    ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
                    for (int slot = 45; slot < 54; slot++) inventory.setItem(slot, filler);
                    if (page > 0) inventory.setItem(45, menuItem(currentPlayer, Material.ARROW, "previous", Map.of()));
                    inventory.setItem(49, menuItem(currentPlayer, Material.BARRIER, "back", Map.of()));
                    if (page + 1 < pageCount) inventory.setItem(53, menuItem(currentPlayer, Material.ARROW, "next", Map.of()));
                    currentPlayer.openInventory(inventory);
                }));
    }

    private void openSkins(Player player) {
        Pet pet = activePet(player).orElse(null);
        if (pet == null) {
            player.sendMessage(messages.render(player, "no-pet"));
            return;
        }
        PetProgress progress = progressCache.get(pet.getUUID());
        if (progress == null) {
            player.sendMessage(messages.render(player, "not-ready"));
            registerProgress(player.getUniqueId(), pet, ignored -> openSkins(player));
            return;
        }
        PetMenuHolder holder = new PetMenuHolder(player.getUniqueId(), PetMenuHolder.View.SKINS, 0);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Component.text(messages.render(player, "skins-title"), NamedTextColor.DARK_AQUA));
        holder.inventory(inventory);
        ItemStack filler = item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < 27; slot++) inventory.setItem(slot, filler);
        inventory.setItem(11, skinItem(player, progress, "natural", Material.GRASS_BLOCK));
        inventory.setItem(13, skinItem(player, progress, "resonant", Material.PRISMARINE_CRYSTALS));
        inventory.setItem(15, skinItem(player, progress, "ascendant", Material.AMETHYST_SHARD));
        inventory.setItem(22, menuItem(player, Material.BARRIER, "back", Map.of()));
        player.openInventory(inventory);
    }

    private ItemStack skinItem(Player player, PetProgress progress, String skinId, Material material) {
        boolean unlocked = progress.unlockedSkinIds().contains(skinId);
        boolean selected = progress.selectedSkinId().equals(skinId);
        return item(unlocked ? material : Material.GRAY_DYE,
                messages.render(player, "skin." + skinId),
                messages.renderLines(player, unlocked ? "skin-unlocked-lore" : "skin-locked-lore",
                        Map.of("selected", selected ? messages.render(player, "selected") : "")));
    }

    private void selectSkin(Player player, String skinId) {
        PlayerSession playerSession = session(player);
        Pet pet = activePet(player).orElse(null);
        if (pet == null) return;
        PetProgress current = progressCache.get(pet.getUUID());
        if (current == null || !current.unlockedSkinIds().contains(skinId)) {
            player.sendMessage(messages.render(player, "skin-locked"));
            return;
        }
        petProgress.selectSkin(player.getUniqueId(), pet.getUUID(), skinId)
                .whenComplete((result, error) -> runMain(() -> {
                    Player currentPlayer = authenticatedPlayer(playerSession);
                    if (currentPlayer == null) return;
                    if (error != null || result == null || result.status() != PetProgressResult.Status.SUCCESS) {
                        currentPlayer.sendMessage(messages.render(currentPlayer, "skin-failed"));
                        return;
                    }
                    Pet currentPet = activePetByIdentity(currentPlayer.getUniqueId(), pet.getUUID());
                    if (currentPet == null) return;
                    PetProgress updated = result.progress().orElseThrow();
                    progressCache.put(updated.petId(), updated);
                    PetSkinRenderer.apply(currentPet, skinId, true);
                    currentPlayer.sendMessage(messages.render(currentPlayer, "skin-selected", Map.of(
                            "skin", messages.render(currentPlayer, "skin." + skinId))));
                    openSkins(currentPlayer);
                }));
    }

    private void giveSignal(Player player) {
        if (hasSignal(player)) {
            player.closeInventory();
            player.sendMessage(messages.render(player, "signal-already"));
            return;
        }
        ItemStack signal = new ItemStack(Material.GOAT_HORN);
        ItemMeta meta = signal.getItemMeta();
        meta.displayName(Component.text(messages.render(player, "signal-name"), NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(messages.renderLines(player, "signal-lore").stream()
                .map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        meta.getPersistentDataContainer().set(signalKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(
                signalOwnerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        meta.setEnchantmentGlintOverride(true);
        signal.setItemMeta(meta);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(signal);
        overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        player.closeInventory();
        player.sendMessage(messages.render(player, "signal-given"));
    }

    private boolean hasSignal(Player player) {
        if (isSignal(player.getInventory().getItemInOffHand())) return true;
        for (ItemStack item : player.getInventory().getStorageContents()) if (isSignal(item)) return true;
        return false;
    }

    private boolean isSignal(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(signalKey, PersistentDataType.BYTE);
    }

    private void useAbility(Player player) {
        Pet pet = activePet(player).orElse(null);
        if (pet == null || pet.getBukkitEntity() == null) {
            player.sendActionBar(Component.text(messages.render(player, "ability-no-pet"), NamedTextColor.RED));
            return;
        }
        if (policy.petsBlocked(player.getWorld().getName())) {
            player.sendActionBar(Component.text(messages.render(player, "world-blocked"), NamedTextColor.RED));
            return;
        }
        String route = PetProgressRules.route(pet.getSkilltree() == null ? null : pet.getSkilltree().getName());
        if (route.equals("none") || pet.getLevel() < 8) {
            player.sendActionBar(Component.text(messages.render(player, "ability-locked"), NamedTextColor.RED));
            return;
        }
        PetProgress progress = progressCache.get(pet.getUUID());
        PetAbilityState state = abilityStates.computeIfAbsent(pet.getUUID(), ignored -> new PetAbilityState());
        long now = System.currentTimeMillis();
        double recharge = progress != null && progress.personalityId().equals("calm") ? 4.0 : 3.0;
        state.recharge(now, recharge);
        double cost = route.equals("healer") ? 45.0 : 40.0;
        long cooldown = route.equals("guardian") ? 18_000L : route.equals("healer") ? 20_000L : 14_000L;
        if (progress != null && progress.personalityId().equals("curious")) cost -= 5.0;
        if (progress != null && progress.personalityId().equals("brave")) cooldown = cooldown * 9L / 10L;
        if (progress != null && progress.traitId().equals("quickstep")) cooldown = cooldown * 9L / 10L;
        if (!state.canUse(now, cost)) {
            player.sendActionBar(Component.text(messages.render(player, "ability-wait", Map.of(
                    "seconds", state.remainingSeconds(now), "energy", state.energy())), NamedTextColor.RED));
            return;
        }
        if (!executeAbility(player, pet, route, progress)) return;
        state.consume(now, cost, cooldown);
        player.sendActionBar(Component.text(messages.render(player, "ability-used", Map.of(
                "ability", messages.render(player, "ability." + route), "energy", state.energy())),
                NamedTextColor.AQUA));
    }

    private boolean executeAbility(Player player, Pet pet, String route, PetProgress progress) {
        return switch (route) {
            case "guardian" -> guardianAbility(player, pet, progress);
            case "hunter" -> hunterAbility(player, pet, progress);
            case "healer" -> healerAbility(player, pet, progress);
            case "ranger" -> rangerAbility(player, pet, progress);
            default -> false;
        };
    }

    private boolean guardianAbility(Player player, Pet pet, PetProgress progress) {
        PlayerSession playerSession = session(player);
        double granted = 4.0 + (progress == null ? 0.0 : progress.evolutionStage() * 0.5)
                + (progress != null && progress.traitId().equals("ironhide") ? 1.0 : 0.0);
        double before = player.getAbsorptionAmount();
        player.setAbsorptionAmount(Math.min(8.0, before + granted));
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), 4, 3, 4,
                candidate -> candidate instanceof Enemy)) {
            Vector away = entity.getLocation().toVector().subtract(player.getLocation().toVector());
            if (away.lengthSquared() > 0.01) entity.setVelocity(away.normalize().multiply(0.65).setY(0.28));
        }
        ring(player, Color.fromRGB(94, 190, 255));
        player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.75f);
        getServer().getScheduler().runTaskLater(this, () -> {
            Player currentPlayer = authenticatedPlayer(playerSession);
            if (currentPlayer != null) currentPlayer.setAbsorptionAmount(
                    Math.max(before, currentPlayer.getAbsorptionAmount() - granted));
        }, 120L);
        return true;
    }

    private boolean hunterAbility(Player player, Pet pet, PetProgress progress) {
        PlayerSession playerSession = session(player);
        LivingEntity target = aimedEnemy(player, 18.0);
        Mob mob = pet.getBukkitEntity();
        if (target == null || mob == null) return noAbilityTarget(player);
        pet.setTarget(target);
        Vector leap = target.getLocation().toVector().subtract(mob.getLocation().toVector());
        if (leap.lengthSquared() > 0.1) mob.setVelocity(leap.normalize().multiply(1.25).setY(0.35));
        player.getWorld().spawnParticle(Particle.CRIT, mob.getLocation().add(0, mob.getHeight() * 0.5, 0),
                18, 0.25, 0.25, 0.25, 0.15);
        getServer().getScheduler().runTaskLater(this, () -> {
            Player currentPlayer = authenticatedPlayer(playerSession);
            Pet currentPet = currentPlayer == null ? null : activePetByIdentity(currentPlayer.getUniqueId(), pet.getUUID());
            Mob currentMob = currentPet == null ? null : currentPet.getBukkitEntity();
            if (currentMob == null || !target.isValid() || target.isDead() || currentMob.getWorld() != target.getWorld()
                    || currentMob.getLocation().distanceSquared(target.getLocation()) > 20.0) return;
            double requested = 3.5 + currentPet.getLevel() * 0.12
                    + (progress == null ? 0.0 : progress.evolutionStage() * 0.4)
                    + (progress != null && progress.traitId().equals("keen_eye") ? 0.75 : 0.0);
            target.damage(boundedDamage(currentPet, target, requested), currentMob);
            target.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                    target.getLocation().add(0, target.getHeight() * 0.5, 0), 3, 0.25, 0.25, 0.25, 0.0);
        }, 6L);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 1.2f);
        return true;
    }

    private boolean healerAbility(Player player, Pet pet, PetProgress progress) {
        double amount = 3.0 + (progress != null && progress.evolutionStage() >= 2 ? 1.0 : 0.0);
        double maximumHealth = Objects.requireNonNull(player.getAttribute(Attribute.MAX_HEALTH)).getValue();
        player.setHealth(Math.min(maximumHealth, player.getHealth() + amount));
        player.setFireTicks(Math.min(0, player.getFireTicks()));
        ring(player, Color.fromRGB(103, 232, 147));
        player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0, 1.1, 0),
                6, 0.55, 0.35, 0.55, 0.0);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.9f, 1.45f);
        return true;
    }

    private boolean rangerAbility(Player player, Pet pet, PetProgress progress) {
        LivingEntity target = aimedEnemy(player, 24.0);
        if (target == null) return noAbilityTarget(player);
        pet.setTarget(target);
        int freeze = 80 + (progress == null ? 0 : progress.evolutionStage() * 10)
                + (progress != null && progress.traitId().equals("resonant") ? 20 : 0);
        target.setFreezeTicks(Math.min(target.getMaxFreezeTicks(), Math.max(target.getFreezeTicks(), freeze)));
        Vector away = target.getLocation().toVector().subtract(player.getLocation().toVector());
        if (away.lengthSquared() > 0.01) target.setVelocity(away.normalize().multiply(0.35).setY(0.15));
        Vector step = player.getEyeLocation().getDirection().normalize().multiply(0.8);
        Vector point = player.getEyeLocation().toVector();
        for (int index = 0; index < 20; index++) {
            point.add(step);
            player.getWorld().spawnParticle(Particle.SNOWFLAKE, point.toLocation(player.getWorld()),
                    1, 0.03, 0.03, 0.03, 0.0);
        }
        target.getWorld().spawnParticle(Particle.SNOWFLAKE,
                target.getLocation().add(0, target.getHeight() * 0.55, 0), 24, 0.35, 0.5, 0.35, 0.02);
        player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.8f, 1.65f);
        return true;
    }

    private LivingEntity aimedEnemy(Player player, double distance) {
        RayTraceResult trace = player.getWorld().rayTraceEntities(
                player.getEyeLocation(), player.getEyeLocation().getDirection(), distance, 0.7,
                entity -> entity instanceof LivingEntity && entity instanceof Enemy && entity != player);
        return trace != null && trace.getHitEntity() instanceof LivingEntity living ? living : null;
    }

    private boolean noAbilityTarget(Player player) {
        player.sendActionBar(Component.text(messages.render(player, "ability-no-target"), NamedTextColor.RED));
        return false;
    }

    private void ring(Player player, Color color) {
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.0f);
        for (int index = 0; index < 24; index++) {
            double angle = Math.PI * 2.0 * index / 24.0;
            player.getWorld().spawnParticle(Particle.DUST,
                    player.getLocation().add(Math.cos(angle) * 2.0, 0.15, Math.sin(angle) * 2.0),
                    1, 0.0, 0.0, 0.0, 0.0, dust);
        }
    }

    private void runEngine(Player player, String command) {
        player.closeInventory();
        UUID playerId = player.getUniqueId();
        internalCommands.add(playerId);
        try {
            if (!player.performCommand(command)) player.sendMessage(messages.render(player, "not-ready"));
        } finally {
            internalCommands.remove(playerId);
        }
    }

    private void auditActivePets() {
        long now = System.currentTimeMillis();
        for (UUID playerId : Set.copyOf(authenticatedPlayers.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            AuthenticatedSession expected = authenticatedPlayers.get(playerId);
            if (player == null || !player.isOnline() || expected == null || player != expected.player()) {
                if (expected != null && authenticatedPlayers.remove(playerId, expected)) {
                    sessionTokens.remove(playerId, expected.token());
                }
                continue;
            }
            activePet(player).ifPresent(pet -> {
                if (policy.petsBlocked(player.getWorld().getName())) {
                    pet.sendAway();
                    return;
                }
                PetProgress progress = progressCache.get(pet.getUUID());
                if (progress == null) registerProgress(player.getUniqueId(), pet, ignored -> { });
                else applySkinIfNeeded(pet, progress.selectedSkinId());
                double recharge = progress != null && progress.personalityId().equals("calm") ? 4.0 : 3.0;
                abilityStates.computeIfAbsent(pet.getUUID(), ignored -> new PetAbilityState()).recharge(now, recharge);
            });
        }
    }

    private void auditPendingRevivals() {
        for (UUID playerId : Set.copyOf(authenticatedPlayers.keySet())) recoverRevivals(playerId, false);
    }

    private Optional<Pet> activePet(Player player) {
        try {
            if (!MyPetApi.isReady() || !MyPetApi.getPetManager().hasActivePet(player)) return Optional.empty();
            return Optional.ofNullable(MyPetApi.getPetManager().getPet(player));
        } catch (IllegalStateException | SecurityException ignored) {
            return Optional.empty();
        }
    }

    private Pet petFromDamager(Entity damager) {
        Pet direct = petFromEntity(damager);
        if (direct != null) return direct;
        if (!(damager instanceof Projectile projectile)) return null;
        ProjectileSource shooter = projectile.getShooter();
        return shooter instanceof Entity entity ? petFromEntity(entity) : null;
    }

    private Pet petFromEntity(Entity entity) {
        try {
            return entity == null || !MyPetApi.isReady() ? null : MyPetApi.getPetManager().getPetFromEntity(entity);
        } catch (IllegalStateException | SecurityException ignored) {
            return null;
        }
    }

    private Map<String, Object> statusValues(Player player, Pet pet) {
        String tree = pet.getSkilltree() == null ? "—" : stripMarkup(pet.getSkilltree().getDisplayName());
        Map<String, Object> values = new HashMap<>();
        values.put("name", petName(pet));
        values.put("type", pet.getPetType().name());
        values.put("level", pet.getLevel());
        values.put("tree", tree);
        values.put("health", decimal(pet.getHealth()));
        values.put("max_health", decimal(pet.getMaxHealth()));
        values.put("hunger", Math.round(pet.getSaturation()));
        PetProgress progress = progressCache.get(pet.getUUID());
        if (progress == null) {
            values.put("bond", "…");
            values.put("stage", "…");
            values.put("personality", "…");
            values.put("trait", "…");
            values.put("skin", "…");
        } else {
            values.putAll(progressValues(player, progress));
        }
        PetAbilityState ability = abilityStates.get(pet.getUUID());
        values.put("energy", ability == null ? 100 : ability.energy());
        values.put("ability", messages.render(player, "ability." + PetProgressRules.route(
                pet.getSkilltree() == null ? null : pet.getSkilltree().getName())));
        return Map.copyOf(values);
    }

    private Map<String, Object> progressValues(Player player, PetProgress progress) {
        return Map.of(
                "bond", progress.bond(),
                "stage", progress.evolutionStage(),
                "personality", messages.render(player, "personality." + progress.personalityId()),
                "trait", messages.render(player, "trait." + progress.traitId()),
                "skin", messages.render(player, "skin." + progress.selectedSkinId()));
    }

    private void applySkinIfNeeded(Pet pet, String skinId) {
        Mob mob = pet.getBukkitEntity();
        if (mob == null || !mob.isValid()) return;
        SkinApplication wanted = new SkinApplication(mob.getUniqueId(), skinId);
        if (wanted.equals(appliedSkins.get(pet.getUUID()))) return;
        PetSkinRenderer.apply(pet, skinId, false);
        appliedSkins.put(pet.getUUID(), wanted);
    }

    private static String speciesName(String speciesId) {
        if (speciesId == null || speciesId.isBlank()) return "?";
        String[] words = speciesId.split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    private record SkinApplication(UUID entityId, String skinId) {
    }

    private double damageCap(Pet pet) {
        return Math.min(absoluteDamageCap, baseDamageCap + damagePerLevel * Math.max(0, pet.getLevel()));
    }

    private double boundedDamage(Pet pet, Entity target, double requested) {
        double targetHealth = target instanceof LivingEntity living ? living.getHealth() : Double.MAX_VALUE;
        return PetCombatRules.boundedDamage(requested, damageCap(pet), targetHealth, nonLethalDirectDamage);
    }

    private BigDecimal reviveCost(Pet pet) {
        return reviveBase.add(revivePerLevel.multiply(BigDecimal.valueOf(Math.max(0, pet.getLevel()))))
                .setScale(2, RoundingMode.DOWN);
    }

    /**
     * Reconstructs MyPet's offline maximum health from its configured base and
     * every Life upgrade earned through the pet's current level. This mirrors
     * LevelListener/PetImpl without activating an entity merely to revive it.
     */
    private static double maximumPersistedHealth(StoredPet pet) {
        double health = Math.max(1.0, MyPetApi.getPetInfo().getStartHP(pet.getPetType()));
        if (pet.getSkilltree() == null) return health;
        Number bonus = BigDecimal.ZERO;
        for (int level = 1; level <= Math.max(0, pet.getLevel()); level++) {
            for (Upgrade<?> upgrade : pet.getSkilltree().getUpgrades(level)) {
                if (upgrade instanceof LifeUpgrade life && life.getLifeModifier() != null) {
                    UpgradeNumberModifier modifier = life.getLifeModifier();
                    bonus = modifier.modify(bonus);
                }
            }
        }
        double maximum = health + bonus.doubleValue();
        return Double.isFinite(maximum) ? Math.max(1.0, maximum) : health;
    }

    private BigDecimal positiveMoney(String path, String fallback) {
        try {
            BigDecimal value = new BigDecimal(getConfig().getString(path, fallback));
            return value.signum() >= 0 ? value.setScale(2, RoundingMode.DOWN) : new BigDecimal(fallback);
        } catch (NumberFormatException ignored) {
            return new BigDecimal(fallback);
        }
    }

    private static String petName(Pet pet) {
        return PLAIN.serialize(pet.getDisplayName());
    }

    private static String stripMarkup(String input) {
        return input == null ? "—" : input.replaceAll("<[^>]*>", "");
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.DOWN).toPlainString();
    }

    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }

    private static int normalizeMenuSize(int configured) {
        int rows = Math.clamp((configured + 8) / 9, 3, 6);
        return rows * 9;
    }

    private static Set<String> normalizedSet(List<String> values) {
        Set<String> result = new HashSet<>();
        values.stream().filter(Objects::nonNull).map(value -> value.toLowerCase(Locale.ROOT)).forEach(result::add);
        return Set.copyOf(result);
    }

    private static List<String> prefix(List<String> values, String rawPrefix) {
        String lower = rawPrefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(lower)).sorted().toList();
    }

    private static boolean isRightClick(ClickType type) {
        return type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT;
    }

    private <T> T requireService(Class<T> type) {
        return Objects.requireNonNull(getServer().getServicesManager().load(type), type.getSimpleName());
    }
}
