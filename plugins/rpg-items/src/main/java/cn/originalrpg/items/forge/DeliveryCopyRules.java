package cn.originalrpg.items.forge;

import java.util.UUID;

/**
 * 交付标记拷贝的纯决策规则。
 *
 * <p>「交付中物品不可被再次操作」这条幂等保护依赖两件事：出站副本必须带标记，
 * 且标记必须归属于某个具体操作。这里把该决策从 Bukkit 的 ItemStack 上剥离出来，
 * 使「原物 / 拷贝 / 重复交付」三者的关系可以被行为级测试直接断言。</p>
 */
final class DeliveryCopyRules {

    private DeliveryCopyRules() {
    }

    /** 出站副本的标记来源。{@link #PRESERVE} 表示沿用原物已有的标记。 */
    enum MarkerSource {
        /** 原物已带标记：副本沿用同一个标记，不覆盖成另一个操作。 */
        PRESERVE,
        /** 原物未带标记：副本打上本次操作的标记。 */
        ASSIGN,
        /** 无归属操作：副本不带标记。 */
        NONE
    }

    /**
     * 决定出站副本该如何打标记。
     *
     * @param alreadyMarked 原物是否已带操作标记
     * @param operationId   本次结算的操作号；{@code null} 表示没有归属操作
     */
    static MarkerSource markerSource(boolean alreadyMarked, UUID operationId) {
        if (alreadyMarked) return MarkerSource.PRESERVE;
        return operationId == null ? MarkerSource.NONE : MarkerSource.ASSIGN;
    }

    /**
     * 副本最终携带的标记。
     *
     * @param originMarker 原物当前携带的标记（无标记传 {@code null}）
     * @param operationId  本次操作号
     * @return 副本应携带的标记；{@code null} 表示副本不带标记
     */
    static UUID deliveryMarker(UUID originMarker, UUID operationId) {
        return switch (markerSource(originMarker != null, operationId)) {
            case PRESERVE -> originMarker;
            case ASSIGN -> operationId;
            case NONE -> null;
        };
    }

    /**
     * 已标记物品参与新操作时是否必须被拒。
     *
     * <p>标记等于「该物品正被一个未结算的操作托管」。允许它进入新操作会让同一份
     * 资产被两个操作同时引用，即重复交付。仅当标记归属同一个操作时才是幂等重放，
     * 不在此处拒绝。</p>
     */
    static boolean refusesSecondOperation(UUID existingMarker, UUID operationId) {
        if (existingMarker == null) return false;
        return !existingMarker.equals(operationId);
    }
}
