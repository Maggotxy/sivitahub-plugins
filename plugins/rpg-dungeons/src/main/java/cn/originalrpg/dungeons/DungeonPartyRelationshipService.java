package cn.originalrpg.dungeons;

import cn.originalrpg.api.service.PartyService;

import java.util.Objects;
import java.util.UUID;

/** Bridges mutable dungeon groups and active instances through the public API. */
final class DungeonPartyRelationshipService implements PartyService {
    private final DungeonPartyService parties;
    private final DungeonManager manager;

    DungeonPartyRelationshipService(DungeonPartyService parties, DungeonManager manager) {
        this.parties = Objects.requireNonNull(parties, "parties");
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public boolean areAllies(UUID first, UUID second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        return first.equals(second) || parties.areAllies(first, second) || manager.areInstanceAllies(first, second);
    }
}
