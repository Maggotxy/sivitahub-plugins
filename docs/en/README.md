# English developer documentation

[Project](../../README.en.md) · [中文](../../README.md)

This is the engineering edition of SivitaHub: architecture, gameplay mechanisms, APIs, content integration, builds, transactions and recovery. It is written for English-speaking developers working on the project, with the same code and gameplay authority as the original edition.

## Recommended reading order

1. [Developer handbook](DEVELOPER_HANDBOOK.md): practical contracts and worked implementation paths.
2. [Gameplay specification](GAMEPLAY_SPECIFICATION.md): how all server systems connect, including production, trade, encounters, communities and horizontal goals.
3. [Getting started](GETTING_STARTED.md): exact toolchain, dependencies and test commands.
4. [Architecture](ARCHITECTURE.md): all 25 plugin owners, state boundaries and threading.
5. [Constitution](GAME_ECONOMY_CONSTITUTION.md) and [phase plan](PHASE_PLAN.md): invariants and implementation requirements.
6. [Contributing](CONTRIBUTING.md), [Operations](OPERATIONS.md) and [Roadmap](ROADMAP.md): review, migration, recovery and evidence gates.

Keep the [25-plugin reference](PLUGIN_REFERENCE.md) open while implementing a feature: it links each module's commands, permissions, source, configuration, extension contract and recovery checks. The [mechanism completion review](../audits/MECHANISM_COMPLETION_REVIEW.md) distinguishes implemented behavior from deployment and human acceptance.

## Working conventions

Command IDs, permissions, config keys, database names, Java symbols and PDC keys are stable identifiers. Translate their explanations, not the identifiers. Use [the glossary](GLOSSARY.md) to interpret terms in older Chinese comments and design notes.

An implementation, a passing automated test, a model result and a live-server acceptance result are different evidence. Every new mechanism needs a reachable entry, eligibility, authoritative effect, feedback and recovery/exit path. Source presence alone does not complete that loop.

[AGENTS.md](../../AGENTS.md), the [canonical constitution](../GAME_ECONOMY_CONSTITUTION.md) and [rights notice](../../INTERNAL_USE.md) remain authoritative. Historical audits carry their own dates and baselines. The [player guide](PLAYER_GUIDE.md) and English language preset remain available as supporting presentation references; they are not the purpose of this developer edition.
