# Phase 5: Support Credit and historical paid-benefit isolation

## Goal and constitutional invariants

Close the old real-money-to-gameplay routes and provide an isolated, signed Support Credit receipt flow whose only spend targets are reviewed appearance entitlements. This implements C-001, C-002, C-005 and C-012 without adding a currency, a second account authority, a second market or a gameplay reward.

The developer integration contract, exact signing bytes, configuration constraints and retry statuses are in [Support Credit](../../plugins/rpg-commerce/SUPPORT_CREDIT.md).

## Current facts and changed behavior

The previous `recordPayment` path wrote `lifetime_paid` and the mixed `member_until`. The supply shop used active membership to select a Gold discount. Cumulative support rewards included enhancement protection stones and lucky charms. These are gameplay effects and cannot remain reachable from real-money support.

The migration changes live authority as follows:

| Historical field/path | New treatment |
| --- | --- |
| `rpg_commerce_accounts.lifetime_paid` | Retained as historical audit data; cannot create new rewards |
| `rpg_payment_ledger` | Retained; no new writes through legacy `recordPayment` |
| `rpg_commerce_accounts.member_until` | Retained as mixed-source history; ignored for active membership and Gold supply discounts |
| `earned_member_until` | New live expiry, accepted only with matching earned grant, original Starlight debit and purchase evidence |
| `recordPayment` / `/rpgcommerce payment` | Fail closed; compatibility service result is `LEGACY_DISABLED` |
| `claimReward` / `/store claim` | No new cumulative paid-item delivery; service returns `LEGACY_DISABLED`; player entry explains the replacement |
| Old unsigned `SupportCreditService.grant` / generic `spend` | Fail closed; new issuance requires signed receipts and spending requires a reviewed cosmetic purchase |
| Pending mail with source `commerce-reward:%` | Quarantined, excluded from normal pending delivery, and rejected by delivery acknowledgement |
| Previously committed paid cosmetic entitlement | Remains a database-owned appearance when issuance is disabled |

The core migration adds `earned_member_until` as nullable and **does not copy `member_until` into it**. The old column mixes earned and paid history; neither the timestamp nor `lifetime_paid` proves an earned source.

New earned membership purchases retain the existing Starlight account/debit authority. Their transaction records `rpg_earned_membership_grants` with the purchase UUID, original debit reference, product, cost, days, previous expiry and new expiry. A live membership read joins the grant to the matching `rpg_commerce_purchases` row and `rpg_credit_ledger` debit, including the same player and amount. A timestamp alone, a missing debit, or another player's debit fails closed.

Historical earned membership is not automatically reconstructed. Operators must preserve and review the original Starlight purchase/debit evidence before designing an audited migration for those grants. This patch does not invent membership from ambiguous rows, extend from a paid legacy timestamp, erase old history or quietly compensate with currency.

## Data migration

`Database.migrate()` remains the canonical runtime DDL. The schema manifest includes the new tables, unique boundaries and indexes.

Added tables:

- `rpg_support_payment_orders`: unique order, original signed payload/signature/hash, beneficiary, issuing key, audience, amount, accepted UTC week and ledger reference.
- `rpg_support_credit_limits`: per-player and global weekly issuance counters, unique by scope and week.
- `rpg_support_cosmetic_purchases`: immutable purchase operation and exact price with a unique debit reference.
- `rpg_support_cosmetic_entitlements`: unique player/appearance ownership linked to a purchase.
- `rpg_earned_membership_grants`: proof of an earned membership purchase and its original Starlight debit.

The support balance and ledger tables already existed and remain the only Support Credit balance authority. New tables carry receipt, quota and ownership evidence; none is a second balance store.

The delivery migration adds `quarantine_reason` and `quarantined_at`. On each migration pass, only still-`PENDING` rows whose source starts `commerce-reward:` become `QUARANTINED`, with reason `LEGACY_PAID_REWARD`. IDs, payloads, source labels and existing audit data remain intact. `DELIVERED` history is not rewritten. Re-running the migration is safe.

The delivery service also refuses new `commerce-reward:` enqueue requests and prevents legacy paid-source acknowledgement from freeing a frozen inventory delivery. A rejected acknowledgement leaves the in-inventory delivery marker frozen for review; it does not destroy the item or replace it with a currency refund.

### Read-only review commands and queries

Deploy the matching `RpgApi`, `RpgCore`, `RpgCommerce`, `RpgCosmetics` and `RpgMarket` binaries together. An older core or mail consumer can still implement the retired paths even if a newer front end hides them.

The trusted console can inspect one exact canonical player UUID without creating an account or granting a benefit:

```text
rpgcommerce legacy-payments <uuid> [limit]
```

`limit` defaults to 10 and must be 1–50. The command requires `rpg.commerce.legacy.audit`; player operators cannot invoke it. Rows include the original ID, timestamp, amount, historical membership days, external reference and operator. The service returns an immutable list, filters the exact UUID, and orders by `created_at DESC, id DESC`. The `(player_uuid, created_at, id)` index supports this bounded audit. Historical values are shown as stored, including the opaque original row ID, and do not create new membership evidence.

