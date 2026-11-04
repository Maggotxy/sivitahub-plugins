# Gameplay systems and extension contracts

[Developer handbook](DEVELOPER_HANDBOOK.md) · [Architecture](ARCHITECTURE.md) · [Constitution](GAME_ECONOMY_CONSTITUTION.md)

This document explains the whole server as connected gameplay systems for developers. It describes the checked-in design and the implementation boundaries that must remain intact when adding content. It is not a claim that every private deployment integration, future phase or human acceptance gate has already been completed.

## Product direction

The server combines vanilla survival, finite RPG progression, character builds, player production and trade, communities and persistent season history. Survival resources should have meaningful uses; equipment should circulate and eventually leave the economy; capped characters should have horizontal goals. Extending a grind or adding a rare roll is not a substitute for a new decision, encounter or cooperative objective.

The core progression promise is fixed: four stages, each with local levels 1–30; ascension 0–3; effective level 1–120. Effective level is `ascension * 30 + localLevel`. The chapter record, not the calendar, authorizes later stages. A capped character continues through builds, collection, exploration, speed records and community work rather than another vertical level tier.

## Connected player journey

| Stage | Player objective and decision | Systems that must connect | Completion/recovery boundary |
|---|---|---|---|
| Arrival | Authenticate, understand the hub, choose a class | Auth integration, hub, guide, classes, core profile | Ready/revoked session events; never grant through an unverified login |
| First build | Gather resources, forge a usable item, learn/cast skills | Survival, items, skill catalog, combat | Signed item identity, milestone point budget and checked skill eligibility |
| First expedition | Select encounter, pay entry cost, cooperate, recover loot | Party/queue, entry reservation, waves/boss, reward receipt | Durable run identity, return destination, energy/ticket compensation and mailbox |
| Production and exchange | Choose to sell stock or fulfill a funded request | Forge, bind/unbind, escrow listing, buy/craft order | One item escrow; fixed tax; receipt-backed funded hold; durable delivery |
| Build refinement | Decide which equipment deserves further investment | Revelation, refinement, limit break, rare trait and mutation | Existing equipment operation transaction; failure output and replay identity |
| Ascension | Meet the stage's class, profile, equipment and achievement conditions | Branch graph, dungeon evidence, Merit, chapter gate | `AscensionService` rechecks all authoritative conditions in one transaction |
| Shared world | Join projects, mentor, coordinate events | Community permissions, contribution evidence, project rewards | Core membership/approval facts; no exclusive combat advantage |
| Horizontal endgame | Complete discoveries, optimize runs, collect companions and appearances | Field Journal, pet collection, rotating affixes, time records, cosmetics | Permanent facts and explicit season scope; no unlimited level/power loop |
| New season | Review and approve a predictable transition | Settlement preview/freeze/settle/verify, archive and recovery | Preserve permanent assets; transactional settlement rather than changing an ID |

The onboarding prologue is an optional path through this journey. Story must advance meaningful state or provide useful context; it should not create a second currency/XP ladder or reward players for repeatedly opening dialogue.

## Character builds: classes, branches, skills and combat

`rpg-classes` owns class/branch selection and menus. `rpg-skills` owns the forty-two-skill catalog, acquisition, loadout and casting behavior. `rpg-combat` provides shared damage, defense and combat-tag rules. Core owns persistent profile, skill-point, branch and ascension state.

Class branch progression is constrained by `ClassBranchGraph`: branches stay in the same class, parent/child stages are adjacent, and the configured stage structure is validated. Hidden paths must satisfy their actual prerequisites and chapter/cap conditions. A visible menu entry is not proof that its unlock or payment path is complete.

Each skill needs a clear purpose in the build: damage, control, movement, protection or support, with an understandable cost and cooldown. Keep PvP and boss scaling explicit. Automatic PvE acquisition uses `CombatTargetRules` and excludes players; existing friendly-unit, range and visibility checks remain the caller's responsibility. Native hostile categories are broader than `Monster`; marked dungeon mobs can be combatants even if vanilla classifies their type as passive.

Add tests for resource/cooldown consumption on rejected casts, no-target behavior, friendly units, native non-Monster enemies, marked and unmarked Sulfur Cubes, disconnected players and temporary entity cleanup. Then verify the actual cast animation, hit feedback and UI on Paper/client.

