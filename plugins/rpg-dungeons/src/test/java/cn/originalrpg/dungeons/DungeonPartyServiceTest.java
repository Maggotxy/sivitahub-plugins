package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonPartyServiceTest {
    @Test
    void invitationAcceptanceEnforcesLeaderCapacityAndEntryOwnership() {
        DungeonPartyService parties = new DungeonPartyService(3, 60);
        UUID leader = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID fourth = UUID.randomUUID();

        assertEquals(DungeonPartyService.Status.SUCCESS, parties.create(leader).status());
        assertEquals(DungeonPartyService.Status.SUCCESS, parties.invite(leader, second).status());
        assertEquals(DungeonPartyService.Status.SUCCESS, parties.accept(second, leader).status());
        assertTrue(parties.areAllies(leader, second));
        assertFalse(parties.areAllies(leader, fourth));
        assertEquals(DungeonPartyService.Status.NOT_LEADER, parties.entry(second).status());
        assertEquals(DungeonPartyService.Status.SUCCESS, parties.invite(leader, third).status());
        assertEquals(DungeonPartyService.Status.SUCCESS, parties.accept(third, leader).status());
        assertEquals(DungeonPartyService.Status.FULL, parties.invite(leader, fourth).status());
        assertEquals(3, parties.entry(leader).members().size());
    }

    @Test
    void leaderLeavingPromotesOneMemberAndDisbandRemovesEveryMapping() {
        DungeonPartyService parties = new DungeonPartyService(4, 60);
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();

        parties.create(leader);
        parties.invite(leader, member);
        parties.accept(member, leader);
        DungeonPartyService.Result left = parties.leave(leader);

        assertEquals(DungeonPartyService.Status.SUCCESS, left.status());
        assertEquals(member, parties.view(member).leader());
        assertEquals(DungeonPartyService.Status.SUCCESS, parties.entry(member).status());
        assertEquals(DungeonPartyService.Status.SUCCESS, parties.disband(member).status());
        assertNull(parties.view(member));
        assertFalse(parties.areAllies(leader, member));
        assertFalse(parties.entry(leader).members().isEmpty());
    }

    @Test
    void staleOrMismatchedInvitationCannotJoinAnotherParty() {
        DungeonPartyService parties = new DungeonPartyService(4, 60);
        UUID leader = UUID.randomUUID();
        UUID otherLeader = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        parties.create(leader);
        parties.create(otherLeader);
        parties.invite(leader, target);

        assertEquals(DungeonPartyService.Status.INVITE_MISSING,
                parties.accept(target, otherLeader).status());
        assertNull(parties.view(target));
    }
}
