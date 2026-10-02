package cn.originalrpg.api.rules;

import cn.originalrpg.api.model.SeasonAttunementRecord;

/**
 * 赛季调谐的规则面：谁触发、落哪个态、哪些身份可以调谐。
 *
 * <p>与 {@link EquipmentLifecycle} 的关系是「消费」而不是「复制」：
 * 状态机仍是唯一权威，这里只回答接线问题，不重写迁移表。</p>
 */
public final class SeasonAttunementRules {
    /** 触发者只有换季结算（C-010）。 */
    public static final String TRIGGER = "SeasonSettlementService";

    private SeasonAttunementRules() {
    }

    /**
     * 该身份是否可以在此刻被调谐。
     *
     * <p>不可调谐的三类：已经在终态（分解/传承/销毁后不可复活）、
     * 已被隔离（C-013 冻结期间不得处置）、以及本来就已是调谐态（重放不是新事实）。</p>
     */
    public static boolean attunable(EquipmentLifecycle.State current) {
        if (current == null) return false;
        if (EquipmentLifecycle.isTerminal(current)) return false;
        if (current == EquipmentLifecycle.State.QUARANTINED) return false;
        if (current == EquipmentLifecycle.State.SEASON_ATTUNED) return false;
        return EquipmentLifecycle.isValidTransition(current, EquipmentLifecycle.State.SEASON_ATTUNED);
    }

    /** 调谐结果态必须与 C-008 登记的那个态一致，否则本路径实现即失效。 */
    public static boolean outcomeIsAttuned(EquipmentLifecycle.State state) {
        return state == SeasonAttunementRecord.REQUIRED_STATE;
    }

    /** 幂等键形状：换季结算是唯一触发者，键必须带上结算身份。 */
    public static String settlementKey(String settlementId, java.util.UUID ownerId) {
        return "season-attune:" + settlementId + ":" + ownerId;
    }
}
