package cn.originalrpg.api.model;

/** Durable equipment mutations that exchange player-owned items for one deterministic outcome. */
public enum EquipmentOperationKind {
    FORGE,
    REFINEMENT,
    LIMIT_BREAK,
    // Persisted by ordinal in recovery envelopes. Append new values only.
    UNBIND,
    LEGACY,
    SALVAGE,
    // W3-A3：分解（SALVAGE）走到底的终态。
    DESTROY,
    // W6-A2：赛季调谐。不是玩家操作，由换季结算（C-010）在收口旧赛季时调用，
    // 把旧赛季的装备身份收口成 SEASON_ATTUNED 再随唯一邮箱通道交还持有者。
    SEASON_ATTUNEMENT
}
