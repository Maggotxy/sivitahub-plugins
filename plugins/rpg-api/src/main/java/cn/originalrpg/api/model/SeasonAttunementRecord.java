package cn.originalrpg.api.model;

import cn.originalrpg.api.rules.EquipmentLifecycle;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次赛季调谐的落库记录（C-008 的第五条退出路径）。
 *
 * <p>状态流转由 {@link EquipmentLifecycle} 决定，这里只做两件事：
 * 声明「触发者是换季结算」与「结果态必须是 SEASON_ATTUNED」，
 * 好让调用方与测试能直接断言这两条，而不是靠读服务源码相信它。</p>
 *
 * @param seasonId 被收口的旧赛季
 * @param itemId   被调谐的装备身份
 * @param ownerId  原持有者；结算不换主，交还时按此投递
 * @param state    结果态（实现必须写回 {@link #REQUIRED_STATE}）
 */
public record SeasonAttunementRecord(
        String seasonId,
        UUID itemId,
        UUID ownerId,
        String state
) {
    /** C-008：调谐唯一认可的结果态。 */
    public static final EquipmentLifecycle.State REQUIRED_STATE = EquipmentLifecycle.State.SEASON_ATTUNED;

    public SeasonAttunementRecord {
        seasonId = Objects.requireNonNull(seasonId, "seasonId").strip();
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(ownerId, "ownerId");
        state = Objects.requireNonNull(state, "state");
    }

    public boolean attuned() {
        return REQUIRED_STATE.name().equals(state);
    }
}
