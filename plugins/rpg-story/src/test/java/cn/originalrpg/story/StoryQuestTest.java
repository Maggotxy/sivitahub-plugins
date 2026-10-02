package cn.originalrpg.story;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryQuestTest {
    @Test
    void parsesOnlyWhitelistedIdentifiers() {
        assertEquals(StoryQuest.PROLOGUE_ESCORT, StoryQuest.parse("prologue-escort").orElseThrow());
        assertTrue(StoryQuest.parse("arbitrary_console_command").isEmpty());
    }
}
