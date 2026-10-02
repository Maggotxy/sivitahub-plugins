package cn.originalrpg.api.service;

import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityRole;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Phase 6 角色变更入口：把角色变更固定到「双批审批」这一条权威路径上。
 *
 * <p>OWNER 只能通过既有的 {@code /community transfer} 流程更换，本接口拒绝任命 OWNER。</p>
 */
public interface CommunityRoleAssignmentService {
    /**
     * 请求把 {@code target} 的角色改为 {@code role}。
     *
     * <p>调用者必须有 {@code ASSIGN_ROLE} 能力；提交后需要第二名治理角色批准才生效，
     * 生效时返回 {@link CommunityApprovalOutcome#effective()} 为 true 的结果。</p>
     */
    CompletionStage<CommunityApprovalOutcome> request(
            UUID actor, UUID target, CommunityRole role, String idempotencyKey);
}
