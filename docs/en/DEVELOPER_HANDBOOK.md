# Developer handbook

[English home](README.md) · [Architecture](ARCHITECTURE.md) · [Gameplay specification](GAMEPLAY_SPECIFICATION.md) · [Phase plan](PHASE_PLAN.md)

This edition is for engineers who build, change and maintain SivitaHub. Java identifiers, database names, commands, permission nodes, PDC keys and content IDs are the same in every language. There is one codebase and one set of gameplay rules. English player messages are a separate presentation feature; they do not define the scope of this developer edition.

## 1. Establish the exact checkout

Read [AGENTS.md](../../AGENTS.md), the [English constitution](GAME_ECONOMY_CONSTITUTION.md) and [phase plan](PHASE_PLAN.md). Follow the rights notice in [INTERNAL_USE.md](../../INTERNAL_USE.md). Keep the reviewed commit, dependency hashes and test evidence together. Historical audit files describe their own snapshots, not the current branch by implication.

The supported source target is Minecraft Java 26.2, Paper API `26.2.build.100-stable`, JDK 25 and Gradle 9.1.0. The smoke runtime is separately pinned to Paper build 100 in [runtime-smoke-paper.lock.json](../../scripts/runtime-smoke-paper.lock.json). Change the API, runtime and third-party compatibility evidence together when upgrading.

```sh
# Run in a checkout with JDK 25 selected by your normal Java toolchain.
java -version
./gradlew --version
sh scripts/fetch-deps.sh
sh scripts/fetch-deps.sh --check
./gradlew test assemble -PexcludeTags=env --continue
```

`fetch-deps.sh` verifies exact third-party JARs. An HTTP failure is a dependency failure, not permission to substitute a newer Citizens or create API stubs. `-PexcludeTags=env` excludes explicitly marked deployment tests; it does not turn a source build into a server acceptance test. Read [Getting started](GETTING_STARTED.md) for workstation setup and [CI](../../.github/workflows/verify.yml) for the isolated test environment.

## 2. Find the owner before changing behavior

