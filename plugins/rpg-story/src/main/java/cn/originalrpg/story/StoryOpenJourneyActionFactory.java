package cn.originalrpg.story;

import org.betonquest.betonquest.api.instruction.Instruction;
import org.betonquest.betonquest.api.quest.action.PlayerAction;
import org.betonquest.betonquest.api.quest.action.PlayerActionFactory;

final class StoryOpenJourneyActionFactory implements PlayerActionFactory {
    private final StoryIntegrationBridge bridge;

    StoryOpenJourneyActionFactory(StoryIntegrationBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public PlayerAction parsePlayer(Instruction instruction) {
        return PrimaryThreadStoryAction.of(bridge::openJourney);
    }
}
