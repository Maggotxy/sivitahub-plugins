package cn.originalrpg.market;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One complete inventory/cursor scan; malformed or duplicate delivery/token pairs stay frozen. */
final class MailboxReceiptScan {
    private MailboxReceiptScan() { }
    record Input(int slot, String deliveryId, String claimToken) { }
    record Receipt(int slot, UUID deliveryId, UUID claimToken) { }
    record Scan(Map<UUID, Receipt> unique, Set<UUID> ambiguous, Set<Integer> invalidSlots) { }

    static Scan scan(List<Input> inputs) {
        Map<UUID, List<Receipt>> deliveries = new LinkedHashMap<>();
        Map<UUID, List<UUID>> tokens = new HashMap<>();
        Set<Integer> invalid = new HashSet<>();
        Set<UUID> ambiguous = new HashSet<>();
        for (Input input : inputs) {
            if (input.deliveryId() == null && input.claimToken() == null) continue;
            UUID delivery = parse(input.deliveryId());
            UUID token = parse(input.claimToken());
            if (token != null) tokens.computeIfAbsent(token, ignored -> new ArrayList<>()).add(delivery);
            if (delivery == null || input.claimToken() != null && token == null) {
                invalid.add(input.slot());
                if (delivery != null) ambiguous.add(delivery);
                continue;
            }
            deliveries.computeIfAbsent(delivery, ignored -> new ArrayList<>()).add(new Receipt(input.slot(), delivery, token));
        }
        for (var entry : tokens.values()) if (entry.size() > 1) for (UUID delivery : entry) if (delivery != null) ambiguous.add(delivery);
        Map<UUID, Receipt> unique = new LinkedHashMap<>();
        deliveries.forEach((delivery, receipts) -> {
            if (receipts.size() > 1) ambiguous.add(delivery);
            else unique.put(delivery, receipts.getFirst());
        });
        ambiguous.forEach(unique::remove);
        return new Scan(Map.copyOf(unique), Set.copyOf(ambiguous), Set.copyOf(invalid));
    }

    private static UUID parse(String text) {
        if (text == null) return null;
        try {
            UUID value = UUID.fromString(text);
            return value.toString().equals(text) && !value.equals(new UUID(0, 0)) ? value : null;
        } catch (IllegalArgumentException error) { return null; }
    }
}
