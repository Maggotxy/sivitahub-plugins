# Getting started with the source

[English documentation](README.md) · [Architecture](ARCHITECTURE.md) · [Operations](OPERATIONS.md)

This guide covers inspecting, building, and testing the plugin repository. An operational server also needs the owner's deployment configuration, dependencies, worlds, secrets, and a reviewed deployment procedure.

## 1. Check the prerequisites

| Component | Repository requirement | Source |
|---|---|---|
| JDK | Java 25, including the compiler | [Root build](../../build.gradle.kts) |
| Gradle | Use the checked-in 9.1.0 wrapper | [Wrapper properties](../../gradle/wrapper/gradle-wrapper.properties) |
| Minecraft / Paper API | Minecraft 26.2; `26.2.build.100-stable` API | [Version properties](../../gradle.properties) |
| Shell and utilities | POSIX shell and Python 3 for the dependency checks; Bash for Bash scripts | [Scripts](../../scripts) |
| Network | Access to the configured Maven repositories and exact pinned dependency URLs | [Dependency fetcher](../../scripts/fetch-deps.sh) |
| Database | H2 for the local default; PostgreSQL for the intended production deployment | [RpgCore configuration](../../plugins/rpg-core/src/main/resources/config.yml) |

Install a **JDK**, not a Java 8/17/21 runtime. The Gradle wrapper can select an existing bundled JDK in the private deployment layout, but that JDK is not distributed here. Set `SIVITA_JAVA_HOME` explicitly for a source checkout. Without that override or a bundled runtime, the wrapper follows its normal `JAVA_HOME`/`PATH` behavior.

The repository targets specific Paper APIs. Changing only the version string does not establish support for an older server or Folia.

## 2. Obtain the source and dependency files

```bash
git clone https://github.com/Maggotxy/sivitahub-plugins.git
cd sivitahub-plugins
export SIVITA_JAVA_HOME=/absolute/path/to/jdk-25

sh scripts/check-file-deps.sh
sh scripts/fetch-deps.sh
sh scripts/fetch-deps.sh --check
./gradlew --version
```

Replace the JDK path with a real installation path. `check-file-deps.sh` checks that declared file dependencies appear in the fetch/vendor manifest; it does not prove the artifacts are downloaded. The fetcher's `--check` mode checks local prerequisites without downloading them. An unresolved artifact or checksum mismatch is a build blocker; use the exact pinned artifact rather than a nearby version.

Some dependencies are checked in under `libs/vendor/` because the deployment uses specific builds or local patches. Keep their identities and notices intact. Consult [vendor provenance](../../libs/vendor/README.md) and the [runtime dependency lock](../../scripts/runtime-smoke-plugins.lock.json). Build-time dependencies and runtime plugin artifacts are distinct lists; passing the build check does not establish a complete runtime installation.

### Windows

In PowerShell, select Java explicitly:

```powershell
$env:SIVITA_JAVA_HOME = 'C:\Path\To\jdk-25'
.\gradlew.bat --version
.\gradlew.bat :plugins:rpg-api:test
```

Run `.sh` scripts in Git Bash or WSL, with the corresponding tools installed. A Windows JDK path and a WSL JDK path are different environments; configure the JDK in the environment running Gradle.

## 3. Select the right verification level

| Goal | Command | What it establishes |
|---|---|---|
| Test shared deterministic rules | `./gradlew :plugins:rpg-api:test` | The API module's tests execute against the checked-out code |
| Run source tests without deployment-dependent tests | `./gradlew test -PexcludeTags=env` | Runs tests not tagged `env`; inspect results and skips |
| Run all configured tests | `./gradlew test` | Includes environment-dependent tests and needs their documented prerequisites |
| Build plugin and tool artifacts | `./gradlew assemble` | Compiles and packages the configured projects |
| Check dependency declarations | `sh scripts/check-file-deps.sh` | File dependency references have manifest entries |

Gradle module paths include `plugins:`. For example, use `:plugins:rpg-api:test`, not `:rpg-api:test`.

