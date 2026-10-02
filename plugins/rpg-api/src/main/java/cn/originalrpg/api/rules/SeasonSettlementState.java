package cn.originalrpg.api.rules;

/**
 * Phase 5: soft-season settlement transaction machine (C-010).
 * States must be persisted in rpg_season_settlements; only forward transitions allowed
 * except FAILED/ROLLED_BACK.
 */
public enum SeasonSettlementState {
    DRAFT,
    PREVIEWED,
    APPROVED,
    FREEZING,
    SETTLING,
    VERIFYING,
    COMPLETED,
    FAILED,
    ROLLED_BACK;

    public boolean canTransitionTo(SeasonSettlementState next) {
        return switch (this) {
            case DRAFT -> next == PREVIEWED || next == FAILED;
            case PREVIEWED -> next == APPROVED || next == FAILED;
            case APPROVED -> next == FREEZING || next == FAILED;
            case FREEZING -> next == SETTLING || next == FAILED;
            case SETTLING -> next == VERIFYING || next == FAILED;
            case VERIFYING -> next == COMPLETED || next == FAILED;
            case COMPLETED, FAILED, ROLLED_BACK -> false;
        } || (this == FAILED && next == ROLLED_BACK);
    }
}
