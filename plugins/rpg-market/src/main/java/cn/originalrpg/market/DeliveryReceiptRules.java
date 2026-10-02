package cn.originalrpg.market;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure recovery rules for temporary mailbox receipts stored on inventory items. */
final class DeliveryReceiptRules {
    private DeliveryReceiptRules() {
    }

    /**
     * 扫描标记列表。最后一项是游标槽位（见 {@link DeliveryFreezeRules#CURSOR_SLOT}），
     * 因此 {@code uniqueReceipts} 里可能出现 {@code -1}：它代表鼠标游标而不是物品栏槽位。
     * 旧实现不扫游标，断线重连后游标上的凭据被漏掉，物品仍可入包而投递行保持 PENDING。
     */
    static Scan scan(List<String> markers) {
        Map<UUID, Integer> firstSlots = new LinkedHashMap<>();
        Set<UUID> duplicateIds = new LinkedHashSet<>();
        List<Integer> invalidSlots = new ArrayList<>();
        for (int index = 0; index < markers.size(); index++) {
            String marker = markers.get(index);
            if (marker == null) continue;
            UUID id;
            try {
                id = UUID.fromString(marker);
            } catch (IllegalArgumentException exception) {
                invalidSlots.add(slot(markers.size(), index));
                continue;
            }
            if (firstSlots.putIfAbsent(id, slot(markers.size(), index)) != null) duplicateIds.add(id);
        }
        duplicateIds.forEach(firstSlots::remove);
        return new Scan(Map.copyOf(firstSlots), List.copyOf(invalidSlots), Set.copyOf(duplicateIds));
    }

    /** 游标标记位于列表末尾时映射为游标槽位，其余按原下标。 */
    static int slot(int markerCount, int index) {
        return index == markerCount - 1 ? DeliveryFreezeRules.CURSOR_SLOT : index;
    }

    record Scan(Map<UUID, Integer> uniqueReceipts, List<Integer> invalidSlots, Set<UUID> duplicateIds) {
    }
}
