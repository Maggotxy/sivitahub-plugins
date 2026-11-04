# Progression receipts: migration, recovery and rollback

This note accompanies the progression reliability changes on `codex/international-readiness`. The authoritative player level, XP and skill-point balances remain in `rpg_profiles`. No currency, additional ascension, progression formula or second progression balance is introduced.

## What changes

| Change | Storage / behavior |
|---|---|
| Stable XP grants | New `rpg_experience_grants`: `grant_id` primary key, `player_uuid`, positive `amount`, `granted_at`; index on `(player_uuid, granted_at)`. It records the immutable operation identity. |
| Atomic settlement | A new grant receipt, normal XP/level changes and skill-point milestones commit in one existing profile transaction. The profile row lock serializes same-player requests. |
| Replay | The same grant ID, player and amount returns the current profile with no additional award. Reusing an ID with different content fails and rolls back. |
| Natural-mob reward recovery | Each durably accepted kill keeps its XP receipt, exact item bytes, drop rolls and qualification snapshot in the bounded local journal. Core XP and mailbox services deduplicate replay. Only UI feedback remains batched. |
| Skill-point schema | Runtime DDL now matches the consumer: `milestone_key` primary key; `receipt` audit field; `player_uuid`, `points`, `ascension`, `milestone_index`, `to_local_level`, `granted_at`. |
| Fresh installation | The season-transition index is created after `rpg_season_settlement_transitions`, so a new database no longer references a table before creating it. |

Implementation: [Database](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java), [SkillPointGrantSchema](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/SkillPointGrantSchema.java), [JdbcProfileService](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java), [ExperienceWriteBuffer](../../plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/ExperienceWriteBuffer.java), [MobRewardJournal](../../plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/MobRewardJournal.java), and the matching [migration manifest](../../tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/SchemaManifest.java). See the complete [natural-mob recovery protocol and upgrade notes](../../plugins/rpg-mobs/RECOVERY.md).

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
- `ExperienceWriteBufferTest`: journal-owned XP IDs and amounts, invalid/unresolved authority inputs, session revocation and shutdown without an independent volatile retry path. It supplies lightweight Paper interfaces; it is not a Paper server test.
- `MobRewardJournalTest`, `MobRewardProcessRecoveryTest`, `MobRewardSettlementTest` and `MobRewardAsyncLifecycleContractTest`: real files and abrupt child-process exits; no authority call before durability acknowledgement; lost-response and partial-delivery replay; frozen qualification; load bounds, disk failures, strict cleanup and finite shutdown.
- `MechanismRegistryPersistenceTest`: actual H2 writes, operator decisions across restart, deleted switches, a single-connection pool, transaction failures and interrupted cache refresh.

Execution results belong in the delivery/CI report. H2 validates the exercised database behavior; it is not PostgreSQL runtime evidence. PostgreSQL's conflict-target requirement is documented in the [official INSERT reference](https://www.postgresql.org/docs/18/sql-insert.html); migration locks use the existing [transaction and table-lock mechanism](https://www.postgresql.org/docs/18/explicit-locking.html).

## Retention and unresolved crash windows

Grant receipts are needed for replay protection. Do not delete them as routine cleanup while a matching journal intent or supported backup may still be retried. This change provides no automatic expiry or archival policy; measure receipt growth from actual per-kill traffic before designing one. Include receipts in the same database backup as profiles and skill-point grants, and preserve the natural-mob journal together with that authoritative database.

Natural-mob rewards now have their own bounded fsync-backed recovery instructions. The server thread freezes a kill's receipt, serialized items and original rolls; the worker forces a staging file, atomically renames it and forces the directory before any XP or mailbox call. A surviving accepted intent retries the exact IDs after partial success, lost replies or restart. Custom natural-mob items now use `/market claim`; vanilla drops and economic values are unchanged.

The remaining boundary is **before durable acceptance**: a death event or volatile queue reservation is not a promise of recovery, and a hard exit before its successful file/directory sync can lose that observation. Unsupported filesystem guarantees, disk failure, corruption or an excessive backlog pause new custom reward admission and preserve files for investigation. Corrupt final files stay in place; staging files are retained and never automatically promoted into accepted grants. Shutdown makes only a bounded final persistence pass and explicitly reports unconfirmed candidates.

Pity counters remain player PDC data and are not in the same transaction as the journal. Recovery never rerolls or overwrites later PDC counters. A missing profile at death creates a conditional candidate whose first later authoritative eligibility result must itself be persisted before rewards; it is not presented as a historical death-time snapshot. These limits, deployment requirements and runtime verification still needed are detailed in [the recovery note](../../plugins/rpg-mobs/RECOVERY.md).

## Rollback

Rollback application JARs as a matching set. Retain `rpg_experience_grants` and canonical skill-point audit rows; dropping receipts removes replay protection. Existing profile balances are not recalculated by this migration, so there is no automatic balance reversal to run. The new receipt table is additive and may remain unused by a rolled-back application.

Do not roll `rpg-mobs` back to a memory-only build while accepted natural-mob intents are still pending. Preserve the journal and use a compatible recovery build to settle that backlog; an older plugin cannot interpret or drain it. Do not delete the files to make a rollback appear complete.

If startup stops because a nonempty old skill-point table needs review, that check ran before this release changed runtime schema. Restore the previously working matching application build and keep the historical table intact while preparing the reviewed migration. Never empty an audit table merely to make startup accept it.

PostgreSQL schema work shares the migration transaction. H2 may commit DDL independently; the only automatically replaced old table is proven empty, and a repeat startup can recreate it if a later migration fails. If another unrelated migration still fails, keep the exact error and database snapshot; do not label a partial H2 DDL pass as a completed upgrade.

中文说明：XP 操作收据与技能点 DDL 修复保持原有权威，非空旧审计表不猜测转换。自然怪奖励现已接入有界持久日志，先确认文件和目录落盘，再用固定收据提交 XP 与现有邮箱；恢复不重新随机掉落。经济数值不变，原版掉落不变；自定义掉落统一用 `/market claim` 领取。死亡事件到真正持久接受之间，以及玩家 PDC 保底计数与奖励日志之间，仍有明确的非原子边界；损坏或磁盘失败保留证据并停止新奖励接收。
