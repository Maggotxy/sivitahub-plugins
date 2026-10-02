package cn.originalrpg.dungeons;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class DungeonCompletionPolicy {
    private DungeonCompletionPolicy() {
    }

    static boolean isManagedBoss(String entityDungeonId, boolean bossMarked, String instanceDungeonId) {
        return bossMarked && entityDungeonId != null && entityDungeonId.equals(instanceDungeonId);
    }

    static Set<UUID> recipientIds(Collection<UUID> eligibleSessionMembers, UUID fallbackKiller) {
        Set<UUID> eligible = Set.copyOf(Objects.requireNonNull(
                eligibleSessionMembers, "eligibleSessionMembers"));
        if (!eligible.isEmpty()) return eligible;
        return fallbackKiller == null ? Set.of() : Set.of(fallbackKiller);
    }
}
