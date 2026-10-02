package cn.originalrpg.api.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Phase 6 七角色。枚举只描述身份，能力判定统一走 {@link CommunityRolePower}。
 */
public enum CommunityRole {
    OWNER,      // LEADER alias — 宪法 Phase 6 LEADER
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

    /** 解析角色名；兼容既有别名 LEADER/DEPUTY，非法值返回 empty，调用方必须 fail closed。 */
    public static Optional<CommunityRole> parse(String raw) {
        if (raw == null) return Optional.empty();
        String normalized = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("LEADER".equals(normalized)) return Optional.of(OWNER);
        if ("DEPUTY".equals(normalized)) return Optional.of(OFFICER);
        for (CommunityRole role : values()) {
            if (role.name().equals(normalized)) return Optional.of(role);
        }
        return Optional.empty();
    }
}
