# 跨 agent 交接协议（CNB 主导）

> 目的：让 **WorkBuddy AI / Qoder / Codex / CodeBuddy NPC / 未来任何 coding agent** 能接力同一件事，
> 而不依赖任何一个会话的上下文。**任何 agent 只要读本文件 + `state/world-state.json`，就应当能接着干。**

## 1. 三条铁律

1. **CNB 主导**：实现、构建、发布在 CNB 上做。本地 agent 只做「派发 / 验收 / 补 CNB 做不到的事」。
2. **产出必须落仓**：结论、清单、代码、证据，写进本仓或 `state/world-state.json`。
   **留在会话里的结论 = 不存在。**
3. **不推翻已锁决策**：`doc/cnb/decisions-locked.md` 里的条目只能由人类解锁，NPC 与本地 agent 一律不得改。

## 2. 本地 agent 的开场自检（每次接手必做）

```
1. 读 doc/cnb/GOAL.md            —— 目标与验收口径
2. 读 doc/cnb/decisions-locked.md —— 哪些已锁、不许重开
3. 读 state/world-state.json     —— round / evidence / unverified / next_action
4. 读 doc/cnb/gap-registry.md    —— 差集：需求 → 任务 id → 状态
5. 看 open PR：GET /{repo}/-/pulls —— 有没有上一轮没合完的
```

**不要**从零重新审计源码。`doc/gameplay/` 与差集登记是上一轮的产物，重读 10000 行源码是浪费。

## 3. 每轮标准动作（本地 agent）

| 步骤 | 动作 | 判据 |
|---|---|---|
| 1 | 派 Planner NPC | 产出 `plan/tasks.json` + `plan/hardware-steps.md` + 更新后的 `world-state.json` + `verdict.json` |
| 2 | `orchestrator.py --dry-run` 看派发内容 | 任务 goal 里有规格文件路径、验收点、禁止项 |
| 3 | 真跑（后台 + 重定向日志） | 每个任务产出 PR + `.cnb/agent-out/<id>.json` |
| 4 | **逐个验收** | 用 `GET /-/git/compare/{base}...{head}` 的 **`path`** 字段查越界；读 evidence 的 grep 命令自己复核一遍 |
| 5 | 逐个合 PR | 编排器不会自动合；合了下一阶段才看得到上一阶段的代码 |
| 6 | **每波合完显式触发一次全量编译门** | 每工程 `BUILD_EXIT=0` 且 `SECONDS>0`；门不绿不派下波 |
| 7 | 把新事实写回 `state/world-state.json` | 新增 `evidence[]` 条目。**`allowed_paths` 必须含 `state/**`**，否则先改派单再派工（§4.1） |

## 4. 交接的载体：`state/world-state.json`（唯一真相源）

字段语义（**Planner 与本地 agent 都必须按此读写**）：

```jsonc
{
  "project": "sivitahub",
  "round": 0,                    // 已完成轮次
  "max_round": 12,
  "repo": "xzqkjmnbvc2026/sivitahub",
  "goal": "把「声明了但没接线」的玩法机制补齐到可验收状态",
  "evidence": [                  // 只放实测事实，每条带出处
    {"id":"E-001","fact":"...","source":"<文件:行号 或 命令输出>","level":"[S]"}
  ],
  "unverified": [                // 待验证的假设，不得当事实用
    {"id":"U-001","claim":"...","how_to_check":"..."}
  ],
  "author_claimed_pending_verification": [],  // NPC 自称完成、本地尚未复核的
  "decisions_locked": ["D1","D2","D3","D4"],
  "next_action": "…",
  "updated_by": "…",             // 哪个 agent 写的
  "updated_at": "…"
}
```

**规则**：
- `evidence[]` 只放**实测**（grep 命中、测试输出、构建退出码）。推算写进 `unverified[]`。
- NPC 自称完成的一律先进 `author_claimed_pending_verification`，本地复核通过才升到 `evidence[]`。
- 每个 agent 写完必须更新 `updated_by` / `updated_at`，否则下一个 agent 无法判断新鲜度。


### 4.1 硬规则：账本路径与 `allowed_paths` 必须对齐（W21-A2 起）

> **任何会更新账本的任务，`allowed_paths` 必须包含 `state/**`；`plan/world-state.json` 已废弃，禁止再建。**

**根因（派单白名单与协议声明不一致，NPC 只能就地造第二份）**：
`.cnb/agent-out/W10-A1-final-verdict-v5.json` 的 `next_steps` 第 5 条原文 ——

> 「`state/world-state.json` 不在本任务 allowed_paths（`plan/**` 与 `.cnb/agent-out/**`）内，本轮**未改**它：跨 agent 交接账本的更新以 `plan/world-state.json` 为准，需要落到 `state/` 的动作交回调度器。」

