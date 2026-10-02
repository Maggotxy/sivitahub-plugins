package cn.originalrpg.core.command;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.EconomyProgress;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.ChapterState;
import cn.originalrpg.api.rules.ProgressionRules;
import cn.originalrpg.api.rules.SeasonSettlementState;
import cn.originalrpg.api.service.ActivityEconomyService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.SeasonMeritWalletService;
import cn.originalrpg.api.service.SeasonSettlementService;
import cn.originalrpg.api.service.ChapterService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class RpgCommand implements CommandExecutor, Listener {
    private final Plugin plugin;
    private final ProfileService profiles;
    private final ActivityEconomyService economy;
    private final ChapterService chapters;
    private final MessageService messages;
    private final SeasonMeritWalletService seasonMerits;
    private final SeasonSettlementService seasonSettlements;

    public RpgCommand(
            Plugin plugin, ProfileService profiles, ActivityEconomyService economy,
            ChapterService chapters, MessageService messages, SeasonMeritWalletService seasonMerits,
            SeasonSettlementService seasonSettlements
    ) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.economy = economy;
        this.chapters = chapters;
        this.messages = messages;
        this.seasonMerits = seasonMerits;
        this.seasonSettlements = seasonSettlements;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        ChapterState chapter = chapters.current();
        profiles.get(player.getUniqueId())
                .thenCombine(economy.progress(player.getUniqueId()), ProfileEconomy::new)
                .thenCombine(seasonMerits.seasonMerit(player.getUniqueId()), ProfileEconomySeason::new)
                .thenCombine(seasonState(chapter.seasonId()), ProfileEconomySeasonState::new)
                .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        player.sendMessage(Component.text(messages.text(player, "profile.load-error"), NamedTextColor.RED));
                        plugin.getLogger().warning("Profile command failed: " + error.getMessage());
                        return;
                    }
                    showProfile(player, result.profile().profile().profile(),
                            result.profile().profile().economy(), chapter,
                            result.profile().seasonMerit(), result.settlementState());
                }));
        return true;
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        UUID playerId = event.player().getUniqueId();
        profiles.get(playerId).exceptionally(error -> {
            plugin.getLogger().warning("Profile preload failed for " + playerId + ": " + error.getMessage());
            return null;
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player current = Bukkit.getPlayer(playerId);
            if (current == null || !current.isOnline()) profiles.evict(playerId);
        }, 200L);
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        // /logout keeps the Bukkit Player online, so the delayed disconnect
        // eviction path above cannot represent this transition.
        profiles.evict(event.player().getUniqueId());
    }

    /**
     * 换季状态对玩家只读展示：把 C-010 九态从「只有枚举」变成玩家能看到的实际状态。
     * 读不到时降级为 DRAFT，而不是让整条 /rpg 失败。
     */
    private CompletionStage<SeasonSettlementState> seasonState(String seasonId) {
        return seasonSettlements.state(seasonId)
                .exceptionally(error -> SeasonSettlementState.DRAFT);
    }

    private void showProfile(
            Player player, PlayerProfile profile, EconomyProgress economy, ChapterState chapter,
            long seasonMerit, SeasonSettlementState settlementState
    ) {
        player.sendMessage(Component.text(messages.text(player, "profile.title"), NamedTextColor.GOLD));
        player.sendMessage(Component.text(messages.text(player, "profile.class", Map.of(
                "class", messages.text(player, "classes." + profile.archetype().name().toLowerCase(Locale.ROOT)))), NamedTextColor.WHITE));
        player.sendMessage(Component.text(messages.text(player, "profile.stage", Map.of(
                "ascension", profile.ascension(), "level", profile.level())), NamedTextColor.WHITE));
        if (profile.level() >= ProgressionRules.levelCap(profile.ascension())) {
            player.sendMessage(Component.text(messages.text(player, "profile.experience-cap"), NamedTextColor.GREEN));
        } else {
            long needed = ProgressionRules.experienceToNextLevel(profile.ascension(), profile.level());
            // 阶段总预算与累计经验都取自 ProgressionRules，与 grantExperience 的升级循环同源。
            long stageBudget = ProgressionRules.totalExperienceForAscension(profile.ascension());
            long stageSpent = ProgressionRules.stageExperienceSpent(
                    profile.ascension(), profile.level(), profile.experience());
            player.sendMessage(Component.text(messages.text(player, "profile.experience", Map.of(
                    "experience", profile.experience(),
                    "needed", needed,
                    "remaining", Math.max(0L, needed - profile.experience()),
                    "stage_spent", stageSpent,
                    "stage_budget", stageBudget,
                    "stage_remaining", ProgressionRules.stageExperienceRemaining(
                            profile.ascension(), profile.level(), profile.experience()),
                    "effective_total", ProgressionRules.totalExperienceToEffective(profile.effectiveLevel()))),
                    NamedTextColor.AQUA));
        }
        player.sendMessage(Component.text(messages.text(player, "profile.chapter", Map.of(
                "season", chapter.seasonId(),
                "chapter", messages.text(player, "chapters." + chapter.chapterId() + ".name"),
                "maximum", chapter.maximumOpenAscension())), NamedTextColor.LIGHT_PURPLE));
        player.sendMessage(Component.text(messages.text(player, "profile.energy", Map.of(
                "energy", profile.energy(), "cap", profile.energyCap())), NamedTextColor.AQUA));
        player.sendMessage(Component.text(messages.text(player, "profile.gold-merit", Map.of(
                "gold", economy.gold().toPlainString(), "merit", economy.merit(),
                "season", chapter.seasonId(), "season-merit", seasonMerit)), NamedTextColor.YELLOW));
        player.sendMessage(Component.text(messages.text(player, "profile.points", Map.of(
                "attributes", profile.unspentAttributePoints(), "skills", profile.skillPoints())), NamedTextColor.GREEN));
        player.sendMessage(Component.text(messages.text(player, "profile.attributes", Map.of(
                "defense", profile.attribute(CoreAttribute.DEFENSE),
                "strength", profile.attribute(CoreAttribute.STRENGTH),
                "agility", profile.attribute(CoreAttribute.AGILITY),
                "intelligence", profile.attribute(CoreAttribute.INTELLIGENCE),
                "stamina", profile.attribute(CoreAttribute.STAMINA))), NamedTextColor.GRAY));
        player.sendMessage(Component.text(messages.text(player, "profile.season-settlement", Map.of(
                "state", settlementState.name())), NamedTextColor.GRAY));
        player.sendMessage(Component.text(messages.text(player, "profile.help"), NamedTextColor.AQUA));
    }

    private record ProfileEconomy(PlayerProfile profile, EconomyProgress economy) {
    }

    private record ProfileEconomySeason(ProfileEconomy profile, long seasonMerit) {
    }

    private record ProfileEconomySeasonState(ProfileEconomySeason profile, SeasonSettlementState settlementState) {
    }
}
