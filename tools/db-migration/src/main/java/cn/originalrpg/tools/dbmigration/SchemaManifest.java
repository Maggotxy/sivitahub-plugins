package cn.originalrpg.tools.dbmigration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logical copy order for the schema created by the matching RpgCore build.
 *
 * <p>The migration utility deliberately does not own or recreate that schema.
 * Adding a new {@code rpg_*} table to RpgCore therefore makes discovery fail
 * closed until this manifest is reviewed and updated.</p>
 */
public final class SchemaManifest {
    private static final List<String> TABLES = List.of(
            "rpg_profiles",
            "rpg_chapter_state",
            "rpg_chapter_history",
            "rpg_accounts",
            "rpg_ledger",
            "rpg_progression_wallets",
            "rpg_activity_daily",
            "rpg_activity_ledger",
            "rpg_daily_item_rewards",
            "rpg_daily_item_reward_receipts",
            "rpg_commission_evidence_daily",
            "rpg_commission_evidence_sources",
            "rpg_commission_claims",
            "rpg_activity_purchases",
            "rpg_incident_compensations",
            "rpg_incident_compensation_lines",
            "rpg_merit_ledger",
            "rpg_market_listings",
            "rpg_market_deliveries",
            "rpg_equipment_operations",
            "rpg_skills",
            "rpg_skill_mutation_receipts",
            "rpg_skill_point_grants",
            "rpg_admin_test_profiles",
            "rpg_admin_test_skills",
            "rpg_skill_book_uses",
            "rpg_energy_recovery_quotas",
            "rpg_hidden_path_caps",
            "rpg_hidden_paths",
            "rpg_hidden_path_audit",
            "rpg_dungeon_energy_reservations",
            "rpg_dungeon_ticket_escrows",
            "rpg_dungeon_reward_receipts",
            "rpg_dungeon_ticket_pity",
            "rpg_season_rotation_state",
            "rpg_season_runs",
            "rpg_season_run_members",
            "rpg_season_player_progress",
            "rpg_season_rotation_claims",
            "rpg_season_personal_bests",
            "rpg_node_leases",
            "rpg_merchant_limits",
            "rpg_merchant_ledger",
            "rpg_class_branches",
            "rpg_ascension_receipts",
            "rpg_pet_progress",
            "rpg_pet_collection",
            "rpg_pet_skins",
            "rpg_pet_bond_ledger",
            "rpg_pet_revival_intents",
            "rpg_commerce_accounts",
            "rpg_payment_ledger",
            "rpg_credit_ledger",
            "rpg_starlight_operation_limits",
            "rpg_commerce_purchases",
            "rpg_commerce_reward_claims",
            "rpg_currency_exchange_ledger",
            "rpg_currency_exchange_limits",
            "rpg_community_identity",
            "rpg_communities",
            "rpg_community_members",
            "rpg_community_invites",
            "rpg_community_projects",
            "rpg_community_contributions",
            "rpg_community_project_rewards",
            "rpg_progression_anomalies",
            "rpg_mechanism_switches",
            "rpg_season_settlements",
            "rpg_season_settlement_gate",
            "rpg_season_settlement_transitions",
            "rpg_season_settlement_progress",
            "rpg_season_merit_wallets",
            "rpg_support_credit_accounts",
            "rpg_support_credit_ledger",
            "rpg_market_buy_orders",
            "rpg_community_approvals",
            "rpg_session_leases",
            "rpg_outbox",
            "rpg_equipment_states",
            "rpg_equipment_lifecycle_audit",
            "rpg_season_attunements"
    );

    private static final Map<String, List<List<String>>> UNIQUE_KEYS = buildUniqueKeys();
    private static final Map<String, List<List<String>>> REQUIRED_INDEXES = buildRequiredIndexes();

    private SchemaManifest() {
    }

    public static List<String> tables() {
        return TABLES;
    }

    public static Map<String, List<List<String>>> uniqueKeys() {
        return UNIQUE_KEYS;
    }

    /**
     * Non-unique indexes which are part of the reviewed runtime performance
     * contract. Migration is rejected when either side was not initialized by
     * the matching RpgCore build, rather than silently moving data into a
     * schema whose hot paths will degrade as the tables grow.
     */
    public static Map<String, List<List<String>>> requiredIndexes() {
        return REQUIRED_INDEXES;
    }

    private static Map<String, List<List<String>>> buildUniqueKeys() {
        Map<String, List<List<String>>> keys = new LinkedHashMap<>();
        keys.put("rpg_chapter_history", List.of(List.of("season_id", "revision")));
        keys.put("rpg_ledger", List.of(
                List.of("idempotency_key"),
                List.of("reversal_of")
        ));
        keys.put("rpg_skill_point_grants", List.of(List.of("source,idempotency_key")));
        keys.put("rpg_activity_ledger", List.of(List.of("idempotency_key")));
        keys.put("rpg_activity_purchases", List.of(List.of("idempotency_key")));
        keys.put("rpg_merit_ledger", List.of(List.of("idempotency_key")));
        keys.put("rpg_market_listings", List.of(List.of("open_item_identity")));
        keys.put("rpg_equipment_operations", List.of(List.of("debit_key")));
        // W6-A2：赛季调谐的幂等键 —— 换季结算重放命中唯一键，不二次推进状态。
        keys.put("rpg_season_attunements", List.of(List.of("idempotency_key")));
        keys.put("rpg_merchant_ledger", List.of(List.of("idempotency_key")));
        keys.put("rpg_pet_bond_ledger", List.of(List.of("idempotency_key")));
        keys.put("rpg_pet_revival_intents", List.of(
                List.of("active_pet_uuid"),
                List.of("debit_key")
        ));
        keys.put("rpg_payment_ledger", List.of(List.of("external_reference")));
        keys.put("rpg_credit_ledger", List.of(List.of("external_reference")));
        keys.put("rpg_commerce_purchases", List.of(List.of("idempotency_key")));
        keys.put("rpg_currency_exchange_ledger", List.of(List.of("idempotency_key")));
        keys.put("rpg_community_identity", List.of(List.of("invite_code")));
        keys.put("rpg_communities", List.of(
                List.of("normalized_name"),
                List.of("normalized_tag")
        ));
        keys.put("rpg_community_members", List.of(List.of("player_uuid")));
        keys.put("rpg_community_contributions", List.of(List.of("idempotency_key")));
        keys.put("rpg_support_credit_ledger", List.of(List.of("idempotency_key")));
        return Map.copyOf(keys);
    }

