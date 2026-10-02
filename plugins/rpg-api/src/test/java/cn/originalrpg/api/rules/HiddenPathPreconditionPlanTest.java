package cn.originalrpg.api.rules;

import cn.originalrpg.api.model.HiddenPath;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前置来源清单必须是「路线的全部前置」的唯一真相：
 * 漏一条，可达性门禁就会漏检一例 G-106。
 */
class HiddenPathPreconditionPlanTest {

    @Test
    void everyRouteDeclaresAllThreePreconditionKinds() {
        for (HiddenPath path : HiddenPath.values()) {
            List<HiddenPathPreconditionPlan.Slot> slots = HiddenPathPreconditionPlan.slots(path);
            Map<HiddenPathReachability.Requirement, String> byRequirement =
                    new EnumMap<>(HiddenPathReachability.Requirement.class);
            slots.forEach(slot -> byRequirement.put(slot.requirement(), slot.slot()));

            assertEquals(3, slots.size(), path.name());
            assertEquals(Set.of(HiddenPathReachability.Requirement.SKILL,
                            HiddenPathReachability.Requirement.HIDDEN_DUNGEON,
                            HiddenPathReachability.Requirement.SKILL_BOOK),
                    byRequirement.keySet(), path.name());
            assertEquals(HiddenPathRules.requiredSkill(path),
                    byRequirement.get(HiddenPathReachability.Requirement.SKILL));
            assertEquals(HiddenPathRules.requiredDungeon(path),
                    byRequirement.get(HiddenPathReachability.Requirement.HIDDEN_DUNGEON));
            assertEquals(HiddenPathRules.requiredSkillBook(path),
                    byRequirement.get(HiddenPathReachability.Requirement.SKILL_BOOK));
        }
    }

    @Test
    void skillSlotsAreReadFromTheSkillSectionAndDungeonSlotsFromTheDungeonSection() {
        for (HiddenPath path : HiddenPath.values()) {
            for (HiddenPathPreconditionPlan.Slot slot : HiddenPathPreconditionPlan.slots(path)) {
                HiddenPathPreconditionPlan.Source expected =
                        slot.requirement() == HiddenPathReachability.Requirement.SKILL
                                ? HiddenPathPreconditionPlan.Source.SKILL
                                : HiddenPathPreconditionPlan.Source.DUNGEON;
                assertEquals(expected, slot.source(), slot.slot());
                assertFalse(slot.lookupKey().isBlank(), slot.slot());
            }
        }
    }

    @Test
    void planIsImmutableAndDeterministic() {
        List<HiddenPathPreconditionPlan.Slot> first =
                HiddenPathPreconditionPlan.slots(HiddenPath.BLOODPACT);
        List<HiddenPathPreconditionPlan.Slot> second =
                HiddenPathPreconditionPlan.slots(HiddenPath.BLOODPACT);
        assertEquals(first, second);
        assertThrows(UnsupportedOperationException.class,
                () -> first.add(first.get(0)));
        assertTrue(HiddenPathPreconditionPlan.all().containsKey(HiddenPath.BLOODPACT));
        assertEquals(HiddenPath.values().length, HiddenPathPreconditionPlan.all().size());
    }

    /** 清单的槽位名必须与真实性一致：改了 HiddenPathRules 而没有清单就编译不过。 */
    @Test
    void slotsTrackHiddenPathRules() {
        assertEquals("vanished-horizon",
                HiddenPathPreconditionPlan.slots(HiddenPath.BLOODPACT).get(0).slot());
        assertEquals("rune-reversal",
                HiddenPathPreconditionPlan.slots(HiddenPath.TALISMAN).get(0).slot());
    }
}
