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
| 7 | 把新事实写回 `state/world-state.json` | 新增 `evidence[]` 条目 |

## 4. 交接的载体：`state/world-state.json`

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

## 5. 反模式（实测踩过，别再犯）

| 反模式 | 后果 | 正确做法 |
|---|---|---|
| 结论只写在会话里 | 下个 agent 从零重做 | 写进本仓 / world-state |
| 用 `pr_files` 查路径白名单 | 只给文件名 ⇒ 全是误报 | 用 `compare` 的 `path` 字段 |
| 以为 NPC 卡死就重派 | 实测 NPC 42 分钟零提交但 PR 已开 | 先查 `/-/git/commits/<branch>` 和 `/pulls` |
| 把「文档已更新」当达成 | 这个项目的审计文档系统性拔高 | 只认 src/main 里的调用点 |
| 一次派完不等合 PR | 下游看不到上游代码 | 一波一合，合完再派下一波 |
| 关 PR 用 PUT | 失败 | `PATCH /-/pulls/{n} {"state":"closed"}` |
