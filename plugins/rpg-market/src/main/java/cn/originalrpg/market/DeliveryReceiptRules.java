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

    static Scan scan(List<String> markers) {
        Map<UUID, Integer> firstSlots = new LinkedHashMap<>();
        Set<UUID> duplicateIds = new LinkedHashSet<>();
        List<Integer> invalidSlots = new ArrayList<>();
        for (int slot = 0; slot < markers.size(); slot++) {
            String marker = markers.get(slot);
            if (marker == null) continue;
            UUID id;
            try {
                id = UUID.fromString(marker);
            } catch (IllegalArgumentException exception) {
                invalidSlots.add(slot);
                continue;
            }
            if (firstSlots.putIfAbsent(id, slot) != null) duplicateIds.add(id);
        }
        duplicateIds.forEach(firstSlots::remove);
        return new Scan(Map.copyOf(firstSlots), List.copyOf(invalidSlots), Set.copyOf(duplicateIds));
    }

    record Scan(Map<UUID, Integer> uniqueReceipts, List<Integer> invalidSlots, Set<UUID> duplicateIds) {
    }
}
