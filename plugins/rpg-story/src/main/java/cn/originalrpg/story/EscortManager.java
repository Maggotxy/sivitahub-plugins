package cn.originalrpg.story;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.CurrencyOperationResult;
import cn.originalrpg.api.model.JourneyProgress;
import cn.originalrpg.api.model.ServerLoadState;
import cn.originalrpg.api.model.VisualMode;
import cn.originalrpg.api.service.EconomyService;
import cn.originalrpg.api.service.JourneyService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.PerformanceService;
import cn.originalrpg.api.service.ProfileService;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.trait.SkinTrait;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.betonquest.betonquest.api.BetonQuestApi;
import org.betonquest.betonquest.api.data.TagHolder;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class EscortManager implements AutoCloseable, Listener {
    private static final String REGISTRY_NAME = "sivita_story_escorts";
    private static final Color TRAIL_COLOR = Color.fromRGB(111, 204, 178);

    private final RpgStoryPlugin plugin;
    private final ProfileService profiles;
    private final EconomyService economy;
    private final MessageService messages;
    private final JourneyService journeys;
    private final NPCRegistry registry;
    private final Map<UUID, EscortSession> sessions = new HashMap<>();
    private final Map<UUID, AuthenticatedSession> authenticatedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, RewardLease> rewardsInFlight = new ConcurrentHashMap<>();
    private BetonQuestApi betonQuest;
    private BukkitTask task;

    EscortManager(
            RpgStoryPlugin plugin,
            ProfileService profiles,
            EconomyService economy,
            MessageService messages,
            JourneyService journeys
    ) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.economy = economy;
        this.messages = messages;
        this.journeys = journeys;
        NPCRegistry existing = CitizensAPI.getNamedNPCRegistry(REGISTRY_NAME);
        if (existing != null) existing.deregisterAll();
        registry = existing == null ? CitizensAPI.createInMemoryNPCRegistry(REGISTRY_NAME) : existing;
    }

    void setBetonQuest(BetonQuestApi betonQuest) {
        this.betonQuest = Objects.requireNonNull(betonQuest, "betonQuest");
    }

    void startTicker() {
        int interval = Math.clamp(plugin.getConfig().getInt("escort.poll-interval-ticks", 10), 5, 20);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(interval), interval, interval);
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        Player player = event.player();
        UUID playerId = player.getUniqueId();
        AuthenticatedSession previous = authenticatedPlayers.put(playerId,
                new AuthenticatedSession(player, UUID.randomUUID()));
        if (previous != null) disconnectSession(previous);
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        clearAuthenticated(event.player());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearAuthenticated(event.getPlayer());
    }

    boolean eligible(UUID playerId, StoryQuest quest) {
        if (betonQuest == null) return false;
        TagHolder tags = tags(playerId);
        if (tags.has(quest.completeTag()) || tags.has(quest.rewardedTag())) return false;
        return profiles.cached(playerId).map(profile -> profile.archetype() != Archetype.UNCHOSEN).orElse(false);
    }

    void start(Player player, StoryQuest quest) {
        requirePrimaryThread();
        UUID sessionToken = sessionToken(player);
        if (sessionToken == null) return;
        TagHolder tags = tags(player.getUniqueId());
        if (tags.has(quest.rewardedTag())) {
            send(player, "story.already-completed", NamedTextColor.YELLOW);
            return;
        }
        if (tags.has(quest.completeTag())) {
            claim(player, quest);
            return;
        }
        if (profiles.cached(player.getUniqueId()).map(profile -> profile.archetype() == Archetype.UNCHOSEN).orElse(true)) {
            send(player, "story.choose-class", NamedTextColor.RED);
            return;
        }
        List<Location> route = route();
        EscortSession existingSession = usableSession(player.getUniqueId());
        if (sessions.containsKey(player.getUniqueId()) && existingSession == null) {
            // An actor that Citizens no longer considers spawned cannot be
            // resumed. Remove its bar/tag before applying the normal location
            // gate, then create a fresh echo only if the player is at the hub.
            cancel(player, false);
        }
        if (startAction(player, route, existingSession) != StoryStartPolicy.Action.SPAWN_ECHO) {
            // RpgHub remains the authority for combat, dungeon and cooldown
            // restrictions. A remote story request never becomes a second
            // teleport primitive and, critically, creates no Citizens actor or
            // EscortSession before the safe-return result is known.
            // An existing echo may already be at a route point more than the
            // hard follow radius from hub arrival, so it is reclaimed instead
            // of leaving an unreachable session behind after /hub succeeds.
            cancel(player, false);
            send(player, "story.start-needs-hub", NamedTextColor.YELLOW);
            player.performCommand("hub");
            return;
        }
        if (sessions.containsKey(player.getUniqueId())) {
            send(player, "story.already-active", NamedTextColor.YELLOW);
            return;
        }
        if (state() == ServerLoadState.CRITICAL || sessions.size() >= maximumConcurrent()) {
            send(player, "story.busy", NamedTextColor.RED);
            return;
        }

        NPC npc = registry.createNPC(EntityType.PLAYER, messages.text(player, "story.echo-name"));
        npc.setProtected(true);
        npc.setUseMinecraftAI(false);
        npc.getOrAddTrait(SkinTrait.class).setFetchDefaultSkin(false);
        PlayerFilter filter = npc.getOrAddTrait(PlayerFilter.class);
        filter.setAllowlist();
        filter.addPlayer(player.getUniqueId());
        filter.setApplyRange(72.0);
        npc.getOrAddTrait(Equipment.class).set(
                Equipment.EquipmentSlot.HAND, new ItemStack(Material.RECOVERY_COMPASS));
        if (!npc.spawn(route.getFirst())) {
            npc.destroy();
            send(player, "story.spawn-failed", NamedTextColor.RED);
            return;
        }

        BossBar bar = BossBar.bossBar(
                Component.text(messages.text(player, "story.progress", Map.of("current", 1, "total", route.size() - 1))),
                0.0F, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        player.showBossBar(bar);
        EscortSession session = new EscortSession(player, sessionToken, quest, npc, route, bar);
        sessions.put(player.getUniqueId(), session);
        tags.add(quest.activeTag());
        navigate(session);
        send(player, "story.started", NamedTextColor.GREEN);
    }

    void claim(Player player, StoryQuest quest) {
        requirePrimaryThread();
        UUID sessionToken = sessionToken(player);
        if (sessionToken == null) return;
        TagHolder tags = tags(player.getUniqueId());
        if (tags.has(quest.rewardedTag())) {
            send(player, "story.already-completed", NamedTextColor.YELLOW);
            return;
        }
        if (!tags.has(quest.completeTag())) {
            send(player, "story.not-complete", NamedTextColor.RED);
            return;
        }
        BigDecimal reward = new BigDecimal(plugin.getConfig().getString(
                "rewards." + quest.configKey() + ".gold", "300.00"));
        RewardLease lease = new RewardLease(player, sessionToken, quest);
        if (rewardsInFlight.putIfAbsent(player.getUniqueId(), lease) != null) {
            send(player, "story.reward-pending", NamedTextColor.AQUA);
            return;
        }
        String key = "story:" + quest.id() + ":" + player.getUniqueId();
        send(player, "story.reward-pending", NamedTextColor.AQUA);
        economy.credit(player.getUniqueId(), reward, key, "story:" + quest.id())
                .whenComplete((result, failure) -> onMain(() ->
                        finishReward(lease, reward, result, failure)));
    }

    void cancel(Player player, boolean notify) {
        requirePrimaryThread();
        EscortSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            TagHolder tags = tags(player.getUniqueId());
            if (tags.has(StoryQuest.PROLOGUE_ESCORT.activeTag())) {
                tags.remove(StoryQuest.PROLOGUE_ESCORT.activeTag());
                if (notify) send(player, "story.cancelled", NamedTextColor.YELLOW);
            } else if (notify) {
                send(player, "story.not-active", NamedTextColor.YELLOW);
            }
            return;
        }
        cleanup(session, player);
        tags(player.getUniqueId()).remove(session.quest.activeTag());
        if (notify) send(player, "story.cancelled", NamedTextColor.YELLOW);
    }

    StoryOverview overview(Player player) {
        TagHolder tags = tags(player.getUniqueId());
        JourneyProgress journey = journeys.progress(player.getUniqueId());
        boolean classChosen = profiles.cached(player.getUniqueId())
                .map(profile -> profile.archetype() != Archetype.UNCHOSEN).orElse(false);
        EscortSession activeSession = usableSession(player.getUniqueId());
        StoryStatusPolicy.State state = StoryStatusPolicy.resolve(
                classChosen,
                tags.has(StoryQuest.PROLOGUE_ESCORT.rewardedTag()),
                tags.has(StoryQuest.PROLOGUE_ESCORT.completeTag()),
                tags.has(StoryQuest.PROLOGUE_ESCORT.activeTag()),
                activeSession != null,
                canStartOrContinueHere(player, activeSession));
        return new StoryOverview(state, journey);
    }

    int activeCount() {
        return sessions.size();
    }

    @Override
    public void close() {
        if (task != null) task.cancel();
        for (EscortSession session : List.copyOf(sessions.values())) {
            Player player = plugin.getServer().getPlayer(session.playerId);
            cleanup(session, player);
        }
        // Plugin shutdown is another disconnect boundary. Keep the durable
        // active tag so the route is resumable after maintenance, and never
        // enter BetonQuest while Paper is disabling the dependency graph.
        sessions.clear();
        authenticatedPlayers.clear();
        rewardsInFlight.clear();
        registry.deregisterAll();
    }

    private void tick(int interval) {
        ServerLoadState load = state();
        for (EscortSession session : List.copyOf(sessions.values())) tick(session, interval, load);
    }

    private void tick(EscortSession session, int interval, ServerLoadState load) {
        Player player = session.player;
        if (!isAuthenticated(player, session.sessionToken) || !session.npc.isSpawned()) {
            fail(session, player, "story.interrupted");
            return;
        }
        session.elapsedTicks += interval;
        session.messageCooldownTicks = Math.max(0, session.messageCooldownTicks - interval);
        int timeout = Math.clamp(plugin.getConfig().getInt("escort.total-timeout-seconds", 480), 60, 1800) * 20;
        if (session.elapsedTicks >= timeout) {
            fail(session, player, "story.timeout");
            return;
        }

        if (load == ServerLoadState.CRITICAL) {
            pause(session, true);
            if (!session.pausedForLoad || session.messageCooldownTicks == 0) {
                player.sendActionBar(Component.text(messages.text(player, "story.load-paused"), NamedTextColor.RED));
                session.messageCooldownTicks = 80;
            }
            session.pausedForLoad = true;
            return;
        }
        if (session.pausedForLoad) {
            session.pausedForLoad = false;
            pause(session, false);
        }

        Location npcLocation = session.npc.getEntity().getLocation();
        if (player.getWorld() != npcLocation.getWorld()) {
            fail(session, player, "story.interrupted");
            return;
        }
        double distance = player.getLocation().distance(npcLocation);
        double soft = Math.clamp(plugin.getConfig().getDouble("escort.soft-follow-distance", 14.0), 6.0, 24.0);
        double hard = Math.clamp(plugin.getConfig().getDouble("escort.hard-follow-distance", 30.0), soft + 2.0, 48.0);
        if (distance > hard) session.hardDistanceTicks += interval;
        else session.hardDistanceTicks = 0;
        int hardTimeout = Math.clamp(plugin.getConfig().getInt("escort.hard-distance-timeout-seconds", 12), 5, 60) * 20;
        EscortRules.FollowState follow = EscortRules.evaluate(
                distance, soft, hard, session.hardDistanceTicks, hardTimeout);
        if (follow == EscortRules.FollowState.FAIL) {
            fail(session, player, "story.too-far-failed");
            return;
        }
        if (follow == EscortRules.FollowState.WAITING) {
            pause(session, true);
            if (session.messageCooldownTicks == 0) {
                player.sendActionBar(Component.text(messages.text(player, "story.waiting"), NamedTextColor.YELLOW));
                session.messageCooldownTicks = 60;
            }
            return;
        }
        pause(session, false);

        double margin = Math.clamp(plugin.getConfig().getDouble("escort.waypoint-margin", 2.6), 1.5, 5.0);
        if (npcLocation.distanceSquared(session.target()) <= margin * margin) {
            session.waypoint++;
            session.navigationRetries = 0;
            player.playSound(npcLocation, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS,
                    VisualMode.forPlayer(player).volume(0.55F), 1.2F);
            if (session.waypoint >= session.route.size()) {
                complete(session, player);
                return;
            }
            session.bossBar.progress(session.progress());
            session.bossBar.name(Component.text(messages.text(player, "story.progress", Map.of(
                    "current", session.waypoint, "total", session.route.size() - 1))));
            navigate(session);
        } else if (!session.npc.getNavigator().isNavigating()) {
            session.navigationRetries++;
            if (session.navigationRetries > 3 || !session.npc.getNavigator().canNavigateTo(session.target())) {
                fail(session, player, "story.path-failed");
                return;
            }
            navigate(session);
        }
        renderTrail(session, player, load, interval);
    }

    private void navigate(EscortSession session) {
        Navigator navigator = session.npc.getNavigator();
        navigator.setTarget(session.target());
        navigator.getLocalParameters()
                .range((float) Math.clamp(plugin.getConfig().getDouble("escort.path-range", 56.0), 24.0, 96.0))
                .speedModifier((float) Math.clamp(plugin.getConfig().getDouble("escort.speed-modifier", 1.05), 0.7, 1.35))
                .distanceMargin(Math.clamp(plugin.getConfig().getDouble("escort.waypoint-margin", 2.6), 1.5, 5.0));
    }

    private void renderTrail(EscortSession session, Player player, ServerLoadState load, int interval) {
        int configured = load == ServerLoadState.NORMAL
                ? plugin.getConfig().getInt("visuals.normal-particle-interval-ticks", 40)
                : plugin.getConfig().getInt("visuals.pressure-particle-interval-ticks", 0);
        if (configured <= 0 || session.elapsedTicks % Math.max(interval, configured) != 0) return;
        int count = VisualMode.forPlayer(player).particles(2);
        if (count <= 0) return;
        player.spawnParticle(Particle.DUST, session.npc.getEntity().getLocation().add(0.0, 0.15, 0.0),
                count, 0.12, 0.05, 0.12, 0.0, new Particle.DustOptions(TRAIL_COLOR, 0.9F));
    }

    private void complete(EscortSession session, Player player) {
        sessions.remove(session.playerId);
        cleanup(session, player);
        TagHolder tags = tags(session.playerId);
        tags.remove(session.quest.activeTag());
        tags.add(session.quest.completeTag());
        send(player, "story.route-completed", NamedTextColor.GREEN);
        claim(player, session.quest);
    }

    private void finishReward(
            RewardLease lease,
            BigDecimal reward,
            CurrencyOperationResult result,
            Throwable failure
    ) {
        UUID playerId = lease.player().getUniqueId();
        rewardsInFlight.remove(playerId, lease);
        Player player = isAuthenticated(lease.player(), lease.sessionToken()) ? lease.player() : null;
        if (failure != null || result == null
                || (result.status() != CurrencyOperationResult.Status.SUCCESS
                && result.status() != CurrencyOperationResult.Status.DUPLICATE)) {
            plugin.getLogger().warning("Story reward remains claimable for " + playerId + ": "
                    + (failure == null ? "economy rejected reward: " + result.status() : failure.getMessage()));
            if (player != null) send(player, "story.reward-failed", NamedTextColor.RED);
            return;
        }
        TagHolder tags = tags(playerId);
        tags.remove(lease.quest().activeTag());
        tags.add(lease.quest().completeTag());
        tags.add(lease.quest().rewardedTag());
        if (player != null) {
            send(player, "story.rewarded", NamedTextColor.GOLD, "gold", reward.toPlainString());
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE,
                    SoundCategory.PLAYERS, VisualMode.forPlayer(player).volume(0.75F), 1.1F);
        }
    }

    private void fail(EscortSession session, Player player, String key) {
        sessions.remove(session.playerId);
        cleanup(session, player);
        if (betonQuest != null) tags(session.playerId).remove(session.quest.activeTag());
        if (player != null) send(player, key, NamedTextColor.RED);
    }

    private void cleanup(EscortSession session, Player player) {
        if (player != null) player.hideBossBar(session.bossBar);
        if (session.npc.isSpawned()) session.npc.despawn(DespawnReason.PLUGIN);
        session.npc.destroy();
    }

    private void pause(EscortSession session, boolean paused) {
        if (session.npc.getNavigator().isNavigating()) session.npc.getNavigator().setPaused(paused);
    }

    private List<Location> route() {
        HubAnchor anchor = HubAnchor.resolve(plugin);
        List<?> values = plugin.getConfig().getList("escort.route", List.of());
        ArrayList<Location> route = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof List<?> point)) throw new IllegalArgumentException("escort.route contains a non-list value");
            route.add(anchor.offset(point));
        }
        if (route.size() < 2 || route.size() > 16) {
            throw new IllegalArgumentException("escort.route must contain between 2 and 16 points");
        }
        return route;
    }

    private boolean canStartOrContinueHere(Player player, EscortSession session) {
        return startAction(player, route(), session) == StoryStartPolicy.Action.SPAWN_ECHO;
    }

    private StoryStartPolicy.Action startAction(
            Player player, List<Location> route, EscortSession activeSession
    ) {
        Location start = activeSession == null
                ? route.getFirst() : activeSession.npc.getEntity().getLocation();
        boolean sameWorld = start.getWorld() != null
                && player.getWorld().getUID().equals(start.getWorld().getUID());
        double distanceSquared = sameWorld
                ? player.getLocation().distanceSquared(start) : Double.POSITIVE_INFINITY;
        double configured = plugin.getConfig().getDouble(
                "escort.start-distance", StoryStartPolicy.DEFAULT_START_DISTANCE);
        return StoryStartPolicy.decide(sameWorld, distanceSquared, configured);
    }

    private EscortSession usableSession(UUID playerId) {
        EscortSession session = sessions.get(playerId);
        return session != null && session.npc.isSpawned() && session.npc.getEntity() != null
                && session.npc.getEntity().isValid() ? session : null;
    }

    private TagHolder tags(UUID playerId) {
        BetonQuestApi api = Objects.requireNonNull(betonQuest, "BetonQuest integration is not ready");
        return api.persistence().of(api.profiles().getProfile(playerId)).tags();
    }

    private ServerLoadState state() {
        PerformanceService performance = OptionalServices.enabled(plugin, PerformanceService.class);
        return performance == null ? ServerLoadState.NORMAL : performance.state();
    }

    private int maximumConcurrent() {
        return Math.clamp(plugin.getConfig().getInt("escort.maximum-concurrent", 12), 1, 32);
    }

    private void send(Player player, String key, NamedTextColor color, Object... placeholders) {
        HashMap<String, Object> values = new HashMap<>();
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            values.put(String.valueOf(placeholders[index]), placeholders[index + 1]);
        }
        player.sendMessage(Component.text(messages.text(player, key, values), color));
    }

    private void requirePrimaryThread() {
        if (!plugin.getServer().isPrimaryThread()) throw new IllegalStateException("Story actor mutation must run on the server thread");
    }

    boolean isAuthenticated(Player player) {
        AuthenticatedSession session = player == null ? null : authenticatedPlayers.get(player.getUniqueId());
        return player != null && player.isOnline() && plugin.getServer().getPlayer(player.getUniqueId()) == player
                && session != null && session.player() == player;
    }

    UUID sessionToken(Player player) {
        if (!isAuthenticated(player)) return null;
        AuthenticatedSession session = authenticatedPlayers.get(player.getUniqueId());
        return session == null ? null : session.token();
    }

    boolean isAuthenticated(Player player, UUID sessionToken) {
        if (sessionToken == null || !isAuthenticated(player)) return false;
        AuthenticatedSession session = authenticatedPlayers.get(player.getUniqueId());
        return session != null && session.player() == player && sessionToken.equals(session.token());
    }

    void runAuthenticated(Player player, UUID sessionToken, Consumer<Player> action) {
        onMain(() -> {
            if (isAuthenticated(player, sessionToken)) action.accept(player);
        });
    }

    private void clearAuthenticated(Player player) {
        AuthenticatedSession session = authenticatedPlayers.get(player.getUniqueId());
        if (session == null || session.player() != player
                || !authenticatedPlayers.remove(player.getUniqueId(), session)) return;
        disconnectSession(session);
    }

    private void disconnectSession(AuthenticatedSession authenticated) {
        EscortSession session = sessions.get(authenticated.player().getUniqueId());
        if (session != null && session.player == authenticated.player()
                && session.sessionToken.equals(authenticated.token())) {
            sessions.remove(authenticated.player().getUniqueId(), session);
            cleanup(session, authenticated.player());
        }
        // Durable reward writes keep their exact lease until completion; an
        // old callback can update quest tags but never address a new session.
    }

    private void onMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) action.run();
            });
        } catch (IllegalPluginAccessException | IllegalStateException ignored) {
            // The ledger idempotency key preserves a claim across shutdown;
            // the durable BetonQuest tag is reconciled by the next claim.
        }
    }

    private record AuthenticatedSession(Player player, UUID token) {
    }

    private record RewardLease(Player player, UUID sessionToken, StoryQuest quest) {
    }
}
