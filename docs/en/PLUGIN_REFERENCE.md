# Plugin reference for developers

[Developer handbook](DEVELOPER_HANDBOOK.md) · [Gameplay systems](GAMEPLAY_SPECIFICATION.md) · [Architecture](ARCHITECTURE.md)

This reference covers every custom plugin. It explains the playable loop, the implementation boundary, and the content/recovery details to preserve when extending it. Commands and permission nodes below are read from the current module descriptors; implementation and configuration links are the source of truth.

A plugin without a command can still provide an essential event-driven service. The database migration and runtime-probe tools are separate from these 25 plugins.

## Index

| Plugin | Entry commands | Main role |
|---|---|---|
| [rpg-api](#rpg-api) | Events/services | Shared contracts and deterministic rules |
| [rpg-core](#rpg-core) | `/rpg`, `/language` | Authoritative state and recovery |
| [rpg-classes](#rpg-classes) | `/class` | Character identity and progression choices |
| [rpg-skills](#rpg-skills) | `/skills` | Build expression and active combat |
| [rpg-combat](#rpg-combat) | Events/services | Shared damage and combat restrictions |
| [rpg-items](#rpg-items) | `/forge`, `/salvage`, `/unbind`, `/legacy`, `/destroy` | Production and the equipment lifecycle |
| [rpg-enhancement](#rpg-enhancement) | `/refine`, `/limitbreak`, `/mutate` | Investment, risk and specialization |
| [rpg-sulfur](#rpg-sulfur) | `/imprint` | Survival resources and forging imprints |
| [rpg-mobs](#rpg-mobs) | `/mobguide` | Overworld encounters and durable rewards |
| [rpg-world](#rpg-world) | `/worldstatus`, `/journal` | World rhythm and permanent discovery |
| [rpg-dungeons](#rpg-dungeons) | `/dungeon` | Cooperative expeditions and encounter recovery |
| [rpg-market](#rpg-market) | `/market` | One escrow for player exchange |
| [rpg-economy](#rpg-economy) | `/economy` | Earned activity, commissions and sinks |
| [rpg-commerce](#rpg-commerce) | `/store`, `/support`, `/rpgcommerce` | Earned purchases and isolated support |
| [rpg-cosmetics](#rpg-cosmetics) | `/aura`, `/trail` | Visual identity without economic power |
| [rpg-pets](#rpg-pets) | `/companion` | Companion progression and collection |
| [rpg-community](#rpg-community) | `/communitystorage`, `/civilization`, `/community`, `/account` | Shared projects, governance and custody |
| [rpg-guide](#rpg-guide) | `/menu`, `/tasks`, `/rpghelp`, `/visuals`, `/guidehud`, `/warehouse` | Navigation and understandable next steps |
| [rpg-hub](#rpg-hub) | `/hub`, `/wild`, `/home`, `/sethome`, `/back`, `/hubfind`, `/hubadmin` | Safe travel and the server service center |
| [rpg-story](#rpg-story) | `/story`, `/storyadmin` | Optional contextual progression |
| [rpg-ai-npc](#rpg-ai-npc) | `/ainpc` | Bounded read-only guidance |
| [rpg-integrations](#rpg-integrations) | Events/services | Snapshots for HUD and third-party systems |
| [rpg-essentials](#rpg-essentials) | Events/services | Utilities within RPG rules |
| [rpg-performance](#rpg-performance) | `/rpgperf` | Measurement and bounded optional work |
| [rpg-admin](#rpg-admin) | `/rpgadmin` | Audited operations and recovery |

## rpg-api

**Shared contracts and deterministic rules.** Defines the values and services used by every gameplay adapter. Rule classes centralize progression, gear lifecycle, eligibility, target selection and community permissions. It is also a runtime dependency, so deploy its JAR with the suite.

[Source](../../plugins/rpg-api/src/main/java) · [Descriptor](../../plugins/rpg-api/src/main/resources/plugin.yml) · [Tests](../../plugins/rpg-api/src/test)

**Extension contract.** Add a model and asynchronous service contract here only when another plugin needs the contract. Keep Bukkit presentation and JDBC implementations in their owners. Classify the initiating actor explicitly for fenced writes; a reward recipient may be offline.

**Verification and recovery.** Keep catalog IDs and persisted enum values stable. Test rule boundaries and compatibility constructors. Changing an API requires rebuilding dependent JARs and their provenance stamps.

## rpg-core

**Authoritative state and recovery.** Initializes the database, chapter authority, bounded persistence executor and service registry before gameplay is admitted. Owns profiles, ledgers, reservations, receipts, governance, discovery records and network coordination.

[Source](../../plugins/rpg-core/src/main/java) · [Descriptor](../../plugins/rpg-core/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-core/src/main/resources/config.yml) · [Tests](../../plugins/rpg-core/src/test)

**Extension contract.** Implement a state change in its JDBC service and the canonical Database migration. Balance/custody changes and their receipt commit together. Read projections may be cached; permission and funds checks occur inside the transaction.

**Verification and recovery.** Test concurrency, exact replay, conflicting replay, rollback and stale session rejection. Keep H2 development and real PostgreSQL evidence separate. A failed required startup gate must not expose a half-initialized service.

Command permissions: `rpg.language`, `rpg.profile`.

Required runtime plugins: `RpgApi`. Optional integrations remain declared in the descriptor.

## rpg-classes

**Character identity and progression choices.** Connects class selection, attributes, class branches, hidden paths and ascension. Starter-kit delivery is owned by `rpg-guide/StarterKitService`. The finite progression model has local levels 1–30 in each of four stages; chapter state controls which ascensions are open.

[Source](../../plugins/rpg-classes/src/main/java) · [Descriptor](../../plugins/rpg-classes/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-classes/src/main/resources/config.yml) · [Tests](../../plugins/rpg-classes/src/test)

**Extension contract.** Extend the branch graph and eligibility rules together with menus and skill compatibility. Class and branch choices call core services; transient player handles do not authorize delayed writes after authentication changes.

**Verification and recovery.** Check the same choice through command and menu, prerequisite failure, budget exhaustion, repeated confirmation and reconnect. Hidden-path effects use the shared target rules and may not turn arbitrary passive creatures into hostiles.

Command permissions: `rpg.class`.

Required runtime plugins: `RpgApi`, `RpgCore`, `AuthMe`. Optional integrations remain declared in the descriptor.

## rpg-skills

**Build expression and active combat.** The catalog has six starting classes with seven skills each. Players learn, upgrade and arrange four active slots; the adapter resolves input, equipment-based access, resource cost, cooldown and the actual effect.

[Source](../../plugins/rpg-skills/src/main/java) · [Descriptor](../../plugins/rpg-skills/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-skills/src/main/resources/config.yml) · [Tests](../../plugins/rpg-skills/src/test)

**Extension contract.** A new skill needs a stable ID, catalog definition, execution behavior, eligibility, icon, description and visible failure feedback. Automatic PvE selection uses native enemies plus marked dungeon mobs and excludes players. Preserve the existing range, line-of-sight, friendly-unit and separate player-damage restrictions.

**Verification and recovery.** Exercise no-target and rejected casts, cost/cooldown behavior, passive versus marked Sulfur Cubes, native non-Monster enemies, group support and cleanup after logout. Validate the animation and telegraph on a real client.

Command permissions: `rpg.skills`.

Required runtime plugins: `RpgApi`, `RpgCore`, `RpgClasses`, `RpgItems`, `RpgCombat`, `AuthMe`. Optional integrations remain declared in the descriptor.

## rpg-combat

**Shared damage and combat restrictions.** Combines RPG attack/defense and equipment effects with server damage events. Combat tags also constrain travel and utility integrations, so combat behavior affects the rest of the server.

[Source](../../plugins/rpg-combat/src/main/java) · [Descriptor](../../plugins/rpg-combat/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-combat/src/main/resources/config.yml) · [Tests](../../plugins/rpg-combat/src/test)

**Extension contract.** Change shared formulas and eligibility rules in one place. Preserve the distinction between player attacks, mobs, companion assistance and encounter-owned entities. Other plugins should consult the published combat state.

**Verification and recovery.** Verify PvE/PvP, friendly units, event cancellation, armor/defense scaling and repeated effects. A cosmetic or paid entitlement must never reach this plugin as a damage multiplier.

Required runtime plugins: `RpgApi`, `RpgCore`, `RpgItems`. Optional integrations remain declared in the descriptor.

## rpg-items

**Production and the equipment lifecycle.** Forging turns accepted materials and costs into signed equipment with identity, crafter, rarity/type and slots. Binding, unbinding, salvage, legacy extraction and destruction supply circulation and exit decisions.

[Source](../../plugins/rpg-items/src/main/java) · [Descriptor](../../plugins/rpg-items/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-items/src/main/resources/config.yml) · [Tests](../../plugins/rpg-items/src/test)

**Extension contract.** Add recipes and item models with the existing signing and EquipmentOperationService path. Treat lore as a display projection. A pending inventory-operation marker remains a lock until the authoritative operation settles.

**Verification and recovery.** Test input movement, cancellation, a full mailbox/inventory, disconnect and receipt replay. Never reroll an already accepted output, bypass terminal lifecycle states or rotate the signing key to fix a model/name.

Command permissions: `rpg.destroy`, `rpg.forge`, `rpg.legacy`, `rpg.salvage`, `rpg.unbind`.

Required runtime plugins: `RpgApi`, `RpgCore`. Optional integrations remain declared in the descriptor.

## rpg-enhancement

**Investment, risk and specialization.** Refinement, limit breaks and skill mutation let players invest in an existing build. The operation must make cost, selected safeguards, outcome and recovery understandable before confirmation.

[Source](../../plugins/rpg-enhancement/src/main/java) · [Descriptor](../../plugins/rpg-enhancement/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-enhancement/src/main/resources/config.yml) · [Tests](../../plugins/rpg-enhancement/src/test)

**Extension contract.** Compose the existing signed item identity, core equipment operation and mutation rules. New consumables need explicit catalog/recipe references and a real terminal use. A displayed success percentage must match the execution path.

**Verification and recovery.** Check insufficient inputs, concurrent inventory movement, exact replay, stored random outcomes, refund/output delivery and maximum-level boundaries. Verify all safe/lucky variants and failed mutation restoration.

Command permissions: `rpg.limitbreak`, `rpg.mutate`, `rpg.refine`.

Required runtime plugins: `RpgApi`, `RpgCore`, `RpgItems`. Optional integrations remain declared in the descriptor.

## rpg-sulfur

**Survival resources and forging imprints.** Connects the sulfur resource theme to extraction and signed forging imprints. Its extraction quotas and configured calendar are economic behavior, independent of interface language.

[Source](../../plugins/rpg-sulfur/src/main/java) · [Descriptor](../../plugins/rpg-sulfur/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-sulfur/src/main/resources/config.yml) · [Tests](../../plugins/rpg-sulfur/src/test)

**Extension contract.** Extend the configured recipe and extraction rules together with item identity/verification and forging consumption. Keep imprints a bounded input to existing gear production.

**Verification and recovery.** Verify quota/reset boundaries, actual recipe ingredients, signature checks, repeated extraction and consumption. Do not add an unlimited material-to-NPC-Gold route.

Command permissions: `rpg.imprint`.

Required runtime plugins: `RpgApi`, `RpgCore`. Optional integrations remain declared in the descriptor.

## rpg-mobs

**Overworld encounters and durable rewards.** Adds adaptive monsters, elite affixes and native-world reward integration. Custom rewards freeze the accepted random result, XP identity, item deliveries and pity transition in an immutable durable intent.

[Source](../../plugins/rpg-mobs/src/main/java) · [Descriptor](../../plugins/rpg-mobs/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-mobs/src/main/resources/config.yml) · [Tests](../../plugins/rpg-mobs/src/test)

**Extension contract.** Use the bounded reward coordinator and journal. A queued disk write is not acceptance; persist the intent before settlement. Custom natural-mob item rewards go to the existing mailbox, while vanilla drops keep their ordinary behavior.

**Verification and recovery.** Test queue/disk bounds, unknown eligibility, interrupted writes, lost acknowledgements and process restart. Reuse delivery IDs and frozen results. Pity in player PDC and the journal are not one cross-file atomic transaction.

Command permissions: `rpg.mobs.guide`.

Required runtime plugins: `RpgApi`, `RpgCore`. Optional integrations remain declared in the descriptor.

## rpg-world

**World rhythm and permanent discovery.** Coordinates survival boundaries, asset protection, Blood Moons and hunt chains. The Field Journal adds permanent Overworld, Nether, breeding, food, village-defense and armor-trim goals outside the dungeon loop.

[Source](../../plugins/rpg-world/src/main/java) · [Descriptor](../../plugins/rpg-world/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-world/src/main/resources/config.yml) · [Tests](../../plugins/rpg-world/src/test)

**Extension contract.** Extend field-journal.yml with verified vanilla advancement criteria and bounded API catalog entries. Read live progress on Paper and reconcile completed facts at authenticated login; core owns the unique permanent stamp.

**Verification and recovery.** Test a missed event followed by reconnect, complete and partial criteria, repeated observations, database failure and trusted-menu handling. Journal stamps grant no extra currency, XP or combat multiplier.

Command permissions: `rpg.world.journal`, `rpg.world.status`.

Required runtime plugins: `RpgApi`, `RpgCore`, `RpgMobs`. Optional integrations remain declared in the descriptor.

## rpg-dungeons

**Cooperative expeditions and encounter recovery.** Provides parties, queues, entry reservations, isolated instances, waves, bosses, rotating expeditions and durable reward recovery. Ancient Ruins, Sulfur Depths and Storm Vault each have normal and hidden variants.

[Source](../../plugins/rpg-dungeons/src/main/java) · [Descriptor](../../plugins/rpg-dungeons/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-dungeons/src/main/resources/config.yml) · [Tests](../../plugins/rpg-dungeons/src/test)

**Extension contract.** Extend a complete encounter: entry/chapter policy, ticket/Energy cost, waves/objectives, readable boss attacks, model mappings, victory/failure and return destination. Maintain the existing warm-instance, queued-entry and per-tick block-build bounds. Current arenas are generated in empty flat worlds; a hand-authored world-template cloning pipeline is not registered.

**Verification and recovery.** Exercise all participants leaving, death/reconnect, server loss around entry and reward, full inventories and replay. Registered combatants cannot be bucketed, fed, split or transformed out of the encounter. Test hidden variants for changed decisions, not only larger numbers.

Command permissions: `rpg.dungeon`.

Required runtime plugins: `RpgApi`, `RpgCore`. Optional integrations remain declared in the descriptor.

## rpg-market

**One escrow for player exchange.** Connects listings, physical stall views, funded buy requests, original-crafter commissions, fixed taxes and delivery. Buy/craft fulfillment consumes an actual active escrow listing; callers cannot submit an invented item payload.

[Source](../../plugins/rpg-market/src/main/java) · [Descriptor](../../plugins/rpg-market/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-market/src/main/resources/config.yml) · [Tests](../../plugins/rpg-market/src/test)

**Extension contract.** Use persisted matchers, quantity, identity and crafter. Partial fills reverse the equal original hold, settle the consumed quantity and re-hold the remainder in one transaction. Community custody shares the table but is excluded from all ordinary market paths.

**Verification and recovery.** Verify concurrent buyers/fillers, direct-ID custody bypasses, insufficient/ambiguous holds, exact replay, expiry and cancellation. Physical mailbox receipts stay frozen on refused acknowledgement; historical paid-material deliveries are isolated.

Command permissions: `rpg.market`.

Required runtime plugins: `RpgApi`, `RpgCore`, `RpgItems`. Optional integrations remain declared in the descriptor.

## rpg-economy

**Earned activity, commissions and sinks.** Observes survival activity and connects commissions, shops, merchants and controlled currency conversion. Gold, Merit and Starlight have distinct authoritative ledgers and finite eligible sources.

[Source](../../plugins/rpg-economy/src/main/java) · [Descriptor](../../plugins/rpg-economy/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-economy/src/main/resources/config.yml) · [Tests](../../plugins/rpg-economy/src/test)

**Extension contract.** Add a faucet only with stable source identity, eligibility, daily/weekly quota, metrics and a kill switch. Resource commissions need real evidence and cannot pay repeatedly for arbitrary IDs. Keep conversion reference value non-increasing.

**Verification and recovery.** Test activity eligibility, quota concurrency, source replay, reset calendars, transaction rollback and sink/faucet conservation. Use the fifteen-scenario model as a diagnostic and calibrate it with observed data.

Command permissions: `rpg.economy`.

Required runtime plugins: `RpgApi`, `RpgCore`. Optional integrations remain declared in the descriptor.

## rpg-commerce

**Earned purchases and isolated support.** The ordinary store spends earned Starlight or Gold. Support receipts enter a physically separate account/ledger, and its reviewed catalog grants only cosmetic ownership. Membership discounts require proved earned Starlight purchases.

[Source](../../plugins/rpg-commerce/src/main/java) · [Descriptor](../../plugins/rpg-commerce/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-commerce/src/main/resources/config.yml) · [Tests](../../plugins/rpg-commerce/src/test)

**Extension contract.** Integrate the payment bridge using canonical Ed25519 receipts, a deployment audience, trusted key IDs, bounded validity, global order uniqueness and finite quotas. Configure explicit cosmetic prices and enable the reviewed switch; never store a signing private key in this plugin.

**Verification and recovery.** Test invalid signatures/audiences, conflicting orders, quota races, expiry during lock waits, duplicate purchase and entitlement atomicity. Legacy unsigned grants, payment membership and material reward claims are closed. Retain historical records for review.

Command permissions: `rpg.commerce.admin`, `rpg.store`, `rpg.support`.

Required runtime plugins: `RpgApi`, `RpgCore`, `RpgMarket`. Optional integrations remain declared in the descriptor.

## rpg-cosmetics

**Visual identity without economic power.** Renders auras, trails, palettes and visual effects under load-aware budgets. New paid ownership comes from the database entitlement service; selection and rendering must both check current ownership.

[Source](../../plugins/rpg-cosmetics/src/main/java) · [Descriptor](../../plugins/rpg-cosmetics/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-cosmetics/src/main/resources/config.yml) · [Tests](../../plugins/rpg-cosmetics/src/test)

**Extension contract.** Add an appearance with an explicit catalog identity, bounded render work and a reviewed entitlement path. Invalidate/refresh the cache on authentication and purchase events. Preserve exact connection/generation checks for delayed replies.

**Verification and recovery.** Verify logout/relogin, failed or stale entitlement reads, expired cache, unowned selection and load degradation. Owner-bound visual vouchers remain redeemable, and legacy PDC unlocks still authorize their existing appearances. These compatibility paths grant no Support Credit or database purchase entitlement; their historical funding source must not be inferred from the voucher alone.

Command permissions: `rpg.cosmetics.aura`, `rpg.cosmetics.trail`.

Required runtime plugins: `RpgApi`, `RpgCore`. Optional integrations remain declared in the descriptor.

## rpg-pets

**Companion progression and collection.** Integrates MyPet with capture, Guardian/Hunter/Healer/Ranger routes, Bond, evolution, collection, appearances and the Bond Horn. Companion behavior respects hub/dungeon/PvP restrictions and preserves the player final-hit decision.

[Source](../../plugins/rpg-pets/src/main/java) · [Descriptor](../../plugins/rpg-pets/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-pets/src/main/resources/config.yml) · [Tests](../../plugins/rpg-pets/src/test)

**Extension contract.** Extend route/evolution/collection definitions and the pinned MyPet adapter together. Core owns progression and paid revival intents; MyPet owns its engine entity/storage. Complete a revival only after the engine write-back is verified.

**Verification and recovery.** Test capture eligibility, route/evolution bounds, recall restrictions, inventory/storage access, ability Energy/cooldown and revival retry/compensation. A companion or purchased appearance must not create passive farming or sold combat power.

Command permissions: `rpg.pets.use`.

Required runtime plugins: `RpgApi`, `RpgCore`, `RpgCombat`, `MyPet`. Optional integrations remain declared in the descriptor.

## rpg-community

**Shared projects, governance and custody.** Connects creation, invitations, membership, mentorship and shared projects with seven persisted roles, multi-person approvals, succession, dissolution, public construction storage and civilization history.

[Source](../../plugins/rpg-community/src/main/java) · [Descriptor](../../plugins/rpg-community/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-community/src/main/resources/config.yml) · [Tests](../../plugins/rpg-community/src/test)

**Extension contract.** Use core membership and the community row lock for permission-sensitive transitions. Role proposals bind the intended role. Lifecycle execution rechecks signers, inactivity and cooldown. Public storage uses the single market escrow and durable mailbox.

Advanced governance defaults to disabled in both Core and Community; construction storage additionally requires Core's separate storage flag. Exact commands, eligibility, recovery and the isolated PostgreSQL fixture are documented in [Community governance](COMMUNITY_GOVERNANCE.md). Project/event approval facts authorize work; the implemented construction loop consists of weekly contribution projects and shared materials, with completed project/season history.

**Verification and recovery.** Exercise leave/rejoin, role changes between approvals, returning leaders, concurrent withdrawal, unknown commit results and dissolution with unresolved custody. Completed project/season exhibits read existing records and award nothing twice. World-region backups are an external deployment responsibility.

Command permissions: `rpg.account`, `rpg.community`.

Required runtime plugins: `RpgApi`, `RpgCore`, `RpgGuide`. Optional integrations remain declared in the descriptor.

## rpg-guide

**Navigation and understandable next steps.** Provides the main menu, task/Quest Center, help, visual preferences, guide HUD, starter-kit delivery and storage navigation. It connects current player progress to the actual available system rather than inventing another quest wallet.

[Source](../../plugins/rpg-guide/src/main/java) · [Descriptor](../../plugins/rpg-guide/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-guide/src/main/resources/config.yml) · [Tests](../../plugins/rpg-guide/src/test)

**Extension contract.** Add a navigation action with its reachable command/service, requirement and explanation. Preserve trusted menu ownership and handle clicks/drags through controlled actions. Hints should respect chapter and current progress.

**Verification and recovery.** Check every action and back link, locked states, permissions, first-login flow, full inventories and stale sessions. UI copy must describe the real command and exact requirement.

Command permissions: `rpg.guidehud`, `rpg.help`, `rpg.menu`, `rpg.visuals`, `rpg.warehouse`.

Required runtime plugins: `RpgApi`, `RpgCore`, `RpgEconomy`, `RpgCommerce`, `AuthMe`. Optional integrations remain declared in the descriptor.

## rpg-hub

**Safe travel and the server service center.** Provides hub protection, service NPCs, wayfinding, wild travel, bed homes and return travel. Combat and authentication boundaries apply to every route.

[Source](../../plugins/rpg-hub/src/main/java) · [Descriptor](../../plugins/rpg-hub/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-hub/src/main/resources/config.yml) · [Tests](../../plugins/rpg-hub/src/test)

**Extension contract.** Extend NPC roles with validated commands, locations, labels and guide links. Keep shared-world labels separate from a player language preference. Travel must use the existing safety checks and current destination facts.

**Verification and recovery.** Verify terrain/destination safety, combat lock, invalid homes, permissions, interrupted travel, rebuild confirmation and NPC interaction. A generated model or NPC name alone does not create a working service.

Command permissions: `rpg.hub`, `rpg.hub.admin`, `rpg.hub.back`, `rpg.hub.find`, `rpg.hub.home`, `rpg.hub.home.set`, `rpg.hub.wild`.

Required runtime plugins: `RpgApi`, `RpgCore`, `RpgGuide`, `RpgCombat`. Optional integrations remain declared in the descriptor.

## rpg-story

**Optional contextual progression.** Connects Citizens and BetonQuest to story/escort sessions, actor ownership, timeout/cancellation and authoritative reward collection. The shipped prologue is a route into existing server systems.

[Source](../../plugins/rpg-story/src/main/java) · [Descriptor](../../plugins/rpg-story/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-story/src/main/resources/config.yml) · [Tests](../../plugins/rpg-story/src/test)

**Extension contract.** Extend the story package with real objectives/actions, complete conversation navigation and stable reward identity. Each active session owns its actors and cleanup. Dialogue selection alone must not create repeatable progression rewards.

**Verification and recovery.** Check actor removal, escort separation, timeout, logout, repeated claim, package registration and broken conversation links. The pinned upstream JARs and actual quest package must load on Paper.

Command permissions: `rpg.story`, `rpg.story.admin`.

Required runtime plugins: `RpgApi`, `RpgCore`, `RpgHub`, `Citizens`, `BetonQuest`. Optional integrations remain declared in the descriptor.

## rpg-ai-npc

**Bounded read-only guidance.** Answers questions about available gameplay using knowledge.yml and permitted player context. The service is advisory: it cannot grant wealth, change equipment or execute generated commands.

[Source](../../plugins/rpg-ai-npc/src/main/java) · [Descriptor](../../plugins/rpg-ai-npc/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-ai-npc/src/main/resources/config.yml) · [Tests](../../plugins/rpg-ai-npc/src/test)

**Extension contract.** Update curated knowledge when changing commands or mechanics. Keep bounded request concurrency, timeout, circuit breaker and local fallback. Never include credentials or unrestricted private server state in prompts.

**Verification and recovery.** Test unavailable upstream service, saturation, delayed response after logout, unsupported topics and misleading command suggestions. The ordinary help path must remain useful without an AI connection.

Command permissions: `rpg.ainpc.use`.

Required runtime plugins: `RpgApi`, `RpgCore`. Optional integrations remain declared in the descriptor.

## rpg-integrations

**Snapshots for HUD and third-party systems.** Supplies PlaceholderAPI values, permission contexts and presentation integration with BetterHud/MyPet/LuckPerms. Consumers render snapshots of authoritative facts.

[Source](../../plugins/rpg-integrations/src/main/java) · [Descriptor](../../plugins/rpg-integrations/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-integrations/src/main/resources/config.yml) · [Tests](../../plugins/rpg-integrations/src/test)

**Extension contract.** Extend a placeholder/context with an English definition, bounded snapshot refresh and consistent local/effective-level semantics. A placeholder callback must not perform synchronous JDBC or network work.

**Verification and recovery.** Verify registration, missing optional plugins, stale/unavailable snapshots, refresh cleanup and client layout. Cross-check every HUD placeholder against its actual provider.

Required runtime plugins: `RpgApi`, `RpgCore`. Optional integrations remain declared in the descriptor.

## rpg-essentials

**Utilities within RPG rules.** Adapts EssentialsX utilities so alternate commands do not bypass RPG combat, travel, storage or authentication restrictions.

[Source](../../plugins/rpg-essentials/src/main/java) · [Descriptor](../../plugins/rpg-essentials/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-essentials/src/main/resources/config.yml) · [Tests](../../plugins/rpg-essentials/src/test)

**Extension contract.** Review aliases and third-party command roots whenever adding a utility. Use the shared permission/combat/session facts and document which system owns a home, teleport or storage operation.

**Verification and recovery.** Test aliases, namespaced commands, console/player context, permission changes, combat and unverified sessions. A blocked primary command must not remain available through a utility alias.

Required runtime plugins: `RpgApi`, `RpgCore`, `RpgCombat`, `Essentials`. Optional integrations remain declared in the descriptor.

## rpg-performance

**Measurement and bounded optional work.** Collects runtime telemetry and exposes a shared load state. Visual systems reduce optional rendering work under load while authoritative transactions retain their required semantics.

[Source](../../plugins/rpg-performance/src/main/java) · [Descriptor](../../plugins/rpg-performance/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-performance/src/main/resources/config.yml) · [Tests](../../plugins/rpg-performance/src/test)

**Extension contract.** Add a metric with its sample window and interpretation. Reduce particles/models before changing economic or combat rules. Keep periodic work and queues bounded and visible.

**Verification and recovery.** Measure on real hardware with declared player/entity/instance counts. Source defaults and startup success do not establish capacity. Preserve P95 tick-time and memory evidence for release acceptance.

Command permissions: `rpg.performance.status`.

Required runtime plugins: `RpgApi`, `RpgCore`. Optional integrations remain declared in the descriptor.

## rpg-admin

**Audited operations and recovery.** Provides console mechanism-switch control, economic diagnostics, chapter/season transitions, incident compensation, pet revival recovery, equipment inspection/quarantine/restoration, anomaly review and controlled test profiles.

[Source](../../plugins/rpg-admin/src/main/java) · [Descriptor](../../plugins/rpg-admin/src/main/resources/plugin.yml) · [Configuration](../../plugins/rpg-admin/src/main/resources/config.yml) · [Tests](../../plugins/rpg-admin/src/test)

**Extension contract.** An administrative mutation needs a scoped preview, explicit permission/confirmation, reason and durable receipt. Recovery uses the original transaction identity. Read-only reports must use the same calendar and currency definitions as the underlying rules.

`/rpgadmin mechanism list` reads the durable registry. The authorized server console can use `/rpgadmin mechanism enable|disable <id> CONFIRM <reason>` with a 1–56-character reason. The switch row records its latest `updated_by` label and timestamp; preserve console logs for a history of operator changes. A missing commit acknowledgement requires reading current state before retrying.

**Verification and recovery.** Test duplicate confirmation, wrong scope, partial failure, retry, invalid chapter/season transitions and audit retention. Freeze/limit/alert is permitted; automatic repricing or unrecorded balance edits are not.

Command permissions: `rpg.admin`.

Required runtime plugins: `RpgApi`, `RpgCore`. Optional integrations remain declared in the descriptor.

## Content and release review

Each content change should include its stable ID, configured behavior, reachable entry, prerequisites, cost/quota, item/model/HUD references, feedback and failure path. Use the [content pipeline](DEVELOPER_HANDBOOK.md#7-content-and-asset-pipeline) and preserve asset provenance. A texture file without a registration path is not playable content.

For behavior-changing code, record focused rule/transaction/recovery tests and then the full integration gates. The [mechanism review](../audits/MECHANISM_COMPLETION_REVIEW.md) records the available evidence and remaining deployment/client/human gates. Keep default-off private integrations explicit; do not silently open them as part of an English documentation or player-language change.
