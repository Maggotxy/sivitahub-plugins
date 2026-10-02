package cn.originalrpg.dungeons;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class DungeonPartyService {
    private final Map<UUID, Party> partiesByMember = new HashMap<>();
    private final Map<UUID, Invitation> invitations = new HashMap<>();
    private final int maximumSize;
    private final long invitationMillis;

    DungeonPartyService(int maximumSize, long invitationSeconds) {
        this.maximumSize = Math.clamp(maximumSize, 2, 8);
        this.invitationMillis = Math.clamp(invitationSeconds, 15L, 300L) * 1000L;
    }

    Result create(UUID playerId) {
        Party existing = partiesByMember.get(playerId);
        if (existing != null) return result(Status.ALREADY_MEMBER, existing);
        Party party = new Party(playerId);
        party.members.add(playerId);
        partiesByMember.put(playerId, party);
        return result(Status.SUCCESS, party);
    }

    Result invite(UUID actor, UUID target) {
        if (actor.equals(target)) return Result.empty(Status.INVALID);
        Party party = partiesByMember.get(actor);
        if (party == null) {
            create(actor);
            party = partiesByMember.get(actor);
        }
        if (!party.leader.equals(actor)) return result(Status.NOT_LEADER, party);
        if (partiesByMember.containsKey(target)) return Result.empty(Status.ALREADY_MEMBER);
        if (party.members.size() >= maximumSize) return result(Status.FULL, party);
        invitations.put(target, new Invitation(actor, System.currentTimeMillis() + invitationMillis));
        return result(Status.SUCCESS, party);
    }

    Result accept(UUID target, UUID expectedLeader) {
        if (partiesByMember.containsKey(target)) return Result.empty(Status.ALREADY_MEMBER);
        Invitation invitation = invitations.get(target);
        if (invitation == null || invitation.expiresAt() < System.currentTimeMillis()
                || !invitation.leader().equals(expectedLeader)) return Result.empty(Status.INVITE_MISSING);
        Party party = partiesByMember.get(expectedLeader);
        if (party == null || !party.leader.equals(expectedLeader)) return Result.empty(Status.INVITE_MISSING);
        if (party.members.size() >= maximumSize) return result(Status.FULL, party);
        invitations.remove(target);
        party.members.add(target);
        partiesByMember.put(target, party);
        return result(Status.SUCCESS, party);
    }

    Result leave(UUID actor) {
        Party party = partiesByMember.get(actor);
        if (party == null) return Result.empty(Status.NOT_IN_PARTY);
        party.members.remove(actor);
        partiesByMember.remove(actor);
        invitations.remove(actor);
        if (party.members.isEmpty()) return Result.empty(Status.SUCCESS);
        if (party.leader.equals(actor)) party.leader = party.members.iterator().next();
        return result(Status.SUCCESS, party);
    }

    /** Removes all connection-local party and invitation state for logout. */
    void revoke(UUID playerId) {
        if (playerId == null) return;
        leave(playerId);
        invitations.remove(playerId);
        invitations.entrySet().removeIf(entry -> entry.getValue().leader().equals(playerId));
    }

    Result kick(UUID actor, UUID target) {
        Party party = partiesByMember.get(actor);
        if (party == null) return Result.empty(Status.NOT_IN_PARTY);
        if (!party.leader.equals(actor)) return result(Status.NOT_LEADER, party);
        if (actor.equals(target) || !party.members.remove(target)) return result(Status.INVALID, party);
        partiesByMember.remove(target);
        invitations.remove(target);
        return result(Status.SUCCESS, party);
    }

    Result disband(UUID actor) {
        Party party = partiesByMember.get(actor);
        if (party == null) return Result.empty(Status.NOT_IN_PARTY);
        if (!party.leader.equals(actor)) return result(Status.NOT_LEADER, party);
        party.members.forEach(partiesByMember::remove);
        invitations.entrySet().removeIf(entry -> entry.getValue().leader().equals(actor));
        party.members.clear();
        return Result.empty(Status.SUCCESS);
    }

    PartyView view(UUID member) {
        Party party = partiesByMember.get(member);
        return party == null ? null : view(party);
    }

    EntryView entry(UUID actor) {
        Party party = partiesByMember.get(actor);
        if (party == null) return new EntryView(Status.SUCCESS, actor, List.of(actor));
        if (!party.leader.equals(actor)) return new EntryView(Status.NOT_LEADER, party.leader, List.of());
        return new EntryView(Status.SUCCESS, party.leader, List.copyOf(party.members));
    }

    boolean areAllies(UUID first, UUID second) {
        if (first.equals(second)) return true;
        Party party = partiesByMember.get(first);
        return party != null && party == partiesByMember.get(second);
    }

    private Result result(Status status, Party party) {
        return new Result(status, view(party));
    }

    private PartyView view(Party party) {
        return new PartyView(party.leader, List.copyOf(party.members), maximumSize);
    }

    enum Status {
        SUCCESS,
        ALREADY_MEMBER,
        NOT_IN_PARTY,
        NOT_LEADER,
        FULL,
        INVITE_MISSING,
        INVALID
    }

    record PartyView(UUID leader, List<UUID> members, int maximumSize) {
    }

    record EntryView(Status status, UUID leader, List<UUID> members) {
    }

    record Result(Status status, PartyView party) {
        static Result empty(Status status) {
            return new Result(status, null);
        }
    }

    private record Invitation(UUID leader, long expiresAt) {
    }

    private static final class Party {
        private UUID leader;
        private final LinkedHashSet<UUID> members = new LinkedHashSet<>();

        private Party(UUID leader) {
            this.leader = leader;
        }
    }
}
