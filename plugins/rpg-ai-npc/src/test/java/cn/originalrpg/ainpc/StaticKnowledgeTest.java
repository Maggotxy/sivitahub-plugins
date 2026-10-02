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
}
