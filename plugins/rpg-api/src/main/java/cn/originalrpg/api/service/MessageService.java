package cn.originalrpg.api.service;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provides player-selectable messages while keeping language keys in Java code. */
public interface MessageService {
    String defaultLocale();

    Set<String> supportedLocales();

    String locale(CommandSender audience);

    boolean setLocale(Player player, String locale);

    String text(CommandSender audience, String key);

    String text(CommandSender audience, String key, Map<String, ?> placeholders);

    /**
     * Resolve a known locale without mutating a player's preference. Implementations
     * should override this for item-name migration and shared display rendering.
     * The default keeps existing third-party providers and test doubles compatible.
     */
    default String textForLocale(String locale, String key) {
        return text(null, key);
    }

    default String textForLocale(String locale, String key, Map<String, ?> placeholders) {
        return text(null, key, placeholders);
    }

    default List<String> linesForLocale(String locale, String key) {
        return lines(null, key);
    }

    default List<String> linesForLocale(String locale, String key, Map<String, ?> placeholders) {
        return lines(null, key, placeholders);
    }

    List<String> lines(CommandSender audience, String key);

    List<String> lines(CommandSender audience, String key, Map<String, ?> placeholders);
}
