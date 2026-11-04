# Offline UUID identity migration

This command migrates one explicitly reviewed player identity inside the existing RpgCore database. It preserves amounts, progression, item quantities, business operation IDs and historical evidence. It creates an immutable operational receipt in the same transaction as the owner/reference changes. It does **not** create a runtime alias, merge accounts, infer an authenticated UUID from a name, or claim that external player state has been migrated.

The implementation is `tools/db-migration` → `IdentityMigrationMain` / `IdentityMigrationEngine`. The command is an offline process; it is never registered as a Bukkit command or service. The source policy currently enumerates every column in all 93 RpgCore tables. Any additional table, column or changed type family requires an explicit policy review before this tool will apply a migration.

## Scope and authority

A player UUID is an identity boundary, not merely a filename. The operator must independently verify control of the source account and the authenticated target account. Keep the evidence privately; the tool binds its SHA-256 into the mapping and receipt. A correct hash proves that the reviewed evidence did not change. It does not prove that the operator's identity decision was correct.

The supported operation is a one-to-one move into an unused target identity. A target reference in a covered player column, typed participant list, role subject, event recipient or player quota prevents apply, even when there is no target profile row. An earlier migration involving either UUID also prevents a new move; identity chains, reversals and account merges require a separately reviewed migration design.

Every report states `scope: CORE_DATABASE_ONLY` and `full_server_identity_cutover_verified: false`. Even a successful apply only proves the covered database transformation. A real server that has Paper playerdata, authenticated account stores, pets, protected regions, quest state, signed equipment, payment receipts or pending reward journals will commonly be blocked until its authority-specific adapters are implemented and verified.

Velocity forwards identity information such as UUIDs, addresses and skins. Its documented forwarding configuration is a separate deployment concern; it does not establish ownership of an old local account or supply this repository's database/file migration proof. Review the official [Velocity forwarding documentation](https://docs.papermc.io/velocity/player-information-forwarding/) before changing authentication or proxy settings.

## Before planning

1. Schedule maintenance and stop all Paper nodes, proxy admissions, website writers and recovery workers. Local process checks and database leases provide additional checks; neither proves that every remote process was stopped.
2. Create a coordinated backup of the database, all worlds, plugin data and signed-item state. Restore and validate a copy before approving an identity change. Record the backup identity in the private ownership review.
3. Initialize the selected database with the exact RpgCore build, then stop it. This command never creates schema. `rpg_identity_migration_receipts` is an additive operational table, not an account or wealth authority.
4. Resolve pending dungeon reservations, CLAIMED/STAGING mailbox rows and unsupported in-flight operations using their existing recovery procedures. Do not delete an outbox event, reward journal or item payload to make a migration plan pass.
5. Prepare a reviewed mapping and a complete inventory of external identity authorities. Store these documents and the output report outside the runtime directory.

The tool refuses `RPG_DATABASE_URL` in its environment. Use an explicit `--jdbc-url`; do not inherit a live server's overrides. Passwords belong in a restricted file or `SIVITA_IDENTITY_DATABASE_PASSWORD`, never a URL or command-line value.

## Mapping document

Example only; replace every identity and the proof digest with independently verified values. Usernames are audit labels and are never used to calculate either UUID.

```json
{
  "schema_version": 1,
  "operation_id": "a3cb3d75-c122-4f91-a528-2fdfbc764d10",
  "source_uuid": "d0c41ab1-a31e-47fd-8f6b-c244f113b6d9",
  "target_uuid": "4a4cc80a-621b-4319-a72b-df99ca177f05",
  "source_label": "Reviewed legacy account",
  "target_label": "Reviewed authenticated account",
  "verified_by": "operator-review-ticket-123",
  "evidence_file": "ownership-review.txt",
  "evidence_sha256": "REPLACE_WITH_THE_64_CHARACTER_LOWERCASE_SHA256"
}
```

