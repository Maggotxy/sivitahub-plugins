# Mechanism completion review — developer integration

[English developer home](../en/README.md) · [All 25 plugins](../en/PLUGIN_REFERENCE.md) · [Gameplay contracts](../en/GAMEPLAY_SPECIFICATION.md)

**Review date:** 2026-11-04 UTC. **Scope:** the SivitaHub plugin-source repository, its content/build pipeline and phase-based implementation stack. The English deliverable serves developers; changing player language is optional and does not define this work.

This review supersedes statements about missing mechanisms in the older `c83226ff...` source audit only where the concrete implementations below now exist. Historical test/runtime reports remain attached to their original snapshots. It does not relabel private server deployment, client visual acceptance or human playtesting as completed source work.

## 1. Connected implementation delivered

| Mechanism | Concrete completion | Authoritative boundary | Review location |
|---|---|---|---|
| Funded buy/craft orders | Fund, list, inspect owned orders, quote, match actual escrow, fill partially, cancel and expire | Existing Gold ledger, market listing custody, signed gear identity and mailbox | [Order migration](../operations/MARKET_ORDER_MIGRATION.md) |
| Partial-fill refunds | Equal original debit reversal, settled consumed quantity and re-held remainder in one transaction | C-006 equal-amount, single reversal proof | `JdbcMarketBuyOrderService` |
| Concurrent account creation | Fresh locking read after a competing creator wins the account or Merit-wallet insert | Existing balances, caps and ledger keys; initial read of existing rows unchanged | [Phase 2 follow-up #11](https://github.com/Maggotxy/sivitahub-plugins/pull/11) |
| Natural-mob rewards | Immutable durable acceptance, stable XP/item IDs, frozen random/pity outcome, bounded retry/replay | Journal accepts; core XP/mailbox settles | [Recovery contract](../../plugins/rpg-mobs/RECOVERY.md) |
| Combat targeting | Native `Enemy` and marked dungeon combatants, including relevant non-Monster types | Shared target rules followed by existing eligibility/PvP rules | `CombatTargetRules`, skills/classes adapters |
| Dungeon entity integrity | Reject bucket/feed/split/transform interactions that remove registered combatants from the encounter | Existing dungeon ownership PDC and lifecycle | `DungeonCombatantProtection` |
| Non-dungeon progression | Six permanent Field Journal routes, live remaining criteria, auth-time reconciliation | Vanilla facts and one unique display stamp; no faucet | `FieldJournalService`, `rpg-world` |
| Signed support payment | Canonical Ed25519 receipt, audience/key/order/player/units/validity binding, quotas and kill switch | Separate Support Credit account/ledger/order receipts | `SupportCreditService` and receipt codec |
| Cosmetic purchase | Preview/confirmation, atomic debit + purchase + entitlement, read-through visual ownership | Database entitlement, exact player connection and cache generation | `/support`, commerce and cosmetics |
| Legacy paid-benefit closure | Unsigned grant/spend and material/payment-member routes closed; old mail quarantined | Preserve original historical records; no automatic conversion | `JdbcCommerceService`, `JdbcLegacyPaidDeliveryTest` |
| Earned membership | New earned expiry plus matching purchase, real Starlight debit and grant proof | Old mixed expiry cannot authorize a Gold discount | `rpg_earned_membership_grants` |
| Community roles | Reachable role grants, role-bound proposals and current distinct signers | Existing seven stored roles and approval evidence | `CommunityRoleIntent`, governance services |
| Succession/dissolution | Durable propose/approve/cancel/execute/status/history, inactivity and cooldown rechecks | Community lock, current membership, activity and audit | `CommunityLifecycleService` |
| Public construction custody | Pristine material deposit, exact-recipient multi-person withdrawal, same mailbox, original-donor dissolution return | The single market escrow with community scope | `CommunityStorageService` |
| Civilization exhibition | Completed projects and successful season settlements, including dissolved communities | Read-only existing contribution/completion/reward facts | `/civilization`, `CivilizationHistoryService` |
| Multi-node session lifecycle | Acquire, renew, handoff, accept/cancel and release with token/epoch | Initiating actor verified in the same business transaction | [Session/outbox contracts](../en/SESSION_AND_OUTBOX.md) |
| Durable mailbox admission | `PENDING` → `CLAIMED` → `STAGING` → `DELIVERED`, exact-token receipt recovery and frozen uncertain outcomes | Existing delivery row plus saved physical receipt; no timeout-based reissue after authorization | [Mailbox claim migration](../operations/MAILBOX_CLAIM_MIGRATION.md) |
| Event replay | Transactional outbox, expiring claim/backoff, idempotent consumer and durable dungeon inbox | Event intent and consumer receipt commit with their state | `OutboxDispatcher`, `JdbcDungeonCompletionInbox` |
| Offline identity migration | Reviewed one-to-one UUID move, schema/file inventory, locked plan revalidation and atomic migration receipt | Covered Core database only; active authority, identity collisions and unsupported external state block apply | [UUID identity migration](../operations/UUID_IDENTITY_MIGRATION.md) |
| Economic reporting calendar | Zone-aware instant boundaries, including date crossings and 23/25-hour days | The configured business calendar | [PR #9](https://github.com/Maggotxy/sivitahub-plugins/pull/9) |
| Runtime mechanism control | Console-only confirmed changes, persisted operator reason, two-second async refresh and ten-second stale fail-closed admission | Existing switch registry; payment uses transaction-held authoritative gate | [Mechanism control](../operations/MECHANISM_CONTROL.md) |
| Actual startup verification | Positive enabled-plugin/service probe, pinned runtime and disposable PostgreSQL | Loaded/enabled state; fail closed on missing services/errors | [Runtime verification](../operations/RUNTIME_STARTUP_VERIFICATION.md) |

The database remains the authority for balances, custody, character state and settlement. Display collections, visual entitlements and civilization history do not create another gameplay currency. Existing identity, signing and economic invariants remain intact.

## 2. Every plugin is included in the integration scope

The [plugin reference](../en/PLUGIN_REFERENCE.md) documents all 25 modules, including their commands, permission nodes, configuration/source links, extension contract and recovery checks. The following groupings identify the whole-server connections reviewed; they do not imply that unchanged modules were rewritten.

| Modules | Connected responsibility |
|---|---|
| `rpg-api`, `rpg-core` | Shared rules, service ownership, persistent state, receipts, migration and recovery |
| `rpg-classes`, `rpg-skills`, `rpg-combat` | Character/build choices, active skill execution, enemy selection and combat restrictions |
| `rpg-items`, `rpg-enhancement`, `rpg-sulfur` | Resource inputs, signed crafting, refinement/mutation and equipment exits |
| `rpg-mobs`, `rpg-world`, `rpg-dungeons` | Overworld encounters, world events, permanent discoveries, instanced expeditions and rewards |
| `rpg-economy`, `rpg-market`, `rpg-commerce` | Earned sources, quotas/sinks, circulation, funded labor and isolated support purchases |
| `rpg-community`, `rpg-pets`, `rpg-cosmetics` | Cooperation, shared custody/history, companion collection and non-power visual identity |
| `rpg-guide`, `rpg-hub`, `rpg-story`, `rpg-ai-npc` | Arrival, understandable next steps, safe travel, optional contextual story and advisory help |
| `rpg-integrations`, `rpg-essentials`, `rpg-performance`, `rpg-admin` | HUD/permissions, constrained utilities, bounded work and audited operation/recovery |

## 3. Assets and playable content

The content checker validates the registered item, skill, dungeon and mob-model references together with the resource-pack inputs. The committed client baseline contains 252 registered PNGs. The catalog check covers 81 item model entries, 42 skills, six dungeon variants and 15 mob models. These are source/content coverage counts; they are not a claim that every animation and attachment was watched in a client.

The portable resource-pack build, BetterHud source checks, navigation datapack and story package checks run independently. Server model/HUD registration candidates still require the pinned plugins to compile them and a real client to review animation, occlusion, layout, telegraphs and readability.

The developer bundle includes an explicit inventory of 50 public documents, including all 16 English guides and eight operations runbooks. The handbook, gameplay specification and 25-plugin reference are linked from its English installation guide; each document is covered by the archive hash manifest. The developer bundle preserves the canonical player configuration. `scripts/package-edition.py --english-player-preset` explicitly opts into the existing English presentation overlays. The bundle checks all 25 JARs and their source provenance, including shared-API Java changes; it refuses missing or stale plugin artifacts.

## 4. Evidence ledger

### Published runtime baseline

[PR #10](https://github.com/Maggotxy/sivitahub-plugins/pull/10), commit `035d288cac8e58478e083a4ac3c4b5d9af4bf547`, passed [CI run 41879473522](https://github.com/Maggotxy/sivitahub-plugins/actions/runs/41879473522):

- All 25 plugin artifacts built; 1,171 plugin tests, zero failures/errors/skips.
- Market settlement and database migration re-ran against real PostgreSQL 18.6.
- Paper 26.2 build 100 started against PostgreSQL 18.6.
- Positive probe: 25 custom plugins enabled, all required core services present.
- Classified custom-plugin, community-plugin and unattributed startup errors: zero.

This evidence applies to that commit. Subsequent mechanisms are tested and re-run on their own integration heads; a successful earlier startup is not proof of their later behavior.

### Phase 2 concurrency follow-up

[PR #11](https://github.com/Maggotxy/sivitahub-plugins/pull/11), final head [`b7f0362fb2eb6ef2ac2b17f6eb08f29eb64fc552`](https://github.com/Maggotxy/sivitahub-plugins/commit/b7f0362fb2eb6ef2ac2b17f6eb08f29eb64fc552), fixes the concurrent first-creation failure found while validating the later Phase 5 tree. It contains the production correction from `58f6b15aafc70d5cbf1786cb1cb7e5cc241c8002` and a subsequent strengthening of its regression fixture. It is a separate Phase 2 correction on the published runtime baseline; the updated PR was fast-forwarded without rewriting its published history.

The pinned H2 2.3.232 commits its storage transaction before advancing each modified table's query-cache version. During that interval, `INSERT ... ON CONFLICT DO NOTHING` can observe a committed unique key and return zero affected rows, while an identical ordinary `SELECT` reuses its earlier empty result. A fresh query and a locking query both see the real row. This ordering was checked in the [exact upstream source artifact](https://repo.maven.apache.org/maven2/com/h2database/h2/2.3.232/h2-2.3.232-sources.jar): `SessionLocal.commit`, `MVTable.afterCommit`, `Query.query` and `Select.isEverything`.

The strengthened [creation-visibility regression](../../plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/JdbcActivityEconomyCreationVisibilityTest.java) holds that actual commit interval open using H2's public table-engine extension and an observer of real JDBC results within the still-open service transaction. Both account and Merit-wallet cases failed on the original service with their respective “missing after upsert” exceptions, then passed with only the post-creation reads changed to `FOR UPDATE`. They also verify preservation of the existing balance, reward ceilings, duplicate protection, retry after a real purchase, ledger counts and mailbox output. No account is precreated to hide the competing-creator case, and the initial read of an existing row retains its ordinary-query behavior.

The initial test fixture in PR #11 returned the primed connection to the pool and cleared the relevant query cache, so that snapshot's first 112-test passing run does not establish the race regression. The final head supersedes it with the observer-based fixture. Its isolated Gradle/XML verification produced **2 exact failures with the original service**, then **112 passes with the fixed service: 2 visibility regressions plus all 110 existing `JdbcServicesTest` cases**, with zero errors or skips in the passing run. The production correction is unchanged. These results are bound to final tree `6d1c74f8f713be8ba7b23a8e49e56259564bc697`; they are local Phase 2 evidence, not a new whole-stack, PostgreSQL or Paper-runtime total.

### Phase 8 mailbox and identity boundaries

The mailbox path is implemented through [`MailboxDeliveryLedger`](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/MailboxDeliveryLedger.java), [`MailboxDeliveryCoordinator`](../../plugins/rpg-market/src/main/java/cn/originalrpg/market/MailboxDeliveryCoordinator.java) and the actual `MarketCommand` inventory adapter. A 30-second `CLAIMED` reservation can be released or reacquired before physical authorization. The single successful `CLAIMED` → `STAGING` transition authorizes one insertion; repeated authorization returns an indeterminate outcome. `STAGING` never becomes pending merely because a lease expires or a process disconnects.

The main-thread adapter inserts one validated stack into one empty slot with both delivery-ID and claim-token PDC markers, saves player data, acknowledges the exact token in SQL, and clears the markers only after that acknowledgement succeeds. A new authenticated session can recover a unique saved receipt; ambiguous, malformed, duplicate or refused receipts stay frozen. Legacy acknowledgement is restricted to rows with no claim token, and quarantined or `commerce-reward:` deliveries remain refused throughout. The token protocol also runs in single-node mode; enabling player-session authority adds the actor's node/process/session/fence checks. The [migration guide](../operations/MAILBOX_CLAIM_MIGRATION.md) describes additive schema changes and why mixed old/new Market binaries are unsafe.

The real JDBC claim tests and the production callback coordinator tests exercise lost replies, expiry, reconnect, unload and receipt recovery with a controlled inventory port. They provide service/callback evidence. Actual Paper playerdata durability, transport between nodes and two-node crash/reconnect acceptance remain external validation requirements.

The offline `sivita-db-migration identity --dry-run|--apply` command is also implemented. [`IdentityMigrationEngine`](../../tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/IdentityMigrationEngine.java) binds an explicitly reviewed UUID mapping and ownership-evidence hash to an immutable plan, inventories typed Core columns and external files, revalidates that plan under the maintenance/table locks, and writes the identity-only transformation plus its audit receipt in one transaction. The exact same operation and original plan can resolve an uncertain commit through the durable receipt. The tool provides no runtime alias, account merge or inferred official UUID.

Apply refuses active nodes/sessions/outbox claims, unrecovered dungeon reservations, every unresolved `CLAIMED`/`STAGING` mailbox row, target-identity collisions, unknown schema fields, unsupported opaque assets, affected live equipment/pets, signed payment evidence and required external adapters. The coverage contract requires explicit review of all external providers; declaring a present provider absent does not make its files supported. There is no force/ignore-adapter mode. [Database integration, input-safety and public CLI tests](../operations/UUID_IDENTITY_MIGRATION.md#validation-and-remaining-gates) cover the implemented transformation and refusal paths. Every report retains `CORE_DATABASE_ONLY` and `full_server_identity_cutover_verified: false`; authenticated account stores, signed payloads and physical player/world state still require their own verified adapters and cutover evidence.

<!-- INTEGRATION_EVIDENCE_START -->
### Final integrated source verification

The complete executable source stack is [PR #16](https://github.com/Maggotxy/sivitahub-plugins/pull/16), commit [`54ee9fddd86715c41399bfbeb38a535291ffe6e5`](https://github.com/Maggotxy/sivitahub-plugins/commit/54ee9fddd86715c41399bfbeb38a535291ffe6e5), tree `4c0268b3513dcf16110dfc898b58adabe4ae7edc`. All four jobs passed in [CI run 41894556258](https://github.com/Maggotxy/sivitahub-plugins/actions/runs/41894556258); GitHub tested PR merge commit `8b573256f816c4c29ba7ee2dd4738a38fd702615`. The English developer handoff adds documentation and packaging instructions to that source stack.

| Executed verification | Result |
|---|---|
| All 25 plugin modules | Built successfully; 1,419 tests passed, zero failures/errors, one PostgreSQL-only case skipped in this backend-neutral job |
| Complete Core H2 suite | 478 passed; one PostgreSQL-only case skipped |
| Migration tools on the local/H2 path | 47 passed; three PostgreSQL-only cases skipped |
| Selected real PostgreSQL 18.6 Core transaction suites | 157 passed; zero failures/errors/skips |
| Migration suite with PostgreSQL configured | 50 passed; zero failures/errors/skips |
| Real Paper 26.2 build 100 with PostgreSQL 18.6 | Startup gate GREEN; probe ready; all 25 custom plugins enabled |
| Runtime error classification | Zero custom-plugin, community-plugin and unattributed startup errors |

The PostgreSQL rerun includes market settlement, signed support, legacy isolation, community custody/governance, session fencing, node lock ordering, outbox replay and mailbox claims. The migration suite includes its real PostgreSQL copy/advisory-lock/identity maintenance-lock cases as well as parser, H2 and CLI tests. The four backend-conditional local skips were executed successfully in their configured PostgreSQL job; they are not counted as local passes. The startup probe checks eight explicitly named Core services and enabled state for every custom plugin; it does not claim every service or player path was exercised.

The final local developer source tree separately built all 25 real JARs. The packager verified their common fingerprint over 722 main-source/resource/build input files:

`835b8c2b0d8ff663bdcf24ed73b6fbad49dd37c1a1c5d3812c69f859cd958ea8`

Its local final results match 1,419 plugin passes plus 47 migration-tool passes, with the four conditional skips described above. Three old season-test fixtures that cast the private registry cache were replaced with actual database initialization and switch updates; all original settlement/zero-effect assertions were retained before the successful full-Core rerun.

Content and engineering checks also passed: 450 source-contract checks across 78 specifications; 140 mechanism entries (79 configuration, 58 behavior, three Java constants, zero weak entries); 93 consumed/registered tables with no table exemptions; six bilingual groups with 3,203 keys per locale; navigation/dialog and story references. Local Python verification executed 103 tooling tests and 25 economy-model tests. The final developer packager's 16 tests include documentation inventory, canonical configuration preservation, source provenance, excluded private inputs, mandatory signing-key instructions and failure paths.

The fifteen-scenario economic model executed successfully, including the expected forbidden support-to-power rejection. Its result remains `CALIBRATION=NEEDS_TUNING`; simulated participation and sensitivity checks are not a calibrated production economy. The portable client pack was rebuilt from all 597 committed files, with SHA-256 `3e1f31a62d56dd9240eb08d47f4726155079727be9ac197213a6d7b7061bbbe0`. Asset-baseline, closure and 141 HUD-layout checks passed.

### Review stack

Each implementation PR has one phase scope. Follow the dependency order rather than merging only the final documentation PR:

| PR | Review scope |
|---|---|
| [#7](https://github.com/Maggotxy/sivitahub-plugins/pull/7) | Funded buy/craft order fulfillment |
| [#8](https://github.com/Maggotxy/sivitahub-plugins/pull/8) | Developer verification workflow |
| [#9](https://github.com/Maggotxy/sivitahub-plugins/pull/9) | Economic reporting calendar correction |
| [#10](https://github.com/Maggotxy/sivitahub-plugins/pull/10) | Positive real-runtime startup probe |
| [#11](https://github.com/Maggotxy/sivitahub-plugins/pull/11) | Concurrent activity account creation and demonstrated regression |
| [#12](https://github.com/Maggotxy/sivitahub-plugins/pull/12) | Signed cosmetic support and legacy paid-benefit isolation |
| [#13](https://github.com/Maggotxy/sivitahub-plugins/pull/13) | Community custody, succession/dissolution and civilization history |
| [#14](https://github.com/Maggotxy/sivitahub-plugins/pull/14) | Field Journal, combat protections and durable natural rewards |
| [#15](https://github.com/Maggotxy/sivitahub-plugins/pull/15) | Sessions, mailbox claims, event replay and reviewed identity tooling |
| [#16](https://github.com/Maggotxy/sivitahub-plugins/pull/16) | Runtime mechanism controls and bounded snapshot refresh |

Earlier PRs #1–#6 form the inherited source/build/asset baseline; their evidence remains attached to those snapshots. All branches are reviewable drafts. This work has not merged the stack into main or deployed a production server.
<!-- INTEGRATION_EVIDENCE_END -->

Evidence labels retain their actual meaning: `[D]` designed, `[S]` implemented, `[T]` executed tests, `[M]` executed model, `[R]` real runtime, `[H]` real human interaction. Static source-string checks are not behavioral execution. A skipped PostgreSQL-specific test is not a PostgreSQL pass.

## 5. Failure and migration decisions

**Preserve proof instead of guessing.** Old order holds with ambiguous partial refunds do not auto-settle. New gear matchers do not retroactively trust an unverified payload. Legacy payment rows, mixed membership expiry and paid material payloads remain historical facts. New runtime eligibility does not infer a lawful earned source from those mixed records.

**Keep physical ambiguity visible.** A refused mailbox acknowledgement leaves both receipt markers frozen. An uncertain `STAGING` row cannot authorize another item or be reset by timeout; a saved exact-token receipt can complete acknowledgement without reissuing the item. Prepared or quarantined community deposits block dissolution. Accepted natural rewards replay with the same outcome and IDs. A process dying before durable acceptance is distinct from losing the reply after a committed transaction.

**Do not treat timeouts as proof of absence.** Session/outbox leases can fence SQL ownership and permit bounded event retries; they do not establish which physical playerdata file is canonical after a multi-node inventory handoff. Consistent player/world transport and actual cross-node failure drills are separate deployment requirements.

**Keep governance authorization distinct from world execution.** The generic project/event approvals emit authorization facts. They do not implement automatic land claims, block construction or scheduled event scripts. The delivered weekly contribution project, shared construction-material custody and completion exhibition support actual cooperative play; map snapshots remain a deployment responsibility.

**Retain audit when social state changes.** A dissolved community is a tombstoned historical entity. Contributions, awarded honor, proposal history and completed exhibition entries remain. Recoverable public supplies return to original donors through the same durable mailbox transaction; no world build or historical score is silently deleted.

**Rollback requires compatible state.** Reverting Java cannot undo a committed purchase, a delivered item, a renamed identity or a completed season. Use the documented migration notes, stop writes when required, retain old/new snapshots, and verify restoration before opening the corresponding production gate.

## 6. Remaining external and acceptance work

The public source can implement and test service contracts, but it cannot manufacture the missing production facts:

1. **Private deployment:** production worlds, website/payment issuer, AuthMe/LuckPerms data, proxy routing, operator credentials, populated NPC/map registration and backup automation.
2. **Physical multi-node state:** authoritative playerdata/inventory/PDC transport and cross-node mailbox handoff under node loss. SQL fencing alone is not an inventory synchronizer.
3. **Full-server identity conversion:** the implemented offline tool covers the reviewed Core database subset. Production cutover additionally needs a verified old/new UUID mapping and complete coverage of signed Bukkit payloads, plugin databases, files and external accounts. Unsupported or unknown stores must block apply; never guess official identities from names or treat a Core receipt as proof of whole-server migration.
4. **Client presentation:** actual models, HUD, story/NPC interaction, combat telegraphs, layout and inventory behavior on the pinned client/server.
5. **Human/economic acceptance:** at least 20 testers for seven days, observed pacing/retention/economy, no duplication or duplicate debits, accepted crash recovery and backup restoration.
6. **Capacity:** target-hardware load evidence, including the roadmap P95 tick-time target of at most 45 ms. No public player-capacity promise follows from the test count.

Default-off integration switches remain deliberate deployment gates. Their configurations and exact enabling requirements are documented in English. Opening them is an operator decision after the corresponding evidence exists, not a side effect of packaging a developer edition.

## 7. Developer handoff

Start with the [handbook](../en/DEVELOPER_HANDBOOK.md), then the [whole-server gameplay specification](../en/GAMEPLAY_SPECIFICATION.md), [25-plugin reference](../en/PLUGIN_REFERENCE.md), [phase plan](../en/PHASE_PLAN.md) and [operations guide](../en/OPERATIONS.md). Keep a reviewed source commit, dependency hashes, migration inputs and test/runtime artifacts together.

Follow the existing one-phase-per-PR rule. Each PR names its goal, non-goals, current facts, constitutional invariants, data migration, failure modes, rollback, tests, runtime evidence and remaining unproven items. The deliverable is reviewable implementation plus evidence, with operational limits stated precisely.
