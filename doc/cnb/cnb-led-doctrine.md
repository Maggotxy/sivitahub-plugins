# CNB 主导原则（本 skill 的最高原则）

> **任何 agent 加载本 skill 后，第一件要接受的事实是：CNB 主导，你只做调度与配合。**
> 这条不随 agent 身份变化——WorkBuddy AI、Qoder、Codex、Cursor、Claude Code、任何 coding agent，一视同仁。
> 写在本文件里的原因：SKILL.md 是**唯一所有 agent 都会加载的东西**；写在某个会话的 memory 里，下个 agent 看不到。

---

## 1. 为什么必须这样（根因，不是偏好）

多 agent 协作失败的真实形态只有一个：**产出留在各自的会话里。**

| 症状 | 根因 |
|---|---|
| 上个 agent 刚分析完，下个 agent 从零重做 | 结论只存在于会话上下文里，没有落到任何持久位置 |
| 本地有一堆改动，云端仓库还是旧的 | 「改代码」发生在本地，「真相」在云上，两边分叉 |
| 三个人说三套「下一步该做什么」 | 没有单一账本记录「现在到哪一步了」 |
| 同一个 bug 被修了三遍，或者三遍都没修 | 没人知道别人修过 |

**CNB 主导就是把这四个症状一次消掉**：把「唯一真相源」和「唯一执行体」都放到 CNB 上，
于是「谁做的」「做到哪了」「下一步做什么」都变成**读仓库**就能回答的问题，而不是「问某个 agent 记不记得」。

**推论（很重要）**：本地 agent 的会话是**易失的**，CNB 仓库是**持久的**。
所以任何结论如果不进仓，就等于没发生。这不是流程洁癖，是防止重复劳动的唯一办法。

---

## 2. 分工线：能不能碰到内网 / 硬件

这是唯一的分界线，不是「谁更聪明」。

```
能碰到内网 / 硬件 / 本地文件  →  本地 agent 做（CNB 做不到）
碰不到                        →  CNB 做（本地不该做：token 消耗大、且 CNB 更便宜更可复现）
```

| 事项 | 归属 | 原因 |
|---|---|---|
| 读大文件、写长文档、写复杂脚本、做分析推理、设计实验 | **CNB** | 本地做这些 token 消耗大且不可复现 |
| 编译 / 构建 / 打包 / 部署到公网 | **CNB** | 本地环境不一致；CNB 每次都是干净环境 |
| 实现代码、跑测试、出报告 | **CNB（NPC）** | 每个任务独立分支 + PR + 结构化证据 |
| SSH 进内网机器、读生产机状态 | **本地** | CNB 是公有云构建平台，没有到私网的路由 |
| 碰真实硬件（NPU/GPU/串口/设备） | **本地** | 同上 |
| 拿本地文件 / 本地凭据 / 本地磁盘 | **本地** | CNB 设计上「零本地凭据」 |
| 把私有数据「运进」云（首次播种） | **本地** | 只有本地有那份数据 |

> ⚠️ **「零本地凭据」指的是云侧**：外部系统（EdgeOne/COS/Cloudflare）的凭据不进本地、不进代码库，
> 存私有密钥仓 + `imports:` 注入。**派发用的 CNB 个人令牌是本地凭据，这是允许的**——
> 它是「调度」这个动作的凭据，不是「执行」的凭据。

---

## 3. 三个角色（写死，不要临场发明）

```
┌─ CNB 云（主导 / 执行体 / 唯一真相源）────────────────────┐
│ 仓 main        = 唯一真相源（代码 + 规格 + 差集 + 世界状态）│
│ Planner NPC    = 决定下一轮派什么任务（本地不得代替）      │
│ Executor NPCs  = 并行执行，各自独立分支 + PR + 证据文件    │
│ 流水线编译门    = 唯一机械判据                            │
└──────────────────────────────────────────────────────┘
          ▲ 派发 tasks.json                │ PR + agent-out/*.json
          │                                ▼
┌─ 本地 agent（调度 / 验收 / 补位）────────────────────────┐
│ 1. 派发（orchestrator.py / loop_planner.py）             │
│ 2. 验收（compare 的 path + 读证据文件，自己复核一遍）      │
│ 3. 合 PR（编排器不会自动合）                              │
│ 4. 触发编译门（门不绿不派下波）                            │
│ 5. 做 CNB 做不到的事（SSH / 硬件 / 运数据上云）            │
│ 6. 把新事实写回 state/world-state.json                    │
└──────────────────────────────────────────────────────┘
```

**本地 agent 明确不做的事**：
- 不代替 Planner 判断「下一步派什么」（那是 CNB 的活；本地只转发和执行）
- 不把代码只改在本地
- 不把结论只留在会话里
- 不凭记忆声称「这个我修过了」

---

## 4. 五条铁律

1. **单一真相源**：代码、规格、差集、世界状态都在 CNB 仓里。本地检出可随时丢弃重建。
2. **产出必须落仓**：结论 / 清单 / 代码 / 证据 → 写进仓或 `state/world-state.json`。**留在会话里 = 不存在。**
3. **判断由 CNB 做，动作由本地做**：本地不产生判断，只产生动作（派发 / 验收 / 补位）。
4. **只认机械判据**：编译门退出码、`src/main` 里的真实调用点、行为级测试。
   不认「文档已更新」「DDL 已建表」「枚举已定义」「单元测试通过但无消费者」。
5. **不推翻已锁决策**：`doc/cnb/decisions-locked.md` 只能由人类解锁。

---

## 5. 跨 agent 交接协议（通用版）

任何 agent（无论来自哪个 IDE）接手一个 CNB 主导的项目，**开场五步**：