`evidence_file` is relative to the mapping file unless it is absolute. It must be a regular file, not a symlink. Duplicate JSON keys, missing/unknown fields, noncanonical UUIDs and reused operation IDs are rejected. The same operation ID and mapping must be retained when retrying an uncertain commit.

## External coverage document

All nine provider IDs must occur exactly once. The supported states are `ABSENT` and `REQUIRES_ADAPTER`. There is no generic `MIGRATED`, `IGNORE` or `force` option.

```json
{
  "schema_version": 1,
  "source_uuid": "d0c41ab1-a31e-47fd-8f6b-c244f113b6d9",
  "target_uuid": "4a4cc80a-621b-4319-a72b-df99ca177f05",
  "verified_by": "operator-review-ticket-123",
  "evidence_sha256": "REPLACE_WITH_THE_SAME_OWNERSHIP_EVIDENCE_SHA256",
  "providers": [
    {"id": "paper-player-state", "state": "REQUIRES_ADAPTER", "note": "Review all world/playerdata, statistics, advancements and entity owner references."},
    {"id": "authme", "state": "REQUIRES_ADAPTER", "note": "Review the actual authentication authority and credentials migration policy."},
    {"id": "luckperms", "state": "REQUIRES_ADAPTER", "note": "Review user records, permissions, inheritance and contexts."},
    {"id": "mypet", "state": "REQUIRES_ADAPTER", "note": "Review authoritative pet UUID/owner links and serialized pets."},
    {"id": "betonquest", "state": "REQUIRES_ADAPTER", "note": "Review player objectives, conditions, events and quest progress."},
    {"id": "citizens", "state": "REQUIRES_ADAPTER", "note": "Review owner/trait data and plugin-specific UUID references."},
    {"id": "worldguard", "state": "REQUIRES_ADAPTER", "note": "Review region owner/member UUIDs in every world."},
    {"id": "website", "state": "REQUIRES_ADAPTER", "note": "Review account linkage, external payment identity and asynchronous writers."},
    {"id": "custom-player-state", "state": "REQUIRES_ADAPTER", "note": "Review all custom plugin journals, PDC, caches and other external stores."}
  ]
}
```

The example deliberately declares adapter requirements; it must not be changed to `ABSENT` without evidence. An isolated database-only environment can legitimately have absent providers. A production server with those authorities needs real adapters, not an edited declaration.

The scanner inventories every local path, hashes regular files, rejects symlinks and blocks unknown state files. Known static configuration is checked for unclassified source/target UUID text; oversized text is blocked. Runtime logs remain historical evidence. Recognized H2 database files are covered by the database snapshot, not rewritten through the file scanner. Provider folders contradicting an `ABSENT` declaration also block apply. Remote-store absence remains an explicit reviewed operator assertion.

### Required adapter boundaries

| Surface | Why a plain string replacement is insufficient | Current behavior |
| --- | --- | --- |
| Paper playerdata, entity NBT, statistics and advancements | Filenames and nested owner references must agree; inventories and pending delivery markers are authoritative | File adapter required |
| AuthMe and authenticated account linkage | Identity control and authentication policy cannot be inferred from usernames | Provider adapter required |
| LuckPerms, WorldGuard and Citizens | Permission, region and NPC references have their own identifiers and persistence rules | Provider adapter required |
| MyPet | RpgCore's owner reference is only one side of the authoritative pet relationship | Affected pet progress/revival blocks apply |
| BetonQuest | Quest state and external rewards can refer to the old identity | Provider adapter required |
| Signed equipment and active item escrow | Encoded item payloads can contain crafter/bound-owner UUIDs, including items owned by someone else | Active opaque payloads and affected live equipment block apply |
| Signed support-payment receipts | The signature/hash commits to the original payer identity; editing it invalidates the evidence | Affected signed receipts block apply; no automatic re-signing |
| Recovery journals and website workers | They can replay old recipient IDs after a database-only change | External adapter and coordinated shutdown required |

## Build and dry-run

Build the existing migration distribution with the same Java/Gradle toolchain used for the server:

