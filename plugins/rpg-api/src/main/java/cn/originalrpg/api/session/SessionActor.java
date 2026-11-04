package cn.originalrpg.api.session;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Identifies the player who authorizes a service operation, never its beneficiary.
 *
 * <p>When player leases are enabled, the core service facade captures that actor's
 * current immutable lease before queuing JDBC work. The same transaction must
 * verify the captured fence before any SQL and again before committing. Missing,
 * released, transferred, or expired authority fails closed. The annotation does
 * not grant authority to a UUID supplied by an untrusted client.</p>
 *
 * <p>Use the zero-based {@link #argument()} of a UUID parameter. For an immutable
 * request record, {@link #member()} names a public, zero-argument accessor that
 * returns a UUID, for example {@code playerId}. Do not annotate a recipient of
 * a durable reward, a refund, or background recovery merely because it has a UUID.
 * Delayed application work should retain its original lease and use
 * {@code PlayerSessionService.fenced}; it must not borrow a replacement login's
 * authority. Service implementations remain responsible for their own gameplay,
 * ownership, permission, idempotency, and ledger checks.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SessionActor {
    int argument();

    String member() default "";
}
