package cn.originalrpg.guide;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HelpCommandRouteTest {
    @Test
    void noArgumentUsesTheContextualOnboardingEntry() {
        HelpCommandRoute route = HelpCommandRoute.parse(new String[0]);

        assertEquals(HelpCommandRoute.Target.CONTEXTUAL, route.target());
        assertNull(route.topic());
    }

    @Test
    void explicitNavigationAliasesRemainDistinct() {
        assertTarget("current", HelpCommandRoute.Target.CURRENT);
        assertTarget("next", HelpCommandRoute.Target.CURRENT);
        assertTarget("home", HelpCommandRoute.Target.HOME);
        assertTarget("menu", HelpCommandRoute.Target.HOME);
        assertTarget("npc", HelpCommandRoute.Target.NPCS);
        assertTarget("npcs", HelpCommandRoute.Target.NPCS);
        assertTarget("all", HelpCommandRoute.Target.TOPICS);
        assertTarget("topics", HelpCommandRoute.Target.TOPICS);
    }

    @Test
    void topicIdsAreNormalisedWithoutBecomingCommands() {
        HelpCommandRoute route = HelpCommandRoute.parse(new String[]{"  SkIlLs  "});

        assertEquals(HelpCommandRoute.Target.TOPIC, route.target());
        assertEquals("skills", route.topic());
    }

    private void assertTarget(String argument, HelpCommandRoute.Target expected) {
        HelpCommandRoute route = HelpCommandRoute.parse(new String[]{argument});
        assertEquals(expected, route.target());
        assertNull(route.topic());
    }
}
