# plan/ 目录说明

> 本文件只做一件事：说明**账本在哪**，避免再出现两份不一致的状态文件。

## 账本已并入 `state/world-state.json`

- 唯一真相源：**`state/world-state.json`**（跨 agent 交接账本）。
- `plan/world-state.json`：**已废弃并在 W21-A2 删除**。同一 commit `6500a21` 里它曾被写成与 `state/` 那份**不同内容**
  （`round` 都是 24、`updated_by` 都是 `CNB CodeBuddy NPC（W17-A1-verdict-v8）`，但 `assets`/`gates`/`status`/`next_action`/
  `open_questions_for_human`/`wave_plan`/`evidence` 共 7 个字段不同），两个 agent 分别读两份就会得出不同结论。
  漂移内容已按并集并入 `state/world-state.json`（见其中 `_ledger` 字段，`evidence` 顺延编号 E-057..E-062）。
- **禁止再建 `plan/world-state.json`**：`scripts/selftest-gates.sh` 的 §11 判据 1（`SINGLE_SOURCE_OF_TRUTH_GATE`）
  会把它判红。规则原文见 `doc/cnb/agent-protocol.md` §4。

## 本目录放什么

| 用途 | 例子 |
|---|---|
| 波次任务清单 | `tasks-w*.json` |
| 差集矩阵（只读审计结论） | `gap-matrix-v8.md` |
| 收尾判定 | `verdict-v8.json` |
| 遗留清单 | `source-string-tests-remaining.json`、`deferred-tables-tasks.json` |
| DDL 迁移与冻结的裁决 | `migrations/`、`decisions-*.md` |

## 与账本的关系（W21-A2 起的硬规则）

1. **任何会更新账本的任务，`allowed_paths` 必须包含 `state/**`**（见 `doc/cnb/agent-protocol.md` §4）。
2. 矩阵/判定的机器可读结论行：`gap-matrix-vN.md` 前 20 行内必须有
   `> verdict: done=<bool> remaining=<int>`，且与 `plan/verdict-vN.json` 的 `done`/`remaining` 严格一致、
   `basis` 值必须出现在同前 20 行内 —— 由 §11 判据 2（`VERDICT_MATRIX_CONSISTENCY_GATE`）守。
