package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonCompletionPolicyTest {
    @Test
    void onlyTheMarkedBossFromTheMatchingManagedInstanceCanComplete() {
        assertTrue(DungeonCompletionPolicy.isManagedBoss(
                "ancient-ruins", true, "ancient-ruins"));
        assertFalse(DungeonCompletionPolicy.isManagedBoss(
                "ancient-ruins", false, "ancient-ruins"));
        assertFalse(DungeonCompletionPolicy.isManagedBoss(
                "ancient-ruins", true, null));
        assertFalse(DungeonCompletionPolicy.isManagedBoss(
                "ancient-ruins-hidden", true, "ancient-ruins"));
    }

    @Test
    void completionUsesTheWholeEligiblePartyAndOnlyFallsBackWhenItIsEmpty() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID outsiderKiller = UUID.randomUUID();
        List<UUID> eligible = new ArrayList<>(List.of(first, second));

        Set<UUID> party = DungeonCompletionPolicy.recipientIds(eligible, outsiderKiller);
        eligible.clear();

        assertEquals(Set.of(first, second), party);
        assertThrows(UnsupportedOperationException.class, () -> party.add(UUID.randomUUID()));
        assertEquals(Set.of(outsiderKiller),
                DungeonCompletionPolicy.recipientIds(List.of(), outsiderKiller));
        assertTrue(DungeonCompletionPolicy.recipientIds(List.of(), null).isEmpty());
    }
}
