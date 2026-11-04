# Roadmap and evidence

[English documentation](README.md) · [Mechanism completion review](../audits/MECHANISM_COMPLETION_REVIEW.md) · [Operations](OPERATIONS.md)

The development objective is a connected survival RPG whose mechanisms have reachable entries, authoritative settlement, useful feedback and recovery. This English edition gives developers the contracts needed to extend that server. Player language selection remains an independent presentation setting.

Historical audits describe the commit named in each audit. The earlier [source review](../audits/CURRENT_SOURCE_REVIEW.md) targets `c83226ff87c8ecb868277d9ca5bce575b4684abc`; it must not be used to conclude that later implementations are still missing. Conversely, a later source change does not inherit runtime evidence from an earlier commit.

## What is implemented in the current development stack

| Area | Implementation | Evidence still required for operating acceptance |
|---|---|---|
| Build and startup | All 25 custom plugins and pinned dependencies; the recorded baseline below has a full build and positive startup probe | Re-run on the final candidate and the actual deployment configuration |
| Progression | Four ascension stages, local 1–30/effective 1–120, chapter authority, XP and milestone receipts | Review migrated characters and run progression with real players |
| Market | Escrow sell orders, funded buy/craft orders, partial fills, fixed taxes, expiry and proved refunds | Real client inventory, crash/reconnect and economic observation |
| Natural rewards | Durable immutable accepted intents, stable XP IDs, mailbox item settlement and replay | Target-filesystem failure behavior and actual death/load testing |
| Combat/content | Native enemy and marked-combatant targeting, dungeon interaction protection, six Field Journal routes | Client telegraphs, animation, layout, difficulty and fun |
| Support payments | Default-off signed, audience-bound receipts; isolated credits; atomic cosmetic entitlement purchases; closed legacy gameplay benefits | Configure the trusted payment issuer and verify real settlement/reversal operations |
| Communities | Default-off advanced roles/lifecycle/storage; reachable weekly projects and retained completed project/season exhibits | Multi-person interaction, actual inventory custody and operator acceptance |
| Seasons | Preview/approval/freeze/settle/verify and permanent project/season exhibition | Fault/recovery drills on a copied real dataset and approved operation |
| Multiple servers | Session acquire/renew/handoff/release, actor fencing inside business transactions, transactional outbox/inbox | Multiple real Paper processes, proxy routing and consistent player/world storage |
| Economy model | Fourteen executable cohort scenarios plus a deliberately rejected Support Credit isolation scenario | Calibration against observed activity, wealth, stock and retention |
| English development | Build path, module ownership, API/transaction contracts, content extension, schema, failure and release guides | Developer onboarding feedback and continued documentation maintenance |

The table describes concrete source work and the remaining acceptance boundary. Detailed tests, migration behavior and exceptions belong to the [mechanism completion review](../audits/MECHANISM_COMPLETION_REVIEW.md) and the phase PRs.

## Recorded runtime baseline

[Runtime-readiness PR #10](https://github.com/Maggotxy/sivitahub-plugins/pull/10), commit `035d288cac8e58478e083a4ac3c4b5d9af4bf547`, passed [CI run 41879473522](https://github.com/Maggotxy/sivitahub-plugins/actions/runs/41879473522). That run built all 25 plugins, recorded 1,171 plugin tests with no failures/errors/skips, passed real PostgreSQL market/migration verification, and started Paper 26.2 build 100 against PostgreSQL 18.6.

The positive probe observed all 25 custom plugins enabled and the eight core services in its explicit checklist registered. Classified custom, community-plugin and unattributed errors were zero. This is startup evidence for that exact snapshot. The subsequent payment, community, discovery and network changes require the same gates on their final integration head; the earlier result is not silently extended to them. Registration of a service also does not establish its gameplay or recovery behavior.

See [runtime startup verification](../operations/RUNTIME_STARTUP_VERIFICATION.md) for the probe, pinned artifacts, isolation rules and its limits. A successful startup does not establish correct player inventory recovery or supported player capacity.

## Acceptance work after implementation

### 1. Run the complete player journey

Exercise authentication, class choice, gathering/forging, skill use, an ordinary and hidden encounter, an escrow trade/order fill, mailbox claim, community membership/storage, discovery progress and reconnect. Verify every displayed cost against committed receipts. Follow the route with distinct roles and inventories, including full bags and disconnects during confirmation.

**Acceptance:** a recorded Paper/PostgreSQL/client run with no unexplained duplicate debit, duplicate item, missing accepted reward, orphan reservation or permanently stuck return path.

### 2. Exercise real failure boundaries

Use disposable snapshots to interrupt dungeon settlement, natural-reward acceptance, mailbox confirmation, community withdrawal, season settlement and session handoff. Distinguish operations that were never durably accepted from accepted operations requiring replay. Never label H2 concurrency or a startup probe as a completed multi-node crash drill.

**Acceptance:** the same operation identity either commits once or remains explicitly recoverable; original debit/refund references and authoritative item custody remain intact. Preserve logs, before/after rows and the exact failure point.

### 3. Calibrate the already implemented economy model

Run [the fifteen scenarios](../../tools/economy-sim/README.md) with configuration hashes, seeds and reviewed participation assumptions. Compare their outputs with real ledger/activity evidence before using them to justify prices or population forecasts. The support-credit-to-power scenario is supposed to be rejected.

**Acceptance:** input provenance is reproducible, conservation and quotas hold, and differences between synthetic assumptions and observed behavior are explained. No automated price/drop repricing is authorized.

### 4. Review private integrations and identity migration

The public repository does not contain the site's payment issuer, production AuthMe/LuckPerms data, proxy routing, shared playerdata transport, populated maps or backup system. A database UUID rewrite cannot silently cover signed ItemStack/PDC blobs, third-party accounts or world files. Inventory these stores, review the mapping and use supported migration coverage; unknown stores block migration.

**Acceptance:** a reviewed dry run, offline backup, complete store coverage, an atomic supported apply, deterministic verification and an exercised restoration procedure. Never infer official UUIDs from names without verified mapping evidence.

### 5. Complete human and load acceptance

The [canonical roadmap](../ROADMAP.md) requires at least 20 testers for seven days, no duplication or duplicate debits, accepted dungeon crash recovery, P95 tick time no higher than 45 ms and verified backup restoration. These are release targets. Larger player/entity/instance scenarios must be measured in stages on the actual hardware.

**Acceptance:** real interaction, telemetry and incident evidence satisfy the target; polished icons and passing tests alone cannot establish enjoyable pacing or operational capacity.

## Evidence levels

| Label | Meaning | It does not substitute for |
|---|---|---|
| `[D]` Design | Purpose, invariants and specification | An implementation |
| `[S]` Source | Concrete reachable code and wiring | Executed tests |
| `[T]` Test | Recorded automated execution | Real deployed gameplay |
| `[M]` Simulation | Executed model with inputs and outputs | Real economic behavior |
| `[R]` Runtime | Actual Paper/PostgreSQL evidence | Human experience over time |
| `[H]` Human | Real interaction and sustained playtesting | Missing source or technical checks |

Use one phase per implementation PR. The authoritative constraints remain in [AGENTS.md](../../AGENTS.md), [the phase plan](PHASE_PLAN.md), [the machine-readable plan](../planning/long-term-game-economy-v1.yaml) and [the constitution](GAME_ECONOMY_CONSTITUTION.md). No roadmap item authorizes a new currency, a second market or another vertical ascension tier.
