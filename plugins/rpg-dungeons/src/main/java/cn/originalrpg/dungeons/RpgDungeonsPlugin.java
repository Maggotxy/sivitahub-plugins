package cn.originalrpg.dungeons;

import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.DungeonEntryService;
import cn.originalrpg.api.service.DungeonRewardService;
import cn.originalrpg.api.service.PartyService;
import cn.originalrpg.api.service.ChapterService;
import cn.originalrpg.api.service.SeasonExpeditionService;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class RpgDungeonsPlugin extends JavaPlugin {
    private DungeonManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigurationSection configuredDungeons = Objects.requireNonNull(
                getConfig().getConfigurationSection("dungeons"), "dungeons configuration");
        DungeonRewardRules.requireConfiguredIds(configuredDungeons.getKeys(false));
        // 世界名由 DungeonManager 运行时生成；残留的 dungeons.*.world 键只会误导运维，
        // 因此这里直接拒绝启动（G-108）。
        DungeonConfigKeys.requireNoReservedWorldKeys(getConfig().getValues(false));
        ProfileService profiles = Objects.requireNonNull(
                getServer().getServicesManager().load(ProfileService.class), "ProfileService");
        MessageService messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        DungeonEntryService entries = Objects.requireNonNull(
                getServer().getServicesManager().load(DungeonEntryService.class), "DungeonEntryService");
        DungeonRewardService rewards = Objects.requireNonNull(
                getServer().getServicesManager().load(DungeonRewardService.class), "DungeonRewardService");
        ChapterService chapters = Objects.requireNonNull(
                getServer().getServicesManager().load(ChapterService.class), "ChapterService");
        SeasonExpeditionService seasonService = Objects.requireNonNull(
                getServer().getServicesManager().load(SeasonExpeditionService.class), "SeasonExpeditionService");
        SeasonExpeditionController seasonExpeditions = new SeasonExpeditionController(
                this, seasonService, chapters, messages);
        manager = new DungeonManager(this, profiles, messages, rewards, seasonExpeditions);
        getServer().getPluginManager().registerEvents(manager, this);
        PluginCommand dungeon = Objects.requireNonNull(getCommand("dungeon"), "dungeon command");
        DungeonPartyService parties = new DungeonPartyService(
                getConfig().getInt("party.maximum-size", 4),
                getConfig().getLong("party.invitation-seconds", 60L));
        DungeonCommand command = new DungeonCommand(
                this, profiles, entries, manager, messages, parties, seasonExpeditions);
        getServer().getServicesManager().register(PartyService.class,
                new DungeonPartyRelationshipService(parties, manager), this, ServicePriority.Normal);
        manager.setExitHandler(player -> command.onCommand(player, dungeon, "dungeon", new String[]{"leave"}));
        dungeon.setExecutor(command);
        dungeon.setTabCompleter(command);
        getServer().getPluginManager().registerEvents(command, this);
        manager.start();
        // Run after the plugin-enable pass: Paper has discovered stale 26.2
        // dimension folders by now, while the ingress is still closed and no
        // player can be inside a disposable instance.
        getServer().getScheduler().runTask(this, manager::cleanupStartupInstances);
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (manager != null) manager.shutdown();
    }
}
