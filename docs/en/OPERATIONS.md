# Operations and release readiness

[English documentation](README.md) · [Getting started](GETTING_STARTED.md) · [Roadmap](ROADMAP.md)

This guide is for an operator authorized by the project owner. It identifies the runtime inputs and verification gates visible from this repository. It is not a complete installation bundle: the private deployment scripts, production worlds, website, credentials, and host configuration are not included.

## Runtime inputs

| Input | What the operator must establish |
|---|---|
| Java and Paper | Exact supported JDK/server versions and checksums, rather than assuming the API dependency is the runtime server |
| Custom plugins | A coherent build of all selected custom modules, including `RpgApi`, with load dependencies satisfied |
| Community plugins | Exact compatible artifacts, correct configuration, and preserved upstream notices |
| Database | Reviewed schema, credentials, backups, capacity, and recovery procedure |
| Authentication | Correct AuthMe behavior and limits for the deployment's identity model |
| Resource pack and datapack | Matching artifacts, client acceptance, navigation integration, and asset provenance |
| Secrets | Operator-controlled signing keys and provider credentials outside source control |
| Monitoring | Tick latency, database health, queues, delivery failures, and audit visibility |

The [runtime plugin lock](../../scripts/runtime-smoke-plugins.lock.json) identifies a target community-plugin set. Hard requirements also appear in `plugin.yml`: for example, several entry-point plugins require AuthMe, the story module requires Citizens and BetonQuest, the companion module requires MyPet, and the Essentials bridge requires Essentials. A missing hard dependency can prevent the affected plugin from enabling even when compilation succeeded.

## Database configuration

RpgCore reads these environment overrides:

| Variable | Purpose |
|---|---|
| `RPG_DATABASE_URL` | JDBC connection URL |
| `RPG_DATABASE_USER` | Database user |
| `RPG_DATABASE_PASSWORD` | Database password |
| `RPG_NODE_ID` | Stable node identity; single-server default is `standalone` |

The local default is a file-backed H2 database. Production guidance targets PostgreSQL. Inspect [RpgCore defaults](../../plugins/rpg-core/src/main/resources/config.yml) and [Database](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java) for pool, executor, timeout, and startup behavior. Provision secrets through the deployment environment; do not put live credentials in a PR or an example command.

Changing a JDBC URL does not migrate player data. The [migration utility](../../tools/db-migration/README.md) and [migration procedure](../DATABASE_MIGRATION.md) require a reviewed manifest, source shutdown, target validation, and rollback evidence. Their private wrapper is not included in this checkout. Do not copy a live H2 file or invent a migration from documentation alone.

## Language and regional settings

Set the generated runtime `plugins/RpgCore/config.yml` as follows for an English default:

```yaml
language:
  default: en_us
  allow-player-selection: true
```

Players select their own interface with `/language en_us` or `/language zh_cn`. Selection is persisted in player data. Existing selections override the default; the shared service does not infer a choice from client locale. Restart through the normal procedure after operator configuration changes, then reopen menus during verification.

Review the whole journey in English:

- Authentication and resource-pack prompts before gameplay.
- Main menu, class choice, attributes, skill slots, costs, and probability text.
- Hub navigation, shared NPC names, map labels, story actors, and other world-space text.
- Market listing/confirmation, delivery, insufficient funds, pending settlement, and error states.
- Quest, dungeon, travel, and reconnect recovery messages.
- Third-party menus, website content, and administrator messages.

Core/hub bundle completeness does not cover all these surfaces. The [English preset](../../examples/en_us/README.md) provides the core locale and configurable shared labels as reviewed text overlays. Its generator writes a fresh preview directory and a hash manifest; it does not install anything. Shared NPC names are server-wide, so both English and Chinese players see the chosen labels.

Hub map labels, shared actors, supported personal items, and Blood Moon names now use the language presentation paths described in the [runtime edition record](../audits/PLAYABLE_ENGLISH_EDITION_DELIVERY.md). Custom operator labels, unknown legacy items, stored non-equipment market previews, and external-plugin content retain the boundaries listed in the [localization review](../audits/LOCALIZATION_REVIEW.md). Keep placeholders such as `{player}`, `{amount}`, and `{cost}` intact. Installed overrides are preserved, so compare customized files during upgrades instead of assuming a new JAR overwrote them. Merge approved text changes into existing deployment configuration; generated development defaults are not a replacement for live configuration.

**Language does not set the economic calendar.** Checked-in quota settings include `Asia/Shanghai`. Review each activity's timezone and reset policy before announcing international reset times. A timezone change is an operational/economic change and should be assessed for quota behavior; it is not a translation edit.

## Feature switches and rollout

