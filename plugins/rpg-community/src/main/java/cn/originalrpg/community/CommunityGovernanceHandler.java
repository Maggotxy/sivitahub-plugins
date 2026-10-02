package cn.originalrpg.community;

import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityGovernanceAction;
import cn.originalrpg.api.model.CommunityRole;
import cn.originalrpg.api.model.CommunityRolePower;
import cn.originalrpg.api.service.CommunityGovernanceService;
import cn.originalrpg.api.service.CommunityRoleAssignmentService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Phase 6 治理子命令的行为实现（不依赖 Bukkit，可行为级测试）。
 *
 * <p>命令层只负责把玩家输入翻译成这里的调用，并把结果渲染成文案。
 * 七角色能力判定一律经过 {@link CommunityRolePower} 与服务层，本类不重复写角色表，
 * 只负责两件事：把「未启用」这条开关前置到写操作之前；把第二票落回库里
 * 待批准动作的真实目标（工程=动作键，角色变更=目标 UUID）。</p>
 */
final class CommunityGovernanceHandler {

    /**
     * 渲染结果：按文案键有序输出，命令层只负责发送。
     *
     * <p>用 {@link List} 而不是 Map 存消息，因为「列出七角色」这类输出是同一文案键的多行，
     * 用 Map 会互相覆盖。</p>
     */
    record Rendering(List<Message> messages) {
        Rendering() {
            this(new ArrayList<>());
        }

        record Message(String key, Map<String, ?> placeholders) {
            Message {
                placeholders = Map.copyOf(placeholders);
            }
        }

        void add(String key) {
            messages.add(new Message(key, Map.of()));
        }

        void add(String key, Map<String, ?> placeholders) {
            messages.add(new Message(key, placeholders));
        }

        List<String> keys() {
            return messages.stream().map(Message::key).toList();
        }
    }

    /** 是否具备某项能力（真实调用服务层，供命令层给出准确提示）。 */
    @FunctionalInterface
    interface CapabilityProbe {
        CompletableFuture<Boolean> can(UUID playerId, CommunityRolePower.Capability capability);

        /**
         * 是否是治理成员（治理通道准入）。
         *
         * <p>准入与能力是两条判定：准入回答「能不能申请走双批」，
         * {@link CommunityRolePower.Capability#APPROVE} 回答「能不能投票」。
         * 默认实现只做兜底，真实实现由命令层接到服务层的
         * {@code CommunityGovernanceService#isGovernanceMember}。</p>
         */
        default CompletableFuture<Boolean> isGovernanceMember(UUID playerId) {
            return can(playerId, CommunityRolePower.Capability.GOVERNANCE_ACTION);
        }
    }

    private final CommunityGovernanceService governance;
    private final CommunityRoleAssignmentService roleAssignments;
    private final CapabilityProbe capability;
    private final boolean enabled;

    CommunityGovernanceHandler(
            CommunityGovernanceService governance,
            CommunityRoleAssignmentService roleAssignments,
            CapabilityProbe capability,
            boolean enabled
    ) {
        this.governance = governance;
        this.roleAssignments = roleAssignments;
        this.capability = capability;
        this.enabled = enabled;
    }

    boolean available() {
        return governance != null && roleAssignments != null && capability != null;
    }

    boolean enabled() {
        return enabled;
    }

    /** 写操作在「未启用 / 未接线」时必须 fail closed，且不得触达服务层。 */
    private boolean gatesOpen(Rendering out) {
        if (!enabled || !available()) {
            out.add("community.governance.disabled");
            return false;
        }
        return true;
    }

    /** {@code /community role [name]}、{@code /community roles}：能力表展示。 */
    Rendering roleLookup(String rawRole) {
        Rendering out = new Rendering();
        if (rawRole == null) {
            for (CommunityRole role : CommunityRole.values()) appendRole(out, role);
            return out;
        }
        Optional<CommunityRole> role = CommunityRole.parse(rawRole);
        if (role.isEmpty()) {
            out.add("community.governance.role-unknown", Map.of("role", rawRole));
            return out;
        }
        appendRole(out, role.orElseThrow());
        return out;
    }

    private void appendRole(Rendering out, CommunityRole role) {
        // 能力展示直接选文案键，避免在 messages.text 的占位符里做动态拼接。
        String lineKey = role.isGovernance() ? "community.governance.role-approver"
                : CommunityRolePower.isDomainRole(role) ? "community.governance.role-domain"
                : "community.governance.role-member";
        out.add("community.governance.role-line", Map.of(
                "roleKey", CommunityRolePower.displayKey(role),
                "detailKey", lineKey,
                "moderateKey", role.canModerate()
                        ? "community.governance.moderator" : "community.governance.non-moderator"));
    }