## Equipment: production, specialization and exit

`rpg-items` creates signed gear from accepted inputs. Gear has a persistent identity, original crafter, binding/ownership information, rarity/type, slots and combat properties. `rpg-enhancement` composes refinement, limit breaks and mutation with the same operation service. Player-visible text is a projection of those facts.

The lifecycle contains twelve named states; the allowed transition graph in `EquipmentLifecycle` is authoritative. The list of state names is **not** an instruction to traverse them as a linear chain. Escrow, sale, binding, salvage, sacrifice, legacy extraction, season attunement, destruction, quarantine and restoration each have specific preconditions and terminal/recovery behavior.

The important production decisions are which base and materials to invest, which slots/traits fit the build, when to refine or attempt a risky improvement, and whether to retain, unbind/sell, salvage or extract legacy value. Preserve a meaningful exit for repeatably generated high-value items. Season attunement is initiated by settlement under its gate, not an unrestricted player shortcut.

For every change, verify that input removal cannot race an unrelated inventory move, accepted operations survive reconnect/restart, a replay does not reroll the outcome, and output/refund uses existing durable delivery. Never change the signing key to fix a display or translation problem.

## Encounters: overworld, world events and dungeons

`rpg-mobs` supplies adaptive overworld behavior, elite variants and natural-mob reward integration. `rpg-world` coordinates world events and hunt chains. `rpg-dungeons` supplies the party/queue, isolated instance and encounter lifecycle.

The checked-in dungeon catalog contains Ancient Ruins, Sulfur Depths and Storm Vault, each with a normal and hidden variant. Each needs an entrance gate, readable threats, progression through waves/objectives, boss telegraphs, a reliable victory/failure condition, rewards and a return path. Hidden variants should change decisions or mechanics, not merely multiply health and drop rarity.

The instance system maintains a warm pool, bounded waiting queues and a per-tick construction budget. `DungeonManager.startNewInstance` creates an empty flat world and builds its arena procedurally; no hand-authored world-template cloning entry is registered. Preserve those resource bounds when extending construction. A new family also requires boss/wave model mappings, complete registration inputs and content-check updates. A model file that no configured entity references is not delivered encounter content.

Registered dungeon combatants cannot be bucketed, fed into invulnerability, split or transformed out of the encounter. Native entity interactions are part of encounter design and must be reviewed when introducing new vanilla types.

Natural-mob rewards now use an explicit acceptance boundary: the immutable accepted outcome is persisted before authoritative XP/item settlement. XP reuses its stable grant receipt; custom item drops use the existing mailbox. Vanilla drops remain vanilla. Recovery reuses frozen outcomes and IDs rather than spawning a second world item or rerolling. PDC-based pity and the journal are not a cross-file atomic transaction; document this limitation rather than claiming physical-playerdata crash atomicity.

## Economy: circulation, labor and commerce

| Resource | Intended role | Authoritative restriction |
|---|---|---|
| Gold | Player trade, crafting and system sinks | Core balance + audit + stable idempotency; trades redistribute existing value |
| Merit | Account-bound activity/progression resource | Controlled eligible sources, quota and core ledger |
| Starlight | Account-bound, audited configured purchases/conversion | Conversion graph cannot increase reference value; no silent alternate payment route |
| Support Credit | Isolated real-payment credit for permitted cosmetics/convenience | Separate physical account/ledger; cannot enter gameplay wealth or power |

Renewable material must not become unlimited fixed-price NPC income. Resource commissions require activity evidence, quota and account-age rules. Each faucet requires identity, daily/weekly quota, metrics and a kill switch. Automated anomaly handling may freeze/limit/alert; it must not dynamically rewrite prices or drop rates.

The offline [economy model](../../tools/economy-sim/README.md) executes fifteen scenarios with configuration hashes and explicit population/behavior assumptions. Use it to expose conservation, concentration, stock age and sink/faucet risks. Calibrate against observed ledger/activity data before interpreting synthetic results as server forecasts.

### One market, including buy requests and crafting commissions

