package cn.originalrpg.api.service;

import cn.originalrpg.api.model.CommunityLifecycleAction;
import cn.originalrpg.api.model.CommunityLifecycleAudit;
import cn.originalrpg.api.model.CommunityLifecyclePolicy;
import cn.originalrpg.api.model.CommunityLifecycleResult;
import cn.originalrpg.api.model.CommunityLifecycleSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Core authority for timed succession and dissolution, sharing the existing community,
 * membership and approval rows. All database work is asynchronous; no Bukkit objects cross
 * this boundary. Callers must discard UI callbacks from expired authenticated sessions.
 *
 * <p>Two distinct, still-qualified governors are mandatory, including the proposer's first
 * signature. Small communities receive no implicit administrator or single-signature bypass.
 * Execution rechecks authority and timing under one transaction. Dissolution preserves names,
 * contribution history, personal honors and audit records; it never refunds or destroys items.</p>
 */
public interface CommunityLifecycleService {
    boolean enabled();

    CommunityLifecyclePolicy policy();

    CompletionStage<Optional<CommunityLifecycleSnapshot>> status(UUID playerId);

    /**
     * Record only a currently authenticated connection, never an offline recipient or replay.
     * The service supplies the timestamp. Incumbent activity cancels pending succession.
     */
    CompletionStage<Void> recordAuthenticatedActivity(UUID playerId);

    /** Succession nominates the proposing governor; dissolution can only be proposed by the owner. */
    CompletionStage<CommunityLifecycleResult> propose(UUID actorId, CommunityLifecycleAction action,
                                                     String idempotencyKey);

    CompletionStage<CommunityLifecycleResult> approve(UUID actorId, UUID proposalId);

    /** Explicit execution after the cooling deadline; approval does not schedule destructive work. */
    CompletionStage<CommunityLifecycleResult> execute(UUID actorId, UUID proposalId);

    CompletionStage<CommunityLifecycleResult> cancel(UUID actorId, UUID proposalId);

    /** Public, bounded audit history remains readable after the community is dissolved. */
    CompletionStage<List<CommunityLifecycleAudit>> history(UUID communityId, int limit);
}
