package cn.originalrpg.api.i18n;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocaleBundleTest {
    @Test
    void incompleteInstalledEnglishUsesPackagedEnglishBeforeCustomizedChinese() {
        LocaleBundle bundles = new LocaleBundle("zh_cn",
                Map.of("en_us", Map.of("known", "My English"),
                        "zh_cn", Map.of("new", "自定义中文", "lore", List.of("中文说明"))),
                Map.of("en_us", Map.of("known", "English", "new", "New English", "lore", List.of("English lore")),
                        "zh_cn", Map.of("known", "中文", "new", "新中文", "lore", List.of("中文说明"))),
                ignored -> fail("Valid overrides should not warn"));
        assertEquals("My English", bundles.text(" EN-us ", "known"));
        assertEquals("New English", bundles.text("en_us", "new"));
        assertEquals(List.of("English lore"), bundles.lines("en_us", "lore"));
        assertEquals("自定义中文", bundles.text("unknown", "new"));
    }

    @Test
    void malformedOverridesCannotHideValidTranslationsAndDoNotRewriteInput() {
        Map<String, Object> installed = new HashMap<>();
        installed.put("greeting", "Hello {wrong}");
        installed.put("kind", List.of("Wrong type"));
        installed.put("blank", "  ");
        installed.put("list", List.of("Broken", 12));
        List<String> warnings = new ArrayList<>();
        LocaleBundle bundles = new LocaleBundle("en_us", Map.of("en_us", installed),
                Map.of("en_us", Map.of("greeting", "Hello {player}", "kind", "A sentence",
                        "blank", "Visible", "list", List.of("Level {level}"))), warnings::add);
        assertEquals("Hello Lamb", LocaleBundle.format(bundles.text("en_us", "greeting"), Map.of("player", "Lamb")));
        assertEquals("A sentence", bundles.text("en_us", "kind"));
        assertEquals("Visible", bundles.text("en_us", "blank"));
        assertEquals(List.of("Level {level}"), bundles.lines("en_us", "list"));
        assertEquals(4, warnings.size());
        assertEquals("Hello {wrong}", installed.get("greeting"));
        assertEquals(List.of("Broken", 12), installed.get("list"));
    }

    @Test
    void operatorCanReorderAndRepeatPlaceholdersWithoutLosingCustomization() {
        LocaleBundle bundles = new LocaleBundle("en_us",
                Map.of("en_us", Map.of("lore", List.of("{player}: {amount}", "Again: {amount}"))),
                Map.of("en_us", Map.of("lore", List.of("{amount} for {player}"))), ignored -> fail());
        assertEquals(List.of("{player}: {amount}", "Again: {amount}"), bundles.lines("en_us", "lore"));
    }

    @Test
    void constructionTakesAnImmutableSnapshotIncludingListValues() {
        List<String> mutableLines = new ArrayList<>(List.of("Original"));
        Map<String, Object> mutable = new HashMap<>(Map.of("text", "Original", "lines", mutableLines));
        LocaleBundle bundles = new LocaleBundle("en_us", Map.of("en_us", mutable), Map.of(), ignored -> fail());
        mutable.put("text", "Changed");
        mutableLines.set(0, "Changed");
        assertEquals("Original", bundles.text("en_us", "text"));
        assertEquals(List.of("Original"), bundles.lines("en_us", "lines"));
        assertThrows(UnsupportedOperationException.class, () -> bundles.lines("en_us", "lines").add("Injected"));
    }

    @Test
    void lookupKeepsTextAndListContractsSeparateAndHasPredictableMissingValues() {
        LocaleBundle bundles = new LocaleBundle("en_us", Map.of(),
                Map.of("en_us", Map.of("text", "English", "lines", List.of("Line"))), ignored -> fail());
        assertEquals("missing", bundles.text(null, "missing"));
        assertEquals("lines", bundles.text("en_us", "lines"));
        assertEquals(List.of(), bundles.lines("en_us", "text"));
        assertEquals("English", bundles.text(null, "text"));
    }

    @Test
    void substitutionIsSinglePassAndTreatsPlayerTextAsLiteralData() {
        Map<String, Object> values = Map.of("player", "{amount} $1 \\path", "amount", 42);
        assertEquals("{amount} $1 \\path receives 42; {amount} $1 \\path; {unknown}",
                LocaleBundle.format("{player} receives {amount}; {player}; {unknown}", values));
        assertEquals("No placeholders", LocaleBundle.format("No placeholders", values));
    }
    @Test
    void sameLanguageContentLookupDoesNotBorrowAnotherLanguagesCustomProse() {
        LocaleBundle bundles = new LocaleBundle("zh_cn", Map.of("zh_cn", Map.of("answer", List.of("中文回答"))),
                Map.of("en_us", Map.of("generic", List.of("English fallback"))), ignored -> fail());
        assertEquals(List.of(), bundles.linesInLocale("en_us", "answer"));
        assertEquals(List.of("English fallback"), bundles.linesInLocale("EN-US", "generic"));
        assertEquals(List.of("中文回答"), bundles.lines("en_us", "answer"));
    }

}
