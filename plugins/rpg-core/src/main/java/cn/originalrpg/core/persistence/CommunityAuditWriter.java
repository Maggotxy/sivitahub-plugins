package cn.originalrpg.core.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Shared append-only community governance audit, always written in the authority transaction. */
final class CommunityAuditWriter {
    private CommunityAuditWriter() {}

    static void append(Connection connection, UUID communityId, UUID correlationId, UUID actorId,
                       String transition, String detail) throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO rpg_community_lifecycle_audit
                    (audit_id, community_id, proposal_id, actor_uuid, transition, detail)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, communityId.toString());
            insert.setString(3, correlationId.toString());
            insert.setString(4, actorId.toString());
            insert.setString(5, transition);
            insert.setString(6, detail);
            insert.executeUpdate();
        }
    }
}
