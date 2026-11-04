package cn.originalrpg.api.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable matching facts captured from the actual item before it enters escrow.
 * A matcher is either a pristine vanilla material ({@code minecraft:iron_ingot})
 * or signed equipment ({@code equipment:sword:rare}). It is never a display name.
 * The Paper adapter verifies authenticity/ownership; core persists these facts
 * with the PREPARED listing and never accepts a replacement payload at fill time.
 */
public record MarketGoods(String matcher, int quantity, UUID crafterId) {
    public MarketGoods {
        Objects.requireNonNull(matcher, "matcher");
        if (!validMatcher(matcher) || quantity < 1 || quantity > 64
                || equipmentMatcher(matcher) && (quantity != 1 || crafterId == null)
                || !equipmentMatcher(matcher) && crafterId != null) {
            throw new IllegalArgumentException("Invalid escrow matching facts");
        }
    }

    public static boolean validMatcher(String matcher) {
        return matcher != null && matcher.length() <= 64
                && matcher.matches("minecraft:[a-z0-9_]+|equipment:[a-z0-9_]+:[a-z0-9_]+");
    }

    public static boolean equipmentMatcher(String matcher) {
        return matcher != null && matcher.startsWith("equipment:");
    }
}
