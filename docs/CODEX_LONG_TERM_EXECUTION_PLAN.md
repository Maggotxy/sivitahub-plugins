# Codex 文件级执行手册（长期机制 v1）

> 本手册是可直接交给 Codex 的实施合同，精确到分支、文件、迁移、测试与证据。

## 0. 通用规则

- 先读 `AGENTS.md` → 宪法 → 总策划 → 本手册 → yaml → ROADMAP → 审计初稿
- 一个 Phase 一个分支、一个 PR，禁止混入无关改动
- 六级证据：`[D] Design / [S] Source / [T] Test / [M] Simulation / [R] Runtime / [H] Human`
- 危险旧 API 白名单外一律删除，禁止 `CallerRunsPolicy`、主线程 JDBC、内存锁充当分布式锁

## Phase 0 — 机制目录与 Kill Switch（不改数值）

- 分支：`codex/mechanism-registry`
- 产出：`rpg-core` 机制注册表 + 每 Faucet 的 `enabled` 开关 + 指标埋点
- 涉及文件：`plugins/rpg-core/**`, `plugins/rpg-economy/**`, `plugins/rpg-commerce/**`
- 测试：配置缺失/非法值启动失败；开关关闭时对应来源零产出
- 影子模式：先记录将冻结对象，不实际冻结

## Phase 1 — 成长语义本地化（P0）

- 分支：`codex/progression-local-levels`（审计分支为 `codex/progression-semantics-audit` 只读）
- 目标模型：本地 1-30 / 有效 1-120 四段，经验阶段预算+权重
- 关键文件：
  - `plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java`
  - `plugins/rpg-api/src/main/java/cn/originalrpg/api/model/PlayerProfile.java`
  - `plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java`
  - `plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcAscensionService.java`
  - `plugins/rpg-mobs/**`, `rpg-dungeons/**`, `rpg-skills/**`, `rpg-community/**`, `rpg-guide/**`, `rpg-world/**`, `rpg-integrations/**`
- 迁移：
  ```sql
  -- dry-run 先行
  SELECT player_uuid, ascension, level FROM rpg_profiles WHERE ascension>0 AND level>30;
  -- 禁止 level % 30 猜测，无法唯一映射进 rpg_progression_anomalies
  ```
  新增 `rpg_progression_anomalies` + 回滚脚本，前后摘要比对
- 测试：晋升后有效等级不回退、经验曲线单调、技能点幂等、并发晋升仅一次成功
- 真人门禁：创角→满级→晋升→回退校验→怪物/副本门槛一致

## Phase 2 — Economy V2 与物品生命周期

- 分支：`codex/economy-v2-lifecycle`
- 12态状态机 + 唯一 `item_id` 索引 + 托管结算统一化
- 涉及：`rpg-items/**`, `rpg-market/**`, `rpg-economy/**`, `rpg-core/persistence/*Wallet*`
- 测试：复制/重放/箱子/漏斗/丢弃隔离；市场双花竞争

## Phase 3 — 经济仿真器

- 分支：`codex/economy-sim-v1`
- 规格见 `tools/economy-sim/README.md`，场景见 `scenarios.v1.yaml`
- 要求：价格/掉率/额度从真实 `config.yml` 与数据库读取，禁止仿真私参漂移
- 门禁：15 场景全部通过，输出 Sink/Faucet、周增长、库存龄报表

## Phase 4 — 装备退出与市场 V2

- 分支：`codex/equipment-exit-market-v2`
- 落分解/献祭/传承/调谐，市场增加买单与制作订单
- 评估浮动销毁需独立 RFC，不得默认启用

## Phase 5 — support_credit 分离与软赛季结算

- 分支：`codex/support-credit-season-settlement`
- 独立 `support_credit` 账本 + 验签订单 + 周额度；`SeasonSettlementService` 事务机
- 测试：换季中途失败重放、幂等、回滚、历史归档校验

## Phase 6 — 文明社区治理

- 分支：`codex/community-governance`
- 七角色 + 双批 + 继任 + 冷静期 + 审计 + 城市工程 + 世界归档

## Phase 7 — 非副本终局与内容管线

- 分支：`codex/endgame-pipeline`
- 图鉴/轮换词缀/竞速/生产管线，严禁低概率冒充深度

## Phase 8 — 正版 UUID 与多服

- 分支：`codex/uuid-velocity`
- UUID 映射幂等 + Session Lease + Outbox + 邮箱租约 + Advisory Lock
- 故障注入：PostgreSQL 分区、节点失联、重复领取

## PR 证据模板（每 PR 必写）

```
Goal / Non-goals
Current facts (commit / schema version)
Constitution invariants (C-xxx)
Data migration (dry-run / forward / rollback)
Failure modes
Tests ([T] + [M] + [R] when applicable)
Runtime evidence (TPS/MSPT/JDBC/log)
Remaining unproven items ([H] etc.)
```

## 完成定义

- 代码完成：`[D][S][T]` 齐全
- 运营就绪：`[M][R][H]` 齐全，且真实 7日指标达标
