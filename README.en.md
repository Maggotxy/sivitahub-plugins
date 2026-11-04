# SivitaHub — A Survival RPG for Minecraft

[简体中文](README.md) · **English** · [Documentation](docs/en/README.md)

**SivitaHub combines Minecraft's survival sandbox with character builds, player-forged equipment, cooperative dungeons, a player economy, and persistent community progression.** This repository contains its 25 custom Paper plugins, shared APIs, resource-pack workspace, datapack, and engineering tools.

The project targets **Minecraft Java Edition 26.2, Paper, and Java 25**. It is developed independently and is not affiliated with or endorsed by Mojang or Microsoft.

## Start here

| I want to… | Read |
|---|---|
| Understand the game and its commands | [Player guide](docs/en/PLAYER_GUIDE.md) |
| Build or test the plugins | [Getting started](docs/en/GETTING_STARTED.md) |
| Understand module and data ownership | [Architecture](docs/en/ARCHITECTURE.md) |
| Prepare an authorized deployment | [Operations](docs/en/OPERATIONS.md) |
| Review a change or contribute a translation | [Contributing](docs/en/CONTRIBUTING.md) |
| Understand unfamiliar RPG terminology | [English–Chinese glossary](docs/en/GLOSSARY.md) |
| Separate current code from release requirements | [Roadmap and evidence](docs/en/ROADMAP.md) |
| Review the implemented English project edition | [Implementation and acceptance record](docs/audits/PLAYABLE_ENGLISH_EDITION_DELIVERY.md) |

## What is in the game?

- **Survival with RPG progression.** Six starting classes, five attributes, learnable skills, and up to three ascensions. Each stage has local levels 1–30; effective level spans 1–120. Server chapters control when later stages become available.
- **Equipment that develops through use.** Forge from vanilla base items, reveal attribute slots, refine equipment, attempt limit breaks, and use salvage or legacy extraction to give old equipment an exit path. Item identities and signatures protect the settlement boundary.
- **Cooperative encounters.** Adaptive overworld mobs, readable attack warnings, Blood Moons, hunt chains, and shared dungeons for parties of one to four. The checked-in catalog has three dungeon families, each with a normal and hidden variant.
- **A connected economy.** Gold, Merit, and Starlight have explicit roles, transaction records, and configured limits. An escrow market handles sales and durable item delivery. Resource commissions connect ordinary survival activity to the RPG loop.
- **A shared world.** A protected hub, service NPCs, optional story guidance, communities, companions, and cosmetic effects connect the systems. The AI companion provides read-only advice and has a local fallback.
- **An English in-game edition.** English and Simplified Chinese cover the custom menus, progression messages, NPC guidance, story package, knowledge answers, and supported item presentation. Use `/language en_us`; supported existing personal items refresh as well. The English edition preset sets the default language for shared NPCs and world labels. Third-party interfaces and rendered client surfaces retain their own acceptance checks.

These are source-level capabilities. A feature can exist in code while its deployment switch remains off or its multiplayer acceptance tests remain outstanding. See the [evidence model](docs/en/ROADMAP.md#evidence-levels).

## Repository scope

**This is the plugin-source repository, not a complete server distribution.** The private deployment tree, website, production worlds, credentials, and operator-specific orchestration are not supplied here. Historical documents describe that broader environment; their runtime statements are not independently reproduced by cloning this repository.

```text
plugins/        25 custom plugin modules
resource-pack/  Art, models, localization assets, and pack tooling
datapacks/      Navigation and quick-action datapack
scripts/        Dependency, source, artifact, and runtime checks
tools/          Database migration, economy simulation, and load-test tools
libs/vendor/    Pinned third-party artifacts and their provenance notes
docs/           Design, audits, specifications, and English guides
```

The build pins its versions in [gradle.properties](gradle.properties). It does not claim compatibility with other Minecraft versions or Folia.

## Development quick start

Install JDK 25, Git, Python 3, and a POSIX shell. On Windows, use Git Bash or WSL for the shell scripts and `gradlew.bat` for native Gradle commands.

```bash
git clone https://github.com/Maggotxy/sivitahub-plugins.git
cd sivitahub-plugins

# Set this to an existing JDK 25 installation on your machine.
export SIVITA_JAVA_HOME=/absolute/path/to/jdk-25

sh scripts/fetch-deps.sh
sh scripts/fetch-deps.sh --check
./gradlew --version
./gradlew test -PexcludeTags=env
./gradlew assemble
```

Dependency downloads require network access to the pinned upstream artifacts. The `env` exclusion omits deployment-dependent checks; it does not validate a running server. Plugin JARs are generated under `plugins/<module>/build/libs/`. Read [Getting started](docs/en/GETTING_STARTED.md) before choosing a test or staging task. The private deployment scripts required by `stagePlugins` and `stageRelease` are outside this repository.

For an English default and English shared NPC names, the [English configuration preset](examples/en_us/README.md) generates a separate reviewable preview. It changes approved text fields and locale selection; deployment remains an operator action.

The [English edition build instructions](docs/en/GETTING_STARTED.md) also cover the portable client pack, BetterModel/BetterHud registration candidates, bundled bilingual prologue quest, and `scripts/package-edition.py`. The edition packager requires all 25 current JARs with matching source fingerprints and refuses incomplete or stale builds. Review the [25-plugin gameplay and asset audit](docs/audits/FULL_PLUGIN_GAMEPLAY_REVIEW.md) for each module's implemented behavior and outstanding runtime checks.

## Design commitments

The [gameplay and economy constitution](docs/en/GAME_ECONOMY_CONSTITUTION.md) requires one authoritative state source, audited and idempotent monetary changes, a single escrow market, explicit item exit paths, transactional season settlement, and strict separation between paid Support Credit and gameplay wealth or power. More content must not introduce another currency, market, or progression ledger without the prescribed design review.

For release readiness, the project requires real Paper/PostgreSQL evidence and human playtests in addition to automated tests. The current roadmap includes multiplayer failure recovery, authentication behavior, economic observation, client UI validation, and staged load testing. It does not establish a verified public player capacity.

## Usage rights

Read [INTERNAL_USE.md](INTERNAL_USE.md). Public repository visibility does not change that notice: no permission is granted there to redistribute the project's original code or assets, sublicense them, or operate a derivative public server. Third-party projects retain their own licenses and notices. The English documentation does not grant additional rights or replace third-party obligations.

## Source reference

The initial documentation baseline was `c83226ff87c8ecb868277d9ca5bce575b4684abc`. The subsequent runtime edition, gameplay repairs, source fingerprint, actual build results, and exact remaining blocker are recorded in the [2026-11-04 acceptance record](docs/audits/PLAYABLE_ENGLISH_EDITION_DELIVERY.md).
