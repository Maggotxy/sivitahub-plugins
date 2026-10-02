package cn.originalrpg.api.rules;

import cn.originalrpg.api.model.EquipmentOperationKind;

import java.util.Set;

/**
 * Phase 2: canonical 12-state equipment lifecycle (C-008, C-009).
 * Single authoritative state per item_id; transitions are validated here
 * before any PDC or DB mutation.
 */
public final class EquipmentLifecycle {
    public enum State {
        CREATED,            // forged, not yet bound
        BOUND,              // first combat/refine/limitbreak
        UNBOUND,            // after /unbind fee, tradeable
        LISTED,             //托管 PREPARED/ACTIVE
        SOLD,               // settled, new owner
        SALVAGED,           // decomposed to bound essence
        SACRIFICED,         //献祭前置 (reserved: dedicated /sacrifice exit, Phase 4)
        LEGACY_EXTRACTED,   //蓝图已提
        SEASON_ATTUNED,     //新赛季调谐
        DESTROYED,          //明确销毁
        QUARANTINED,        //反作弊隔离
        RESTORED            //核实后恢复
    }

    private static final Set<State> TERMINAL = Set.of(State.SALVAGED, State.LEGACY_EXTRACTED, State.DESTROYED);
    private static final Set<State> TRADEABLE = Set.of(State.UNBOUND, State.LISTED);

    private EquipmentLifecycle() {}

    public static boolean isValidTransition(State from, State to) {
        if (from == null || to == null) return false;
        if (TERMINAL.contains(from)) return false;
        return switch (from) {
            case CREATED -> to == State.BOUND || to == State.UNBOUND || to == State.SALVAGED
                    || to == State.QUARANTINED;
            // DESTROYED 是 BOUND/UNBOUND 上「分解到底」的第二个终态出口
            // （W3-A3）：账本用它区分「分解到精华」（SALVAGED）与「明确销毁」。
            case BOUND -> to == State.UNBOUND || to == State.LISTED || to == State.SALVAGED
                    || to == State.SACRIFICED || to == State.LEGACY_EXTRACTED
                    || to == State.SEASON_ATTUNED || to == State.DESTROYED || to == State.QUARANTINED;
            case UNBOUND -> to == State.BOUND || to == State.LISTED || to == State.SALVAGED
                    || to == State.SACRIFICED || to == State.LEGACY_EXTRACTED
                    || to == State.SEASON_ATTUNED || to == State.DESTROYED || to == State.QUARANTINED;
            case LISTED -> to == State.SOLD || to == State.UNBOUND || to == State.QUARANTINED;
            case SOLD -> to == State.BOUND || to == State.UNBOUND || to == State.LISTED || to == State.QUARANTINED;
            case SALVAGED, LEGACY_EXTRACTED, DESTROYED -> false;
            case SACRIFICED -> to == State.LEGACY_EXTRACTED || to == State.DESTROYED || to == State.QUARANTINED;
            case SEASON_ATTUNED -> to == State.BOUND || to == State.SALVAGED || to == State.QUARANTINED;
            case QUARANTINED -> to == State.RESTORED || to == State.DESTROYED;
            // A verified thaw returns the identity to circulation. Exits stay
            // open (a freeze is not a life sentence), but UNBIND is not: the
            // fee targets live ownership, and the command side cannot tell
            // what the frozen PDC carried.
            case RESTORED -> to == State.BOUND || to == State.UNBOUND || to == State.LISTED
                    || to == State.SALVAGED || to == State.LEGACY_EXTRACTED || to == State.QUARANTINED;
        };
    }

