# GOAL — 希维塔大陆 SivitaHub 玩法机制补全

> 本文件是**所有 agent（WorkBuddy AI / Qoder / Codex / CodeBuddy NPC / 任何 coding agent）进入本仓后的第一入口**。
> 读本文件之前不要写任何代码。读完本文件后必须读 `state/world-state.json` 和 `doc/cnb/agent-protocol.md`。

## 0. 主导权（不可协商）

**CNB 是本项目的主导与唯一执行体。**

- 本仓 `main` 分支 = 唯一真相源。代码、玩法规格、差集登记、世界状态都在这里。
- 实现工作由 **CNB 云上的 NPC** 完成，每个任务走「独立分支 → PR → 结构化证据」。
- 本地 agent（WorkBuddy AI / Qoder / Codex / 任何 coding agent）**只做三件事**：
  1. 拆任务 / 派发（`orchestrator.py`、`loop_planner.py`）
  2. 验收（`compare` 的 `path` 字段查越界 + 读 `.cnb/agent-out/<id>.json` 的 evidence）
  3. 执行 CNB 做不到的事（SSH 进内网、碰真实硬件、拿本地文件）
- **本地 agent 不得**把代码只改在自己本地、不得把结论只留在自己的会话里、不得替 CNB 做「下一步派什么」的判断。
  任何产出要么进本仓，要么写进 `state/world-state.json`，否则等于没发生。
- 本地 agent 的会话是**易失的**，本仓是**持久的**。跨 agent 交接一律通过本仓 + `state/world-state.json`，不通过会话。

## 1. 目标

把希维塔大陆 25 个 `rpg-*` 插件里**「声明了但没接线」的玩法机制**补齐到可验收状态。

这个项目的系统性失败模式是：**枚举 / 表 / 注册点 / 状态机存在，但没有消费者**。
所以本目标的判据不是「文档说做完了」，而是「**src/main 里有真实调用点**」。

## 2. 验收口径（机械可查，不接受叙述）

一个机制算「已接线」，当且仅当同时满足：

1. **消费者存在**：对该机制的类名 / 表名 / 方法名全仓 grep `src/main`，调用点数 ≥ 1，且调用点不是「它自己」。
2. **有测试**：至少一条**事件级 / 行为级**测试（`@Test` 真的执行行为并断言结果），不是 `Files.readString + indexOf` 的源码字符串断言。
3. **编译门绿**：`.cnb.yml` 的全量编译门通过（每工程 `BUILD_EXIT=0` 且 `SECONDS>0`）。
4. **证据留痕**：任务产出 `.cnb/agent-out/<task-id>.json`，内含 `evidence.grep`（命令 + 命中行）与 `evidence.test`（命令 + 结果）。

**明确排除**（这些不算达成）：推算、应该、等价于、单元测试通过但无消费者、文档已更新、DDL 已建表、枚举已定义。

## 3. 数据流约束（宪法级）

- `docs/GAME_ECONOMY_CONSTITUTION.md` 是最高机制约束（`C-001` 起）。违反宪法的机制不得直接实现，必须先提 RFC。
- 所有金币 / 功勋 / 星辉变动必须过 `rpg-core` 账本 + `idempotency_key` + 审计流水。**禁止内存补偿铸币。**
- 禁止新增：新货币、新转数（`MAX_ASCENSION` 固定 3）、第二套市场、第二套装备状态机、第二套等级账本。
- 证据六级 `[D][S][T][M][R][H]`：`有代码` 或 `单元测试通过` ≠ 运营就绪。

## 4. 防失控（递归编排的硬约束）

1. **轮次上限**：`state/world-state.json` 的 `round` 达到 `MAX_ROUND`（当前 12）时，Planner 必须输出
   `verdict: {done:false, impossible:true, reason:...}` 并停止产出 `tasks.json`。
2. **每轮必须有新证据**：`evidence[]` 必须新增实测数字或实测 grep 结果。没有新证据就换假设，不得原样重跑。
3. **目标判定只看实测**：见 §2。
4. **不可逆动作白名单**：只允许改本仓源码 + 在 CNB 上跑流水线。其余（改生产机、删表、动 `lamb`）一律标 `REQUIRES_HUMAN`。
5. **生产机只读**：`lamb`（192.168.77.28）上的 `/home/lamb/minecraft` 是运行中的生产环境，**只读**。任何写入必须先拿到人类显式批准。

## 5. 本仓与其它位置的关系

| 位置 | 角色 | 谁可写 |
|---|---|---|
| 本仓 `main` | **唯一真相源** | CNB NPC 经 PR；人类/本地 agent 经 PR |
| `state/world-state.json` | 跨 agent 唯一交接账本 | Planner NPC 每轮更新 |
| `doc/cnb/*` | 目标、差集、已锁决策、协议 | 人类锁；NPC 只读 |
| `lamb:/home/lamb/minecraft` | 生产运行时（只读） | 仅人类 |
| 本地镜像 `D:\Development\sivitahub-server` | 只读研究副本 | 只读 |
| 本地 `D:\Development\sivitahub` | 本仓的本地检出（可丢弃） | 可重建 |