Test reports live under each module's `build/reports/tests/test/`, with machine-readable results under `build/test-results/test/`. A successful build with skipped database tests is not evidence that PostgreSQL recovery passed. For example, the [PostgreSQL migration integration test](../../tools/db-migration/src/test/java/cn/originalrpg/tools/dbmigration/PostgreSqlMigrationIntegrationTest.java) requires its own `SIVITA_MIGRATION_TEST_POSTGRES_*` environment variables.

The default Gradle configuration uses one worker and a bounded heap. CI can provide reviewed resource overrides. Increase concurrency only after checking memory use and test isolation.

## 4. Locate the output

Each custom plugin is a separate JAR under:

```text
plugins/<module>/build/libs/
```

The build also creates source JARs. Deploy the runtime JARs, not the `-sources.jar` files. `RpgApi` is itself a plugin and must be included when assembling a runtime set.

`stagePluginCandidates`, `stagePlugins`, and `stageRelease` belong to the deployment workflow. In particular, `stagePlugins` delegates to a private deployment script and `stageRelease` also invokes the resource-pack workflow. They are not a substitute for the source build commands above.

## 5. Enable English in an authorized runtime

For one player, authenticate first and run:

```text
/language en_us
```

Close and reopen existing menus after switching. `/language zh_cn` switches back, and `/language` reports the current selection.

To make English the server default, edit the generated **runtime** `plugins/RpgCore/config.yml` during a maintenance window:

```yaml
language:
  default: en_us
  allow-player-selection: true
```

Restart through the normal server procedure. The checked-in template is [RpgCore's config](../../plugins/rpg-core/src/main/resources/config.yml). A player's saved selection overrides the default when selection is allowed. The [shared message service](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/i18n/YamlMessageService.java) does not automatically choose a language from the Minecraft client's locale.

Core and hub language bundles live under their modules' `src/main/resources/lang/` directories. Other custom modules also have embedded bilingual text, and external plugins have their own language settings. Changing the core default does not configure AuthMe, a website, or every third-party UI. See [Operations](OPERATIONS.md#language-and-regional-settings).

### Generate a reviewable English configuration preview

The [English preset](../../examples/en_us/README.md) contains small overlays for the core locale, eleven hub NPC names/subtitles, the story actor name, and the performance join message. Generate full development-config previews into a new directory:

```bash
python3 -m pip install 'PyYAML==6.0.3'
python3 scripts/generate-english-configs.py --output build/en_us-preview
```

The generator refuses an existing output directory, file, or symlink. It writes four plugin configuration previews and a source-hash manifest. It does not install them or write into a running server. For an existing deployment, merge the small overlays into your own reviewed staging configuration; replacing live files with generated development defaults could discard unrelated operator settings.

The preset changes only the approved 25 locale/text fields. It does not enable gameplay switches, alter economic values, or translate hardcoded and third-party surfaces. Check [the localization review](../audits/LOCALIZATION_REVIEW.md) for the remaining coverage boundaries.

## Troubleshooting

| Symptom | First check |
|---|---|
| Java version or toolchain error | Confirm `SIVITA_JAVA_HOME` selects a JDK 25 and inspect `./gradlew --version` |
| Missing classes from a third-party plugin | Run `fetch-deps.sh --check`; confirm the exact `libs/` and vendor files |
| Download or checksum failure | Check connectivity, source URL, and the pinned hash; preserve the failure output |
| A `server/` script cannot be found | Check whether you invoked a private deployment task instead of a source build task |
| Source tests pass but full tests fail | Inspect `env` prerequisites and per-module reports; do not silently remove the checks |
| English selection does not change an open menu | Reopen it; inspect the player's saved preference and module-specific language support |
| A plugin stays disabled | Read its `plugin.yml` hard dependencies and startup error; compile-only dependencies still need runtime plugins |

For production validation, continue with the [operator guide](OPERATIONS.md). This repository does not supply a public server address or a ready-to-run production bundle.
