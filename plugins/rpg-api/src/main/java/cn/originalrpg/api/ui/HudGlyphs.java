package cn.originalrpg.api.ui;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import cn.originalrpg.api.model.Archetype;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/** Resource-pack glyphs rendered as text, avoiding extra entities and particle packets. */
public final class HudGlyphs {
    public static final char BLOOD_MOON = '\uE000';
    public static final char MANA = '\uE001';
    public static final char CRITICAL = '\uE002';
    public static final char EXECUTE = '\uE003';
    public static final char FORGE = '\uE004';
    public static final char PROTECTION = '\uE005';
    public static final char RARE_DROP = '\uE006';
    public static final char SKILL = '\uE007';
    public static final char HUB = '\uE008';
    public static final char OBJECTIVE = '\uE009';
    public static final char NPC = '\uE00A';
    private static final char CLASS_START = '\uE020';
    private static final char SKILL_START = '\uE026';
    private static final java.util.List<String> SKILLS = java.util.List.of(
            "heal", "war-cry", "shadow-step", "fireball", "power-shot", "improvisation",
            "renewal", "sanctuary", "fate-seal", "shield-wall", "fortress", "blood-rage",
            "venom-blade", "smoke-bomb", "toxic-burst", "frost-nova", "meteor", "mana-surge",
            "volley", "piercing-shot", "beast-call", "second-wind", "battle-trance", "arcane-edge",
            "life-thread", "omen-reversal", "soul-resonance", "rallying-standard", "ruin-charge", "unbroken-line",
            "void-cut", "plague-bloom", "vanished-horizon", "ash-domain", "time-lock", "axiomatic-break",
            "gale-step", "pack-hunt", "horizon-arrow", "adaptive-arsenal", "rune-reversal", "open-form");
    public static final Key FONT = Key.key("originalrpg", "hud");

    private HudGlyphs() {
    }

    public static Component icon(char glyph) {
        return Component.text(String.valueOf(glyph)).font(FONT);
    }

    public static char classGlyph(Archetype archetype) {
        if (archetype == null || archetype == Archetype.UNCHOSEN) return HUB;
        return (char) (CLASS_START + switch (archetype) {
            case HEALER -> 0;
            case WARRIOR -> 1;
            case ASSASSIN -> 2;
            case MAGE -> 3;
            case RANGER -> 4;
            case FREEFORM -> 5;
            case UNCHOSEN -> throw new IllegalStateException("handled above");
        });
    }

    public static java.util.Optional<Character> skillGlyph(String skillId) {
        int index = SKILLS.indexOf(skillId);
        return index < 0 ? java.util.Optional.empty()
                : java.util.Optional.of((char) (SKILL_START + index));
    }

    /** True only after this client has confirmed that the server pack is active. */
    public static boolean available(Player player) {
        return player != null && available(player.getResourcePackStatus());
    }

    /** Status-only overload keeps the fail-closed policy directly testable. */
    public static boolean available(PlayerResourcePackStatusEvent.Status status) {
        return status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
    }

    /**
     * Adds a glyph as a sibling instead of a parent. This prevents normal text
     * from inheriting the bitmap-only HUD font and becoming missing glyphs.
     */
    public static Component withIcon(char glyph, Component content) {
        return Component.empty().append(icon(glyph)).append(Component.space()).append(content);
    }
}
