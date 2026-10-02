package cn.originalrpg.api.service;

import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityGovernanceAction;
import cn.originalrpg.api.model.CommunityRolePower;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Phase 6 社群治理：七角色能力判定 + 双批审批 + 审计落库。
 *
 * <p>所有能力判定都必须经过实现方，调用点不得自行比较角色常量；
 * 治理开关关闭时写操作一律返回
 * {@link CommunityApprovalOutcome.Status#DISABLED}，即「接线但不启用」。</p>
 */
public interface CommunityGovernanceService {
    /** 治理开关（C-005 Kill Switch 的本地投影，默认关闭）。 */
    boolean enabled();

    /** 该成员当前是否具备某项能力；角色查询全部由服务层完成。 */
    CompletionStage<Boolean> can(UUID playerId, CommunityRolePower.Capability capability);

    /**
     * 该成员当前是否是治理成员（能否进入治理通道）。
     *
     * <p>存在的理由：命令层在「治理通道准入」这一点上需要判定，
     * 但不得自己去读角色表。与 {@link #can} 的 APPROVE 是两条口径：
     * 本条回答「能不能申请走双批」，APPROVE 回答「能不能投票」。</p>
     */
    CompletionStage<Boolean> isGovernanceMember(UUID playerId);

    /** 该成员所处社群当前待处理的动作。 */
    CompletionStage<Optional<CommunityGovernanceAction>> pending(UUID playerId);

    /** 提交一个需要双批的动作，同时写入第一条审批记录。 */
    CompletionStage<CommunityApprovalOutcome> submit(
            UUID actor,
            CommunityGovernanceAction action,
            String subject,
            String idempotencyKey
    );

    /** 追加一次批准；同一人不能凑两次批准，提交人不能批准自己的动作。 */
    CompletionStage<CommunityApprovalOutcome> approve(
            UUID actor, CommunityGovernanceAction action, String subject);

    /** 追加一次批准，并在审批生效时把 {@code target} 的角色改为 {@code targetRole}。 */
    CompletionStage<CommunityApprovalOutcome> approveAssignment(
            UUID actor, UUID target, String targetRole, String idempotencyKey);

    /** 废除一个待处理动作；只有治理角色可以废除。 */
    CompletionStage<CommunityApprovalOutcome> deny(
            UUID actor, CommunityGovernanceAction action, String subject);

    /** 该玩家所在社群中，某动作当前待批准记录对应的具体目标（工程=动作键，角色变更=目标 UUID）。 */
    CompletionStage<Optional<String>> subjects(UUID playerId, CommunityGovernanceAction action);

    /** 读取某动作当前的批准记录（角色 + UUID）。 */
    CompletionStage<List<CommunityApprovalOutcome.GrantedApproval>> approvals(
            UUID playerId, CommunityGovernanceAction action, String subject);
}
