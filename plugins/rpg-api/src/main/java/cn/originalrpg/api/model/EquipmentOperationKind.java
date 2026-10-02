package cn.originalrpg.api.model;

/** Durable equipment mutations that exchange player-owned items for one deterministic outcome. */
public enum EquipmentOperationKind {
    FORGE,
    REFINEMENT,
    LIMIT_BREAK,
    // Persisted by ordinal in recovery envelopes. Append new values only.
    UNBIND,
    LEGACY,
    SALVAGE
}
