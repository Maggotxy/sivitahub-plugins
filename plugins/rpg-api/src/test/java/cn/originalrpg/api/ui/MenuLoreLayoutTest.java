package cn.originalrpg.api.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuLoreLayoutTest {
    @Test
    void wrapsChineseByVisualWeightWithoutLosingText() {
        String value = "选择职业并分配五维后打开技能树，满足条件即可学习并查看全部冷却";
        List<String> lines = MenuLoreLayout.wrap(value, 20);

        assertTrue(lines.size() > 1);
        assertEquals(value, String.join("", lines));
        assertTrue(lines.stream().allMatch(line -> MenuLoreLayout.units(line) <= 20));
    }

    @Test
    void preservesEnglishWordsAndCommands() {
        List<String> lines = MenuLoreLayout.wrap(
                "Click a learned skill or use /skills cast <skill-id> to activate it", 24);

        assertEquals("Click a learned skill or use /skills cast <skill-id> to activate it",
                String.join(" ", lines));
        assertTrue(lines.stream().allMatch(line -> MenuLoreLayout.units(line) <= 24));
    }

    @Test
    void clampsUnreasonableLimits() {
        assertTrue(MenuLoreLayout.wrap("abcdefghijklmnop", 1).stream()
                .allMatch(line -> MenuLoreLayout.units(line) <= 12));
    }
}
