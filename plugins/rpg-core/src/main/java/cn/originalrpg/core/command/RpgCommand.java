package cn.originalrpg.core.command;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.EconomyProgress;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.ChapterState;
import cn.originalrpg.api.rules.ProgressionRules;
import cn.originalrpg.api.service.ActivityEconomyService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
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

public final class RpgCommand implements CommandExecutor, Listener {
    private final Plugin plugin;
    private final ProfileService profiles;
    private final ActivityEconomyService economy;
    private final ChapterService chapters;
    private final MessageService messages;

    public RpgCommand(
            Plugin plugin, ProfileService profiles, ActivityEconomyService economy,
            ChapterService chapters, MessageService messages
    ) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.economy = economy;
        this.chapters = chapters;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        profiles.get(player.getUniqueId()).thenCombine(economy.progress(player.getUniqueId()), ProfileAndEconomy::new)
                .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        player.sendMessage(Component.text(messages.text(player, "profile.load-error"), NamedTextColor.RED));
                        plugin.getLogger().warning("Profile command failed: " + error.getMessage());
                        return;
                    }
                    showProfile(player, result.profile(), result.economy(), chapters.current());
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

    private void showProfile(
            Player player, PlayerProfile profile, EconomyProgress economy, ChapterState chapter
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
            player.sendMessage(Component.text(messages.text(player, "profile.experience", Map.of(
                    "experience", profile.experience(),
                    "needed", needed,
                    "remaining", Math.max(0L, needed - profile.experience()))), NamedTextColor.AQUA));
        }
        player.sendMessage(Component.text(messages.text(player, "profile.chapter", Map.of(
                "season", chapter.seasonId(),
                "chapter", messages.text(player, "chapters." + chapter.chapterId() + ".name"),
                "maximum", chapter.maximumOpenAscension())), NamedTextColor.LIGHT_PURPLE));
        player.sendMessage(Component.text(messages.text(player, "profile.energy", Map.of(
                "energy", profile.energy(), "cap", profile.energyCap())), NamedTextColor.AQUA));
        player.sendMessage(Component.text(messages.text(player, "profile.gold-merit", Map.of(
                "gold", economy.gold().toPlainString(), "merit", economy.merit())), NamedTextColor.YELLOW));
        player.sendMessage(Component.text(messages.text(player, "profile.points", Map.of(
                "attributes", profile.unspentAttributePoints(), "skills", profile.skillPoints())), NamedTextColor.GREEN));
        player.sendMessage(Component.text(messages.text(player, "profile.attributes", Map.of(
                "defense", profile.attribute(CoreAttribute.DEFENSE),
                "strength", profile.attribute(CoreAttribute.STRENGTH),
                "agility", profile.attribute(CoreAttribute.AGILITY),
                "intelligence", profile.attribute(CoreAttribute.INTELLIGENCE),
                "stamina", profile.attribute(CoreAttribute.STAMINA))), NamedTextColor.GRAY));
        player.sendMessage(Component.text(messages.text(player, "profile.help"), NamedTextColor.AQUA));
    }

    private record ProfileAndEconomy(PlayerProfile profile, EconomyProgress economy) {
    }
}
