package cn.originalrpg.api.ui;

import cn.originalrpg.api.model.JourneyStep;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class JourneyGlyphsTest {
    @Test
    void mapsEveryJourneyStepToOneUniqueResourceGlyph() {
        Set<Character> glyphs = Arrays.stream(JourneyStep.values())
                .map(JourneyGlyphs::glyph)
                .collect(Collectors.toSet());

        assertEquals(JourneyStep.values().length, glyphs.size());
        assertEquals(Set.of('\uE100', '\uE101', '\uE102', '\uE103',
                '\uE104', '\uE105', '\uE106', '\uE107'), glyphs);
        assertEquals("originalrpg:journey", JourneyGlyphs.FONT.asString());
    }
}