```sql
-- Ambiguous historical membership: audit only, never copy it wholesale.
SELECT player_uuid, lifetime_paid, member_until, earned_member_until
FROM rpg_commerce_accounts
WHERE member_until IS NOT NULL;

-- Original payment history; retain external references for provider reconciliation.
SELECT external_reference, player_uuid, amount, membership_days, operator_name, created_at
FROM rpg_payment_ledger
ORDER BY created_at;

-- Pending legacy paid rewards held for review; payloads are retained.
SELECT id, player_uuid, source, state, quarantine_reason, quarantined_at
FROM rpg_market_deliveries
WHERE source LIKE 'commerce-reward:%';

-- New signed order audit and accepted UTC-week issuance.
SELECT order_id, player_uuid, key_id, audience, credit_units, payload_hash, week_key, accepted_at
FROM rpg_support_payment_orders
ORDER BY accepted_at;
SELECT scope_id, week_key, issued_units
FROM rpg_support_credit_limits
ORDER BY week_key, scope_id;

-- Earned membership must retain all three matching pieces of evidence.
SELECT g.player_uuid, g.idempotency_key, g.member_until, g.credit_cost,
       p.id AS purchase_id, l.external_reference AS original_debit
FROM rpg_earned_membership_grants g
JOIN rpg_commerce_purchases p
  ON p.id = g.purchase_id AND p.idempotency_key = g.idempotency_key
 AND p.player_uuid = g.player_uuid AND p.credit_cost = g.credit_cost
 AND p.product_id = g.product_id AND p.period_key = g.period_key AND p.gold_cost = 0
JOIN rpg_credit_ledger l
  ON l.external_reference = g.debit_reference AND l.player_uuid = g.player_uuid
 AND l.delta = -g.credit_cost AND l.operator_name = 'commerce-purchase';
```

**Previously delivered materials cannot be automatically inferred or reclaimed.** A protection stone or lucky charm may have been consumed, transferred, combined with other stacks, or have no surviving delivery marker. The patch does not scan unrelated inventories and guess which item was paid. Historical delivered goods require operator review using the preserved payment, reward and delivery records. Legacy cosmetic PDC flags likewise do not prove a paid or earned origin; their compatibility is limited to the existing appearance and does not grant new currency or membership.

## Failure handling and rollback

A receipt is accepted only when its complete transaction commits. Queueing a request, verifying a signature, or logging an attempt does not mean credit has been durably granted. An executor rejection or JDBC failure may return an exceptional completion. If the commit acknowledgement is lost, replay the same signed envelope; an accepted exact order becomes `DUPLICATE` with zero new credit.

The import lock order is the authoritative payment mechanism row, global UTC-week quota, player UTC-week quota, then Support Credit account. The mechanism row remains locked until commit, and the final time check happens after the last financial lock. Different orders cannot overrun a global or player cap, and a request that waits into a new week must re-enter admission. A purchase locks its account and commits its receipt, debit and entitlement together.

The registry gate is read from the same business transaction, not trusted from a process cache. Its in-memory allowed/denied metrics count admission checks, **not distinct payments**; duplicate replays also check the gate. Use the persisted orders, ledger and quota rows for financial reconciliation. Never derive a balance from transient metric counters.

To stop new issuance, run `rpgcommerce support-payments disable` from the trusted console and check its confirmed effective status. The disable locks the same mechanism row, waits for previously admitted transactions to commit or roll back, and then affects new receipt admission on every node. It cannot cancel an earlier committed order. To stop new cosmetic purchases as well, disable the deployment policy and restart with the disabled configuration. Retain all order, ledger, ownership and historical quarantine records. Never roll back to an old binary that re-enables `recordPayment`, consumes the old mixed membership timestamp, or releases `commerce-reward:` mail.

There is no automatic balance compensation, entitlement deletion, chargeback or refund implementation in this patch. A future reversal must refer to the exact original debit and enforce a unique reversal; it cannot be a manual unsigned `grant` replacement. Do not remove accepted order rows to make a receipt replayable.

Signed payloads are immutable audit material. A canonical player UUID migration cannot edit the embedded UUID, signature or payload hash and still call it the original receipt. An account-migration adapter must explicitly reconcile the signed beneficiary; a blind string replacement is not a valid migration.

## Evidence and unproven boundaries

- **[S] Source:** signed receipt protocol, signature verification, independent support accounts/ledger, atomic limits and entitlements, legacy fail-closed paths, evidence-backed earned membership, exact-session checkout, expiring rendering cache and legacy paid-mail quarantine.
- **[T] Test:** actual `Database.migrate()` schema, JDK Ed25519 RFC 8032 vector, altered payload/signature, wrong audience/key, time and cap limits, concurrent duplicate and distinct orders, rollback before commit, lost commit response, failed entitlement write, duplicate purchases, source-mismatched membership evidence and stale session/cache completions.
- **[R] Runtime:** a live provider-to-bridge-to-Paper purchase, provider key rotation, PostgreSQL lock latency under production load, and visual selection after reconnect must be checked on the target deployment. Automated JDBC tests are not these runtime observations.
- **[H] Human:** spending and refund expectations, support workflow, operator reconciliation and long-term use remain unproven.

The PostgreSQL-capable tests create independent schemas when the dedicated `SIVITA_SUPPORT_TEST_POSTGRES_*` variables are explicitly supplied. Report the actual backend and results of the executed run; the availability of that fixture is not evidence that a PostgreSQL run occurred.