Ordinary listings, physical stalls, funded buy requests and crafting commissions all use the existing market's gold ledger, signed item lifecycle and mailbox. A stall is a world-space view, never a second inventory or shop ledger.

The completed order loop is: buyer funds request → seller produces/selects goods → seller places goods into the existing escrow → seller confirms the exact funded quote → core atomically settles → buyer claims the durable delivery. Ordinary goods pay 5%; signed equipment pays 10%. CRAFT requires the delivering player to be the original crafter. The maker provides materials; the contract purchases the specified finished equipment.

Exact matchers use material IDs or equipment type/rarity, never translated names. Whole listing stacks move, while larger orders can fill in several transactions. A failed match leaves both resources recoverable. Cancellation and the existing 168-hour expiry refund only a proved remaining hold. [The migration/recovery contract](../operations/MARKET_ORDER_MIGRATION.md) explains equal-amount reversals and ambiguous old orders.

### Payment isolation and cosmetic ownership

Real-money support enters only through a canonical Ed25519-signed receipt with a trusted key ID, deployment audience, unique order, player, units, currency and validity window. Core verifies the signature and binds the order to its exact payload before changing the isolated account/ledger. Player and global UTC weekly limits and the collection kill switch apply to first acceptance. A replay of an accepted identical order does not create new credit.

Only the explicitly configured cosmetic catalog is spendable. A purchase debits Support Credit, writes its unique purchase receipt and grants the database entitlement together. The appearance plugin reads those entitlements; a lore string or paid PDC flag cannot grant gameplay power. The initial products are visual aura, wings and comet effects; their IDs, prices and enabled state are operator configuration.

The shipped support configuration is disabled, with an empty audience/key set, zero collection limits and no cosmetic prices. Operators must supply the reviewed configuration and enable the payment mechanism before acceptance. Existing owner-bound appearance vouchers and legacy visual PDC unlocks remain usable solely for their appearances; they do not become new Support Credit or database purchase rights.

The unsigned legacy grant/spend and payment-to-material reward routes fail closed. Historical unclaimed `commerce-reward:` mail is quarantined with its original payload and receipt preserved. Physical delivery markers are cleared only after an allowed owner-matching database acknowledgement; refused items stay frozen. Already distributed historical items without receipts require separate operator review.

Membership purchased with earned Starlight is separate from support payment. A live membership requires the new earned expiry plus matching purchase, actual Starlight debit and earned grant. The old mixed `member_until` field remains historical data and cannot give a Gold discount. There is no automatic conversion of old paid balances, rewards or expiries.

## Permanent discovery and companion goals

The Field Journal adds six permanent, non-dungeon routes: Overworld exploration, Nether exploration, breeding, food variety, village defense and armor-trim collection. `/journal` opens the journal, and `/journal status` provides a textual view. The interface shows real remaining advancement criteria and the first journal-registration timestamp. Reconciliation can register a much older completed advancement; it does not invent its original completion time. See the [Field Journal contract](FIELD_JOURNAL.md).

Completed vanilla facts are reconciled at authenticated login, so a lost notification is not a lost achievement. The database records one permanent stamp per player and catalog entry. No XP, currency, combat multiplier or random reward is attached to these stamps. This provides a bounded, extendable horizontal content path without another economy.

`rpg-pets` integrates MyPet's active companion with progression, routes, bond, evolution and collection. Revival uses a durable core intent and audited cost/recovery. Cosmetic appearances and collection goals must remain separate from paid combat strength. Verify the pinned MyPet integration's actual write-back before completing a revival intent.

## Community and seasons

Communities store seven role identifiers: OWNER, OFFICER, TREASURER, BUILDER, EVENT_HOST, RECRUITER and MEMBER. LEADER and DEPUTY are accepted aliases for OWNER and OFFICER; new integrations must preserve the stored identifiers. Permission checks must read authoritative membership, and multi-person approvals must represent distinct eligible people. Notifications happen only after committed state changes and reach the current authorized audience.

Projects and mentorship connect survival work and social progression. Add concrete contribution opportunities, visible progress and an end state; do not make the same action payable repeatedly under new receipt IDs. Community membership must not grant exclusive attack/drop/XP/equipment caps.

