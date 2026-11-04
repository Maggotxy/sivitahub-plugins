# Contributing and reviewing changes

[English documentation](README.md) · [Developer handbook](DEVELOPER_HANDBOOK.md) · [Architecture](ARCHITECTURE.md) · [Constitution](GAME_ECONOMY_CONSTITUTION.md)

Work on this repository must preserve its gameplay rules, persistent data, and evidence requirements. Read [INTERNAL_USE.md](../../INTERNAL_USE.md) before using or contributing original material. This guide does not introduce an open-source license, change ownership, or grant redistribution rights.

## Required reading

The authoritative contributor/agent entry point is [AGENTS.md](../../AGENTS.md). Its required sequence continues through the [constitution](../GAME_ECONOMY_CONSTITUTION.md), [long-term plan](../LONG_TERM_GAME_ECONOMY_PLAN.md), [execution plan](../CODEX_LONG_TERM_EXECUTION_PLAN.md), [machine-readable phase plan](../planning/long-term-game-economy-v1.yaml), [roadmap](../ROADMAP.md), and [progression audit](../audits/PROGRESSION_SEMANTICS_AUDIT.md).

The [English constitution](GAME_ECONOMY_CONSTITUTION.md) translates all 15 invariants. Older completion documents can describe earlier commits. Check their statements against current source, switches, and the [current source review](../audits/CURRENT_SOURCE_REVIEW.md) before treating them as a new task or an established capability.

## Scope a change

Use a separate branch and PR; do not commit directly to `main`. Keep each implementation phase in its own PR. Documentation, translation, and build-tooling improvements should explain their scope without quietly changing gameplay values, currency routes, or schema behavior.

The planned mechanism phases are:

| Phase | Scope |
|---|---|
| 0 | Mechanism inventory, kill switches, and metrics |
| 1 | Local-level progression semantics and migration |
| 2 | Economy and equipment lifecycle consistency |
| 3 | Economy simulation using real configuration |
| 4 | Equipment exit paths and market improvements |
| 5 | Support Credit isolation and transactional season settlement |
| 6 | Community governance |
| 7 | Endgame content and content pipeline |
| 8 | Identity migration and multi-server prerequisites |

The phase plan defines dependencies; these rows do not say that every phase is complete. Changes that conflict with the constitution require the [mechanism RFC](../templates/MECHANISM_RFC_TEMPLATE.md) and the prescribed review before implementation.

## Implementation expectations

- Use shared API contracts and the existing authoritative ledger. Do not add a second market, progression ledger, or equipment state machine.
- Keep JDBC away from the Paper tick thread, use bounded work queues, and handle rejection explicitly.
- Recheck balances, ownership, quotas, state, and authorization at the authoritative transaction boundary. A GUI preview is not that boundary.
- Preserve idempotency identity across retries, including ambiguous commits and disconnects. Refunds must refer to the original debit.
- Distinguish an in-process lock from a cross-node protocol. A lease reader alone is not a lease lifecycle.
- Make schema and data changes reviewable with dry-run, forward, verification, and rollback procedures. Never infer a legacy local level with `level % 30`.
- Preserve third-party artifact provenance, exact version/hash constraints, and applicable notices.

## Presentation changes (supporting workflow)

Translate the player's intended meaning using [the glossary](GLOSSARY.md), then compare the result with the actual operation. In particular, do not interchange **Energy** with **Stamina**, **Starlight** with **Support Credit**, or local level with effective level.

Core messages live under [`rpg-core/src/main/resources/lang/`](../../plugins/rpg-core/src/main/resources/lang), and hub messages under [`rpg-hub/src/main/resources/lang/`](../../plugins/rpg-hub/src/main/resources/lang). Classes, companions, Essentials integration, and AI knowledge also contain bilingual configuration. Shared NPC/map labels and third-party interfaces need separate review.

For each changed message:

1. Keep the same key, value type, and placeholder names in both languages. Check each line of list-valued messages.
2. Preserve literal command IDs, configuration keys, and technical identifiers. Translate display labels around them.
3. State costs, eligibility, probabilities, permanence, and recovery consequences accurately.
4. Check positive, empty, pending, failure, and confirmation states.
5. Inspect the rendered result on the supported client, including different GUI scales, before claiming visual acceptance.

The automated locale checker compares the six bilingual data groups and catches key, type, and placeholder drift:

```bash
python3 -m pip install 'PyYAML==6.0.3'
python3 scripts/check-locales.py
python3 -m unittest discover -s scripts/tests -p 'test_locales.py'
```

Use an isolated Python environment when appropriate. The checker is a structural gate, not a proof that the whole game is translated. See [the localization review](../audits/LOCALIZATION_REVIEW.md).

## Verification and evidence

Run focused tests for changed behavior, then the required gates for the scope. [Getting started](GETTING_STARTED.md) explains the source build and environment-dependent tests. Do not remove an acceptance test or turn a missing prerequisite into success to obtain a green result.

Use the repository's evidence labels consistently:

| Label | Required evidence |
|---|---|
| `[D]` | Reviewed design, specification, or RFC |
| `[S]` | Identified source implementation |
| `[T]` | Executed automated tests with results and environment |
| `[M]` | Executed simulation with real inputs and recorded outputs |
| `[R]` | Real Paper/PostgreSQL runtime evidence |
| `[H]` | Human interaction and sustained playtesting |

An existing test file is source evidence until its execution is recorded. A simulator stub with scenario names is not simulation evidence. Historical runtime logs do not validate a new code commit automatically.

## PR description

Include the following sections as required by `AGENTS.md`:

```text
Goal
Non-goals
Current facts (base commit and schema/configuration context)
Constitution invariants (C-xxx)
Data migration (dry-run, forward change, verification)
Failure modes
Rollback
Tests (commands, results, skips, and evidence labels)
Runtime evidence (environment, logs, measurements, or explicitly not run)
Remaining unproven items
```

State the problem and resulting behavior before implementation details. Link the evidence and identify what was actually tested. A documentation-only PR should say that it changes no runtime behavior or schema and should not claim newly acquired gameplay/runtime validation.
