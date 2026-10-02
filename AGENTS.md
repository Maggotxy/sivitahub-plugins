# AGENTS — Codex 执行边界（希维塔长期机制 v1）

> 本文件是 Codex / 自动化 Agent 进入本仓库后的第一入口。
> 所有长期玩法、经济、赛季与社区改动必须服从 `docs/GAME_ECONOMY_CONSTITUTION.md`。

## 1. 最高约束

1. 宪法 `docs/GAME_ECONOMY_CONSTITUTION.md` 是最高机制约束，编号 `C-001` 起。
   任何新增机制违反宪法不得直接实现，必须先提交 `docs/templates/MECHANISM_RFC_TEMPLATE.md` 评审。
2. 禁止自行增加：新货币、新转数（MAX_ASCENSION 固定为 3）、第二套市场、第二套装备状态机、第二套等级账本。
3. 现实付费积分 `support_credit`（如需落地则映射为独立账本，与 `starlight` 物理隔离）不得连接：
   `gold / merit / starlight / 装备 / 材料 / 强化成功率 / 保护石 / 幸运符 / 经验 / 体力 / 掉率 / 赛季调谐 / 排行榜战力`。
4. 所有金币/功勋/星辉变动必须经过 `rpg-core` 账本 + `idempotency_key` + 审计流水，禁止内存补偿铸币。
5. 货币兑换图不得存在正价值闭环，启动自检以 `gold=1, merit=600, starlight=1400` 为参考向量。

## 2. 分支与 PR 纪律

- 不直接提交 `main`。一个 Phase 一个独立分支、一个 PR。
- 推荐分支命名见 `docs/planning/long-term-game-economy-v1.yaml`。
- 每个 PR 必须包含：
  `Goal / Non-goals / Current facts / Constitution invariants / Data migration / Failure modes / Rollback / Tests / Runtime evidence / Remaining unproven items`
  并以六级证据标注 `[D][S][T][M][R][H]`。
- 未取得 `[R] Runtime` 与 `[H] Human` 不得声称长期运营就绪。

## 3. 执行顺序（强制）

```
Phase 0  机制目录与 Kill Switch  （不改数值）
  ↓
Phase 1  成长语义本地化（LOCAL_LEVEL_CAP=30 / MAX_ASCENSION=3 / effective 1-120）
  ↓
Phase 2  Economy V2 与物品生命周期（12 态）
  ↓
Phase 3  经济仿真器（15 场景，参数来自真实配置）
  ↓
Phase 4  装备退出与市场 V2（分解/献祭/调谐/浮动销毁评估）
  ↓
Phase 5  support_credit 分离与软赛季结算事务
  ↓
Phase 6  文明社区治理（七角色/双批/继任/冷静期/审计/城市工程）
  ↓
Phase 7  非副本终局与内容管线
  ↓
Phase 8  正版 UUID 迁移与 Velocity 多服（Session Lease / Outbox）
```

Codex 第一任务是 **只读成长语义审计**，分支 `codex/progression-semantics-audit`，不得修改运行 Java / Schema / 经济配置。

第二任务是 `codex/progression-local-levels`，仅实现 Phase 1：
- 统一 `ProgressionRules`：`LOCAL_LEVEL_CAP=30`, `MAX_ASCENSION=3`, `effectiveLevel(asc, local)`, `experienceToNextLocalLevel(...)`, `totalExperienceForAscension(...)`
- 经验改为「阶段总预算 + 本地 1-29 归一化权重」
- `JdbcProfileService` 技能点按 `有效等级 /3` 改为 `本地里程碑 + 幂等补偿`
- 删除 `ProfileService.ascend()` / `JdbcProfileService.ascend()` 简化旁路，全部走 `AscensionService`
- 同步更新 `怪物/副本/技能/社群/引导/Placeholder/官网` 的语义调用点
- 数据库 dry-run：`SELECT * FROM rpg_profiles WHERE ascension>0 AND level>30`，禁止 `level % 30` 猜测，无法唯一映射进人工清单

## 4. 禁止事项

- 用低概率 / 超长经验冒充内容深度。
- 固定价格 NPC 无限回购可再生材料。
- 全服删档续命。
- 每个玩法造一种新货币。
- 内存锁称为跨节点锁。
- 只改 `season-id` 假装完成换季。

## 5. 必读顺序

1. 本文件 `AGENTS.md`
2. `docs/GAME_ECONOMY_CONSTITUTION.md`
3. `docs/LONG_TERM_GAME_ECONOMY_PLAN.md`
4. `docs/CODEX_LONG_TERM_EXECUTION_PLAN.md`
5. `docs/planning/long-term-game-economy-v1.yaml`
6. `docs/ROADMAP.md`
7. `docs/audits/PROGRESSION_SEMANTICS_AUDIT.md`

## 6. 证据等级

```
[D] Design     设计与 RFC
[S] Source     源码实现
[T] Test       自动化测试（含并发与故障注入）
[M] Simulation 仿真（15 场景）
[R] Runtime    真实 Paper + PostgreSQL 运行
[H] Human      真人长期验证
```

`有代码` 或 `单元测试通过` ≠ 运营就绪。

## 7. 回滚与补偿

- 退款必须引用原扣款 `idempotency_key` 并受唯一冲正约束。
- 补偿不得铸币，必须走审计流水。
- 自动系统只能冻结异常来源，不得自动改价或掉率。
