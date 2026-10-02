package cn.originalrpg.mobs;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.service.GuideMenuService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.mobs.ability.MobAbilityController;
import cn.originalrpg.mobs.ability.MobBehavior;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Level-aware answer to “where do I fight and how do I level?” */
final class MobGuideCommand implements CommandExecutor, Listener {
    private final JavaPlugin plugin;
    private final ProfileService profiles;
    private final MessageService messages;
    private final MobAbilityController abilities;
    private final Map<UUID, AuthenticatedSession> authenticatedPlayers = new ConcurrentHashMap<>();

    MobGuideCommand(JavaPlugin plugin, ProfileService profiles, MessageService messages,
                    MobAbilityController abilities) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.messages = messages;
        this.abilities = abilities;
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        authenticatedPlayers.put(event.player().getUniqueId(),
                new AuthenticatedSession(event.player(), UUID.randomUUID()));
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        clear(event.player());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        UUID sessionToken = sessionToken(player);
        if (sessionToken == null) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("dungeons")) {
            GuideMenuService guide = plugin.getServer().getServicesManager().load(GuideMenuService.class);
            if (guide != null) guide.open(player, GuideMenuService.Section.DUNGEON);
            else player.performCommand("menu");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("abilities")) {
            showBehaviors(player, sessionToken);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            testBehavior(player, args[1], sessionToken);
            return true;
        }
        profiles.get(player.getUniqueId()).whenComplete((profile, error) -> onMain(() -> {
            if (!isAuthenticated(player, sessionToken)) return;
            if (error != null) {
                player.sendMessage(Component.text(messages.text(player, "mobs.guide.load-error"), NamedTextColor.RED));
                return;
            }
            show(player, sessionToken, profile, dungeonOptions());
        }));
        return true;
    }

    private void show(Player player, UUID sessionToken, PlayerProfile profile,
                      List<MobGuideRules.DungeonOption> dungeons) {
        long minimumExperience = Math.max(1L, plugin.getConfig().getLong("experience.minimum-per-kill", 10L));
        long normalMultiplier = Math.max(1L,
                plugin.getConfig().getLong("experience.normal-level-multiplier", 10L));
        long eliteMultiplier = Math.max(1L,
                plugin.getConfig().getLong("experience.elite-level-multiplier", 40L));
        MobGuideRules.GuidePlan plan = MobGuideRules.guidePlan(profile, minimumExperience,
                normalMultiplier, eliteMultiplier, dungeons);

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(messages.text(player, "mobs.guide.profile", Map.of(
                "ascension", plan.ascension(),
                "level", plan.level(),
                "effective", plan.effectiveLevel())), NamedTextColor.WHITE), 380));
        body.add(DialogBody.plainMessage(progress(player, plan), 380));
        body.add(DialogBody.plainMessage(Component.text(messages.text(player, "mobs.guide.dialog.range", Map.of(
                "minimum", plan.minimumMobLevel(),
                "maximum", plan.maximumMobLevel(),
                "normal_min", plan.minimumNormalExperience(),
                "normal_max", plan.maximumNormalExperience(),
                "elite_min", plan.minimumEliteExperience(),
                "elite_max", plan.maximumEliteExperience())), NamedTextColor.YELLOW), 380));
        body.add(DialogBody.plainMessage(route(player, plan), 380));
        body.add(DialogBody.plainMessage(Component.text(
                messages.text(player, "mobs.guide.dialog.scope"), NamedTextColor.GRAY), 380));
        body.add(DialogBody.plainMessage(Component.text(
                messages.text(player, "mobs.behaviors.guide-summary"), NamedTextColor.AQUA), 380));

        MobGuideRules.HuntRoute huntRoute = MobGuideRules.huntRoute(huntLocation(player));
        List<ActionButton> actions = List.of(
                huntButton(player, sessionToken, huntRoute),
                commandButton(player, "mobs.guide.dialog.actions.world",
                        "mobs.guide.dialog.actions.world-tooltip",
                        "/worldstatus", NamedTextColor.AQUA, sessionToken),
                commandButton(player, "mobs.guide.dialog.actions.dungeons",
                        "mobs.guide.dialog.actions.dungeons-tooltip",
                        "/mobguide dungeons", NamedTextColor.LIGHT_PURPLE, sessionToken),
                commandButton(player, "mobs.guide.dialog.actions.abilities",
                        "mobs.guide.dialog.actions.abilities-tooltip",
                        "/mobguide abilities", NamedTextColor.GOLD, sessionToken));
        ActionButton close = ActionButton.create(
                Component.text(messages.text(player, "mobs.guide.dialog.actions.close"), NamedTextColor.GRAY),
                Component.text(messages.text(player, "mobs.guide.dialog.actions.close-tooltip")), 150, null);
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(
                                messages.text(player, "mobs.guide.title"), NamedTextColor.GOLD))
                        .externalTitle(Component.text(messages.text(player, "mobs.guide.title")))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(actions).columns(2).exitAction(close).build()));
        player.closeInventory();
        player.showDialog(dialog);
    }

    private void showBehaviors(Player player, UUID sessionToken) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
                messages.text(player, "mobs.behaviors.guide-summary"), NamedTextColor.AQUA), 380));
        for (MobBehavior behavior : MobBehavior.values()) {
            String root = "mobs.behaviors." + behavior.id();
            body.add(DialogBody.plainMessage(Component.text(messages.text(player, root + ".name") + "｜",
                            NamedTextColor.GOLD)
                    .append(Component.text(messages.text(player, root + ".guide"), NamedTextColor.WHITE)), 380));
        }
        ActionButton back = commandButton(player, "mobs.behaviors.back",
                "mobs.behaviors.back-tooltip", "/mobguide", NamedTextColor.GREEN, sessionToken);
        ActionButton close = ActionButton.create(
                Component.text(messages.text(player, "mobs.guide.dialog.actions.close"), NamedTextColor.GRAY),
                Component.text(messages.text(player, "mobs.guide.dialog.actions.close-tooltip")), 150, null);
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(
                                messages.text(player, "mobs.behaviors.title"), NamedTextColor.GOLD))
                        .externalTitle(Component.text(messages.text(player, "mobs.behaviors.title")))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(List.of(back)).columns(1).exitAction(close).build()));
        player.closeInventory();
        player.showDialog(dialog);
    }

    private void testBehavior(Player player, String requested, UUID sessionToken) {
        if (!isAuthenticated(player, sessionToken)) return;
        if (!player.hasPermission("rpg.mobs.test")) {
            player.sendMessage(Component.text(messages.text(player, "mobs.behaviors.test-denied"), NamedTextColor.RED));
            return;
        }
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            player.sendMessage(Component.text(messages.text(player, "mobs.behaviors.test-gamemode"),
                    NamedTextColor.YELLOW));
            return;
        }
        MobBehavior behavior = MobBehavior.fromId(requested.toLowerCase(java.util.Locale.ROOT));
        if (behavior == null) {
            player.sendMessage(Component.text(messages.text(player, "mobs.behaviors.test-usage"),
                    NamedTextColor.YELLOW));
            return;
        }
        Entity target = player.getTargetEntity(12);
        if (!(target instanceof LivingEntity living) || !(living instanceof Enemy)
                || !abilities.force(living, behavior)) {
            player.sendMessage(Component.text(messages.text(player, "mobs.behaviors.test-target"),
                    NamedTextColor.RED));
            return;
        }
        living.customName(Component.text("[" + abilities.localizedName(player, behavior) + "] ",
                        NamedTextColor.AQUA)
                .append(Component.translatable(living.getType().translationKey())));
        living.setCustomNameVisible(true);
        if (living instanceof Mob mob) mob.setTarget(player);
        player.sendMessage(Component.text(messages.text(player, "mobs.behaviors.test-ready", Map.of(
                "skill", abilities.localizedName(player, behavior))), NamedTextColor.GREEN));
    }

    private Component progress(Player player, MobGuideRules.GuidePlan plan) {
        if (plan.capped()) {
            return Component.text(messages.text(player, "mobs.guide.progress-cap"), NamedTextColor.GREEN);
        }
        return Component.text(messages.text(player, "mobs.guide.progress", Map.of(
                "experience", plan.experience(),
                "needed", plan.neededExperience(),
                "remaining", plan.remainingExperience(),
                "stage_spent", plan.stageExperienceSpent(),
                "stage_budget", plan.stageExperienceBudget(),
                "stage_remaining", plan.stageExperienceRemaining(),
                "effective_total", plan.totalExperienceToEffective())), NamedTextColor.AQUA);
    }

    private Component route(Player player, MobGuideRules.GuidePlan plan) {
        MobGuideRules.DungeonOption normal = plan.recommendedNormal();
        Component result = normal == null
                ? Component.text(messages.text(player, "mobs.guide.recommend-wild"), NamedTextColor.GREEN)
                : Component.text(messages.text(player, "mobs.guide.recommend-dungeon", Map.of(
                        "dungeon", dungeonName(player, normal),
                        "level", normal.minimumLevel(),
                        "experience", normal.experience())), NamedTextColor.GREEN);
        MobGuideRules.DungeonOption hidden = plan.recommendedHidden();
        if (hidden != null) {
            return result.append(Component.newline()).append(Component.text(
                    messages.text(player, "mobs.guide.hidden-dungeon", Map.of(
                            "dungeon", dungeonName(player, hidden),
                            "level", hidden.minimumLevel(),
                            "experience", hidden.experience())), NamedTextColor.LIGHT_PURPLE));
        }
        MobGuideRules.DungeonOption next = plan.nextUnlock();
        if (next == null) return result;
        return result.append(Component.newline()).append(Component.text(
                messages.text(player, "mobs.guide.next-dungeon", Map.of(
                        "dungeon", dungeonName(player, next),
                        "level", next.minimumLevel(),
                        "experience", next.experience())), NamedTextColor.YELLOW));
    }

    private List<MobGuideRules.DungeonOption> dungeonOptions() {
        Plugin candidate = plugin.getServer().getPluginManager().getPlugin("RpgDungeons");
        if (!(candidate instanceof JavaPlugin dungeonPlugin) || !candidate.isEnabled()) return List.of();
        ConfigurationSection root = dungeonPlugin.getConfig().getConfigurationSection("dungeons");
        if (root == null) return List.of();
        List<MobGuideRules.DungeonOption> options = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null || !section.getBoolean("enabled", true)) continue;
            int level = Math.max(1, section.getInt("minimum-effective-level", 1));
            long experience = Math.max(0L, section.getLong("boss.rpg-experience", 0L));
            options.add(new MobGuideRules.DungeonOption(id, level, experience,
                    section.contains("ticket-id") || id.endsWith("-hidden")));
        }
        return List.copyOf(options);
    }

    private String dungeonName(Player player, MobGuideRules.DungeonOption dungeon) {
        return messages.text(player, "dungeon.names." + dungeon.id());
    }

    private MobGuideRules.HuntLocation huntLocation(Player player) {
        Plugin candidate = plugin.getServer().getPluginManager().getPlugin("RpgHub");
        if (!(candidate instanceof JavaPlugin hub) || !candidate.isEnabled()) {
            return MobGuideRules.HuntLocation.OTHER;
        }
        String worldName = hub.getConfig().getString("hub.world", "world");
        World hubWorld = plugin.getServer().getWorld(worldName);
        if (hubWorld == null || hubWorld.getEnvironment() != World.Environment.NORMAL
                || !hubWorld.getUID().equals(player.getWorld().getUID())) {
            return MobGuideRules.HuntLocation.OTHER;
        }

        PersistentDataContainer data = hubWorld.getPersistentDataContainer();
        Integer storedVersion = data.get(new NamespacedKey(hub, "hub_build_version"),
                PersistentDataType.INTEGER);
        Integer centerX = data.get(new NamespacedKey(hub, "hub_center_x"), PersistentDataType.INTEGER);
        Integer centerZ = data.get(new NamespacedKey(hub, "hub_center_z"), PersistentDataType.INTEGER);
        int expectedVersion = Math.max(1, hub.getConfig().getInt("hub.build-version", 1));
        if (storedVersion == null || storedVersion != expectedVersion || centerX == null || centerZ == null) {
            return MobGuideRules.HuntLocation.OTHER;
        }
        int buildRadius = Math.clamp(hub.getConfig().getInt("hub.radius", 52), 36, 96);
        int protectionRadius = Math.clamp(
                hub.getConfig().getInt("hub.protection-radius", 60), buildRadius, 128);
        return MobGuideRules.insideHub(centerX, centerZ, protectionRadius,
                player.getLocation().getBlockX(), player.getLocation().getBlockZ())
                ? MobGuideRules.HuntLocation.HUB
                : MobGuideRules.HuntLocation.OVERWORLD_FRONTIER;
    }

    private ActionButton huntButton(Player player, UUID sessionToken, MobGuideRules.HuntRoute route) {
        String key = "mobs.guide.dialog.actions.hunt-" + route.id();
        if (!route.alreadyThere()) {
            return commandButton(player, key, key + "-tooltip", route.command(), NamedTextColor.GREEN, sessionToken);
        }
        return ActionButton.builder(Component.text(messages.text(player, key), NamedTextColor.GREEN))
                .tooltip(Component.text(messages.text(player, key + "-tooltip")))
                .width(180)
                .action(DialogAction.customClick((response, audience) -> {
                    if (!(audience instanceof Player responder)) return;
                    onMain(() -> {
                        if (isAuthenticated(responder, sessionToken)) responder.sendMessage(Component.text(messages.text(
                                responder, "mobs.guide.dialog.actions.hunt-frontier-confirmation"),
                                NamedTextColor.GREEN));
                    });
                }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build()))
                .build();
    }

    private ActionButton commandButton(
            Player player, String textKey, String tooltipKey, String command, NamedTextColor color,
            UUID sessionToken
    ) {
        return ActionButton.builder(Component.text(messages.text(player, textKey), color))
                .tooltip(Component.text(messages.text(player, tooltipKey)))
                .width(180)
                .action(DialogAction.customClick((response, audience) -> {
                    if (!(audience instanceof Player responder)) return;
                    onMain(() -> {
                        if (!isAuthenticated(responder, sessionToken)) return;
                        responder.performCommand(command.startsWith("/") ? command.substring(1) : command);
                    });
                }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build()))
                .build();
    }

    void close() {
        authenticatedPlayers.clear();
    }

    private void clear(Player player) {
        AuthenticatedSession session = authenticatedPlayers.get(player.getUniqueId());
        if (session != null && session.player() == player) {
            authenticatedPlayers.remove(player.getUniqueId(), session);
        }
    }

    private UUID sessionToken(Player player) {
        AuthenticatedSession session = authenticatedPlayers.get(player.getUniqueId());
        return isAuthenticated(player, session == null ? null : session.token()) ? session.token() : null;
    }

    private boolean isAuthenticated(Player player, UUID sessionToken) {
        if (player == null || sessionToken == null || !player.isOnline()
                || plugin.getServer().getPlayer(player.getUniqueId()) != player) return false;
        AuthenticatedSession session = authenticatedPlayers.get(player.getUniqueId());
        return session != null && session.player() == player && sessionToken.equals(session.token());
    }

    private void onMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) action.run();
            });
        } catch (IllegalPluginAccessException | IllegalStateException ignored) {
            // A database or dialog callback may settle during plugin disable.
        }
    }

    private record AuthenticatedSession(Player player, UUID token) {
    }
}
