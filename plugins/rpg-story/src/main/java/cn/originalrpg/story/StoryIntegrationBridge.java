package cn.originalrpg.story;

import cn.originalrpg.api.model.JourneyProgress;
import cn.originalrpg.api.model.JourneyStep;
import cn.originalrpg.api.service.GuideMenuService;
import cn.originalrpg.api.service.JourneyService;
import org.betonquest.betonquest.api.BetonQuestApi;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;

final class StoryIntegrationBridge {
    private BetonQuestApi api;
    private EscortManager escorts;
    private JourneyService journeys;
    private GuideMenuService guideMenus;

    void api(BetonQuestApi value) {
        api = Objects.requireNonNull(value, "BetonQuestApi");
        connect();
    }

    void attach(EscortManager escortManager, JourneyService journeyService, GuideMenuService menuService) {
        escorts = Objects.requireNonNull(escortManager, "EscortManager");
        journeys = Objects.requireNonNull(journeyService, "JourneyService");
        guideMenus = Objects.requireNonNull(menuService, "GuideMenuService");
        connect();
    }

    void clearApi() {
        api = null;
    }

    boolean eligible(UUID playerId, StoryQuest quest) {
        return manager().eligible(playerId, quest);
    }

    void start(Player player, StoryQuest quest) {
        manager().start(player, quest);
    }

    void claim(Player player, StoryQuest quest) {
        manager().claim(player, quest);
    }

    boolean journeyAt(UUID playerId, JourneyStep step) {
        JourneyProgress progress = journeyService().progress(playerId);
        return progress.nextStep().map(step::equals).orElse(false);
    }

    boolean journeyComplete(UUID playerId) {
        return journeyService().progress(playerId).complete();
    }

    void openJourney(Player player) {
        if (!manager().isAuthenticated(player)) return;
        journeyService().refresh(player);
        menuService().open(player, GuideMenuService.Section.ONBOARDING);
    }

    void openClass(Player player) {
        if (!manager().isAuthenticated(player)) return;
        menuService().open(player, GuideMenuService.Section.CLASS);
    }

    void openTasks(Player player) {
        if (!manager().isAuthenticated(player)) return;
        menuService().open(player, GuideMenuService.Section.QUESTS);
    }

    private void connect() {
        if (api != null && escorts != null) escorts.setBetonQuest(api);
    }

    private EscortManager manager() {
        return Objects.requireNonNull(escorts, "RpgStory runtime is not enabled");
    }

    private JourneyService journeyService() {
        return Objects.requireNonNull(journeys, "JourneyService is not attached");
    }

    private GuideMenuService menuService() {
        return Objects.requireNonNull(guideMenus, "GuideMenuService is not attached");
    }
}
