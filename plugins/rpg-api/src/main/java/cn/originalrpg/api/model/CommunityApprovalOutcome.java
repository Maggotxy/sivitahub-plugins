package cn.originalrpg.api.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 一次双批审批操作的结果：状态 + 该动作当前的批准人（角色 + UUID）。 */
public record CommunityApprovalOutcome(
        Status status,
        CommunityGovernanceAction action,
        Optional<String> subject,
        List<GrantedApproval> approvals,
        UUID actor,
        long requiredApprovals
) {
    public CommunityApprovalOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(subject, "subject");
        approvals = List.copyOf(Objects.requireNonNull(approvals, "approvals"));
    }

    public enum Status {
        /** 该次操作已记录/已生效。 */
        SUCCESS,
        /** 该动作已经由这个人批准过；不能凑两次批准。 */
        ALREADY_APPROVED,
        /** 这个人自己提交的动作不能由本人批准。 */
        SELF_APPROVAL,
        /** 本次操作先前已经结算（同一动作已完成）。 */
        DUPLICATE,
        /** 非法输入。 */
        INVALID,
        /** 调用者不是该社群成员。 */
        NOT_MEMBER,
        /** 角色能力不足（例如非治理角色投批、缺少 BUILDER 资格提交工程）。 */
        NOT_ALLOWED,
        /** 该社群没有待处理的动作。 */
        NO_PENDING_ACTION,
        /** 找不到目标成员。 */
        TARGET_NOT_MEMBER,
        /**【接线但不启用】治理开关未开启。 */
        DISABLED
    }

    public static CommunityApprovalOutcome of(Status status, CommunityGovernanceAction action) {
        return new CommunityApprovalOutcome(status, action, Optional.empty(), List.of(), null,
                CommunityRolePower.REQUIRED_APPROVALS);
    }

    public boolean effective() {
        return status == Status.SUCCESS && approvals.size() >= requiredApprovals;
    }

    /** 该状态是否意味着审批记录已被写入。 */
    public boolean recorded() {
        return status == Status.SUCCESS;
    }

    public record GrantedApproval(UUID actor, CommunityRole role) {
        public GrantedApproval {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(role, "role");
        }
    }
}
