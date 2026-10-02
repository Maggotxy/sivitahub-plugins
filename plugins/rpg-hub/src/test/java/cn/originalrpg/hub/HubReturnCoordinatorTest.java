package cn.originalrpg.hub;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HubReturnCoordinatorTest {
    @Test
    void walkingAndVisitCallbacksShareOnePhysicalReturn() {
        HubReturnCoordinator<String> returns = new HubReturnCoordinator<>();
        UUID player = UUID.randomUUID();
        AtomicInteger walking = new AtomicInteger();
        AtomicInteger visit = new AtomicInteger();

        assertEquals(HubReturnCoordinator.Registration.START,
                returns.register(player, "wayfinder", success -> walking.incrementAndGet()));
        assertEquals(HubReturnCoordinator.Registration.JOINED,
                returns.register(player, "visitor", success -> visit.incrementAndGet()));
        assertEquals(2, returns.subscriberCount(player));

        returns.finish(player).forEach(callback -> callback.accept(true));
        assertEquals(1, walking.get());
        assertEquals(1, visit.get());
        assertEquals(0, returns.subscriberCount(player));
    }

    @Test
    void sameSubscriberIsReplacedAndCompletionIsOneShot() {
        HubReturnCoordinator<String> returns = new HubReturnCoordinator<>();
        UUID player = UUID.randomUUID();
        AtomicInteger stale = new AtomicInteger();
        AtomicInteger latest = new AtomicInteger();

        returns.register(player, "visitor", success -> stale.incrementAndGet());
        returns.register(player, "visitor", success -> latest.incrementAndGet());
        assertEquals(1, returns.subscriberCount(player));

        returns.finish(player).forEach(callback -> callback.accept(true));
        returns.finish(player).forEach(callback -> callback.accept(true));
        assertEquals(0, stale.get());
        assertEquals(1, latest.get());
    }
}
