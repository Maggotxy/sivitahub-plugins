# SivitaHub — Survival RPG Plugin Suite for Developers

[简体中文](README.md) · **English** · [Documentation](docs/en/README.md)

**SivitaHub combines Minecraft's survival sandbox with character builds, player-forged equipment, cooperative dungeons, a player economy, and persistent community progression.** This repository contains its 25 custom Paper plugins, shared APIs, resource-pack workspace, datapack, and engineering tools.

The project targets **Minecraft Java Edition 26.2, Paper, and Java 25**. It is developed independently and is not affiliated with or endorsed by Mojang or Microsoft.

## Start here: developer edition

This English edition is intended for developers who need to build, debug, extend and maintain the **entire project**. It keeps the same Java code, APIs, identifiers and gameplay rules. Player localization is a separate feature.

| Engineering task | Read |
|---|---|
| Work on the codebase end to end | [Developer handbook](docs/en/DEVELOPER_HANDBOOK.md) |
| Understand the whole server's gameplay loops | [Gameplay systems and extension contracts](docs/en/GAMEPLAY_SPECIFICATION.md) |
| Extend a particular plugin, command or content loop | [Reference for all 25 plugins](docs/en/PLUGIN_REFERENCE.md) |
| Build, test and diagnose dependencies | [Getting started](docs/en/GETTING_STARTED.md) |
| Find module/data ownership | [Architecture and all 25 modules](docs/en/ARCHITECTURE.md) |
| Implement the next mechanism correctly | [Phase plan](docs/en/PHASE_PLAN.md) and [Constitution](docs/en/GAME_ECONOMY_CONSTITUTION.md) |
| Review a migration, failure path or PR | [Contributing](docs/en/CONTRIBUTING.md) and [Operations](docs/en/OPERATIONS.md) |
| Follow transaction/content verification | [Developer CI](.github/workflows/verify.yml) and [Roadmap/evidence](docs/en/ROADMAP.md) |
| Understand Chinese design terms in existing code | [English–Chinese glossary](docs/en/GLOSSARY.md) |


## What is in the game?

- **Survival with RPG progression.** Six starting classes, five attributes, learnable skills, and up to three ascensions. Each stage has local levels 1–30; effective level spans 1–120. Server chapters control when later stages become available.
- **Equipment that develops through use.** Forge from vanilla base items, reveal attribute slots, refine equipment, attempt limit breaks, and use salvage or legacy extraction to give old equipment an exit path. Item identities and signatures protect the settlement boundary.
- **Cooperative encounters.** Adaptive overworld mobs, readable attack warnings, Blood Moons, hunt chains, and shared dungeons for parties of one to four. The checked-in catalog has three dungeon families, each with a normal and hidden variant.
- **A connected economy.** Gold, Merit, and Starlight have explicit roles, transaction records, and configured limits. An escrow market handles sales and durable item delivery. Resource commissions connect ordinary survival activity to the RPG loop.
- **A shared world.** A protected hub, service NPCs, optional story guidance, communities, companions, and cosmetic effects connect the systems. The AI companion provides read-only advice and has a local fallback.

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

When reviewing this implementation stack, check out the reviewed PR head or its exact commit before building. Cloning the default branch does not include unmerged PR work. The [completion review](docs/audits/MECHANISM_COMPLETION_REVIEW.md) identifies the verified source snapshots and review order.

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

Player localization is optional and separate from this engineering edition. The [English configuration preset](examples/en_us/README.md) remains available for localization testing. Developer builds preserve the canonical player-language defaults unless an explicit preset is selected.

The [developer bundle build instructions](docs/en/GETTING_STARTED.md) also cover the portable client pack, BetterModel/BetterHud registration candidates, bundled bilingual prologue quest, and `scripts/package-edition.py`. The edition packager requires all 25 current JARs with matching source fingerprints and refuses incomplete or stale builds. Use the [developer handbook](docs/en/DEVELOPER_HANDBOOK.md) for implementation contracts and the [gameplay specification](docs/en/GAMEPLAY_SPECIFICATION.md) for the complete system map. Earlier asset/localization work is recorded in the dated [25-plugin audit](docs/audits/FULL_PLUGIN_GAMEPLAY_REVIEW.md).

## Design commitments

The [gameplay and economy constitution](docs/en/GAME_ECONOMY_CONSTITUTION.md) requires one authoritative state source, audited and idempotent monetary changes, a single escrow market, explicit item exit paths, transactional season settlement, and strict separation between paid Support Credit and gameplay wealth or power. More content must not introduce another currency, market, or progression ledger without the prescribed design review.

For release readiness, the project requires real Paper/PostgreSQL evidence and human playtests in addition to automated tests. The current roadmap includes multiplayer failure recovery, authentication behavior, economic observation, client UI validation, and staged load testing. It does not establish a verified public player capacity.

## Usage rights

Read [INTERNAL_USE.md](INTERNAL_USE.md). Public repository visibility does not change that notice: no permission is granted there to redistribute the project's original code or assets, sublicense them, or operate a derivative public server. Third-party projects retain their own licenses and notices. The English documentation does not grant additional rights or replace third-party obligations.

## Source reference

The current implementation, migration decisions, exact test/runtime evidence and remaining acceptance work are recorded in the [mechanism completion review](docs/audits/MECHANISM_COMPLETION_REVIEW.md). It covers the complete phase-based review stack and supersedes older missing-feature statements where implementation now exists. Earlier asset/localization evidence remains attached to its original snapshot in the [2026-11-04 edition record](docs/audits/PLAYABLE_ENGLISH_EDITION_DELIVERY.md).
