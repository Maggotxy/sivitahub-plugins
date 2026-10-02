package cn.originalrpg.ainpc;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AiSessionRegistryTest {
    @Test
    void anAnswerFromAnOldConnectionCannotTargetAReconnectedPlayer() {
        AiSessionRegistry sessions = new AiSessionRegistry();
        UUID player = UUID.randomUUID();

        long first = sessions.activate(player);
        assertTrue(sessions.valid(player, first));

        sessions.revoke(player);
        long second = sessions.activate(player);

        assertFalse(sessions.valid(player, first));
        assertTrue(sessions.valid(player, second));
    }

    @Test
    void shutdownInvalidatesEveryOutstandingSessionToken() {
        AiSessionRegistry sessions = new AiSessionRegistry();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        long first = sessions.activate(firstPlayer);
        long second = sessions.activate(secondPlayer);

        sessions.clear();

        assertFalse(sessions.valid(firstPlayer, first));
        assertFalse(sessions.valid(secondPlayer, second));
    }
}
