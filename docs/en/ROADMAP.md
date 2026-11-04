# Roadmap and evidence

[English documentation](README.md) · [Current source review](../audits/CURRENT_SOURCE_REVIEW.md) · [Operations](OPERATIONS.md)

The central release question is whether the integrated experience is reproducible, understandable, and reliable under real play. Module count, a startup log, or a passing set of source tests cannot answer that alone.

This English overview describes source baseline `c83226ff87c8ecb868277d9ca5bce575b4684abc`. Historical phase documents remain useful design/history records, but must be checked against current implementation and the evidence actually available.

## Current state

| Area | What the repository supports | What remains to establish |
|---|---|---|
| Gameplay suite | 25 custom plugins, core gameplay catalogs, resource-pack and datapack workspaces | Complete authorized deployment and client acceptance |
| Progression | Local 1–30, ascensions 0–3, effective 1–120, stage budgets and local skill-point milestones | Legacy-data review and current runtime/human regression evidence |
| Transactions and recovery | Persistent services for profiles, wealth, market, delivery, progression, and other systems | PostgreSQL fault injection and complete gameplay recovery under Paper lifecycle failures |
| English support | Extensive bilingual message data and player language selection; English documentation entry points | Remaining shared-world labels, third-party/operator interfaces, rendered UI and semantic acceptance |
| Economy simulation | Fourteen executable cohort scenarios and an expected support-credit isolation rejection; actual config inputs and explicit behavior assumptions | Offline and uncalibrated; requires observed data and separate runtime fault/recovery tests |
| Season and governance | State/services and guarded entry points exist | Default-off integration paths, full failure recovery, and human operational evidence |
| Multiple servers | Some node/lease/outbox primitives exist | Complete player lease lifecycle, real event consumers, fencing, idempotent processing, and cross-node tests |

The [simulator runner](../../tools/economy-sim/run.py) does not yet model the 14 economic behavior scenarios. The [source review](../audits/CURRENT_SOURCE_REVIEW.md) also distinguishes read-side session admission and an outbox dispatcher from a complete multi-node integration. Do not label either capability production-ready from class names alone.

## Recommended next increments

### 1. Make the source and onboarding reproducible

Keep the repository entry points accurate about missing private deployment inputs. Pin dependencies with verified hashes, make checks report real failures, and maintain an English path from project overview through build, gameplay, and operations. These improvements make existing work reviewable and reduce onboarding errors.

**Acceptance:** a clean checkout can follow the documented source workflow; links, locale structure, placeholders, and dependency manifests pass their checks; unavailable environment-dependent steps are identified clearly.

### 2. Prove the core player journey

Exercise authentication, class choice, first equipment, skill use, a normal dungeon, a trade, a delivery claim, and recovery after reconnect. Run that journey in both languages. Check real costs and outcomes against every preview and confirmation.

**Acceptance:** recorded Paper/PostgreSQL and client evidence, with no unexplained duplicate debit, item duplication, orphan reservation, or stuck return path in the tested scenarios.

### 3. Implement the missing economy model

Use real configuration and reviewed population/activity assumptions to run all fifteen scenarios. Record input hashes, seeds, measured outputs, and unmet conditions. Compare model output with observed gameplay before using it to justify prices, quotas, or expansion.

**Acceptance:** scenario behavior actually executes; model assumptions and limitations are explicit; no placeholder status is reported as a passing simulation. See the [simulator specification](../../tools/economy-sim/README.md).

### 4. Validate settlement and community governance separately

Keep season settlement, attunement, and advanced governance behind their reviewed gates. Test preview/approval, concurrency, partial failure, replay, audit, and rollback before enabling them for players. A phase PR should make one coherent capability reviewable.

**Acceptance:** documented authority, migration, failure, rollback, and transaction evidence for the complete operation; human review of the resulting player-facing behavior.

### 5. Finish the multi-node protocol before deploying it

Implement and verify the lease acquisition/renewal/release path, stale-owner fencing, mailbox claim coordination, event handlers, idempotent consumers, and identity migration requirements. Exercise node loss and database failure on more than one actual server process.

**Acceptance:** complete cross-node tests and operating evidence satisfy C-014. Enabling a configuration switch or deploying Velocity does not itself meet this gate.

## Evidence levels

| Label | Meaning | It does not substitute for |
|---|---|---|
| `[D]` Design | Specification, plan, RFC, or invariant | An implementation |
| `[S]` Source | Concrete code and wiring | Executed tests |
| `[T]` Test | Recorded automated test execution | Real deployed gameplay |
| `[M]` Simulation | Executed model with inputs and outputs | Real economic behavior |
| `[R]` Runtime | Real Paper/PostgreSQL deployment evidence | Human experience over time |
| `[H]` Human | Real interaction and sustained playtesting | Missing source or technical checks |

This edition does not claim a new production rollout, real PostgreSQL failure drill, or human playtest. Consult the current PR/test report for commands actually executed during the documentation and tooling change.

## Release gates

The [canonical roadmap](../ROADMAP.md) defines the Alpha target of at least 20 testers for seven days, no duplication or duplicate debits, complete dungeon crash recovery in the accepted scenarios, P95 tick time no higher than 45 ms, and verified backup recovery. Larger capacity scenarios are test goals and must be approached in stages.

The long-term phase plan remains in [AGENTS.md](../../AGENTS.md) and [the machine-readable plan](../planning/long-term-game-economy-v1.yaml). This roadmap does not authorize new currencies, higher ascension limits, a second market, or a shortcut around the [constitution](GAME_ECONOMY_CONSTITUTION.md).