    private static Map<String, List<List<String>>> buildRequiredIndexes() {
        Map<String, List<List<String>>> indexes = new LinkedHashMap<>();
        indexes.put("rpg_market_listings", List.of(
                List.of("state", "created_at"),
                List.of("seller_uuid", "state", "created_at")
        ));
        indexes.put("rpg_market_deliveries", List.of(List.of("player_uuid", "state", "created_at")));
        indexes.put("rpg_equipment_operations", List.of(List.of("player_uuid", "created_at")));
        indexes.put("rpg_activity_daily", List.of(List.of("activity_date", "activity")));
        indexes.put("rpg_ledger", List.of(List.of("created_at")));
        indexes.put("rpg_merit_ledger", List.of(List.of("created_at")));
        indexes.put("rpg_activity_ledger", List.of(List.of("created_at")));
        indexes.put("rpg_daily_item_rewards", List.of(List.of("reward_date", "reward_id")));
        indexes.put("rpg_daily_item_reward_receipts", List.of(List.of("player_uuid", "created_at")));
        indexes.put("rpg_commission_claims", List.of(List.of("player_uuid", "evidence_date", "state")));
        indexes.put("rpg_payment_ledger", List.of(List.of("created_at")));
        indexes.put("rpg_commerce_purchases", List.of(List.of("player_uuid", "product_id", "period_key")));
        indexes.put("rpg_credit_ledger", List.of(List.of("created_at")));
        indexes.put("rpg_skill_book_uses", List.of(List.of("player_uuid", "limit_group")));
        indexes.put("rpg_energy_recovery_quotas", List.of(List.of("source_id", "period_key")));
        indexes.put("rpg_skill_mutation_receipts", List.of(List.of("player_uuid", "settled_at")));
        indexes.put("rpg_skill_point_grants", List.of(List.of("player_uuid", "granted_at")));
        indexes.put("rpg_hidden_path_audit", List.of(List.of("player_uuid", "created_at")));
        indexes.put("rpg_incident_compensations", List.of(List.of("player_uuid", "applied_at")));
        indexes.put("rpg_dungeon_energy_reservations", List.of(
                List.of("state", "created_at"),
                List.of("state", "owner_node", "owner_process")
        ));
        indexes.put("rpg_dungeon_ticket_escrows", List.of(List.of("state", "created_at")));
        indexes.put("rpg_dungeon_reward_receipts", List.of(List.of("player_uuid", "settled_at")));
        indexes.put("rpg_season_runs", List.of(
                List.of("season_id", "rotation_id", "party_size", "duration_ms"),
                List.of("season_id", "rotation_id", "party_size", "team_key", "duration_ms")
        ));
        indexes.put("rpg_season_run_members", List.of(List.of("player_uuid", "run_id")));
        indexes.put("rpg_season_rotation_claims", List.of(List.of("player_uuid", "season_id", "rotation_id")));
        indexes.put("rpg_node_leases", List.of(List.of("lease_expires_at")));
        indexes.put("rpg_merchant_ledger", List.of(List.of("created_at")));
        indexes.put("rpg_pet_progress", List.of(List.of("owner_uuid", "updated_at")));
        indexes.put("rpg_pet_collection", List.of(List.of("player_uuid", "first_captured_at")));
        indexes.put("rpg_pet_bond_ledger", List.of(List.of("pet_uuid", "created_at")));
        indexes.put("rpg_pet_revival_intents", List.of(List.of("player_uuid", "state", "created_at")));
        indexes.put("rpg_community_members", List.of(List.of("community_id", "role")));
        indexes.put("rpg_community_invites", List.of(List.of("target_uuid", "expires_at")));
        indexes.put("rpg_community_projects", List.of(List.of("period_key", "gold_contributed")));
        indexes.put("rpg_community_contributions", List.of(
                List.of("player_uuid", "period_key"),
                List.of("player_uuid", "community_id", "period_key")
        ));
        indexes.put("rpg_community_project_rewards", List.of(List.of("community_id", "period_key")));
        indexes.put("rpg_equipment_states", List.of(List.of("owner_uuid", "state")));
        indexes.put("rpg_equipment_lifecycle_audit", List.of(List.of("item_id", "created_at")));
        indexes.put("rpg_season_attunements", List.of(List.of("season_id", "owner_uuid")));
        indexes.put("rpg_season_settlement_transitions", List.of(List.of("settlement_id", "created_at")));
        // Phase 8：单实例多服前置。两张表与它们的运行索引同批落库（Database.java 建表语句尾部），
        // 迁移前必须登记，否则 H2 → PostgreSQL 会静默搬进一个丢掉热路径索引的库。
        indexes.put("rpg_session_leases", List.of(List.of("lease_expires_at")));
        indexes.put("rpg_outbox", List.of(List.of("processed_at", "created_at")));
        return Map.copyOf(indexes);
    }
}
