# Field Journal: a durable horizontal progression feature

The Field Journal gives exploration and collection a persistent display record without adding combat power or another progression ledger. The `/journal` menu belongs to `RpgWorld`; `RpgCore` stores only completed journal stamps. Minecraft owns every partial advancement criterion. Repeated events, reconnect reconciliation and concurrent writes converge on the same permanent stamp. [Public contract](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/service/FieldJournalService.java).

## Shipped content

The bundled catalog is reviewed against the official Minecraft 26.2 client data with SHA-1 `2dc72797acbc1b63fc16a11c4ac393605f453754`. Its complete criteria are stored in [field-journal.yml](../../plugins/rpg-world/src/main/resources/field-journal.yml).

| Stable journal ID | Authoritative advancement | Criteria |
|---|---|---:|
| `overworld-explorer` | `minecraft:adventure/adventuring_time` | 55 |
| `nether-explorer` | `minecraft:nether/explore_nether` | 5 |
| `naturalist` | `minecraft:husbandry/bred_all_animals` | 26 |
| `forager` | `minecraft:husbandry/balanced_diet` | 40 |
| `village-defender` | `minecraft:adventure/hero_of_the_village` | 1 |
| `armor-artisan` | `minecraft:adventure/trim_with_all_exclusive_armor_patterns` | 8 |

At startup, the catalog resolves each advancement through the live registry and compares its complete criterion set with the reviewed data. A missing advancement, changed criterion set or wrong key disables that entry and logs the reason. A custom datapack therefore cannot silently award a stamp for a weaker, substituted target. Invalid catalog structure fails feature initialization. [Catalog validation](../../plugins/rpg-world/src/main/java/cn/originalrpg/world/FieldJournalCatalog.java).

## Player path and lifecycle

An authenticated player opens `/journal` from the command or world status entry, selects a goal and sees its current native progress. An advancement completion event schedules reconciliation. Authentication, menu refresh and a bounded periodic pass also reconcile previously completed goals, so a disconnected result or missed event does not require the player to repeat content. The default pass interval is 200 ticks and processes two players; the interval and batch size are bounded in code. [Configuration](../../plugins/rpg-world/src/main/resources/config.yml), [controller](../../plugins/rpg-world/src/main/java/cn/originalrpg/world/FieldJournalController.java).

The controller reads Bukkit advancement state on the server thread, captures an immutable set of completed journal IDs, and submits those verified facts to Core. It does not read Bukkit objects from a JDBC continuation. Result rendering checks the exact authenticated player handle; revocation, a new connection with the same UUID or plugin shutdown prevents stale UI work. The menu uses the shared trusted-menu holder boundary and cancels clicks and drags before handling its own actions.

A completed stamp remains after death, disconnection, season changes or later native advancement revocation. It grants no gold, merit, starlight, support credit, equipment, attributes or skill points. The journal does not rewrite an advancement or infer completion from a player-supplied ID. Persisted completion time means **the first journal registration**, which may occur during later reconciliation; it is not an invented historic vanilla completion timestamp.

## Persistence and extension contract

`rpg_field_journal_entries` has one unique completion receipt per `(player_uuid, entry_id)`. `JdbcFieldJournalService.recordCompleted` validates the finite catalog, inserts missing records with conflict protection and returns the resulting records plus newly recorded entries. An empty completion set is a read-only refresh. No wallet or progression service is called. [SQL implementation](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcFieldJournalService.java), [entry identifiers](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/model/FieldJournalEntry.java).

To add a reviewed goal:

1. Define its stable ID and canonical advancement key in `FieldJournalEntry`.
2. Copy the exact criteria from the pinned official data into the catalog and verify the new content at startup.
3. Add presentation text and update the locale checker expectations where appropriate.
4. Extend the catalog and SQL behavior tests, including repeated completion and invalid registry data.
5. Validate the goal on the target Paper build and record the runtime evidence for that revision.

Future non-advancement goals need an equally authoritative completion adapter and a reviewed data contract. Block breaks or inventory events should not create a competing tracker merely because they are easy to observe. A background recovery adapter may record accepted historical completion after a session ends; its UI must independently respect current authentication.

## Verification and boundaries

```sh
./gradlew :plugins:rpg-core:test --tests '*JdbcFieldJournalServiceTest'
./gradlew :plugins:rpg-world:test --tests '*FieldJournal*'
python3 scripts/check-locales.py
sh scripts/check-trusted-menu-holders.sh
```

The SQL tests exercise concurrent duplicate completion, immutable first-registration time and rollback. Catalog and lifecycle tests cover missing or altered advancement data, completion recovery and deferred callbacks. A passing test run establishes those behaviors; it does not substitute for a Paper client walkthrough of all six goals.

This feature is one extensible, playable horizontal-content path. Existing dungeon instance warmup, queue admission and generated arena construction remain separate systems. The presence of a procedural warm pool does not imply a registered library of hand-authored world templates.
