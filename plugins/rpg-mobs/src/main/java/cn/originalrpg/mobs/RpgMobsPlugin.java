package cn.originalrpg.mobs;

import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.MobAppearanceService;
import cn.originalrpg.api.service.ChapterService;
import cn.originalrpg.api.service.MarketService;
import cn.originalrpg.mobs.listener.AdaptiveMobListener;
import cn.originalrpg.mobs.ability.MobAbilityController;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;

import java.util.Objects;

public final class RpgMobsPlugin extends JavaPlugin {
    private ExperienceWriteBuffer experienceBuffer;
    private BetterModelMobAppearanceService appearances;
    private LegacyMobMaskMigration legacyMaskMigration;
    private MobAbilityController abilities;
    private IllegalAdaptiveMobCleanup illegalMobCleanup;
    private DelayedMobRewardQueue delayedRewards;
    private MobGuideCommand mobGuide;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ProfileService profiles = Objects.requireNonNull(
                getServer().getServicesManager().load(ProfileService.class), "ProfileService");
        MessageService messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        ChapterService chapters = Objects.requireNonNull(
                getServer().getServicesManager().load(ChapterService.class), "ChapterService");
        MarketService market = Objects.requireNonNull(
                getServer().getServicesManager().load(MarketService.class), "MarketService");
        experienceBuffer = new ExperienceWriteBuffer(this, profiles, messages);
        appearances = new BetterModelMobAppearanceService(this);
        getServer().getServicesManager().register(MobAppearanceService.class,
                appearances, this, ServicePriority.Normal);
        legacyMaskMigration = new LegacyMobMaskMigration(this, appearances);
        getServer().getPluginManager().registerEvents(legacyMaskMigration, this);
        legacyMaskMigration.start();
        illegalMobCleanup = new IllegalAdaptiveMobCleanup(this, chapters, appearances,
                Math.clamp(getConfig().getInt("adaptive.maximum-positive-offset", 6), 0, 100));
        getServer().getPluginManager().registerEvents(illegalMobCleanup, this);
        illegalMobCleanup.start();
        abilities = new MobAbilityController(this, messages);
        getServer().getPluginManager().registerEvents(abilities, this);
        abilities.start();
        AdaptiveMobListener mobListener = new AdaptiveMobListener(this, profiles, chapters, messages,
                experienceBuffer, appearances, abilities);
        delayedRewards = new DelayedMobRewardQueue(this, profiles, market, experienceBuffer, mobListener);
        mobListener.setDelayedRewards(delayedRewards);
        getServer().getPluginManager().registerEvents(mobListener, this);
        PluginCommand guide = Objects.requireNonNull(getCommand("mobguide"), "mobguide command");
        mobGuide = new MobGuideCommand(this, profiles, messages, abilities);
        guide.setExecutor(mobGuide);
        getServer().getPluginManager().registerEvents(mobGuide, this);
        experienceBuffer.start();
        delayedRewards.start();
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (delayedRewards != null) delayedRewards.close();
        if (abilities != null) abilities.close();
        if (legacyMaskMigration != null) legacyMaskMigration.close();
        if (illegalMobCleanup != null) illegalMobCleanup.close();
        if (appearances != null) appearances.close();
        if (experienceBuffer != null) experienceBuffer.shutdown();
        if (mobGuide != null) mobGuide.close();
    }
}
