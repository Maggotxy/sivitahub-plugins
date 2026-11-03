# Gameplay and economy constitution

[English documentation](README.md) · [Contributing](CONTRIBUTING.md)

This is an English translation of [the version 1.0 constitution](../GAME_ECONOMY_CONSTITUTION.md), the project's highest-level mechanism constraints. It does not amend those constraints. If an interpretation differs, review it against the canonical Chinese text and resolve it through the amendment process.

Any gameplay, balance, shop, season, or currency change that conflicts with the constitution requires an RFC and a reviewed constitutional amendment before implementation.

## Purpose

The project combines **vanilla survival, MMORPG character builds, a player economy, community development, and persistent history across soft seasons**. The economy serves the complete gameplay loop; accumulating Gold is not a separate substitute for that loop.

## C-001 — One authoritative state source

Each system—levels, currencies, equipment, market, seasons, and communities—has one authoritative state source. Do not introduce dual writes, an independent in-memory authority, or private YAML state that overrides the database.

## C-002 — Currency changes go through the ledger

Every change to `gold`, `merit`, `starlight`, or `support_credit` must go through an `rpg-core` ledger transaction. The balance, idempotency key, and audit record commit together. Direct balance updates, in-memory compensation, and unaudited money-granting commands are prohibited.

## C-003 — Player trades do not create money

Player-to-player transactions redistribute existing Gold. They do not mint additional Gold. Transaction fees are permanently removed from circulation.

## C-004 — Renewable materials cannot produce unlimited money on their own

Renewable or automatable resources such as logs, crops, stone, and iron must not have unlimited fixed-price NPC buyback. They must use the escrow market with its fee sink, or commissions that require labor evidence, daily limits, and account playtime.

## C-005 — Every faucet needs four controls

Each currency-creating source—including hunting, dungeons, commissions, events, exchanges, and compensation—must have an idempotency key, daily/weekly limits, metrics, and a kill switch. A source missing any of these is incomplete.

## C-006 — Refunds reference the original debit

A refund or reversal must reference a real, unreversed debit for the same player and amount within the transaction, with uniqueness enforcement. Compensation cannot create an unrelated grant without the original transaction relationship.

## C-007 — The currency graph has no positive-value cycle

Validate the exchange graph using the reference value vector `gold = 1`, `merit = 600`, and `starlight = 1400`. A route that increases total reference value must fail startup under the constitutional requirement. A strictly lossy sequence cannot produce a profitable cycle.

This article states the required invariant; it is not a substitute for verifying that the current startup and transaction code enforce it.

## C-008 — Repeated high-value output needs an exit path

Every repeatedly produced high-value item, including equipment, rare materials, and tickets, needs at least one removal path: salvage, sacrifice, legacy extraction, attunement, or destruction through taxation. A system that only creates items without retiring them has a design defect.

## C-009 — One escrow market

Custom equipment and high-value materials circulate through one escrow settlement system. Its listing flow uses `PREPARED → ACTIVE → SOLD / CANCELLED / EXPIRED`, a unique `item_id` index, and the specified 5%/10% fees. Do not add a competing trade system, a direct high-value peer transfer, or a duplicated chest-shop implementation.

## C-010 — Season settlement is transactional

Season settlement must use the `SeasonSettlementService` state machine:

```text
DRAFT → PREVIEWED → APPROVED → FREEZING → SETTLING → VERIFYING → COMPLETED
Failure/recovery states: FAILED, ROLLED_BACK
```

Changing only `season-id` or a configuration value does not perform settlement. Permanent and seasonal asset layers must remain physically separated. The actual legal transitions and recovery procedures must be checked in the state-machine implementation.

## C-011 — Do not rely on full-server wipes

Full-server wipes, rollbacks, or private database edits must not become the routine way to repair an unhealthy economy. Use reviewed parameter changes, targeted freezes, and audited compensation transactions.

## C-012 — Paid support is isolated

`support_credit` is a separate paid-support ledger, physically isolated from `gold`, `merit`, and `starlight`. It must not provide a route to any of the following:

- Gold, Merit, or Starlight.
- Equipment, materials, protection stones, or lucky charms.
- Experience, Energy, or drop-rate benefits.
- Season attunement or combat power used in rankings.

This isolation is a required design and implementation boundary. A display label alone does not prove it.

## C-013 — Automation can freeze; it cannot tune prices

Automated risk or anti-cheat actions are limited to freezing abnormal sources, throttling, and alerting. They must not automatically alter prices, drop rates, quotas, or tax rates.

## C-014 — Multi-server prerequisites

Before enabling a Velocity network or multiple Paper nodes, the system must have player Session Leases, mailbox claim leases, a Transactional Outbox, and globally coordinated advisory-lock migrations. An in-memory lock must not be described as a cross-node lock.

## C-015 — Chapters and ascensions are bound together

`MAX_ASCENSION = 3` and `LOCAL_LEVEL_CAP = 30`, producing four effective-level ranges:

| Ascension | Local levels | Effective levels |
|---|---|---|
| 0 | 1–30 | 1–30 |
| 1 | 1–30 | 31–60 |
| 2 | 1–30 | 61–90 |
| 3 | 1–30 | 91–120 |

`rpg_chapter_state` is the sole authority for chapter progression. Do not open chapters automatically by date, skip a chapter, or move chapter progression backward.

## Amendment process

1. Submit the [mechanism RFC template](../templates/MECHANISM_RFC_TEMPLATE.md).
2. Identify the constitutional articles being changed or introduced.
3. After review, amend the canonical constitution in a PR and increment its version. Update this English translation alongside it.

Source baseline for this translation: `c83226ff87c8ecb868277d9ca5bce575b4684abc`.
