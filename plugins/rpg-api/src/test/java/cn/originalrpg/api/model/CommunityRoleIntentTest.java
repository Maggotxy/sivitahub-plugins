package cn.originalrpg.api.model;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

final class CommunityRoleIntentTest {
    @Test
    void allNonOwnerRolesRoundTripInTheExistingApprovalSubjectWidth() {
        UUID player = UUID.randomUUID();
        for (CommunityRole role : CommunityRole.values()) {
            if (role == CommunityRole.OWNER) continue;
            var intent = new CommunityRoleIntent(player, role);
            assertEquals(intent, CommunityRoleIntent.parse(intent.subject()).orElseThrow());
            assertTrue(intent.subject().length() <= 64);
        }
    }

    @Test
    void missingOrForgedIntentNeverDefaultsToAnArbitraryRole() {
        UUID player = UUID.randomUUID();
        assertTrue(CommunityRoleIntent.parse(player.toString()).isEmpty());
        assertTrue(CommunityRoleIntent.parse(player + ":OWNER").isEmpty());
        assertTrue(CommunityRoleIntent.parse(player + ":unknown").isEmpty());
        assertTrue(CommunityRoleIntent.parse("1-1-1-1-1:OFFICER").isEmpty());
        assertTrue(CommunityRoleIntent.parse(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new CommunityRoleIntent(player, CommunityRole.OWNER));
    }
}
