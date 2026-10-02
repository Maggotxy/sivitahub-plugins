package cn.originalrpg.story;

/** Player-facing prologue state; infrastructure capacity remains admin-only. */
final class StoryStatusPolicy {
    enum State {
        NEEDS_CLASS,
        NEEDS_HUB,
        AVAILABLE,
        ACTIVE,
        RESUMABLE,
        CLAIMABLE,
        COMPLETED
    }

    private StoryStatusPolicy() {
    }

    static State resolve(
            boolean classChosen,
            boolean rewarded,
            boolean routeComplete,
            boolean activeTag,
            boolean activeSession,
            boolean atTourStart
    ) {
        if (rewarded) return State.COMPLETED;
        if (routeComplete) return State.CLAIMABLE;
        if (activeSession) return atTourStart ? State.ACTIVE : State.NEEDS_HUB;
        if (activeTag) return atTourStart ? State.RESUMABLE : State.NEEDS_HUB;
        if (!classChosen) return State.NEEDS_CLASS;
        return atTourStart ? State.AVAILABLE : State.NEEDS_HUB;
    }
}
