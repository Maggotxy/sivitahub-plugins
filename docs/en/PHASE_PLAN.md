# Mechanism implementation plan

[Developer handbook](DEVELOPER_HANDBOOK.md) · [Constitution](GAME_ECONOMY_CONSTITUTION.md) · [Canonical execution plan](../CODEX_LONG_TERM_EXECUTION_PLAN.md)

This is the English engineering contract for the existing long-term plan. Phase numbers identify design dependencies and review scope; they are not completion badges. One phase per implementation PR, no direct main commits, and explicit `[D][S][T][M][R][H]` evidence remain mandatory. Historical first-task instructions in AGENTS describe earlier audit/progression work; inspect the current branch and accepted task before repeating it.

## Scope and acceptance

| Phase | Mechanism to deliver | Required acceptance and boundaries |
|---|---|---|
| 0 | Mechanism registry, kill switches and metrics | Missing/invalid config fails clearly; disabled sources produce zero rewards; automation may freeze/limit/alert, not reprice |
| 1 | Local 1–30 progression, stage budgets, ascension 0–3 and unique milestone grants | Effective level never accidentally resets; no retired simple ascension bypass; migration anomalies remain reviewable; accepted XP replays once |
| 2 | Economy and the twelve-state equipment lifecycle | Core owns money/identity; unique open item escrow; duplicate/transfer/container/replay paths cannot create wealth or identities |
| 3 | Fifteen-scenario economic simulation | Real config inputs, hashes and seeds; executable behavior; conservation and explicit synthetic assumptions; calibrate before forecasting |
| 4 | Equipment exits and market V2 | Salvage/sacrifice/legacy/attunement connect to the existing lifecycle; funded buy/craft orders have reachable fulfillment, tax, delivery and receipt-backed exit; floating destruction needs a separate RFC |
| 5 | Isolated Support Credit and transactional soft seasons | Verified payment order boundary and weekly quota; no paid power/wealth conversion; preview/approve/freeze/settle/verify/replay/rollback and permanent-layer preservation |
| 6 | Community governance and civilization | Seven roles, distinct-person approvals, succession, dissolution cooldown, audited public storage, city projects and world history; no exclusive combat bonuses |
| 7 | Non-dungeon endgame and content production | Permanent collection, rotating affixes, time attack and usable content pipeline; bounded template warm pools and complete asset references; no rarity/grind substitution for depth |
| 8 | Online UUID migration and multi-node operation | Idempotent identity mapping, session acquire/renew/release/handoff and stale-owner fencing, mailbox coordination, transactional outbox/consumer idempotency, advisory migration lock and node-loss tests |

The canonical dependency graph is in [long-term-game-economy-v1.yaml](../planning/long-term-game-economy-v1.yaml). Dependencies must hold for each reviewed change even when a later audit finds a missing path in an earlier phase.

## Current delivery principles

Progression rules, equipment operations, core ledgers, entry/reward recovery, season services and community projects are existing implementation areas. This revision closes additional paths rather than replacing their authority: escrow-backed buy/craft fulfillment, durable natural-mob reward acceptance, inclusive combatant selection, permanent Field Journal objectives, and complete lease/event infrastructure.

The Field Journal provides a bounded six-route collection feature with real vanilla completion facts. Buy/crafting orders provide a complete maker-supplied finished-goods loop. Their explicit scope must remain visible: the journal does not implement every possible future collection; a crafting commission does not lend buyer materials.

Never infer a whole phase is complete because a class or enum exists. In particular, a support-credit balance is not a verified payment integration; a transfer command is not an inactive-leader succession process; an approval record is not a completed city build; a lease handoff is not transport of Minecraft inventory/PDC; and a database-copy tool is not an offline-to-online UUID migration of every external plugin and signed item.

## Reviewable deliverable for each operation

Describe the player/operator entry point, authorizing actor, prerequisites, authoritative state source, stable operation identity, exact cost/output, state transitions, quotas, kill switch, repeated-request semantics, disconnect/restart behavior and exit path. Include the content assets and messages needed to understand every meaningful state.

Schema work includes a dry-run, additive forward path, verification, rollback conditions and ambiguous-data handling. A rollback must preserve accepted intent, audit receipts and permanent assets. Refunds reference genuine equal-amount debits and unique reversals; they are not discretionary minting.

## Verification ladder

1. Rules and service tests exercise real outputs, boundaries and transaction state.
2. Concurrency/replay/failure injection proves the accepted operation's recovery contract.
3. Source/content checks verify wiring, IDs, resource coverage and migration manifest consistency.
4. The model runs fifteen scenarios and identifies calibration/assumption limits.
5. Real Paper/PostgreSQL and client evidence validates events, serialization, UI, recovery and node behavior.
6. Human acceptance covers the complete journey and sustained play/economy observations.

Do not replace missing runtime or human evidence with more source-string assertions. The canonical Alpha acceptance remains at least twenty testers over seven days, zero unexplained duplication or double debit, validated accepted crash-recovery scenarios, P95 tick time at or below 45 ms, and verified backup recovery. These are release gates, not results claimed by this document.