```bash
./gradlew :tools:db-migration:installDist
```

Example PostgreSQL preview:

```bash
tools/db-migration/build/install/sivita-db-migration/bin/sivita-db-migration identity \
  --dry-run \
  --jdbc-url 'jdbc:postgresql://127.0.0.1:5432/sivita' \
  --user migration_operator \
  --password-file /srv/review/database.secret \
  --schema public \
  --paper-runtime /srv/paper \
  --mapping /srv/review/mapping.json \
  --coverage /srv/review/coverage.json \
  --plan /srv/review/identity-plan-001.json
```

For an existing embedded H2 database, use its exact file path and existing compatibility settings:

```text
--jdbc-url 'jdbc:h2:file:/srv/paper/plugins/RpgCore/data/rpg;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE'
--user sa
--schema public
```

H2 dry-run opens the file with `IFEXISTS=TRUE` and `ACCESS_MODE_DATA=r`. H2 TCP, in-memory, `AUTO_SERVER`, `INIT`, credential URL settings and database creation are prohibited. PostgreSQL connection parameters are limited to `sslmode`, `connectTimeout`, `socketTimeout` and `currentSchema`.

The output path must not exist. A complete report is written to a restricted temporary file, forced to disk, then published with an atomic create that cannot overwrite another operator's report. Keep the resulting report private: it contains UUIDs, audit labels, file inventory and database metadata, although it does not dump balances or opaque item payloads.

Exit status `0` means a ready Core database preview or successful/replayed Core apply; `5` means a blocked preview was saved for review; `2` means an input or execution failure. A ready preview is not approval and is not a full-server migration claim.

## Review the plan

The plan binds the mapping evidence, database locator/catalog/schema, every column policy, every table's before/expected-after row digest, changed-row counts and external file inventory. Review `blockers`, `core_apply_ready`, the exact catalog, the source/target labels and all transformed/retained columns.

| Policy | Transformation |
| --- | --- |
| `PLAYER` | Rewrite an exact player reference; refuse an occupied target identity |
| `PLAYER_LIST` | Parse unique party members and re-canonicalize with the same UUID ordering used by season runs |
| `PLAYER_SCOPE` | Rewrite the typed `player:<UUID>` support quota; keep `global` unchanged |
| `MILESTONE` | Rebuild `(player, ascension, local milestone)` keys so an already granted skill point cannot be granted again |
| `ROLE_SUBJECT` | Rewrite the player part of a recognized `ROLE_GRANT` subject, retaining its exact role |
| `DUNGEON_EVENT` | Rewrite only a strict, recognized dungeon-completion recipient; keep its event and idempotency IDs |
| `OPERATION_ID`, `HISTORY`, `SESSION_TOKEN` | Keep stable business identities, historical request hashes, audit descriptions and old connection tokens unchanged |
| `OPAQUE_ASSET` | Block unsupported active/signed payloads; recognized terminal delivery/escrow history remains byte-for-byte intact |
| `VALUE` | Keep the value; an embedded source/target UUID that has no typed policy is a blocker |
| `MIGRATION_AUDIT` | Retain prior operational receipts; never resolve runtime accounts through them |

The report makes retained historical source references visible. Keeping an old UUID inside an immutable transaction key or human audit description is intentional. It does not create a second active account. The tool never re-hashes historical requests to disguise their original contents.

Bounds are explicit: at most one million rows per table, 250,000 changed rows and 100,000 runtime paths. Exceeding a bound requires a reviewed larger-data adapter, not a partial migration.

## Apply the exact reviewed plan

Keep all authorities stopped. Use the same mapping, coverage, database and original report; copy the exact `database_catalog` from that report into `--confirm-database`:

```bash
tools/db-migration/build/install/sivita-db-migration/bin/sivita-db-migration identity \
  --apply \
  --jdbc-url 'jdbc:postgresql://127.0.0.1:5432/sivita' \
  --user migration_operator \
  --password-file /srv/review/database.secret \
  --schema public \
  --paper-runtime /srv/paper \
  --mapping /srv/review/mapping.json \
  --coverage /srv/review/coverage.json \
  --reviewed-plan /srv/review/identity-plan-001.json \
  --confirm-database sivita
```