后果：同一个 commit `6500a21`（W17-A1）把两份账本写成了**不同内容** ——
`round` 都是 24、`updated_by` 都是 `CNB CodeBuddy NPC（W17-A1-verdict-v8）`，
但 `assets` / `gates` / `status` / `next_action` / `open_questions_for_human` / `wave_plan` / `evidence` 共 7 个字段不同
（plan 40076B / md5 `fbf8a434cacd8693947a5f55f2012e62`；state 35064B / md5 `ace966486ed360318aaa4939ec43e130`）。
两个 agent 分别读两份就会得出不同结论 ⇒ 交接协议直接失效。登记为 `G-DUP-WORLDSTATE`。

**机械判据**（`scripts/selftest-gates.sh` §11，`SINGLE_SOURCE_OF_TRUTH_GATE`）：
- `state/world-state.json` 必须存在；
- `plan/world-state.json` 必须**不存在**（存在且与 `state/` 那份不一致即 RED，并打印两份字节数）;
- GREEN 时打印 `WORLD_STATE_FILES=<n>`，期望 1。

**同一节的第二条硬规则**：矩阵与判定的结论必须机器可读且一致 ——
`plan/gap-matrix-vN.md` 前 20 行内必须有 `> verdict: done=<bool> remaining=<int>`，
其取值与 `plan/verdict-vN.json` 严格一致，且 `verdict-vN.json` 的 `basis` 值必须出现在同前 20 行内。
登记为 `G-VERDICT-MATRIX-SPLIT`（`VERDICT_MATRIX_CONSISTENCY_GATE`）。

## 5. 反模式（实测踩过，别再犯）

| 反模式 | 后果 | 正确做法 |
|---|---|---|
| 结论只写在会话里 | 下个 agent 从零重做 | 写进本仓 / world-state |
| 用 `pr_files` 查路径白名单 | 只给文件名 ⇒ 全是误报 | 用 `compare` 的 `path` 字段 |
| 以为 NPC 卡死就重派 | 实测 NPC 42 分钟零提交但 PR 已开 | 先查 `/-/git/commits/<branch>` 和 `/pulls` |
| 把「文档已更新」当达成 | 这个项目的审计文档系统性拔高 | 只认 src/main 里的调用点 |
| 一次派完不等合 PR | 下游看不到上游代码 | 一波一合，合完再派下一波 |
| 关 PR 用 PUT | 失败 | `PATCH /-/pulls/{n} {"state":"closed"}` |

## 6. 复验轮（verdict / gap-matrix）的 basis 口径 —— **勿要求「字面等于合入后的 main HEAD」**

### 6.1 结论

复验轮产出的 `plan/gap-matrix-vN.md` 与 `plan/verdict-vN.json` 里的 `basis`，**必须等于「它自己那一轮分叉时的 main HEAD」**，
即该轮 NPC 实测的 `git rev-parse HEAD`（= 当时的 `origin/main`）。判定「矩阵不是旧的」用两条：

1. **basis 必须是它自己那一轮分叉出来的**（不是更早的 main）—— 任务书里要写死
   「先跑 `git rev-parse HEAD` 与 `git rev-parse origin/main`，把两者是否相等写进矩阵前 20 行，
   `basis` 写**实测**短 sha，**不要抄任务书里的数字**」。
2. `git diff --name-only <basis> <当前 main HEAD>` **只含非构建输入**
   （`plan/**`、`state/**`、`doc/**`、`.workbuddy/**`、`.cnb/agent-out/**`）。

并且**门禁必须在「最近一次有构建输入变化的 main」上实测全绿**；若当前 main HEAD 相对那次只多了非构建输入，
可沿用该次判据行，但**必须在日志里写明两个 sha 与 diff 清单**。

### 6.2 为什么不能要求「basis 字面等于合入后的当前 main HEAD」（自引用悖论）

矩阵文件本身必须落在 main 上（「产出必须落仓」）。设矩阵内容为 `C`、其中含 `basis = B`；
把 `C` 提交为提交 `X`，则 `sha(X) = H(tree(C), parents, meta)`，而 `tree(C)` 包含 `B`
⇒ `H` 是 `B` 的函数。要求 `B == main HEAD == H`，即 **`B = H(B)`** ——
「内容包含自身哈希的固定点」，密码学哈希下不存在。**每合入一轮，main 前进一格，basis 必然落后一格。**

唯一的绕法是「把 basis 存进不参与哈希的载体」（`git tag` / `git notes` / CI 元数据），
但那会让 basis **从仓库正文里消失**，而 basis 的全部价值正是让下一个 agent **读文件就知道这批结论是对着哪版代码做的**；
且 `scripts/selftest-gates.sh` §11 的 `VERDICT_MATRIX_CONSISTENCY_GATE` 要求
`verdict-vN.json` 的 `basis` 值**作为子串出现在矩阵前 20 行**，绕法会撞门禁。
⇒ **不要为了满足字面条件而牺牲跨 agent 可读性。**

### 6.3 历史

- v13（basis=`c74249e`）与 v14（basis=`7c23a35a`）两轮都因此被驳回一次；
- v15（basis=`c241979` = 分叉时的 main HEAD = M14）按 6.1 的口径收口，并在合入所得的 M15 上重跑门禁全绿。