These checked-in defaults require particular care:

| Setting | Default | Meaning of the boundary |
|---|---|---|
| RpgCore `session-lease.enabled` | `false` | Multi-node player admission requires a complete, proven lease lifecycle |
| RpgCore `outbox.enabled` / `outbox.dispatch.enabled` | `false` / `false` | A stored event and dispatcher need actual consumers and end-to-end delivery proof |
| RpgCore `community.governance-enabled` | `false` | Advanced governance is not enabled merely because its interfaces exist |
| RpgCore `season-attunement.enabled` | `false` | Attunement depends on reviewed season settlement and lifecycle handling |
| RpgAdmin `season-settlement.enabled` | `false` | The administrative season entry point is closed by default |

Sources: [core config](../../plugins/rpg-core/src/main/resources/config.yml) and [admin config](../../plugins/rpg-admin/src/main/resources/config.yml). Database-backed mechanism switches are another control layer; do not confuse a configuration flag with proof that every transaction honors its intended gate.

Do not turn on every switch as part of English setup. Localization does not require opening later progression, settlement, governance, or multi-node behavior.

## Deployment and rollback sequence

1. Record the code commit, artifact hashes, server version, configuration revision, and database schema evidence.
2. Take consistent database/world/configuration backups and verify that a restore procedure exists.
3. Use a staging copy to exercise migrations and representative login, progression, equipment, market, and dungeon paths.
4. Stop or fence the runtime through the established maintenance procedure before replacing deployment artifacts.
5. Deploy a matching plugin/resource-pack/datapack set. Preserve signing material, data files, and provenance.
6. Verify startup, dependencies, database health, authentication, packet/UI behavior, and transaction recovery.
7. Record the result and reopen only within the capacity and features actually validated.

A code revert does not undo committed ledger transactions or safely reverse a schema/data migration. The rollback plan must name the artifacts, configuration, data compatibility, and transaction compensation procedure. Refunds and compensation must retain their original audited references.

The source's `stagePlugins` task relies on an external deployment script. The public default `bash resource-pack/build.sh` now builds the complete committed client baseline offline; the old private generation and deployment workflow is available only through `--deployment`. [Getting started](GETTING_STARTED.md#build-the-english-staging-edition) documents the public model/HUD candidate exporter and the all-25-plugin edition packager. The latter rejects missing or stale build-provenance stamps, including Java-only changes to shared APIs.

The edition's `runtime-candidates/` contains 15 BetterModel registrations and a bilingual BetterHud layout. These need the pinned external plugins to compile their runtime resources. Keep BetterHud fonts/shaders and BetterModel model indices from the same compilation together; the committed client baseline is not automatically matched to a newly compiled candidate. The native G-key datapack and bilingual BetonQuest prologue are also included, but their actual plugin loading, NPC binding and client interaction remain staging acceptance checks. The edition carries no production state or third-party runtime binaries.

## Required runtime evidence

The project's [roadmap](../ROADMAP.md) calls for real Paper/PostgreSQL and human evidence, including:

| Area | Exercise |
|---|---|
| Authentication | Pre-login restrictions, failed attempts, reconnects, and duplicate sessions |
| Market | Concurrent purchase, duplicate clicks, insufficient funds, cancellation, expiry, and mailbox claim replay |
| Dungeons | Entry reservations, party changes, partial teleport failure, disconnect, death, restart, and return recovery |
| Equipment | Binding, signing, upgrade interactions, duplicate identity, salvage/destruction, and quarantine |
| Persistence | Database timeout/outage, crash during settlement, replay, and restoration from backups |
| International UI | English/Chinese text versus actual costs, probabilities, eligibility, and delivered outcomes |
| Load | Increasing player and instance counts with tick latency, database queues, heap, and failure metrics |

The roadmap's Alpha exit criteria include 20 testers over seven days, no duplication or duplicate debits, successful dungeon crash recovery, P95 tick time at or below 45 ms, and a verified backup restoration. These are acceptance targets, not measurements supplied by this English edition. The larger 100-player/2,000-mob/24-instance scenario is also a test target, not a supported-capacity claim.

## Incident handling

Capture the player's UUID, time window, operation/reference ID, relevant logs, and authoritative ledger/delivery state. Freeze the affected source when appropriate, preserve evidence, and use reviewed recovery or compensation tools. Do not invent a new transaction reference to retry an ambiguous payment, grant items manually before checking the mailbox, or edit balances directly to make the UI look correct.

For suspected duplication, retain the item identity and lifecycle audit. For a delayed reward, distinguish a pending delivery from an uncommitted reward before acting. For a database outage, respect fail-closed startup and settlement behavior rather than forcing the server into an unverified fallback.
