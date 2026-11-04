package cn.originalrpg.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/**
 * Fired synchronously after a player's language preference has been saved.
 * Presentation listeners may refresh existing item names, lore and cached views;
 * this notification does not authorize changes to gameplay or persistent assets.
 */
public final class PlayerLocaleChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String previousLocale;
    private final String locale;

    public PlayerLocaleChangeEvent(Player player, String previousLocale, String locale) {
        this.player = Objects.requireNonNull(player, "player");
        this.previousLocale = Objects.requireNonNull(previousLocale, "previousLocale");
        this.locale = Objects.requireNonNull(locale, "locale");
    }

    public Player player() { return player; }
    public String previousLocale() { return previousLocale; }
    public String locale() { return locale; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
