# Session and outbox schema rollout

This change adds a connection-authority protocol and a real dungeon-completion consumer to the existing core database. It does not change XP formulas, balances, prices, tax rates or equipment authority, and it does not enable multi-Paper routing. Read the [developer authority guide](../en/SESSION_AND_OUTBOX.md) before adding an actor annotation or external consumer.

## Additive schema changes

| Object | Added fields or constraints | Meaning |
|---|---|---|
| `rpg_session_leases` | `session_token`, `fence`, `session_state`, `handoff_id`, `handoff_target` | Separates one connection from a Paper process, supports monotonic fencing and explicit handoff reservations |
| `rpg_outbox` | `claim_token`, `claim_expires_at`, `next_attempt_at`; eligibility index on `(processed_at, next_attempt_at, claim_expires_at)` | Exclusive expiring claims and persisted retry scheduling |
| `rpg_dungeon_completion_inbox` | Event/run/player/dungeon identity, queued-delivery count, creation/notification timestamps; unique `(run_id, player_uuid)` and player/unread index | A receipt-verified notification projection; it owns no asset balance |
| Migration manifest | Outbox idempotency, inbox run/player identity, existing community-approval identity, new table/index coverage | H2-to-PostgreSQL copying must preserve the actual runtime uniqueness guarantees |

`Database.migrate()` adds these fields idempotently. PostgreSQL uses the existing migration advisory lock and transaction. H2 may commit DDL independently; repeat migration is tested and must be used to finish an interrupted additive upgrade. No old reward receipt, processed event or ledger row is deleted.

A legacy session row has no connection token and fence zero. Its existing unexpired ownership is preserved, so a new login cannot steal it. After expiry, a fresh connection can acquire a new fence. An operator must not fabricate a connection token or mark all legacy rows released while old Paper processes still hold player state.

Existing processed outbox rows remain processed. Pending legacy rows receive an immediately eligible retry time. Their JSON payload remains readable by the dungeon consumer. The new producer fixes future party events by using run/player identity. Historical members whose event was suppressed by the old run-only key are not silently backfilled: inspect the authoritative per-player reward receipts and decide whether a notification-only backfill is needed. Their actual reward settlement is independent of this notification defect.

## Rollout

1. Use the existing stop/backup deployment procedure and deploy matching API/core/plugin JARs. A mixed API/core set can miss actor metadata even if Java signatures still link.
2. Run the normal core migration and inspect its exact result. The default standalone configuration leaves all three feature flags false.
3. On an isolated staging copy, test session admission, AuthMe logout/re-login, bounded-queue rejection, renewal failure, replacement connections, expiry during a transaction, normal quit and clean shutdown. No main-thread event should perform JDBC.
4. If evaluating event delivery, enable `outbox.enabled` and `outbox.dispatch.enabled` together. Confirm both party members create separate events and inbox rows, then interrupt a claimed delivery and verify recovery. This is an event test, not authorization to route live players between servers.
5. Keep player-state/identity/mailbox/network gates closed until their own acceptance procedure passes. These feature flags do not satisfy those gates.

Relevant defaults: connection lease 60 seconds; admission wait 5 seconds on the asynchronous pre-login thread; outbox claim 30 seconds; retry starts at 2 seconds and caps at 5 minutes. The runtime configuration bounds session duration and admission timeout. Leases are not cleanup timers: do not shorten them to clear a blocked deployment without understanding the process holding the player state.

## Read-only checks

```sql
SELECT player_uuid, node_id, fence, session_state, lease_expires_at
FROM rpg_session_leases
ORDER BY lease_expires_at;

SELECT aggregate_type, COUNT(*) AS waiting,
       MIN(next_attempt_at) AS next_retry, MAX(attempts) AS most_attempts
FROM rpg_outbox
WHERE processed_at IS NULL
GROUP BY aggregate_type;

SELECT event_id, aggregate_type, attempts, next_attempt_at,
       claim_expires_at, last_error
FROM rpg_outbox
WHERE processed_at IS NULL
ORDER BY next_attempt_at, created_at;

SELECT player_uuid, COUNT(*) AS unread_hints
FROM rpg_dungeon_completion_inbox
WHERE notified_at IS NULL
GROUP BY player_uuid;
```

Do not expose session/process/claim tokens in public status pages. The inspection queries intentionally omit them. A pending event with no registered handler or an invalid/missing reward receipt remains pending with an error; deleting it would discard the investigation evidence. A retry can resolve only a transient failure or a repaired handler, not an invalid business receipt.

## Recovery and rollback boundary

Expired outbox claims are recovered automatically. Do not clear claim tokens while workers may still be running. An acknowledgement is conditional on its exact claim token; an old worker cannot mark a newer worker's attempt complete. SQL-side notification creation and `processed_at` are atomic. External chat acknowledgement has a deliberate at-least-once boundary and can repeat a hint after an ambiguous outcome.

An expired player token cannot be renewed. A node/process replacement must obtain a fresh node token and new player connection authority; it must never copy the old token into a new process. Failed handoff acceptance/cancellation must preserve the original handoff ID and the intended new connection token across retries.

Rollback the application only while all participating Paper processes are stopped and routing is closed. Preserve the additive columns and inbox/outbox rows. Disable the optional features in the rolled-back standalone deployment; an older reader does not understand the new handoff state or write fencing and is not a safe rolling peer of a newer node. Never remove the unique outbox/inbox keys to make a downgrade start. There is no balance reversal: no gameplay balances are recalculated by this migration.

Full player/inventory rollback requires the matching database, identity-store and world/playerdata snapshot. This change has no automatic inverse for a partly transported signed inventory because it does not provide that transport in the first place.

## Verification entry points

- `JdbcPlayerSessionLifecycleTest`: exact replay, expiry, fresh reconnect, competing nodes, handoff accept/cancel, lost commit response, legacy schema upgrade and node expiry before authority commit.
- `SessionWriteFencingTest`: actual profile debit under a captured token, stale queued requests after reconnect, missing admission, offline accepted XP, commit-time expiry, blocked renewal, default API overloads and context cleanup.
- `TransactionalOutboxTest`: production migration, event/transaction rollback, conflict detection, claim recovery, stale claim refusal, concurrency, backoff, transactional handler failure and schema restart.
- `DungeonRewardOutboxWiringTest`: producer disable/enable behavior, rollback, party-recipient identity, real inbox consumer and rejection of an invented event without a receipt.
- `PostgresSessionLockOrderTest`: conditional real PostgreSQL concurrency test. An H2 pass or skipped PostgreSQL test does not replace this result.

中文说明：本次完成数据库会话 acquire/renew/release/handoff、明确发起者的事务围栏、以及有实际消费者的 Outbox/通知收件箱。它们不等于已完成背包、PDC、身份库、邮箱物品和 Velocity 的跨服交接。旧审计事实与物品账本均保留，禁止用清空租约/收据来绕过上线门槛。
