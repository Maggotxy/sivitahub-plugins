package cn.originalrpg.story;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoryStatusPolicyTest {
    @Test
    void staleActiveTagBecomesAnExplicitResumeAction() {
        assertEquals(StoryStatusPolicy.State.RESUMABLE,
                StoryStatusPolicy.resolve(true, false, false, true, false, true));
    }

    @Test
    void claimAndCompletionTakePriorityOverTransientActorState() {
        assertEquals(StoryStatusPolicy.State.CLAIMABLE,
                StoryStatusPolicy.resolve(true, false, true, true, false, false));
        assertEquals(StoryStatusPolicy.State.COMPLETED,
                StoryStatusPolicy.resolve(true, true, true, true, true, false));
    }

    @Test
    void classChoiceIsExplainedBeforeTheOptionalTourCanStart() {
        assertEquals(StoryStatusPolicy.State.NEEDS_CLASS,
                StoryStatusPolicy.resolve(false, false, false, false, false, true));
        assertEquals(StoryStatusPolicy.State.AVAILABLE,
                StoryStatusPolicy.resolve(true, false, false, false, false, true));
    }

    @Test
    void selectedPlayerOutsideTheHubGetsAnExplicitReturnState() {
        assertEquals(StoryStatusPolicy.State.NEEDS_HUB,
                StoryStatusPolicy.resolve(true, false, false, false, false, false));
    }

    @Test
    void remoteActiveActorIsResetThroughHubInsteadOfShownAsContinuable() {
        assertEquals(StoryStatusPolicy.State.NEEDS_HUB,
                StoryStatusPolicy.resolve(true, false, false, true, true, false));
        assertEquals(StoryStatusPolicy.State.ACTIVE,
                StoryStatusPolicy.resolve(true, false, false, true, true, true));
    }
}
