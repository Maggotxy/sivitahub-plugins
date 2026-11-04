# Reproducible Paper startup verification

The startup gate uses the exact Paper 26.2 build 100 in `scripts/runtime-smoke-paper.lock.json` and the fifteen runtime dependency identities in `scripts/runtime-smoke-plugins.lock.json`. A dependency hash mismatch is a failure. The compile-time and runtime dependency locks are checked independently.

## Run

Use JDK 25 and the checked-in Gradle 9.1.0 wrapper. From the repository root:

```sh
sh scripts/fetch-deps.sh
./gradlew assemble
sh scripts/run-paper-smoke.sh --skip-build --timeout 360
```

The script creates a new temporary world, binds Minecraft to loopback on an ephemeral port, and generates an ephemeral equipment-signing key only for this test process. It does not reuse production signing keys or worlds. The existing smoke EULA acceptance applies to this disposable test server. Test items must never be imported into a real server.

The default database is the source configuration's local H2 database inside the disposable server directory. For a dedicated, empty PostgreSQL test database, provide `SIVITA_SMOKE_POSTGRES_URL`, `SIVITA_SMOKE_POSTGRES_USER`, and `SIVITA_SMOKE_POSTGRES_PASSWORD`. The script refuses an inherited runtime `RPG_DATABASE_URL`; never point the smoke variables at a live or shared gameplay database. The GitHub job creates its own PostgreSQL 18.6 container and discards it after execution.

## What is actually checked

`tools/runtime-smoke` builds a disposable `SivitaRuntimeProbe` plugin. It is excluded from the 25-plugin release. Its expected plugin list is generated from the actual plugin descriptors. Two seconds after Paper's startup event, on the server thread, it checks:

- all 25 custom plugins exist and report `Plugin.isEnabled()`;
- eight core services are registered: profiles, economy, listings, buy/craft orders, dungeon entry/rewards, equipment operations, and skills;
- its own generated manifest has exactly 25 distinct plugin identities.

The harness requires Paper's ready marker, the positive probe result, all 25 positive plugin records, and zero attributed custom-plugin exceptions. Merely printing `Enabling RpgItems` is no longer sufficient: a plugin that disables itself during initialization makes the probe fail. Community-plugin and unattributed errors remain separately visible in the report and must be reviewed; this source gate does not certify every external plugin's behavior.

The script stops Paper after collecting the result. It reports `PAPER_SMOKE_GATE`, `PAPER_STARTED`, `PAPER_PROBE_READY`, `PAPER_PLUGINS_ENABLED`, and exception counts. The temporary path and complete server log remain available for diagnosis. CI uploads these logs even on failure.

## Evidence boundary

A successful run proves startup, real schema initialization against the selected database, and the observed registration state for that source/build snapshot. It does not prove login, UI, combat, trade, inventory crash recovery, cross-node handoff, capacity, or sustained human gameplay. Record the exact Git commit and the workflow result alongside any runtime claim. Never describe an unexecuted or red startup job as runtime acceptance.

The probe performs no rewards, financial writes, teleports, or player-data edits. Remove it from any production deployment; it is a verification tool only.
