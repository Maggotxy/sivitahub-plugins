# English documentation

[Project overview](../../README.en.md) · [中文入口](../../README.md)

SivitaHub is a custom Minecraft survival RPG built as a suite of Paper plugins. These guides explain the project to players, developers, and authorized operators without assuming familiarity with its Chinese documentation.

## Choose your path

| Audience | Reading order |
|---|---|
| Players | [Player guide](PLAYER_GUIDE.md) → [Glossary](GLOSSARY.md) |
| Developers | [Getting started](GETTING_STARTED.md) → [Architecture](ARCHITECTURE.md) → [Contributing](CONTRIBUTING.md) |
| Operators | [Operations](OPERATIONS.md) → [Roadmap and evidence](ROADMAP.md) |
| Designers and reviewers | [Gameplay and economy constitution](GAME_ECONOMY_CONSTITUTION.md) → [Architecture](ARCHITECTURE.md) → [Roadmap](ROADMAP.md) |

## Reading conventions

- Command IDs, configuration keys, database names, and Java identifiers are kept exactly as implemented. Translate their explanations, not the identifiers.
- **Default** means a value in checked-in configuration. An authorized deployment can use different reviewed settings.
- **Implemented** describes code presence; **validated** requires identified test or runtime evidence. These terms are not interchangeable.
- Paths under the private `server/` deployment tree are not files supplied by this repository unless explicitly stated otherwise.
- Original project policy remains in [AGENTS.md](../../AGENTS.md), the [Chinese constitution](../GAME_ECONOMY_CONSTITUTION.md), and [INTERNAL_USE.md](../../INTERNAL_USE.md). This English edition explains those rules without changing them.

Baseline reviewed: `c83226ff87c8ecb868277d9ca5bce575b4684abc`.

## Verification of this edition

See the [delivery and verification record](../audits/INTERNATIONAL_READINESS_DELIVERY.md) for the fixes, 17 targeted Java tests, 29 Python tests, the Citizens download blocker, and the runtime/human checks still required.
