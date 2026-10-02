package cn.originalrpg.market;

import java.util.Set;

/**
 * Server-owned interaction items are entry points, not economic goods.
 *
 * <p>The marker list is deliberately explicit. A namespace-wide rule would
 * also block legitimate RPG materials and equipment that are meant to move
 * through the escrow market.</p>
 */
final class SystemItemPolicy {
    static final String GUIDE_HANDBOOK = "rpgguide:handbook";
    static final String COMPANION_SIGNAL = "rpgpets:companion_signal";
    static final String COMPANION_SIGNAL_OWNER = "rpgpets:companion_signal_owner";
    static final String DUNGEON_TICKET_ESCROW = "rpgdungeons:ticket_entry_escrow";

    private static final Set<String> NON_TRANSFERABLE = Set.of(
            GUIDE_HANDBOOK,
            COMPANION_SIGNAL,
            DUNGEON_TICKET_ESCROW
    );

    private SystemItemPolicy() {
    }

    static boolean isNonTransferableMarker(String markerId) {
        return markerId != null && NON_TRANSFERABLE.contains(markerId);
    }
}
