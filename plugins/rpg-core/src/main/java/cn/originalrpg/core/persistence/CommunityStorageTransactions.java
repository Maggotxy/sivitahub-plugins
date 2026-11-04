package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.MarketGoods;
import cn.originalrpg.api.model.MarketListing;
import cn.originalrpg.api.rules.CommunityStorageRules;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Shared custody/dissolution operations; caller must already hold the authoritative community row lock. */
final class CommunityStorageTransactions {
    private CommunityStorageTransactions() {}

    static MarketListing listing(ResultSet row) throws SQLException {
        String matcher = row.getString("item_matcher");
        String crafter = row.getString("crafter_uuid");
        String buyer = row.getString("buyer_uuid");
        Timestamp completed = row.getTimestamp("completed_at");
        return new MarketListing(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("seller_uuid")),
                row.getString("item_payload"), row.getBigDecimal("price"), row.getBigDecimal("fee_rate"),
                MarketListing.State.valueOf(row.getString("state")), buyer == null ? null : UUID.fromString(buyer),
                row.getTimestamp("created_at").toInstant(), completed == null ? null : completed.toInstant(),
                matcher == null ? Optional.empty() : Optional.of(new MarketGoods(matcher, row.getInt("item_quantity"),
                        crafter == null ? null : UUID.fromString(crafter))));
    }

    static boolean validSupply(ResultSet row) throws SQLException {
        return row.getString("item_identity") == null && row.getString("open_item_identity") == null
                && row.getString("crafter_uuid") == null && row.getString("item_matcher") != null
                && CommunityStorageRules.materials().contains(row.getString("item_matcher"))
                && row.getInt("item_quantity") >= 1 && row.getInt("item_quantity") <= 64;
    }

    /**
     * Refuse unresolved physical custody. Fully stored supplies are returned to their original
     * donors through the existing mailbox in the same transaction as community dissolution.
     */
    static boolean releaseForDissolution(Connection connection, UUID communityId, UUID actorId) throws SQLException {
        List<MarketListing> stored = new ArrayList<>();
        try (var query = connection.prepareStatement("""
                SELECT * FROM rpg_market_listings WHERE custody_community_id = ?
                    AND state IN ('PREPARED', 'ACTIVE', 'QUARANTINED') ORDER BY id FOR UPDATE
                """)) {
            query.setString(1, communityId.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    if (!rows.getString("state").equals("ACTIVE") || !validSupply(rows)) return false;
                    stored.add(listing(rows));
                }
            }
        }
        for (MarketListing item : stored) {
            deliver(connection, communityId, item, item.sellerId(), item.id(), actorId,
                    "community-return:" + item.id(), "STORAGE_RETURNED");
        }
        List<UUID> pending = new ArrayList<>();
        try (var query = connection.prepareStatement("""
                SELECT request_id FROM rpg_community_storage_requests
                WHERE community_id = ? AND state = 'PENDING' ORDER BY request_id FOR UPDATE
                """)) {
            query.setString(1, communityId.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) pending.add(UUID.fromString(rows.getString(1)));
            }
        }
        try (var update = connection.prepareStatement("""
                UPDATE rpg_community_storage_requests
                SET state = 'CANCELLED', active_listing_id = NULL, completed_at = CURRENT_TIMESTAMP
                WHERE community_id = ? AND state = 'PENDING'
                """)) {
            update.setString(1, communityId.toString());
            update.executeUpdate();
        }
        for (UUID request : pending) {
            CommunityAuditWriter.append(connection, communityId, request, actorId,
                    "STORAGE_REQUEST_VOID", "Community dissolved; custody returned to original donors");
        }
        return true;
    }

    static void deliver(Connection connection, UUID communityId, MarketListing item, UUID recipient,
                        UUID correlation, UUID actorId, String source, String transition) throws SQLException {
        UUID deliveryId = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
        try (var insert = connection.prepareStatement("""
                INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                VALUES (?, ?, ?, ?, 'PENDING') ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, deliveryId.toString());
            insert.setString(2, recipient.toString());
            insert.setString(3, item.itemPayload());
            insert.setString(4, source);
            if (insert.executeUpdate() == 0) {
                try (var check = connection.prepareStatement("""
                        SELECT player_uuid, item_payload, source FROM rpg_market_deliveries WHERE id = ?
                        """)) {
                    check.setString(1, deliveryId.toString());
                    try (ResultSet row = check.executeQuery()) {
                        if (!row.next() || !row.getString(1).equals(recipient.toString())
                                || !row.getString(2).equals(item.itemPayload()) || !row.getString(3).equals(source)) {
                            throw new SQLException("Community delivery identity collision: " + deliveryId);
                        }
                    }
                }
            }
        }
        try (var update = connection.prepareStatement("""
                UPDATE rpg_market_listings SET state = 'CANCELLED', completed_at = CURRENT_TIMESTAMP,
                    open_item_identity = NULL
                WHERE id = ? AND custody_community_id = ? AND state = 'ACTIVE'
                """)) {
            update.setString(1, item.id().toString());
            update.setString(2, communityId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Community custody changed before delivery");
        }
        CommunityAuditWriter.append(connection, communityId, correlation, actorId, transition,
                "listing=" + item.id() + ";recipient=" + recipient + ";donor=" + item.sellerId());
    }
}
