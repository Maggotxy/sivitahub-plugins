package cn.originalrpg.story;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.PlayerLocaleChangeEvent;
import cn.originalrpg.api.i18n.LocaleBundle;
import cn.originalrpg.api.service.MessageService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Keeps optional BetonQuest conversations on the player's resolved RPG language. */
final class StoryListener implements Listener {
    private final MessageService messages;

    StoryListener(MessageService messages) {
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        synchronize(event.player());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLanguageChange(PlayerLocaleChangeEvent event) {
        synchronize(event.player());
    }

    private void synchronize(Player player) {
        if (!player.isOnline()) return;
        String questLocale = switch (LocaleBundle.normalize(messages.locale(player))) {
            case "zh_cn" -> "zh-CN";
            case "en_us" -> "en-US";
            default -> null;
        };
        if (questLocale != null) {
            // Use BetonQuest's supported player command: language availability,
            // permissions and the operator's questlang whitelist still apply.
            // Both events are synchronous; no stale next-login callback is queued.
            player.performCommand("questlang " + questLocale);
        }
    }
}
