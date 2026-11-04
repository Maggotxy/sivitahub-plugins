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

`stagePluginCandidates`, `stagePlugins`, and `stageRelease` belong to the private deployment workflow. `stagePlugins` delegates to a private deployment script; `stageRelease` invokes `buildDeploymentResourcePack`, which explicitly runs `resource-pack/build.sh --deployment` before staging the JARs. The separate `buildResourcePack` task builds the portable source-checkout artifact. Use the source build commands above until an authorized deployment tree is available.

### Build a complete developer staging bundle

The public resource-pack builder now works without a private server tree. The server-resource exporter additionally needs Node.js and PyYAML; its model-only path uses existing reviewed PNG bytes and has no Sharp or Pillow dependency.

```bash
python3 -m pip install PyYAML==6.0.3

# Complete committed client assets; output is build/resource-pack/.
bash resource-pack/build.sh

# Optional separate review copy of 15 model registrations and the bilingual HUD.
# The output directory must be NEW.
python3 resource-pack/tools/build_server_resources.py --output build/server-resources-candidate

# After ./gradlew assemble, require all 25 actual runtime JARs and package them.
# The directory and its sibling ZIP must both be NEW.
python3 scripts/package-edition.py --output build/sivitahub-developer-26.2
```

The developer bundle contains all custom plugin JARs, canonical development configs with their original player-language defaults, the client resource baseline, native G-key datapack, bilingual BetonQuest prologue, and the model/HUD inputs under `runtime-candidates/`. Its explicit public documentation inventory includes 50 files: all 16 English guides, eight operations runbooks, the governing rules and referenced recovery notes. The handbook, gameplay specification and 25-plugin reference are available directly from `INSTALL.en.md`; source-code and executable-tool links require the corresponding source checkout. It also writes a file-by-file SHA-256 manifest, a ZIP, and a ZIP checksum. Missing or stale JARs or required documentation fail the whole command. The [packager](../../scripts/package-edition.py) verifies each JAR's `META-INF/sivitahub-build.json` against the same fingerprint of **all** plugin main sources/resources and Gradle inputs; a Java-only shared API change requires a matching rebuild of the whole suite. Build provenance and archive checks do not establish server startup or client acceptance.

The client ZIP contains every committed `originalrpg`, `betterhud`, `bettermodel` and `minecraft` asset. The candidate exporter supplies all 15 complete server `.bbmodel` registrations and a four-slot HUD with 42 skill icons, seven class states, cooldowns, mana, progression and language-sensitive control labels. The complete HUD is gated by the actual resource-pack/profile readiness placeholder. Compile these inputs with the pinned external plugins on an isolated server, then keep their **matching model indices, font codepoints and shaders together** in the final runtime pack. A new HUD layout cannot safely reuse the old shader layout table simply because both packs load as ZIP files. See the generated `runtime-candidates/README.en.md` and [resource build guide](../../resource-pack/README.md) for the boundary and checks.

Third-party plugin binaries, worlds, player data, databases, credentials and production configuration are excluded. Follow the generated `INSTALL.en.md` before copying anything into a staging server. Existing servers must review and merge configuration changes rather than replace operational settings with development defaults. English developer documentation is independent of player-language selection. The optional `--english-player-preset` flag explicitly applies the separate English presentation overlay; it is off by default.

## 5. Optional player localization

This developer edition does not change the language your players use. The packaged source defaults remain unchanged unless `--english-player-preset` is selected explicitly. Players may use the existing `/language en_us` or `/language zh_cn` controls after authentication. For localization work, consult the [player guide](PLAYER_GUIDE.md), [message/content conventions](CONTRIBUTING.md) and [optional English preset](../../examples/en_us/README.md).

For startup verification, follow [the real Paper/PostgreSQL probe guide](../operations/RUNTIME_STARTUP_VERIFICATION.md). Its disposable signing key and database are test inputs; do not copy them into a live server.

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