    /** {@code /community assign <player>}：发起角色变更审批（先过治理通道准入）。 */
    CompletableFuture<Rendering> assign(UUID actor, UUID target, String idempotencyKey) {
        Rendering out = new Rendering();
        if (!gatesOpen(out)) return CompletableFuture.completedFuture(out);
        CapabilityProbe probe = capability;
        // 准入走治理通道判定（isGovernance），不代入审批能力：领域角色/普通成员都拿不到角色任命通道。
        return probe.isGovernanceMember(actor)
                .thenCompose(allowed -> {
                    if (!allowed) {
                        // 非治理成员不能发起角色变更；不把必然失败的操作发到服务层。
                        return CompletableFuture.completedFuture(CommunityApprovalOutcome.of(
                                CommunityApprovalOutcome.Status.NOT_ALLOWED,
                                CommunityGovernanceAction.ROLE_GRANT));
                    }
                    return roleAssignments.request(actor, target, CommunityRole.RECRUITER, idempotencyKey);
                })
                .thenApply(outcome -> render(out, outcome));
    }

    /** {@code /community approve <action>}：投出一次批准（第二票落在真实目标上）。 */
    CompletableFuture<Rendering> approve(UUID actor, CommunityGovernanceAction action) {
        Rendering out = new Rendering();
        if (action == null) {
            out.add("community.governance.action-unknown", Map.of("action", ""));
            return CompletableFuture.completedFuture(out);
        }
        if (!gatesOpen(out)) return CompletableFuture.completedFuture(out);
        // 能力查询只用于给出准确提示；真正的拒绝仍然由服务层做出。
        return capability.can(actor, CommunityRolePower.Capability.APPROVE)
                .thenCompose(allowed -> allowed
                        ? pendingSubject(actor, action).thenCompose(subject -> governance.approve(
                                actor, action, subject.orElse(null)))
                        : CompletableFuture.completedFuture(CommunityApprovalOutcome.of(
                                CommunityApprovalOutcome.Status.NOT_ALLOWED, action)))
                .thenApply(outcome -> render(out, outcome));
    }

    /** {@code /community approvals <action>}：列出该动作当前的批准记录。 */
    CompletableFuture<Rendering> approvals(UUID actor, CommunityGovernanceAction action) {
        Rendering out = new Rendering();
        if (action == null) {
            out.add("community.governance.action-unknown", Map.of("action", ""));
            return CompletableFuture.completedFuture(out);
        }
        if (!gatesOpen(out)) return CompletableFuture.completedFuture(out);
        return pendingSubject(actor, action)
                .thenCompose(subject -> governance.approvals(actor, action, subject.orElse(null))
                        .thenApply(votes -> new PendingApprovals(subject.isPresent() ? action : null, votes)))
                .thenApply(snapshot -> {
                    if (snapshot.action() == null) {
                        out.add("community.governance.no-pending");
                        return out;
                    }
                    out.add("community.governance.pending", Map.of(
                            "action", snapshot.action().key(),
                            "required", CommunityRolePower.REQUIRED_APPROVALS));
                    for (CommunityApprovalOutcome.GrantedApproval vote : snapshot.votes()) {
                        out.add("community.governance.approval-entry", Map.of(
                                "player", vote.actor().toString(),
                                "roleKey", CommunityRolePower.displayKey(vote.role())));
                    }
                    return out;
                });
    }

    /** {@code /community deny <action>}：废除待批准动作（需要 DENY 能力）。 */
    CompletableFuture<Rendering> deny(UUID actor, CommunityGovernanceAction action) {
        Rendering out = new Rendering();
        if (action == null) {
            out.add("community.governance.action-unknown", Map.of("action", ""));
            return CompletableFuture.completedFuture(out);
        }
        if (!gatesOpen(out)) return CompletableFuture.completedFuture(out);
        return pendingSubject(actor, action)
                .thenCompose(subject -> governance.deny(actor, action, subject.orElse(null)))
                .thenApply(outcome -> render(out, outcome));
    }

    /** 可被任命的角色名（OWNER 与治理角色不可经审批任命）。 */
    List<String> assignableRoleNames() {
        List<String> names = new ArrayList<>();
        for (CommunityRole role : CommunityRole.values()) {
            CommunityRolePower.assignable(role.name()).ifPresent(assignable -> names.add(assignable.name()));
        }
        return List.copyOf(names);
    }

    private static Rendering render(Rendering out, CommunityApprovalOutcome outcome) {
        String suffix = outcome.status().name().toLowerCase(Locale.ROOT).replace('_', '-');
        out.add("community.governance." + suffix, Map.of(
                "action", outcome.action().key(),
                "count", outcome.approvals().size(),
                "required", outcome.requiredApprovals()));
        if (outcome.effective()) out.add("community.governance.effective");
        return out;
    }

    /**
     * 该玩家所在社群中该动作当前待批准记录的真实目标。
     *
     * <p>工程类动作的目标就是动作键；角色变更的目标是目标玩家 UUID，
     * 必须从库里的审批记录读回来，命令层不得猜测。目标不存在时返回 empty，
     * 由服务层的目标校验给出准确状态。</p>
     */
    private CompletableFuture<Optional<String>> pendingSubject(
            UUID actor, CommunityGovernanceAction action
    ) {
        return governance.approvals(actor, action, action.key())
                .thenCompose(votes -> votes.isEmpty()
                        ? CompletableFuture.completedFuture(Optional.<String>empty())
                        : governance.subjects(actor, action))
                .toCompletableFuture();
    }

    private record PendingApprovals(
            CommunityGovernanceAction action,
            List<CommunityApprovalOutcome.GrantedApproval> votes
    ) {
    }
}
