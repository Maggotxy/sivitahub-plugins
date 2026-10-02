package cn.originalrpg.api.model;

public enum CommunityRole {
    OWNER,      // LEADER alias —宪法 Phase 6 LEADER
    OFFICER,    // DEPUTY alias — Phase 6 DEPUTY
    TREASURER,
    BUILDER,
    EVENT_HOST,
    RECRUITER,
    MEMBER;

    // Phase 6 aliases for new code
    public static final CommunityRole LEADER = OWNER;
    public static final CommunityRole DEPUTY = OFFICER;

    public boolean canModerate() {
        return this == OWNER || this == OFFICER || this == TREASURER;
    }

    public boolean canApprove() {
        // 双人审批：LEADER/DEPUTY/TREASURER 可批
        return this == OWNER || this == OFFICER || this == TREASURER;
    }

    public boolean isGovernance() {
        return this == OWNER || this == OFFICER || this == TREASURER;
    }
}
