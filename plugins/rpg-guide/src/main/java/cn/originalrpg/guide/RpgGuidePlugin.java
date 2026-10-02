package cn.originalrpg.guide;

import cn.originalrpg.api.service.ActivityEconomyService;
import cn.originalrpg.api.service.ChapterService;
import cn.originalrpg.api.service.CommerceService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.GuideMenuService;
import cn.originalrpg.api.service.JourneyService;
import cn.originalrpg.api.service.CombatStateService;
import cn.originalrpg.api.service.DungeonRewardService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

import java.util.Objects;

public final class RpgGuidePlugin extends JavaPlugin {
    private PlayerListHud playerListHud;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // This bridge is the sole AuthMe boundary for the RPG suite. Register
        // it before every stateful guide listener so all modules see one
        // deterministic post-authentication lifecycle.
        getServer().getPluginManager().registerEvents(new AuthenticationReadyBridge(this), this);
        ProfileService profiles = Objects.requireNonNull(
                getServer().getServicesManager().load(ProfileService.class), "ProfileService");
        ActivityEconomyService economy = Objects.requireNonNull(
                getServer().getServicesManager().load(ActivityEconomyService.class), "ActivityEconomyService");
        DungeonRewardService dungeonRewards = Objects.requireNonNull(
                getServer().getServicesManager().load(DungeonRewardService.class), "DungeonRewardService");
        CommerceService commerce = Objects.requireNonNull(
                getServer().getServicesManager().load(CommerceService.class), "CommerceService");
        ChapterService chapters = Objects.requireNonNull(
                getServer().getServicesManager().load(ChapterService.class), "ChapterService");
        MessageService messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        CombatStateService combat = getServer().getServicesManager().load(CombatStateService.class);
        StarterKitService starterKit = new StarterKitService(this, messages);
        GuideTracker tracker = new GuideTracker(
                this, profiles, economy, dungeonRewards, messages, starterKit);
        GuideCommand command = new GuideCommand(
                this, profiles, economy, commerce, chapters, messages, tracker);
        getServer().getServicesManager().register(
                GuideMenuService.class, command::openSection, this, ServicePriority.Normal);
        getServer().getServicesManager().register(
                JourneyService.class, tracker, this, ServicePriority.Normal);
        PluginCommand menu = Objects.requireNonNull(getCommand("menu"), "menu command");
        PluginCommand tasks = Objects.requireNonNull(getCommand("tasks"), "tasks command");
        PluginCommand help = Objects.requireNonNull(getCommand("rpghelp"), "rpghelp command");
        menu.setExecutor(command);
        tasks.setExecutor(command);
        help.setExecutor(command);
        help.setTabCompleter(command);
        PluginCommand warehouse = Objects.requireNonNull(getCommand("warehouse"), "warehouse command");
        warehouse.setExecutor(new WarehouseCommand(this, messages, combat));
        PluginCommand visuals = Objects.requireNonNull(getCommand("visuals"), "visuals command");
        VisualPreferenceCommand visualCommand = new VisualPreferenceCommand(messages);
        visuals.setExecutor(visualCommand);
        visuals.setTabCompleter(visualCommand);
        PluginCommand hud = Objects.requireNonNull(getCommand("guidehud"), "guidehud command");
        GameplayHudCommand gameplayHudCommand = new GameplayHudCommand(this, messages);
        hud.setExecutor(gameplayHudCommand);
        hud.setTabCompleter(gameplayHudCommand);
        getServer().getPluginManager().registerEvents(command, this);
        getServer().getPluginManager().registerEvents(tracker, this);
        tracker.setMenuOpener(command::openCurrentObjective);
        getServer().getPluginManager().registerEvents(
                new WelcomeExperience(this, messages, command::openSection), this);
        getServer().getScheduler().runTaskTimer(this,
                () -> getServer().getOnlinePlayers().stream()
                        .filter(player -> tracker.isReady(player.getUniqueId()))
                        .forEach(tracker::evaluate), 100L, 200L);
        if (getConfig().getBoolean("tab-hud.enabled", true)) {
            playerListHud = new PlayerListHud(this, profiles, messages);
            getServer().getPluginManager().registerEvents(playerListHud, this);
            long interval = Math.clamp(getConfig().getLong("tab-hud.update-ticks", 100L), 100L, 1200L);
            getServer().getScheduler().runTaskTimer(this, playerListHud::updateAll, 20L, interval);
        }
    }

    @Override
    public void onDisable() {
        if (playerListHud != null) playerListHud.clear();
    }
}
