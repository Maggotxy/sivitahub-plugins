package cn.originalrpg.story;

import cn.originalrpg.api.service.EconomyService;
import cn.originalrpg.api.service.GuideMenuService;
import cn.originalrpg.api.service.JourneyService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import org.betonquest.betonquest.api.integration.IntegrationService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class RpgStoryPlugin extends JavaPlugin {
    private final StoryIntegrationBridge integrationBridge = new StoryIntegrationBridge();
    private StoryActorManager actor;
    private EscortManager escorts;

    @Override
    public void onLoad() {
        IntegrationService integration = requireService(IntegrationService.class);
        integration.withPolicies().builder()
                .enable(api -> {
                    integrationBridge.api(api);
                    api.actions().registry().register(
                            "sivita_story_begin", new StoryBeginActionFactory(integrationBridge));
                    api.actions().registry().register(
                            "sivita_story_claim", new StoryClaimActionFactory(integrationBridge));
                    api.conditions().registry().register(
                            "sivita_story_eligible", new StoryEligibilityConditionFactory(integrationBridge));
                    api.conditions().registry().register(
                            "sivita_story_journey", new StoryJourneyConditionFactory(integrationBridge));
                    api.actions().registry().register(
                            "sivita_story_open_journey", new StoryOpenJourneyActionFactory(integrationBridge));
                    getLogger().info("Registered BetonQuest components: sivita_story_begin, "
                            + "sivita_story_claim, sivita_story_eligible, sivita_story_journey, "
                            + "sivita_story_open_journey.");
                })
                .disable(integrationBridge::clearApi)
                .integrate(this);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getInt("config-version", 0) != 1) {
            throw new IllegalStateException("Unsupported RpgStory config-version");
        }
        ProfileService profiles = requireService(ProfileService.class);
        EconomyService economy = requireService(EconomyService.class);
        MessageService messages = requireService(MessageService.class);
        JourneyService journeys = requireService(JourneyService.class);
        GuideMenuService guideMenus = requireService(GuideMenuService.class);

        actor = new StoryActorManager(this);
        actor.ensureRegistryEntry();
        escorts = new EscortManager(this, profiles, economy, messages, journeys);
        integrationBridge.attach(escorts, journeys, guideMenus);
        getServer().getPluginManager().registerEvents(escorts, this);

        StoryCommand command = new StoryCommand(this, escorts, actor, messages, integrationBridge);
        register("story", command);
        register("storyadmin", command);
        getServer().getPluginManager().registerEvents(new StoryListener(this, escorts), this);
        escorts.startTicker();
        getServer().getScheduler().runTaskLater(this,
                () -> getLogger().info("Reserved story actor ready=" + actor.spawnAtHub() + " " + actor.status()), 80L);
        getLogger().info("System-driven story bridge enabled: Citizens actors + BetonQuest state + RPG ledger rewards.");
    }

    @Override
    public void onDisable() {
        if (escorts != null) escorts.close();
    }

    private void register(String name, StoryCommand handler) {
        PluginCommand command = Objects.requireNonNull(getCommand(name), name + " command");
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    private <T> T requireService(Class<T> type) {
        return Objects.requireNonNull(getServer().getServicesManager().load(type), type.getSimpleName());
    }
}
