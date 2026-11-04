package cn.originalrpg.ainpc;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class StaticKnowledgeTest {
    private final StaticKnowledge knowledge = new StaticKnowledge(YamlConfiguration.loadConfiguration(new StringReader("""
            zh_cn:
              generic: ["默认回答"]
              topics:
                forge:
                  keywords: ["锻造", "forge"]
                  answer: ["锻造回答一", "锻造回答二"]
            en_us:
              generic: ["fallback"]
              topics: {}
            """)));

    @Test
    void selectsKeywordTopicCaseInsensitively() {
        assertEquals("锻造回答一 锻造回答二", knowledge.answer("zh_cn", "FORGE 和锻造怎么玩"));
    }

    @Test
    void returnsLocaleGenericAnswer() {
        assertEquals("fallback", knowledge.answer("en_us", "unknown topic"));
    }
    @Test
    void olderEnglishOverridesUsePackagedEnglishForNewTopics() {
        YamlConfiguration installed = yaml("""
                zh_cn:
                  generic: ["旧中文默认"]
                  topics:
                    forge:
                      keywords: ["forge"]
                      answer: ["旧中文锻造"]
                en_us:
                  generic: ["My English fallback"]
                """);
        YamlConfiguration packaged = yaml("""
                zh_cn:
                  generic: ["中文默认"]
                en_us:
                  generic: ["English fallback"]
                  topics:
                    forge:
                      keywords: ["forge", "craft"]
                      answer: ["Use the Forge menu.", "Keep the required materials in its input slots."]
                """);
        StaticKnowledge upgraded = new StaticKnowledge(installed, packaged, "zh_cn", ignored -> { });
        assertEquals("Use the Forge menu. Keep the required materials in its input slots.",
                upgraded.answer("EN-US", "How do I CRAFT?"));
        assertEquals("My English fallback", upgraded.answer("en_us", "unknown"));
        assertEquals("旧中文默认", upgraded.answer("unsupported", "unknown"));
    }

    @Test
    void malformedEnglishListsFallBackWithoutLosingOtherValidCustomizations() {
        YamlConfiguration installed = yaml("""
                en_us:
                  generic: "Invalid scalar"
                  topics:
                    forge:
                      keywords: ["craft"]
                      answer: ["Operator instructions"]
                """);
        YamlConfiguration packaged = yaml("""
                en_us:
                  generic: ["Safe English fallback"]
                  topics:
                    forge:
                      keywords: ["forge"]
                      answer: ["Packaged instructions"]
                """);
        StaticKnowledge customized = new StaticKnowledge(installed, packaged, "en_us", ignored -> { });
        assertEquals("Operator instructions", customized.answer("en_us", "CRAFT"));
        assertEquals("Safe English fallback", customized.answer("unknown", null));
    }

    @Test
    void chineseOnlyCustomTopicsCannotLeakIntoEnglishFallback() {
        StaticKnowledge localized = new StaticKnowledge(yaml("""
                zh_cn:
                  generic: ["中文默认"]
                  topics:
                    custom:
                      keywords: ["guild"]
                      answer: ["仅中文自定义答复"]
                en_us:
                  generic: ["English fallback"]
                  topics:
                    custom:
                      keywords: ["guild"]
                """));
        assertEquals("English fallback", localized.answer("en_us", "guild"));
        assertEquals("仅中文自定义答复", localized.answer("zh_cn", "guild"));
    }

    private static YamlConfiguration yaml(String content) {
        return YamlConfiguration.loadConfiguration(new StringReader(content));
    }

}
