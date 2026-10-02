package cn.originalrpg.story;

import cn.originalrpg.api.model.JourneyStep;
import org.betonquest.betonquest.api.QuestException;
import org.betonquest.betonquest.api.instruction.Instruction;
import org.betonquest.betonquest.api.quest.condition.PlayerCondition;
import org.betonquest.betonquest.api.quest.condition.PlayerConditionFactory;

final class StoryJourneyConditionFactory implements PlayerConditionFactory {
    private final StoryIntegrationBridge bridge;

    StoryJourneyConditionFactory(StoryIntegrationBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public PlayerCondition parsePlayer(Instruction instruction) throws QuestException {
        String value = instruction.string().get().getValue(null);
        if ("complete".equalsIgnoreCase(value)) {
            return profile -> bridge.journeyComplete(profile.getPlayerUUID());
        }
        JourneyStep step = JourneyStep.parse(value)
                .orElseThrow(() -> new QuestException("Unknown Sivita journey step: " + value));
        return profile -> bridge.journeyAt(profile.getPlayerUUID(), step);
    }
}
