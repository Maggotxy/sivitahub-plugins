# Progression receipts: migration, recovery and rollback

This note accompanies the progression reliability changes on `codex/international-readiness`. The authoritative player level, XP and skill-point balances remain in `rpg_profiles`. No currency, additional ascension, progression formula or second progression balance is introduced.

## What changes

| Change | Storage / behavior |
|---|---|
| Stable XP grants | New `rpg_experience_grants`: `grant_id` primary key, `player_uuid`, positive `amount`, `granted_at`; index on `(player_uuid, granted_at)`. It records the immutable operation identity. |
| Atomic settlement | A new grant receipt, normal XP/level changes and skill-point milestones commit in one existing profile transaction. The profile row lock serializes same-player requests. |
| Replay | The same grant ID, player and amount returns the current profile with no additional award. Reusing an ID with different content fails and rolls back. |
| Mob XP batching | A frozen batch keeps its ID and amount through normal retries and shutdown retries. XP earned later remains a separate batch. |
| Skill-point schema | Runtime DDL now matches the consumer: `milestone_key` primary key; `receipt` audit field; `player_uuid`, `points`, `ascension`, `milestone_index`, `to_local_level`, `granted_at`. |
| Fresh installation | The season-transition index is created after `rpg_season_settlement_transitions`, so a new database no longer references a table before creating it. |

Implementation: [Database](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java), [SkillPointGrantSchema](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/SkillPointGrantSchema.java), [JdbcProfileService](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java), [ExperienceWriteBuffer](../../plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/ExperienceWriteBuffer.java), and the matching [migration manifest](../../tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/SchemaManifest.java).

## Existing installations

The runtime migrator checks the skill-point table **before making runtime schema changes**. PostgreSQL first acquires the existing transaction-scoped migration advisory lock. These are the supported outcomes:

| Existing state | Result |
|---|---|
| No skill-point grant table | Create the canonical table during the normal migration. |
| Canonical columns with `milestone_key` as the sole primary-key column | Keep the table and all rows. Restarting the migration is safe. |
| Exactly the old range-based columns, with zero rows | Rebuild that empty table using the canonical schema. PostgreSQL holds an exclusive table lock while checking and rebuilding it. No player balance changes. |
| Old range-based table containing historical grants | Stop startup with an explicit migration error. Keep every audit row and profile balance. A reviewed migration of the actual historical data is required. |
| Unknown/mixed column shape or missing canonical primary key | Stop startup for review rather than assuming that replay protection exists. |

The old shape has `source`, `idempotency_key` and `from_local_level`. A range-based row is not automatically a unique local milestone: it may represent several milestones, overlap another receipt, or reflect earlier semantics. The code therefore does not copy `source` into `milestone_key`, derive historical awards from current balances, or guess levels with modulo arithmetic.

For a read-only PostgreSQL preflight, inspect the actual table shape:

```sql
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = current_schema()
  AND table_name = 'rpg_skill_point_grants'
ORDER BY ordinal_position;
```

If the table exists, inspect its row count and historical records through the normal restricted database administration workflow. For the old shape only:

```sql
SELECT COUNT(*) AS historical_rows FROM rpg_skill_point_grants;

SELECT source, idempotency_key, player_uuid, points,
       ascension, from_local_level, to_local_level, granted_at
FROM rpg_skill_point_grants
ORDER BY granted_at;
```

The separate historical-data migration must preserve original receipts, explain the exact mapping to reviewed milestones, and verify player balances before and after. This release intentionally does not include a generic conversion for unseen nonempty legacy data. The older `plan/migrations/W4-A2-skill-point-grants.sql` uses `CREATE TABLE IF NOT EXISTS`; running it over the old table does **not** change its columns and cannot resolve this conflict.

## Deployment and verification

Deploy matching `rpg-api`, `rpg-core` and `rpg-mobs` JARs together after the existing stop/backup procedure. The API's default idempotent method fails explicitly for an older provider; it never silently falls back to an unsafe repeatable `grantExperience` call.

The production migration is used directly by the new tests, instead of constructing only the table shape that the service expects:

- `DatabaseMigrationIntegrationTest`: new database; real XP and skill-point award; repeat migration; preserved disabled mechanism; empty legacy-table upgrade; populated legacy-table refusal with unchanged audit facts and player profile.
- `JdbcExperienceGrantServiceTest`: a real commit followed by an injected error; replay after recreating the service; failure before commit; conflicting receipt content; concurrent duplicate requests mixed with a new grant.
- `ExperienceWriteBufferTest`: the real flush and shutdown paths preserve a frozen grant's identity and keep later XP separate. It supplies lightweight Paper interfaces for configuration and an offline player session; it is not a Paper server test.
- `MechanismRegistryPersistenceTest`: actual H2 writes, operator decisions across restart, deleted switches, a single-connection pool, transaction failures and interrupted cache refresh.

Execution results belong in the delivery/CI report. H2 validates the exercised database behavior; it is not PostgreSQL runtime evidence. PostgreSQL's conflict-target requirement is documented in the [official INSERT reference](https://www.postgresql.org/docs/18/sql-insert.html); migration locks use the existing [transaction and table-lock mechanism](https://www.postgresql.org/docs/18/explicit-locking.html).

## Retention and unresolved crash windows

Grant receipts are needed for replay protection. Do not delete them as routine cleanup while a matching batch may still be retried. This change provides no automatic expiry or archival policy; measure receipt growth from actual batch traffic before designing one. Include the receipts in the same database backup as profiles and skill-point grants.

The XP batch ID remains in the server process until submitted. An abrupt process or machine failure can still lose an unsubmitted in-memory XP batch or delayed natural-mob drop. The new receipt handles **a submitted operation whose commit outcome is uncertain**; it does not create a durable journal for every kill. Dungeon rewards have their separate fsync-backed batch journal and are not evidence that this natural-mob window has closed.

## Rollback

Rollback application JARs as a matching set. Retain `rpg_experience_grants` and canonical skill-point audit rows; dropping receipts removes replay protection. Existing profile balances are not recalculated by this migration, so there is no automatic balance reversal to run. The new receipt table is additive and may remain unused by a rolled-back application.

If startup stops because a nonempty old skill-point table needs review, that check ran before this release changed runtime schema. Restore the previously working matching application build and keep the historical table intact while preparing the reviewed migration. Never empty an audit table merely to make startup accept it.

PostgreSQL schema work shares the migration transaction. H2 may commit DDL independently; the only automatically replaced old table is proven empty, and a repeat startup can recreate it if a later migration fails. If another unrelated migration still fails, keep the exact error and database snapshot; do not label a partial H2 DDL pass as a completed upgrade.

中文说明：本轮只增加 XP 操作收据，并修正运行 DDL 与既有技能点写入的漂移。非空旧审计表不会自动猜测转换；异常会阻止启动并保留原始数据。最终满级 XP 语义与经济数值没有调整，硬退出前未持久化的自然怪奖励仍有丢失窗口。