```
1. 读 <repo>/doc/cnb/GOAL.md            目标 + 验收口径 + 防失控
2. 读 <repo>/doc/cnb/decisions-locked.md 哪些已锁、不许重开
3. 读 <repo>/state/world-state.json      round / evidence / unverified / next_action
4. 读 <repo>/doc/cnb/gap-registry.md     差集：需求 → 任务 id → 状态
5. 列 open PR：GET /{repo}/-/pulls       有没有上一轮没合完的
```

**五步没走完不要写代码。** 走完这五步，任何一个 agent 都能接着干，不需要上一个 agent 的会话。

### 交接账本：`state/world-state.json`

这是**唯一**的跨 agent 交接载体。字段语义：

| 字段 | 谁写 | 语义 |
|---|---|---|
| `round` / `max_round` | Planner | 已完成轮次 / 轮次上限（防无限烧钱） |
| `evidence[]` | 任何 agent | **只放实测**（grep 命中、测试输出、构建退出码），每条带 `source` 与证据等级 |
| `unverified[]` | 任何 agent | 待验证假设。**不得当事实用** |
| `author_claimed_pending_verification[]` | 本地 agent | NPC 自称完成、本地尚未复核的。复核通过才升到 `evidence[]` |
| `next_action` | 任何 agent | 下一个动作（一句话） |
| `updated_by` / `updated_at` | 任何 agent | 谁写的、什么时候。不写这两个字段，下个 agent 无法判断新鲜度 |

**规则**：推算写进 `unverified[]`，实测写进 `evidence[]`。**不要把推算混进 evidence**——
这是这类项目最常见的腐化方式。

---

## 6. 反模式（都是实测踩过的）

| 反模式 | 后果 | 正确做法 |
|---|---|---|
| 结论只写在会话里 | 下个 agent 从零重做 | 写进仓 / world-state |
| 本地改代码不推云 | 云端分叉，构建门跑的是旧代码 | 本地只做「运数据上云」，改代码派 NPC |
| 本地自己拆任务 | 违反主导原则；且本地上下文比云上贵 | 派 Planner NPC 产出 `tasks.json` |
| 用 `pr_files` 查路径白名单 | 只给文件名 ⇒ 全是误报 | 用 `compare` 的 **`path`** 字段 |
| 以为 NPC 卡死就重派 | 实测 NPC 42 分钟零提交但 PR 已开 | 先查 `/-/git/commits/<branch>` 和 `/pulls` |
| 把「文档已更新」当达成 | 有的项目审计文档系统性拔高 | 只认 `src/main` 里的调用点 |
| 一次派完不等合 PR | 下游看不到上游代码 | 一波一合，合完再派下一波 |
| 关 PR 用 PUT | 失败 | `PATCH /-/pulls/{n} {"state":"closed"}` |
| 用定时 Automation 做递归编排 | 定时器跟实际节奏永远对不齐 | 用项目级 Stop hook + `GOAL:` 前缀 |

---

## 7. 「这个项目已经 CNB 主导了吗」——六项体检

一个新项目要落地 CNB 主导，检查这六项：

| # | 检查项 | 判据 |
|---|---|---|
| 1 | 代码在 CNB 仓里 | `GET /{repo}/-/git/contents/?ref=main` 非空 |
| 2 | 有目标文件 | 仓内 `doc/cnb/GOAL.md` 存在，且含**机械可查**的验收口径 |
| 3 | 有已锁决策 | 仓内 `doc/cnb/decisions-locked.md` 存在 |
| 4 | 有差集登记 | 仓内 `doc/cnb/gap-registry.md` 存在（需求 → 任务 id → 状态） |
| 5 | 有世界状态账本 | 仓内 `state/world-state.json` 存在且含 `round`/`evidence`/`next_action` |
| 6 | 有编译门 | 仓内 `.cnb.yml` 有 `push:` 编译门，判据是每工程 `BUILD_EXIT=0` 且 `SECONDS>0` |

六项齐了，任何 agent 接手都能自解释。缺任何一项，下个 agent 就要靠猜。

### 落地顺序（新项目起手）

```
1. 本地把私有数据运上云：建仓 → 本地 git push（带 token URL）或播种法
   —— 这是唯一必须本地做的事，因为只有本地有那份数据
2. 云上补控制面：doc/cnb/{GOAL,agent-protocol,gap-registry,decisions-locked}.md + state/world-state.json
3. 写 .cnb.yml 编译门，先触发一次确认能过（这一步失败率最高，先验）
4. 派 Planner NPC 产出完整差集 + 第一波 tasks.json
5. 逐波执行：派发 → 验收 → 合 PR → 触发编译门
6. 每轮把新事实写回 world-state.json
```

---

## 8. 已落地的案例

| 项目 | 仓 | 状态 |
|---|---|---|
| Cube Recall（MC 1.20.1 Forge mod） | `xzqkjmnbvc2026/cube-recall-mods` | 4 轮已跑；`doc/cnb/{GOAL,w3-gap,decisions-locked}.md` + `state/world-state.json` + `plan/**` |
| 希维塔大陆 SivitaHub（MC 26.2 Paper 插件服） | `xzqkjmnbvc2026/sivitahub` | 2026-10-02 落地；25 个 `rpg-*` 模块 + `doc/cnb/*` + `state/world-state.json` + 编译门 |
| 910A / Ascend 调研 | `xzqkjmnbvc2026/ascend-910a` | 4 份报告、4 个 NPC 任务并行 |

> 新项目落地时，**照抄这个结构**（`doc/cnb/` + `state/` + `.cnb.yml`），不要临场发明。
