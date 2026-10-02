package cn.originalrpg.market;

import java.util.ArrayList;
import java.util.List;

/**
 * 交付冻结判定：邮箱交付期间挂在物品上的临时凭据所覆盖的**全部容器槽位**。
 *
 * <p>凭据可以停留在三个地方：玩家物品栏、鼠标游标、以及拖拽发起时的旧游标。
 * 旧实现只扫描物品栏，游标槽不在范围内，于是断线重连后的恢复流程看不到游标上的
 * 凭据：它既不会被清理，也不会触发 {@code markDelivered}。物品随后仍能进入背包，
 * 而数据库里的投递行仍是 PENDING —— 这就是重复领取窗口。</p>
 *
 * <p>判定顺序（互不覆盖的关键）：
 * <ol>
 *   <li><b>游标优先</b>：先看 {@code cursor}，再看 {@code oldCursor}，最后看物品栏。
 *       玩家物品栏永远不含游标槽，后写的容器槽位不会与被覆盖的游标槽位同名。</li>
 *   <li><b>拖拽槽位集合、按键槽位、副手槽位</b>逐一判定，任一命中即冻结。</li>
 *   <li><b>创造模式容器事件</b>不携带物品栏槽位信息，只携带游标/视图槽位，
 *       因此单独走 {@link #blocksCreativeContainer}，不与点击路径共用优先级。</li>
 * </ol></p>
 */
final class DeliveryFreezeRules {
    private DeliveryFreezeRules() {
    }

    /**
     * 点击/拖拽路径。容器槽位与游标槽位都参与判定，游标单独标记，
     * 让恢复流程知道该去哪个位置清理凭据。
     */
    static Decision decide(ContainerSlots slots) {
        if (slots.cursorMarked()) {
            return new Decision(true, true);
        }
        if (slots.oldCursorMarked()) {
            return new Decision(true, false);
        }
        return new Decision(
                slots.currentMarked() || slots.hotbarMarked() || slots.offhandMarked(),
                false);
    }

    /**
     * 创造模式的容器事件。{@code InventoryCreativeEvent} 只在客户端创造模式物品栏
     * 交互时触发，可能有非空的物品栏槽位，也可能只操作游标。两条路径都冻结，
     * 避免创造模式把受保护的交付物品落到任意容器里。
     */
    static boolean blocksCreativeContainer(boolean cursorMarked, boolean currentMarked) {
        return cursorMarked || currentMarked;
    }

    /**
     * 标记扫描范围：物品栏槽位 + 游标槽位。游标槽位用 {@code -1} 表示，
     * 与 {@link org.bukkit.inventory.Inventory#getSize()} 之后的位置不冲突。
     */
    static final int CURSOR_SLOT = -1;

    /** 收集扫描用的标记列表，顺序与 {@code slot} 一一对应。 */
    static List<String> collectMarkers(int inventorySize, String[] inventoryMarkers, String cursorMarker) {
        if (inventoryMarkers.length != inventorySize) {
            throw new IllegalArgumentException("inventory marker count must match inventory size");
        }
        List<String> markers = new ArrayList<>(inventorySize + 1);
        for (String marker : inventoryMarkers) markers.add(marker);
        markers.add(cursorMarker);
        return markers;
    }

    /** 槽位 -> 标记，游标槽位为 {@code -1}。 */
    record ContainerSlots(
            boolean currentMarked,
            boolean cursorMarked,
            boolean oldCursorMarked,
            boolean hotbarMarked,
            boolean offhandMarked
    ) {
    }

    record Decision(boolean frozen, boolean cursorOwnsReceipt) {
    }
}
