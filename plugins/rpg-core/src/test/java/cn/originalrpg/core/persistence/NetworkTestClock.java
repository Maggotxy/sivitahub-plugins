package cn.originalrpg.core.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

final class NetworkTestClock extends Clock {
    private final AtomicReference<Instant> value = new AtomicReference<>(Instant.now().plusSeconds(60));
    void advance(Duration delta) { value.updateAndGet(current -> current.plus(delta)); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return value.get(); }
}
