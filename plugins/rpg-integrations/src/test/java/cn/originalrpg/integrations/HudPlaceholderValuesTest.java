package cn.originalrpg.integrations;

import cn.originalrpg.api.model.SkillCooldownSnapshot;
import cn.originalrpg.api.model.SkillHudSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class HudPlaceholderValuesTest {
    private static final int SLOTS = 4;
    private static final long NOW = 10_000L;
    private static final BiFunction<String, String, String> TEXT = (key, fallback) -> fallback;

    @Test
    void everyPublicNumericPlaceholderRemainsParseableBeforePlayerDataLoads() {
        List<String> keys = new ArrayList<>(List.of("hud_ready", "mana", "mana_max", "mana_percent",
                "skills_count", "skills_ready", "skill_selected", "ascension", "level", "local_level",
                "effective_level", "effective_level_cap", "experience", "experience_stage_budget",
                "experience_stage_spent", "experience_stage_remaining", "experience_total", "energy",
                "energy_max", "gold", "merit", "starlight", "cache_age_ms"));
        for (int slot = 1; slot <= SLOTS; slot++) {
            keys.add("skill_" + slot + "_cooldown");
            keys.add("skill_" + slot + "_selected");
        }
        for (String key : keys) {
            double value = assertDoesNotThrow(() -> Double.parseDouble(
                    HudPlaceholderValues.missingValue(key, SLOTS)), key);
            assertTrue(Double.isFinite(value) && value >= 0, key);
        }
        assertEquals(0.0, Double.parseDouble(value(null, List.of(), -1, "mana"))
                / Double.parseDouble(value(null, List.of(), -1, "mana_max")));
    }

    @Test
    void zeroManaCapAlsoKeepsRenderedDenominatorPositiveWithoutChangingSnapshot() {
        SkillHudSnapshot snapshot = new SkillHudSnapshot(List.of(), 0, 0, 0);
        assertEquals("1", value(snapshot, List.of(), 0, "mana_max"));
        assertEquals("0", value(snapshot, List.of(), 0, "mana_percent"));
        assertEquals("0", value(snapshot, List.of(), 0, "skill_selected"));
        assertEquals(0, snapshot.manaCap());
    }

    @Test
    void emptyAndMissingSlotsStayNumericWhileTextAndGlyphsRemainBlank() {
        SkillHudSnapshot snapshot = new SkillHudSnapshot(List.of(), 2, 10, NOW);
        for (List<SkillCooldownSnapshot> slots : List.<List<SkillCooldownSnapshot>>of(
                List.of(), List.of(SkillCooldownSnapshot.empty()))) {
            for (String key : List.of("cooldown", "selected")) {
                assertEquals("0", value(snapshot, slots, 0, "skill_1_" + key));
            }
            for (String key : List.of("id", "timer", "glyph")) {
                assertEquals("", value(snapshot, slots, 0, "skill_1_" + key));
                assertEquals("", value(null, slots, 0, "skill_1_" + key));
            }
            assertEquals("0", value(snapshot, slots, 0, "skill_selected"));
            assertEquals("Empty", value(snapshot, slots, 0, "skill_1_status"));
        }
    }

    @Test
    void populatedSlotRestoresItsRealCooldownSelectionAndGlyphAfterLoading() {
        SkillCooldownSnapshot cast = new SkillCooldownSnapshot("fireball", NOW + 1501);
        List<SkillCooldownSnapshot> slots = List.of(SkillCooldownSnapshot.empty(), cast);
        SkillHudSnapshot snapshot = new SkillHudSnapshot(List.of(cast), 6, 12, NOW);
        assertEquals("50", value(snapshot, slots, 1, "mana_percent"));
        assertEquals("2", value(snapshot, slots, 1, "skill_selected"));
        assertEquals("2", value(snapshot, slots, 1, "skill_2_cooldown"));
        assertEquals("2", value(snapshot, slots, 1, "skill_2_timer"));
        assertEquals("1", value(snapshot, slots, 1, "skill_2_selected"));
        assertEquals("<image:skill_fireball>", value(snapshot, slots, 1, "skill_2_glyph"));
        assertEquals("0", value(snapshot, slots, 2, "skill_selected"));
        assertEquals("0", value(snapshot, slots, -1, "skill_selected"));
        assertEquals("0", HudPlaceholderValues.slotValue(slots, 1,
                "skill_2_cooldown", SLOTS, NOW + 2000, TEXT));
        assertEquals("", HudPlaceholderValues.slotValue(slots, 1,
                "skill_2_timer", SLOTS, NOW + 2000, TEXT));
    }

    @Test
    void invalidSlotIdentifiersAreStillUnknownRatherThanAddressingAnotherSlot() {
        SkillHudSnapshot snapshot = new SkillHudSnapshot(List.of(), 0, 10, NOW);
        for (String key : List.of("skill_0_cooldown", "skill_5_cooldown", "skill_1_unknown")) {
            assertNull(value(snapshot, List.of(), 0, key));
        }
    }

    private static String value(SkillHudSnapshot snapshot, List<SkillCooldownSnapshot> slots,
                                int selected, String key) {
        return HudPlaceholderValues.skillValue(snapshot, slots, selected, key, SLOTS, NOW, TEXT);
    }
}