Apply obtains the existing migration advisory lock and PostgreSQL `ACCESS EXCLUSIVE` locks on all covered tables, with the node-lease table first. It then checks active nodes, sessions, outbox claims unrecovered dungeon reservations and every CLAIMED/STAGING mailbox row. Mailbox expiry or an empty payload never bypasses that check. `READ COMMITTED` is intentional here: all data reads occur after the locks, so a node that committed while the tool was waiting is visible. A repeatable/serializable snapshot created by the earlier advisory-lock `SELECT` could be stale after waiting. This follows PostgreSQL's documented [explicit-lock snapshot rules](https://www.postgresql.org/docs/18/sql-lock.html) and [application consistency guidance](https://www.postgresql.org/docs/18/applevel-consistency.html).

The tool recomputes the complete plan while holding those locks. Any changed data, schema policy, ownership evidence or external inventory invalidates the reviewed digest. Updates use original primary keys and require one affected row each. Before commit, the engine compares every row with the expected identity-only transformation, rechecks the evidence and file inventory, and inserts `rpg_identity_migration_receipts`. Amounts, counts and nonidentity contents are therefore checked through their full-row proofs, not a few aggregate totals.

For H2, the CLI accepts only an offline embedded file; the database file lock is the process exclusion boundary. H2 tests do not prove PostgreSQL table-lock or snapshot behavior.

## Commit uncertainty and rollback

If commit succeeds but its response is lost, do not create a new operation ID or a fresh target mapping. Retry the **same operation and original reviewed plan** while authorities remain stopped. Its exact receipt returns `ALREADY_APPLIED`; different mapping or plan content under that operation ID is rejected. The receipt and transformed rows are atomic, so there is no separate success file that can disagree with the database.

An error before commit rolls back the transaction. A commit error is reported as an uncertain outcome, never as proof of rollback. Evidence changes during the apply also abort the transaction.

There is no automatic reverse migration. Before reopening any writer, a coordinated verified backup can restore the complete pre-cutover state. After new writes under the target identity, reversing columns would mix two periods of activity and must be treated as a new recovery design. Do not delete individual receipts or rewrite old ledger keys to bypass this boundary.

Dropping the new audit table is not a data rollback. Preserve it for investigation and subsequent migration refusal. An older RpgCore build does not use this operational table for runtime account resolution.

## Validation and remaining gates

`IdentityMigrationIntegrationTest` starts the real `Database.migrate()` schema and exercises typed transformations, exact grant replay, wealth preservation, target collisions, changed-plan refusal, failure rollback, uncertain-commit replay, live-authority refusal and opaque/signed/external-state blockers. `IdentityInputSafetyTest` covers strict input, immutable report publication, password/connection restrictions and an actually read-only H2 connection.

`IdentityCliIntegrationTest` additionally builds a real file-backed database through `Database.migrate()`, closes its pools, and calls the public command for dry-run, catalog-mismatch refusal, apply and exact replay. These H2 targets passed in the integration build; the PostgreSQL-specific race remains separately gated.

The integration fixture uses disposable H2 by default. With `SIVITA_NETWORK_TEST_POSTGRES_URL`, `SIVITA_NETWORK_TEST_POSTGRES_USER` and `SIVITA_NETWORK_TEST_POSTGRES_PASSWORD`, each fixture creates an isolated random schema and tears it down. Runtime `RPG_DATABASE_*` overrides are refused. The PostgreSQL-only maintenance-lock race must run against a real PostgreSQL server; a skipped H2 case is not evidence for its behavior.

The implemented Core database path is source/test scope. Full deployment migration still needs validated adapters for every authority actually present, verified account-control evidence, a complete backup/restore rehearsal, proxy authentication configuration, physical player-state transport and human acceptance. A database receipt cannot substitute for those results.
