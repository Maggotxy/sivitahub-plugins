package cn.originalrpg.story;

import org.betonquest.betonquest.api.profile.OnlineProfile;
import org.betonquest.betonquest.api.quest.action.OnlineAction;
import org.betonquest.betonquest.api.quest.action.OnlineActionAdapter;
import org.betonquest.betonquest.api.quest.action.PlayerAction;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.Consumer;

final class PrimaryThreadStoryAction {
    private PrimaryThreadStoryAction() {
    }

    static PlayerAction of(Consumer<Player> action) {
        Objects.requireNonNull(action, "action");
        return new OnlineActionAdapter(new OnlineAction() {
            @Override
            public void execute(OnlineProfile profile) {
                action.accept(profile.getPlayer());
            }

            @Override
            public boolean isPrimaryThreadEnforced() {
                return true;
            }
        });
    }
}
