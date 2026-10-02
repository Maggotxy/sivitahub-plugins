package cn.originalrpg.story;

import java.util.Locale;
import java.util.Optional;

enum StoryQuest {
    PROLOGUE_ESCORT(
            "prologue_escort",
            "sivita_prologue_escort_active",
            "sivita_prologue_escort_complete",
            "sivita_prologue_escort_rewarded"
    );

    private final String id;
    private final String activeTag;
    private final String completeTag;
    private final String rewardedTag;

    StoryQuest(String id, String activeTag, String completeTag, String rewardedTag) {
        this.id = id;
        this.activeTag = activeTag;
        this.completeTag = completeTag;
        this.rewardedTag = rewardedTag;
    }

    String id() {
        return id;
    }

    String configKey() {
        return id.replace('_', '-');
    }

    String activeTag() {
        return activeTag;
    }

    String completeTag() {
        return completeTag;
    }

    String rewardedTag() {
        return rewardedTag;
    }

    static Optional<StoryQuest> parse(String input) {
        if (input == null) return Optional.empty();
        String normalized = input.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (StoryQuest quest : values()) {
            if (quest.id.equals(normalized)) return Optional.of(quest);
        }
        return Optional.empty();
    }
}
