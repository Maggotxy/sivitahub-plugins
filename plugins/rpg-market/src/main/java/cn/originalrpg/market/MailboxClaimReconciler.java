package cn.originalrpg.market;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 断线重连后的交付对账：决定每件 PENDING 投递是否需要重新入包。
 *
 * <p>重复领取窗口的根因是「凭据可见性」与「数据库状态」不一致：凭据留在物品上，
 * 而 {@code markDelivered} 还没提交。重连时若只看物品栏，游标上的凭据不可见，
 * 就会把同一件投递再发一次。此处的判定以标记扫描为唯一事实来源 —— 凭据在哪儿
 * 都算「已交付、待确认」，不重复入包。</p>
 */
final class MailboxClaimReconciler {
    private MailboxClaimReconciler() {
    }

    /**
     * @param pendingDeliveryIds 数据库仍返回 PENDING 的投递
     * @param markerScan         物品栏 + 游标槽位的凭据扫描结果
     */
    static Plan plan(Set<UUID> pendingDeliveryIds, DeliveryReceiptRules.Scan markerScan) {
        Map<UUID, Integer> receipts = markerScan.uniqueReceipts();
        Set<UUID> alreadyStaged = receipts.keySet();
        Map<UUID, Integer> toConfirm = new LinkedHashMap<>();
        Map<UUID, Integer> toDeliver = new LinkedHashMap<>();
        for (UUID deliveryId : pendingDeliveryIds) {
            if (alreadyStaged.contains(deliveryId)) toConfirm.put(deliveryId, receipts.get(deliveryId));
            else toDeliver.put(deliveryId, DeliveryFreezeRules.CURSOR_SLOT);
        }
        return new Plan(Map.copyOf(toConfirm), Set.copyOf(toDeliver.keySet()), markerScan.invalidSlots());
    }

    /**
     * 重复凭据无法定位唯一物品，必须隔离而不是重发；重发就是重复领取本身。
     */
    static boolean quarantinesDuplicates(DeliveryReceiptRules.Scan markerScan) {
        return !markerScan.duplicateIds().isEmpty();
    }

    record Plan(
            Map<UUID, Integer> stagedDeliveries,
            Set<UUID> newDeliveries,
            java.util.List<Integer> invalidSlots
    ) {
        boolean duplicateClaimAllowed() {
            return false;
        }
    }
}