    /**
     * Authoritative post-effect state of a durable equipment operation, or
     * {@code null} when the operation must be refused because its transition
     * is not legal. {@code FORGE} introduces a new identity and is always
     * born {@code CREATED}; every other kind mutates an existing item.
     */
    public static State afterOperation(EquipmentOperationKind kind, State current) {
        if (kind == null || current == null) return null;
        if (kind == EquipmentOperationKind.FORGE) return State.CREATED;
        if (isTerminal(current)) return null;
        // Escrowed gear is market-only, and quarantined gear is frozen for audit;
        // no player operation may mutate either (C-009, C-013).
        if (current == State.LISTED || current == State.QUARANTINED) return null;
        // A paid unbind only makes sense on gear that currently has an owner.
        // UNBOUND is tolerated because combat binding is a synchronous PDC
        // mutation that never writes the ledger: a row left behind by a market
        // collection can read UNBOUND while the item itself is bound again. The
        // command side still refuses the fee unless the PDC really has an owner,
        // so the self-transition can never be a wasted charge.
        if (kind == EquipmentOperationKind.UNBIND && current != State.BOUND && current != State.UNBOUND)
            return null;
        State next = switch (kind) {
            case REFINEMENT, LIMIT_BREAK -> State.BOUND;
            case UNBIND -> State.UNBOUND;
            case LEGACY -> State.LEGACY_EXTRACTED;
            // 分解与明确销毁是同一类「回收到底、物品永久离开流通」的操作，
            // 差别只在第一次输入是否已足以表达意图：/salvage 一步确认即 SALVAGED，
            // /destroy 需要再确认一次，落成终态 DESTROYED。
            case SALVAGE -> State.SALVAGED;
            case DESTROY -> State.DESTROYED;
            case FORGE -> throw new IllegalStateException("handled above");
        };
        return current == next || isValidTransition(current, next) ? next : null;
    }

    /**
     * Conservative seed for items that predate the state registry. Refinement
     * and limit-break accept unowned, never-listed gear on the command side, so
     * they adopt the unbound CREATED seed; the ownership rules there (not the
     * lifecycle) still enforce whose gear may be processed. Operations that
     * demand a bound owner (unbind fee, salvage, legacy) adopt BOUND, matching
     * the invariant the command side already checks.
     */
    public static State seedForOperation(EquipmentOperationKind kind) {
        if (kind == null) return null;
        return switch (kind) {
            case REFINEMENT, LIMIT_BREAK, FORGE -> State.CREATED;
            case UNBIND, SALVAGE, LEGACY, DESTROY -> State.BOUND;
        };
    }

    /**
     * 终态是否是「分解走到底」的那一类（SALVAGED / DESTROYED）。
     *
     * <p>C-008 的两条退出路径——/salvage 分解与 /destroy 明确销毁——最终都
     * 必须走完整个操作并产出绑定精华；只发一条结果消息、物品未变的不变（no-op）
     * 操作会把已花的金币留在一个从未推进的账本身份上，因此判定为不合法。</p>
     */
    public static boolean descendsToTerminalExit(State from, State to) {
        return (from == State.BOUND || from == State.UNBOUND)
                && (to == State.SALVAGED || to == State.DESTROYED);
    }

    public static boolean isExitPath(State state) {
        return state == State.SALVAGED || state == State.SACRIFICED || state == State.LEGACY_EXTRACTED
                || state == State.SEASON_ATTUNED || state == State.DESTROYED;
    }

    public static boolean isTerminal(State state) {
        return TERMINAL.contains(state);
    }

    public static boolean isTradeable(State state) {
        return TRADEABLE.contains(state);
    }

    public static boolean requiresExitPath() {
        // C-008: every persistently farmable high-value item type must declare at least one exit
        // TODO(W3-A3-exit-path-remaining)：C-008 列出的第五条退出路径「赛季调谐」
        // （SEASON_ATTUNED）在当前 Phase 下没有触发者，本方法会对它返回 false，
        // 这正是「只声明未接线」的机械证据。它属于 Phase 4「装备退出与市场 V2」
        // 的新赛季调谐玩法，接入时必须走本状态机，不得新造第二套状态机，因此
        // 该路径登记为「待 Phase 7 内容管线接入」，不在 rpg-items 里硬造指令。
        return true;
    }
}
