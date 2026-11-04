package cn.originalrpg.integrations;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import cn.originalrpg.api.model.SkillHudSnapshot;
import cn.originalrpg.api.model.SkillCooldownSnapshot;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.SkillHudService;
import cn.originalrpg.api.service.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

final class SivitaPlaceholderExpansion extends PlaceholderExpansion {
    private final RpgIntegrationsPlugin plugin;
    private final Map<UUID, RpgSnapshot> snapshots;
    private final Duration staleAfter;
    private final MessageService messages;

    SivitaPlaceholderExpansion(
            RpgIntegrationsPlugin plugin, Map<UUID, RpgSnapshot> snapshots, Duration staleAfter,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.snapshots = snapshots;
        this.staleAfter = staleAfter;
        this.messages = messages;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "sivita";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(",", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String normalized = params.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("hud_ready")) return hudReady(player);
        if (player == null) return HudPlaceholderValues.missingValue(normalized, HudRailSpec.slotCount());
        if (normalized.equals("hud_panel")) return hudPanel(player.getUniqueId());
        if (normalized.equals("hud_title")) {
            return localized(player.getUniqueId(), "betterhud.title", "Character");
        }
        if (normalized.equals("hud_mana_label")) {
            return localized(player.getUniqueId(), "betterhud.mana", "Mana");
        }
        if (normalized.equals("hud_controls")) {
            return localized(player.getUniqueId(), "betterhud.controls", "/skills");
        }
        if (normalized.equals("hud_cast_label")) {
            return localized(player.getUniqueId(), "betterhud.cast-label", "Cast");
        }
        if (normalized.equals("hud_cycle_label")) {
            return localized(player.getUniqueId(), "betterhud.cycle-label", "Cycle");
        }
        if (normalized.equals("hud_cast_glyph")) {
            return HudControlGlyphPolicy.glyph(messages, Bukkit.getPlayer(player.getUniqueId()), true);
        }
        if (normalized.equals("hud_cycle_glyph")) {
            return HudControlGlyphPolicy.glyph(messages, Bukkit.getPlayer(player.getUniqueId()), false);
        }
        if (normalized.equals("hud_class_glyph")) {
            RpgSnapshot snapshot = snapshots.get(player.getUniqueId());
            String classId = snapshot == null ? "unchosen"
                    : snapshot.classId().toLowerCase(java.util.Locale.ROOT);
            if (!java.util.Set.of("healer", "warrior", "assassin", "mage", "ranger", "freeform")
                    .contains(classId)) classId = "unchosen";
            return "<image:class_" + classId + ">";
        }
        if (normalized.startsWith("skill") || normalized.startsWith("mana")) {
            return skillValue(player.getUniqueId(), normalized);
        }
        RpgSnapshot snapshot = snapshots.get(player.getUniqueId());
        if (snapshot == null) return normalized.equals("status") ? "loading"
                : HudPlaceholderValues.missingValue(normalized, HudRailSpec.slotCount());
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "status" -> Duration.between(snapshot.refreshedAt(), Instant.now()).compareTo(staleAfter) > 0
                    ? "stale" : "ready";
            case "class" -> snapshot.classId();
            case "class_localized" -> localized(player.getUniqueId(), "classes." + snapshot.classId(),
                    snapshot.classId());
            case "branch" -> snapshot.branchId();
            case "ascension" -> Integer.toString(snapshot.ascension());
            case "level", "local_level" -> Integer.toString(snapshot.level());
            // 有效等级/阶段预算/累计经验统一走 ProgressionRules，避免此处再复制 asc*30+level。
            case "effective_level", "effective_level_cap", "experience", "experience_stage_budget",
                    "experience_stage_spent", "experience_stage_remaining", "experience_total" ->
                    ProgressionPlaceholders.value(snapshot, params.toLowerCase(java.util.Locale.ROOT));
            case "energy" -> Integer.toString(snapshot.energy());
            case "energy_max" -> Integer.toString(snapshot.energyCap());
            case "gold" -> snapshot.gold();
            case "merit" -> Long.toString(snapshot.merit());
            case "starlight" -> Long.toString(snapshot.starlight());
            case "cache_age_ms" -> Long.toString(Math.max(0L,
                    Duration.between(snapshot.refreshedAt(), Instant.now()).toMillis()));
            default -> null;
        };
    }

    private String hudReady(OfflinePlayer offlinePlayer) {
        if (offlinePlayer == null) return "0";
        Player player = offlinePlayer.getPlayer();
        if (player == null || !player.isOnline()) return "0";
        RpgSnapshot profile = snapshots.get(player.getUniqueId());
        SkillHudService skillHud = OptionalServices.enabled(plugin, SkillHudService.class);
        // The service checks the authenticated session. Its refresh timestamp
        // records learned-skill loading, so an idle player's skills must not
        // expire merely because that timestamp is old.
        boolean skillsReady = skillHud != null && skillHud.snapshot(player.getUniqueId()).isPresent();
        return HudReadinessPolicy.ready(player.isOnline(), player.getResourcePackStatus(),
                profile == null ? null : profile.refreshedAt(), skillsReady, staleAfter, Instant.now())
                ? "1" : "0";
    }

    private String skillValue(UUID playerId, String parameter) {
        SkillHudService skillHud = OptionalServices.enabled(plugin, SkillHudService.class);
        SkillHudSnapshot snapshot = skillHud == null ? null : skillHud.snapshot(playerId).orElse(null);
        return HudPlaceholderValues.skillValue(snapshot,
                snapshot == null ? java.util.List.of() : skillHud.slots(playerId),
                snapshot == null ? -1 : skillHud.selectedSlot(playerId),
                parameter, HudRailSpec.slotCount(), System.currentTimeMillis(),
                (key, fallback) -> localized(playerId, key, fallback));
    }

    private String slotValue(
            SkillHudService skillHud, UUID playerId, java.util.List<SkillCooldownSnapshot> slots,
            String parameter, long now
    ) {
        // Slot bounds come from the shared HUD layout, just like PAPI queries.
        return HudPlaceholderValues.slotValue(slots, skillHud.selectedSlot(playerId), parameter,
                HudRailSpec.slotCount(), now, (key, fallback) -> localized(playerId, key, fallback));
    }

    private String localized(UUID playerId, String key, String fallback) {
        Player player = Bukkit.getPlayer(playerId);
        if (messages == null) return fallback;
        String value = player == null ? messages.textForLocale(messages.defaultLocale(), key)
                : messages.text(player, key);
        return value.equals(key) ? fallback : value;
    }

    private String hudPanel(UUID playerId) {
        SkillHudService skillHud = OptionalServices.enabled(plugin, SkillHudService.class);
        RpgSnapshot profile = snapshots.get(playerId);
        SkillHudSnapshot skills = skillHud == null ? null : skillHud.snapshot(playerId).orElse(null);
        if (profile == null || skills == null) {
            return localized(playerId, "betterhud.loading", "Loading character…") + "\n—\n—";
        }
        long now = System.currentTimeMillis();
        java.util.List<SkillCooldownSnapshot> slots = skillHud.slots(playerId);
        java.util.List<String> lines = new java.util.ArrayList<>(3);
        lines.add(localized(playerId, "classes." + profile.classId(), profile.classId())
                + "  Lv." + profile.level() + "  ◆" + skills.mana() + "/" + skills.manaCap());
        lines.add(skillPair(skillHud, playerId, slots, now, 1, 2));
        lines.add(skillPair(skillHud, playerId, slots, now, 3, 4));
        return String.join("\n", lines);
    }

    private String skillPair(
            SkillHudService skillHud, UUID playerId, java.util.List<SkillCooldownSnapshot> slots,
            long now, int first, int second
    ) {
        return compactSkill(skillHud, playerId, slots, now, first) + "   "
                + compactSkill(skillHud, playerId, slots, now, second);
    }

    private String compactSkill(
            SkillHudService skillHud, UUID playerId, java.util.List<SkillCooldownSnapshot> slots,
            long now, int slot
    ) {
        String name = slotValue(skillHud, playerId, slots, "skill_" + slot + "_name", now);
        if (name == null || name.isBlank()) return slot + " —";
        long remaining = Long.parseLong(slotValue(
                skillHud, playerId, slots, "skill_" + slot + "_cooldown", now));
        return slot + " " + abbreviate(name, 4) + (remaining == 0L ? " ✓" : " " + remaining + "s");
    }

    private static String abbreviate(String value, int maximumCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maximumCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }
}