Succession and dissolution now have durable proposal, approval, cooldown, cancellation and execution paths. The default succession policy requires 30 days of owner inactivity, a candidate with seven days of membership, two distinct eligible signers and a 72-hour cooldown. Dissolution has its own 72-hour default cooldown and can be proposed only by the owner. Execution rechecks membership and activity under the community lock. An authenticated returning owner invalidates pending inactivity-based succession. Dissolution preserves the community record and history, removes active membership/invitations and returns remaining fully stored materials to their original donors; it does not erase world builds.

The public store reuses `rpg_market_listings` with a community custody scope. Members deposit only approved pristine vanilla building materials through the existing physical escrow protocol. A withdrawal identifies the exact listing and current member recipient, and needs distinct authorized approval before the same transaction creates the mailbox delivery and audit. Normal market browse, buy, cancel, expiry and order-fill APIs cannot access community custody through direct IDs. Ambiguous prepared or quarantined deposits block dissolution until reviewed.

These mutations require the default-off Core and Community governance flags; storage also has its own default-off Core flag. Leaving does not reclaim donations, and prior withdrawals are not reversed on dissolution. Communities without two eligible governors cannot execute a decision. The [governance and custody guide](COMMUNITY_GOVERNANCE.md) specifies the actual commands and recovery behavior.

`/civilization projects` and `/civilization seasons` exhibit completed shared projects and successful season settlements. The archive reads existing authoritative completion, contribution and reward records, including dissolved communities. It does not issue another reward or create a parallel score. This is an in-game history exhibition; physical world-region snapshots still belong to the deployment/backup system. The generic `COMMUNITY_PROJECT` and `COMMUNITY_EVENT` governance actions authorize work and emit approval facts. They do not automatically claim land, place city blocks or execute an event script; the real weekly contribution project and public construction storage are the implemented gameplay paths.

Season settlement uses DRAFT → PREVIEWED → APPROVED → FREEZING → SETTLING → VERIFYING → COMPLETED, with explicit FAILED/ROLLED_BACK handling. Preview costs and affected assets before approval. Permanent assets, season-scoped assets and short-cycle quotas are different layers. Chapter progression remains a separate authoritative operation.

## Supporting plugins and asset responsibilities

| Plugin(s) | Player-facing purpose | Engineering responsibility |
|---|---|---|
| `rpg-guide`, `rpg-hub` | Onboarding, services, menus, safe travel and wayfinding | Reachable actions, chapter-aware hints and valid NPC/GUI routes |
| `rpg-story` | Optional contextual quest/escort flow | Actor ownership, timeout/cleanup, real state change and receipt-backed reward |
| `rpg-ai-npc` | Explain the player's available systems | Read-only advice, bounded requests, timeout/circuit breaker and local fallback |
| `rpg-integrations` | HUD, placeholders and permission context | Snapshot reads; valid placeholder keys; no blocking DB reads during rendering |
| `rpg-cosmetics` | Non-economic visual identity | Reviewed entitlement checks, bounded effect work and load-aware rendering |
| `rpg-essentials` | Utility commands compatible with RPG travel/combat | Prevent bypasses through alternate teleport/storage commands |
| `rpg-performance` | Stable play under load | Measure before degrading optional visuals; preserve authoritative operations |
| `rpg-admin` | Diagnosis, controlled recovery and season/chapter administration | Audited scope, previews, permissions, confirmation and evidence export |
| `rpg-api`, `rpg-core` | Shared rules and durable state | One authority, bounded work, migrations, receipt identity and service contracts |

Art and presentation belong to each feature's completion criteria: visible item/skill icons, combat telegraphs, boss/wave models, readable HUD, NPC/quest text and all error/pending states. Use the [handbook's content pipeline](DEVELOPER_HANDBOOK.md#7-content-and-asset-pipeline) to cross-check references and build inputs. Actual animation and client layout still require runtime review.

## Definition of a finished mechanism

For implementation review, require `[D][S][T]`: documented purpose/invariants, complete reachable code, and executed behavior/failure tests. For operating the integrated server, also require appropriate simulation, real Paper/PostgreSQL evidence and sustained human play. A locked future chapter, intentionally disabled high-risk integration or missing private deployment is recorded explicitly; it must not be silently presented as either a bug fix or completed deployment.