Use the [25-module responsibility table](ARCHITECTURE.md#all-25-plugin-modules) and [detailed plugin reference](PLUGIN_REFERENCE.md). In practice:

| Change | Start here | Authoritative boundary |
|---|---|---|
| Local/effective level, XP or ascension | `rpg-api/rules/ProgressionRules`, `rpg-core/persistence/JdbcProfileService`, `JdbcAscensionService` | Profile, XP receipts and milestone grants in core |
| Skill targeting, effects or costs | `rpg-skills`, `rpg-classes`, `rpg-combat`, `CombatTargetRules` | Combat rules; persistent skill/attribute changes through core |
| Forge/refine/retire equipment | `rpg-items/forge`, `rpg-enhancement` | `EquipmentOperationService`, signed identity and lifecycle ledger |
| Natural-mob reward | `rpg-mobs` reward coordinator/journal | Durable accepted intent; core XP receipt and market mailbox |
| Dungeon entry, encounter or recovery | `rpg-dungeons` | Entry reservations, run identity, reward receipt and journal |
| Listing, buy request or commission | `rpg-market`, `JdbcMarketService`, `JdbcMarketBuyOrderService` | The existing gold ledger, item escrow, lifecycle and delivery tables |
| Discovery/collection objective | `rpg-world` Field Journal adapter, `FieldJournalService` | Vanilla advancement facts plus permanent, unique display receipts |
| Gold/Merit/Starlight source | `rpg-economy`, the appropriate core service | Quota, idempotency, ledger and kill switch in one transaction |
| Support Credit | `SupportCreditService` and its isolated tables | Never a route to combat, equipment or gameplay currencies |
| Community role/project | `rpg-community`, `CommunityService` | Membership/permissions and transactional approval evidence |
| Season transition | `SeasonSettlementService`, admin interface | Approved settlement state machine, not a date/config edit |
| Session ownership/event replay | `PlayerSessionService`, `SessionActor`, core session/outbox classes | Lease fencing, transactional event intent and consumer receipts |
| Presentation/model/HUD | `MessageService`, resource-pack workspace, owning adapter | Display projection; never reinterpret signed economic payloads |

A feature module may cache a read snapshot. It must not maintain a second authoritative wallet, equipment state, skill-point budget or market inventory.

For shared construction supplies, `CommunityStorageService` is a scoped adapter over the same escrow table. Normal market APIs must exclude those rows even when a caller supplies their IDs directly. `CommunityLifecycleService` owns timed succession and dissolution. Their default-off gates, command paths and recovery contract are in [Community governance](COMMUNITY_GOVERNANCE.md). For permanent history, `CivilizationHistoryService` reads completed projects and season settlements without awarding another score or writing a parallel archive authority. Generic project/event approval facts do not claim land, place blocks or execute an event script.

Mechanism admission uses the existing durable switch registry. Core schedules an asynchronous refresh every 40 ticks, nominally two seconds; cached admission fails closed after ten seconds without a successful full snapshot. A failed refresh or uncertain switch commit also invalidates admission until a successful read. Unknown IDs remain disabled even if a database row is inserted for them. The authorized console reviews or changes registered switches through `/rpgadmin mechanism`; `CONFIRM` and a bounded reason are required for writes. Accepted transactions and refunds retain their recovery paths. Sources: [registry](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/MechanismRegistry.java), [control command](../../plugins/rpg-admin/src/main/java/cn/originalrpg/admin/MechanismControlCommand.java).

## 3. Thread and connection contracts

| Context | Safe responsibilities | Required boundary |
|---|---|---|
| Paper server thread | Read/write entities, inventories, worlds, PDC; inspect live advancement progress; show menus | Capture immutable inputs before dispatching asynchronous work |
| Bounded persistence executor | JDBC transactions, receipt checks, balance/state changes | Return a `CompletionStage`; queue rejection is an explicit failure |
| Bounded journal I/O | Serialize/checksum/fsync reward intent and remove fully settled records | A queued write is not a durable acceptance acknowledgement |
| Asynchronous completion | Inspect immutable service results | Schedule world/UI work back to Paper; revalidate the actual login handle |
| Placeholder/telemetry read | Read prepared snapshots | No synchronous JDBC on the tick thread |

Do not block the Paper thread on `join()`, disk `fsync`, network requests or JDBC. Do not use `CallerRunsPolicy` for rejected persistence work. Completing a future after a disconnect can be correct for a durable reward; displaying that result through a stale `Player` handle is not.

When session leases are enabled, annotate the **initiating player**, not every UUID. For example, the seller authorizes `fillListing(orderId, sellerId, listingId, key)`; the buyer is an offline-capable beneficiary. Core captures the initiator's token before dispatch and verifies it inside the same business connection. Background expiry, reward replay and settlement are not authorized by pretending the recipient is currently logged in. See [`SessionActor`](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/session/SessionActor.java).

## 4. Implement a complete gameplay operation

A content label or service interface is only the beginning. A complete operation has these observable stages:

1. **Reachable entry:** registered command/menu/NPC/event, permissions and authentication.
2. **Preview:** inputs, prerequisites, exact cost, fee, outcome category and recovery destination.
3. **Authoritative eligibility:** recheck ownership, profile/chapter, quota, state and source identity at commit time.
4. **Acceptance:** either a database transaction owns the request, or a durable journal explicitly acknowledges it.
5. **Settlement:** persist the effect and idempotency/audit receipt together; reuse existing wallets and item lifecycle.
6. **Delivery:** committed items enter the existing mailbox; a full inventory does not erase them.
7. **Feedback and recovery:** success, duplicate, rejected, pending, reconnect, restart and ambiguous-response paths are distinguishable.
8. **Exit:** cancellation, expiry, salvage, failure return or terminal state is implemented and tested.

A GUI preview cannot authorize settlement. A fresh random idempotency key on retry cannot recover the previous operation. A process-local set cannot fence a second Paper node.

### Example: funded order matching

The existing API takes identities of already escrowed resources:

```java
MarketBuyOrderService orders = plugin.getServer().getServicesManager()
        .load(MarketBuyOrderService.class);
// Capture these immutable IDs on the authenticated Paper command path.
String receipt = "buy-order-fill:" + orderId + ":" + listingId;
CompletionStage<BuyOrderOperationResult> pending = orders.fillListing(
        orderId, sellerId, listingId, receipt);
// Handle completion through your plugin's lifecycle-aware Paper callback helper.
```

There is no `fill(rawItemPayload)` path. The listing provides its persisted matcher, quantity, crafter and signed identity. Ordinary raw-material requests accept pristine vanilla items; equipment uses `equipment:<type>:<rarity>`. A CRAFT request additionally requires the seller to be the signed original crafter. The maker supplies materials; the buyer reserves a price budget. This is a finished-equipment commission, not unrestricted lending of buyer materials.

Each fill locks the order and listing, validates the current funded hold, closes the item escrow, advances its identity, settles the seller, burns the fee and creates the buyer's delivery in one transaction. Whole listed stacks move; an order can remain partially unfilled. Its quoted unit price includes tax, so the seller's receipt is the quote less the 5% ordinary/10% equipment fee. The confirmation states that this replaces the listing's asking price.

For a partial fill, core reverses the entire current hold, settles the consumed part, and creates a new hold for the remainder **within the same transaction**. Every reversal therefore references an equal-amount debit, including a later cancellation. The buyer's spendable balance does not become available between these steps. See [market migration and recovery](../operations/MARKET_ORDER_MIGRATION.md).

## 5. Add a skill or encounter without breaking target semantics

The current catalog contains six starting classes and seven skills per class. A new skill needs a catalog ID, cost/cooldown data, an execution handler, input/loadout integration, display text/icon, and a failure path. Trace all of these from `RpgSkillsPlugin`, `SkillsCommand` and `SkillMechanics`; the catalog's expected membership is checked by tests.

Use `CombatTargetRules` for automatic PvE selection. It accepts valid living native `Enemy` mobs and explicitly marked dungeon mobs, and excludes players. `Monster.class` alone excludes valid enemies and Paper 26.2 Sulfur Cubes. Preserve the existing friendly-unit, range, visibility and separate player-damage restrictions. An unmarked passive Sulfur Cube is not made hostile just because it shares a type with a dungeon boss.

Dungeon combatants carry the existing boss/wave ownership markers. `DungeonCombatantProtection` prevents collecting, feeding, splitting or transforming a registered combatant out of the encounter. When adding an unusual vanilla entity, verify every native state-changing interaction that could make a wave unfinishable.

Dungeon definitions are in `rpg-dungeons/src/main/resources/config.yml`. Each family/variant needs entrance and chapter requirements, energy/ticket policy, waves, boss behavior, failure/return handling, reward identity, model registration and bounded instance construction. The current warm pool uses procedurally built empty worlds. A new hand-authored template loader would require a separate registered, validated construction path. Update the content checker and catalog tests deliberately if the reviewed family count changes; do not merely add a YAML entry that no dispatcher can reach.

## 6. Add non-dungeon collection content

The [Field Journal](FIELD_JOURNAL.md) is a concrete example of permanent, non-power progression. Its initial catalog covers six survival/exploration objectives. The Paper adapter validates registered advancement IDs, the exact reviewed criterion sets and live completion facts; core stores one receipt per player and entry. The GUI shows remaining criteria and first journal-registration time. Authentication-time reconciliation recovers completed advancements whose event was missed while the database or plugin was unavailable.

To add an objective, update the bounded API catalog, its verified vanilla advancement mapping, bilingual description/action hints, the menu and tests. Do not add a second progression counter by parsing translated advancement titles. A persistent badge is not a currency faucet and does not grant attack, drop-rate or experience bonuses.

## 7. Content and asset pipeline

The [content checker](../../scripts/check-content-assets.py) cross-checks skill and class icons, dungeon/boss/wave IDs, model dispatch and authored textures. Server-side registration candidates and the portable client pack are different outputs:

```sh
python3 scripts/check-content-assets.py
python3 scripts/check-story-package.py
python3 scripts/check-navigation.py
python3 scripts/check-locales.py
./gradlew buildResourcePack
```

Use the owning module's configured content IDs in models, item dispatch, NPC registration and quest packages. BetterModel `.bbmodel` inputs and BetterHud layout inputs still need the pinned server plugins to compile them and a real client to validate rendering. Static presence, texture bounds and registration coverage cannot prove animation, attachment positions, occlusion or readability.

Preserve texture/model provenance and rights. Player-facing text uses `MessageService`; translate explanations, not command names or item identities. Display-only refresh must skip pending escrow/operation receipts and preserve signatures, custom player names and quantities. Locale parity checks key/type/placeholder structure, not the quality of an encounter.

## 8. Schema and compatibility work

Runtime schema belongs to `rpg-core/persistence/Database.java`. The migration utility consumes a reviewed table/unique-key/index manifest; it does not invent the target schema. Whenever adding a table or index, update `tools/db-migration/.../SchemaManifest.java`, its contract test, a migration/recovery note and the relevant behavioral fixture.

Prefer additive migrations. Existing market listings without matching facts remain valid ordinary listings but cannot satisfy a buy/craft order until re-listed. Legacy orders with no provable equal-amount active hold fail closed. Unknown old XP/skill-point shapes require the documented review; never derive old ascension data with a modulo operation.

Legacy paid-material deliveries are preserved as quarantined records with a reason and timestamp. `MarketService.markDelivered` returns true only for this player's allowed, durably delivered receipt, including an exact acknowledgement replay. A false result must keep the physical marker frozen; it is not permission to clear an item lock. Legacy paid membership cannot be copied into the new earned expiry: live membership also requires the purchase, Starlight debit and earned-grant proof.

Data rollback is a state-recovery decision, not just reverting Java. Drain or cancel pending orders before deploying an older matcher; preserve monetary receipts and terminal item states. Never drop a ledger column or journal to obtain a clean startup.

## 9. Test the risk that changed

| Risk | Useful evidence |
|---|---|
| Changed eligibility/formula | Deterministic rules and boundary cases |
| Duplicate or concurrent monetary operation | Real JDBC state/balance/ledger assertions |
| Commit succeeded but response was lost | Replay the same identity; assert one final effect |
| Delivery fails after money work | Inject the failure; assert whole-transaction rollback |
| Journal acceptance/restart | Real files, serialization, failure and replay tests |
| Bukkit entity interaction | Real event objects and targeted execution tests; then actual Paper/client evidence |
| PostgreSQL semantics | Run the same transaction suite on a real isolated PostgreSQL schema |
| Long-term price/inventory stability | Fifteen model scenarios plus observed-data calibration |
| Capacity/player experience | Real target hardware, server/client traces and sustained human tests |

Focused market verification:

```sh
./gradlew :plugins:rpg-core:test \
  --tests cn.originalrpg.core.persistence.JdbcMarketBuyOrderServiceTest -PexcludeTags=env
```

For PostgreSQL, use an empty disposable test database and the `SIVITA_MARKET_TEST_POSTGRES_URL`, `SIVITA_MARKET_TEST_POSTGRES_USER`, `SIVITA_MARKET_TEST_POSTGRES_PASSWORD` variables. The suite creates and drops its own random schema. It refuses the runtime `RPG_DATABASE_URL` override. CI supplies an ephemeral service, never production credentials. Migration integration tests use the separate `SIVITA_MIGRATION_TEST_POSTGRES_*` variables.

Community lifecycle, role approvals and custody use the independent `SIVITA_COMMUNITY_TEST_POSTGRES_*` fixture described in [Community governance](COMMUNITY_GOVERNANCE.md#reproducible-verification). Each test runs the production migrations in its own random schema; H2 results must not be presented as a PostgreSQL lock-order check.

Record the actual Gradle exit, JUnit XML, source commit and environment. Preserve H2 results before a PostgreSQL rerun replaces the test-result directory. Source-string contracts are structural checks; report them separately from behavioral tests.

## 10. Review and release

Use one phase per PR and the required sections: Goal, Non-goals, Current facts, Constitution invariants, Data migration, Failure modes, Rollback, Tests, Runtime evidence, Remaining unproven items. See [Contributing](CONTRIBUTING.md).

`[D][S][T]` establish designed, implemented and tested code. `[M]` adds simulation; `[R]` requires actual Paper/PostgreSQL evidence; `[H]` requires real players. Lease handoff does not transport inventory/PDC files by itself. A complete set of source JARs is not the missing private deployment tree. Keep these boundaries visible in release notes and follow the [roadmap](ROADMAP.md).
