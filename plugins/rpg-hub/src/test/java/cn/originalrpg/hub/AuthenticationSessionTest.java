package cn.originalrpg.hub;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuthenticationSessionTest {
    @Test
    void revokedGenerationNeverBecomesValidAfterRelogin() {
        AuthenticationSession sessions = new AuthenticationSession();
        UUID player = UUID.randomUUID();

        long first = sessions.activate(player);
        assertTrue(sessions.valid(player, first));

        sessions.revoke(player);
        assertFalse(sessions.valid(player, first));

        long second = sessions.activate(player);
        assertTrue(sessions.valid(player, second));
        assertFalse(sessions.valid(player, first));
    }

    @Test
    void quitForgetsTheConnectionWithoutReusingItsToken() {
        AuthenticationSession sessions = new AuthenticationSession();
        UUID player = UUID.randomUUID();
        long first = sessions.activate(player);

        sessions.forget(player);
        assertFalse(sessions.valid(player, first));
        assertFalse(sessions.authenticated(player));
    }
}
