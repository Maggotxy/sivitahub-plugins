package cn.originalrpg.story;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;

final class StoryListener implements Listener {
    private final RpgStoryPlugin plugin;
    private final EscortManager escorts;

    StoryListener(RpgStoryPlugin plugin, EscortManager escorts) {
        this.plugin = plugin;
        this.escorts = escorts;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLanguageChange(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().trim().split("\\s+");
        if (parts.length != 2 || !(parts[0].equalsIgnoreCase("/language") || parts[0].equalsIgnoreCase("/lang"))) {
            return;
        }
        String locale = parts[1].toLowerCase(Locale.ROOT);
        String questLocale = switch (locale) {
            case "zh_cn" -> "zh-CN";
            case "en_us" -> "en-US";
            default -> null;
        };
        if (questLocale != null) {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> event.getPlayer().performCommand("questlang " + questLocale));
        }
    }
}
