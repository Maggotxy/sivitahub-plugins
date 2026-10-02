package cn.originalrpg.api.ui;

import cn.originalrpg.api.model.Archetype;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudGlyphsTest {
    @Test
    void everyClassAndConcreteSkillOwnsADistinctGlyph() {
        List<Character> classes = List.of(
                Archetype.HEALER, Archetype.WARRIOR, Archetype.ASSASSIN,
                Archetype.MAGE, Archetype.RANGER, Archetype.FREEFORM).stream()
                .map(HudGlyphs::classGlyph).toList();
        List<String> skills = List.of(
                "heal", "war-cry", "shadow-step", "fireball", "power-shot", "improvisation",
                "renewal", "sanctuary", "fate-seal", "shield-wall", "fortress", "blood-rage",
                "venom-blade", "smoke-bomb", "toxic-burst", "frost-nova", "meteor", "mana-surge",
                "volley", "piercing-shot", "beast-call", "second-wind", "battle-trance", "arcane-edge",
                "life-thread", "omen-reversal", "soul-resonance", "rallying-standard", "ruin-charge", "unbroken-line",
                "void-cut", "plague-bloom", "vanished-horizon", "ash-domain", "time-lock", "axiomatic-break",
                "gale-step", "pack-hunt", "horizon-arrow", "adaptive-arsenal", "rune-reversal", "open-form");
        List<Character> skillGlyphs = skills.stream()
                .map(id -> HudGlyphs.skillGlyph(id).orElseThrow()).toList();
        HashSet<Character> all = new HashSet<>(classes);
        all.addAll(skillGlyphs);
        assertEquals(48, all.size());
        assertTrue(HudGlyphs.skillGlyph("not-a-real-skill").isEmpty());
    }
}
