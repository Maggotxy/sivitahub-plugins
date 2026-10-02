package cn.originalrpg.api.event;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DungeonCompletedEventTest {
    @Test
    void snapshotsTheExactRecipientSetAndDoesNotExposeMutation() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Set<UUID> source = new HashSet<>(Set.of(first, second));

        DungeonCompletedEvent event = new DungeonCompletedEvent("ancient-ruins", source);
        source.clear();

        assertEquals("ancient-ruins", event.dungeonId());
        assertEquals(Set.of(first, second), event.recipientIds());
        assertThrows(UnsupportedOperationException.class,
                () -> event.recipientIds().add(UUID.randomUUID()));
        assertSame(DungeonCompletedEvent.getHandlerList(), event.getHandlers());
    }

    @Test
    void refusesAnAmbiguousCompletionSignal() {
        assertThrows(IllegalArgumentException.class,
                () -> new DungeonCompletedEvent(" ", Set.of(UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class,
                () -> new DungeonCompletedEvent("ancient-ruins", Set.of()));
    }
}
