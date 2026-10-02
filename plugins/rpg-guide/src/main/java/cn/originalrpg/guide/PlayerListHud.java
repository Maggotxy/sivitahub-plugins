package cn.originalrpg.guide;

import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.SkillCooldownSnapshot;
import cn.originalrpg.api.model.SkillHudSnapshot;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.SkillHudService;
import cn.originalrpg.api.ui.HudGlyphs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Low-frequency player-list status: class/level in the header and at most two
 * skill cooldowns in the footer. Navigation, progression and server health
 * belong to their own surfaces and are intentionally not repeated here.
 */
final class PlayerListHud implements Listener {
    private final JavaPlugin plugin;
    private final ProfileService profiles;
    private final MessageService messages;
    private final Map<UUID, String> lastSignatures = new HashMap<>();
    private final boolean useResourceGlyphs;
    private final int maximumSkills;

    PlayerListHud(
            JavaPlugin plugin,
            ProfileService profiles,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.messages = messages;
        this.useResourceGlyphs = plugin.getConfig().getBoolean("tab-hud.use-resource-glyphs", false);
        this.maximumSkills = TabHudPolicy.maximumSkills(
                plugin.getConfig().getInt("tab-hud.max-skills", TabHudPolicy.MAXIMUM_SKILLS));
    }

    void updateAll() {
        SkillHudService skills = OptionalServices.enabled(plugin, SkillHudService.class);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (profiles.cached(player.getUniqueId()).isPresent()) update(player, skills);
        }
    }

    void clear() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
        lastSignatures.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastSignatures.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        Player player = event.player();
        lastSignatures.remove(player.getUniqueId());
        player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        // The signature includes glyph availability, so intermediate pack
        // states remain no-ops while a successful load upgrades immediately.
        update(event.getPlayer());
    }

    private void update(Player player) {
        update(player, OptionalServices.enabled(plugin, SkillHudService.class));
    }

    private void update(Player player, SkillHudService skills) {
        PlayerProfile profile = profiles.cached(player.getUniqueId()).orElse(null);
        if (profile == null) return;

        String locale = messages.locale(player);
        long now = System.currentTimeMillis();
        SkillHudSnapshot skillSnapshot = skills == null ? null : skills.snapshot(player.getUniqueId()).orElse(null);
        List<SkillCooldownSnapshot> visibleSkills = skillSnapshot == null ? List.of()
                : TabHudPolicy.visibleSkills(skillSnapshot.learnedSkills(), now, maximumSkills);
        String skillSignature = skillSnapshot == null ? "loading" : visibleSkills.stream()
                .map(entry -> entry.skillId() + ":" + entry.remainingSeconds(now))
                .collect(java.util.stream.Collectors.joining(","));
        boolean customVisuals = ResourcePackUiPolicy.customVisuals(useResourceGlyphs, player);
        String signature = String.join("|",
                locale,
                profile.archetype().name(),
                Integer.toString(profile.ascension()),
                Integer.toString(profile.level()),
                skillSignature,
                Boolean.toString(customVisuals));
        if (signature.equals(lastSignatures.put(player.getUniqueId(), signature))) return;

        String archetype = messages.text(player,
                "classes." + profile.archetype().name().toLowerCase(Locale.ROOT));
        Component profileLine = Component.text(messages.text(player, "tab-hud.profile", Map.of(
                "class", archetype,
                "ascension", profile.ascension(),
                "level", profile.level()
        )), NamedTextColor.WHITE);
        Component header = customVisuals
                ? HudGlyphs.withIcon(HudGlyphs.HUB, profileLine)
                : Component.text("◆ ", NamedTextColor.AQUA).append(profileLine);
        player.sendPlayerListHeaderAndFooter(
                header, skillLine(player, skillSnapshot, visibleSkills, now, customVisuals));
    }

    private Component skillLine(
            Player player,
            SkillHudSnapshot snapshot,
            List<SkillCooldownSnapshot> visible,
            long now,
            boolean customVisuals
    ) {
        Component line;
        if (snapshot == null) {
            line = Component.text(messages.text(player, "tab-hud.skills-loading"), NamedTextColor.DARK_GRAY);
        } else if (snapshot.learnedSkills().isEmpty()) {
            line = Component.text(messages.text(player, "tab-hud.skills-empty"), NamedTextColor.GRAY);
        } else {
            line = Component.text(messages.text(player, "tab-hud.skills-prefix") + " ",
                    NamedTextColor.LIGHT_PURPLE);
            for (int index = 0; index < visible.size(); index++) {
                SkillCooldownSnapshot entry = visible.get(index);
                if (index > 0) line = line.append(Component.text(" · ", NamedTextColor.DARK_GRAY));
                long remaining = entry.remainingSeconds(now);
                line = line.append(Component.text(messages.text(player, "skills.names." + entry.skillId()) + " ",
                                NamedTextColor.WHITE))
                        .append(Component.text(remaining == 0L
                                        ? messages.text(player, "tab-hud.skill-ready")
                                        : messages.text(player, "tab-hud.skill-cooldown", Map.of("seconds", remaining)),
                                remaining == 0L ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            int hidden = snapshot.learnedSkills().size() - visible.size();
            if (hidden > 0) line = line.append(Component.text(messages.text(player,
                    "tab-hud.skill-more", Map.of("count", hidden)), NamedTextColor.GRAY));
        }
        return customVisuals ? HudGlyphs.withIcon(HudGlyphs.SKILL, line) : line;
    }
}
