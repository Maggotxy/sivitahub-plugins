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

    List<String> lines(CommandSender audience, String key);

    List<String> lines(CommandSender audience, String key, Map<String, ?> placeholders);
}
