package cn.originalrpg.story;

import cn.originalrpg.api.model.JourneyProgress;

import java.util.Objects;

record StoryOverview(StoryStatusPolicy.State prologue, JourneyProgress journey) {
    StoryOverview {
        Objects.requireNonNull(prologue, "prologue");
        Objects.requireNonNull(journey, "journey");
    }
}
