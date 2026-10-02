package cn.originalrpg.story;

import org.betonquest.betonquest.api.QuestException;
import org.betonquest.betonquest.api.instruction.Instruction;
import org.betonquest.betonquest.api.quest.action.PlayerAction;
import org.betonquest.betonquest.api.quest.action.PlayerActionFactory;

final class StoryBeginActionFactory implements PlayerActionFactory {
    private final StoryIntegrationBridge bridge;

    StoryBeginActionFactory(StoryIntegrationBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public PlayerAction parsePlayer(Instruction instruction) throws QuestException {
        String value = instruction.string().get().getValue(null);
        StoryQuest quest = StoryQuest.parse(value)
                .orElseThrow(() -> new QuestException("Unknown Sivita story id: " + value));
        return PrimaryThreadStoryAction.of(player -> bridge.start(player, quest));
    }
}
