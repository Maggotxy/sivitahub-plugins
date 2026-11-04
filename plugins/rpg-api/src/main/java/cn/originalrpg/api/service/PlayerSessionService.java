package cn.originalrpg.api.service;

import cn.originalrpg.api.model.PlayerSessionHandoff;
import cn.originalrpg.api.model.PlayerSessionLease;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * PostgreSQL/H2 authority for exclusive player sessions and fenced service calls.
 *
 * <p>Database methods use the provider's bounded worker executor and never fall
 * back to the caller thread. {@link #current} is a local, nonblocking snapshot;
 * it cannot authorize a write by itself. Every annotated actor operation uses
 * the original captured token in its own database transaction. Background reward
 * and recovery services do not acquire a recipient's player authority.</p>
 *
 * <p>Acquisition belongs before player-state admission. Renew while the same
 * connection is live; release on disconnect. A failed/expired renewal revokes
 * local admission and cannot resurrect its old token. The lifecycle adapter must
 * close that connection and wait for a new acquisition. Handoff reserves only
 * ownership: enabling a proxy network additionally requires durable player-state
 * transport, mailbox fencing, identity migration, and runtime fault validation.</p>
 */
public interface PlayerSessionService {
    /** Returns this process's captured authority without doing JDBC. */
    Optional<PlayerSessionLease> current(UUID playerId);

    /** Retries use the same connection token; a different live owner is never stolen. */
    CompletionStage<Optional<PlayerSessionLease>> acquire(UUID playerId, UUID connectionToken);

    /** Renews only this still-live token; empty means the caller has lost authority. */
    CompletionStage<Optional<PlayerSessionLease>> renew(PlayerSessionLease lease);

    /** Revokes local admission immediately, then releases only the exact database fence. */
    CompletionStage<Boolean> release(PlayerSessionLease lease);

    /** Fences source writes and reserves a live handoff for one target node. */
    CompletionStage<Optional<PlayerSessionHandoff>> prepareHandoff(
            PlayerSessionLease lease, String targetNodeId, UUID handoffId);

    /** The named target receives a new fence; duplicate acceptance reuses its original token. */
    CompletionStage<Optional<PlayerSessionLease>> acceptHandoff(
            PlayerSessionHandoff handoff, UUID connectionToken);

    /** Cancels an unclaimed offer on its source process, always issuing fresh authority. */
    CompletionStage<Optional<PlayerSessionLease>> cancelHandoff(
            PlayerSessionHandoff handoff, UUID newConnectionToken);

    /**
     * Runs a service invocation with the original actor authority. Invocation
     * itself runs on the caller thread; core JDBC submissions capture the scope
     * and verify it on their bounded workers. The scope is cleared in a finally
     * block and is never inherited by unrelated work. Do not perform Bukkit work
     * on JDBC workers or return lazy work to be invoked after this scope closes.
     */
    <T> CompletionStage<T> fenced(PlayerSessionLease actor, Supplier<CompletionStage<T>> invocation);
}
