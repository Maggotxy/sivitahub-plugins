package cn.originalrpg.world;

import cn.originalrpg.api.model.FieldJournalEntry;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class FieldJournalCatalogTest {
    @Test
    void reviewedCatalogContainsSixRealGoalsAndOneHundredThirtyFiveConditions() {
        FieldJournalCatalog catalog = catalog();
        Map<FieldJournalEntry, Integer> counts = Map.of(
                FieldJournalEntry.OVERWORLD_EXPLORER, 55, FieldJournalEntry.NETHER_EXPLORER, 5,
                FieldJournalEntry.NATURALIST, 26, FieldJournalEntry.FORAGER, 40,
                FieldJournalEntry.VILLAGE_DEFENDER, 1, FieldJournalEntry.ARMOR_ARTISAN, 8);
        counts.forEach((entry, count) -> assertEquals(count, catalog.criteria(entry).size(), entry.id()));
        assertEquals(135, counts.values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(FieldJournalEntry.fromAdvancementKey("minecraft:recipes/food/bread").isEmpty());
        assertTrue(FieldJournalEntry.fromAdvancementKey("custom:adventure/adventuring_time").isEmpty());
    }

    @Test
    void missingAdvancementDisablesOnlyItsOwnGoalAndReportsIt() {
        FieldJournalCatalog catalog = catalog();
        Map<FieldJournalEntry, Advancement> registry = registry(catalog);
        registry.remove(FieldJournalEntry.FORAGER);
        List<String> disabled = new ArrayList<>();
        var resolved = catalog.resolve(key -> FieldJournalEntry.fromAdvancementKey(key.toString())
                .map(registry::get).orElse(null), disabled::add);
        assertEquals(5, resolved.size());
        assertFalse(resolved.containsKey(FieldJournalEntry.FORAGER));
        assertEquals(1, disabled.size());
        assertTrue(disabled.getFirst().contains("forager"));
    }

    @Test
    void changedCriteriaNeverBecomeFalseProgress() {
        FieldJournalCatalog catalog = catalog();
        Map<FieldJournalEntry, Advancement> registry = registry(catalog);
        registry.put(FieldJournalEntry.NATURALIST, advancement(FieldJournalEntry.NATURALIST, Set.of("impossible")));
        List<String> disabled = new ArrayList<>();
        var resolved = catalog.resolve(key -> FieldJournalEntry.fromAdvancementKey(key.toString())
                .map(registry::get).orElse(null), disabled::add);
        assertEquals(5, resolved.size());
        assertFalse(resolved.containsKey(FieldJournalEntry.NATURALIST));
        assertTrue(disabled.getFirst().contains("criteria differ"));
    }

    @Test
    void remainingConditionsComeFromLiveCriteriaAndCompletionRequiresTheNativeFact() {
        FieldJournalProgress partial = FieldJournalProgress.observe(
                Set.of("plains", "forest", "desert"), Set.of("forest"), false, null);
        assertEquals(1, partial.awardedCriteria());
        assertEquals(List.of("desert", "plains"), partial.remaining());
        assertEquals("journal.in-progress", partial.stateKey());
        assertFalse(FieldJournalProgress.observe(Set.of("a"), Set.of("a"), false, null).completed());
        assertEquals("journal.pending", FieldJournalProgress.observe(Set.of("a"), Set.of("a"), true, null).stateKey());
    }

    @Test
    void historicalStampSurvivesVanillaRevocationAndUnavailableContent() {
        Instant stamp = Instant.parse("2026-01-01T12:00:00Z");
        FieldJournalProgress revoked = FieldJournalProgress.observe(Set.of("a"), Set.of(), false, stamp);
        assertEquals("journal.recorded", revoked.stateKey());
        assertEquals(List.of("a"), revoked.remaining());
        FieldJournalProgress unavailable = FieldJournalProgress.unavailable(stamp);
        assertFalse(unavailable.available());
        assertEquals("journal.recorded", unavailable.stateKey());
        assertEquals("journal.unavailable", FieldJournalProgress.unavailable(null).stateKey());
    }

    @Test
    void malformedObservationCannotCreateACompletion() {
        FieldJournalProgress empty = FieldJournalProgress.observe(Set.of(), Set.of(), true, null);
        FieldJournalProgress foreign = FieldJournalProgress.observe(Set.of("a"), Set.of("b"), true, null);
        assertFalse(empty.available());
        assertFalse(empty.completed());
        assertFalse(foreign.available());
        assertFalse(foreign.completed());
    }

    @Test
    void technicalTrimCriterionRendersAsTheActualTemplateItem() {
        String raw = "armor_trimmed_minecraft:silence_armor_trim_smithing_template_smithing_trim";
        assertEquals("silence_armor_trim_smithing_template",
                FieldJournalController.criterionPath(FieldJournalEntry.ARMOR_ARTISAN, raw));
        assertEquals("item.minecraft.silence_armor_trim_smithing_template",
                ((TranslatableComponent) FieldJournalController.criterionName(FieldJournalEntry.ARMOR_ARTISAN, raw)).key());
        assertEquals("biome.minecraft.sulfur_caves", ((TranslatableComponent) FieldJournalController.criterionName(
                FieldJournalEntry.OVERWORLD_EXPLORER, "minecraft:sulfur_caves")).key());
    }

    static FieldJournalCatalog catalog() {
        return FieldJournalCatalog.load(FieldJournalCatalogTest.class.getResourceAsStream("/field-journal.yml"));
    }

    static Map<FieldJournalEntry, Advancement> registry(FieldJournalCatalog catalog) {
        Map<FieldJournalEntry, Advancement> registry = new EnumMap<>(FieldJournalEntry.class);
        for (FieldJournalEntry entry : FieldJournalEntry.values()) registry.put(entry, advancement(entry, catalog.criteria(entry)));
        return registry;
    }

    static Advancement advancement(FieldJournalEntry entry, Set<String> criteria) {
        return (Advancement) Proxy.newProxyInstance(Advancement.class.getClassLoader(), new Class<?>[]{Advancement.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getKey" -> NamespacedKey.fromString(entry.advancementKey());
                    case "getCriteria" -> criteria;
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    default -> null;
                });
    }
}
