package cn.originalrpg.story;

import org.betonquest.betonquest.api.QuestException;
import org.betonquest.betonquest.api.instruction.Instruction;
import org.betonquest.betonquest.api.quest.condition.PlayerCondition;
import org.betonquest.betonquest.api.quest.condition.PlayerConditionFactory;

final class StoryEligibilityConditionFactory implements PlayerConditionFactory {
    private final StoryIntegrationBridge bridge;

    StoryEligibilityConditionFactory(StoryIntegrationBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public PlayerCondition parsePlayer(Instruction instruction) throws QuestException {
        String value = instruction.string().get().getValue(null);
        StoryQuest quest = StoryQuest.parse(value)
                .orElseThrow(() -> new QuestException("Unknown Sivita story id: " + value));
        return profile -> bridge.eligible(profile.getPlayerUUID(), quest);
    }
}
