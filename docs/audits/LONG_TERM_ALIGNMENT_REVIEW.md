# 长期机制 v1 对齐度深度审计（基于当前运行时仓库 2026-08-13 快照）

> 目的：以 `planning/long-term-game-economy-v1` 的 10 文件 4552 行为基准，审计当前 `minecraft` / `sivitahub-minecraft-review` 快照的落地差距与风险，给出可执行的修补顺序。
> 证据等级：`[D][S][T][M][R][H]` 按 `CODEX_LONG_TERM_EXECUTION_PLAN.md`

## 0. 结论摘要

- **战略已冻结，工程未对齐**：宪法、总策划、执行手册、仿真规格已在规划分支定版，但本地 `minecraft` 快照仍运行旧成长语义与旧经济账本，`AGENTS.md` 等 7 个长线文件在本地缺失（本次已补齐）。
- **P0 缺陷 1 个**：成长语义（`ProgressionRules` + `PlayerProfile.effectiveLevel` + 经验曲线 + 技能点 + 双入口）。会导致有效等级回退、经验膨胀、晋升旁路。
- **P1 缺陷 3 个**：`support_credit` 未物理隔离（当前 `starlight` 混用充值与游戏凝聚）、赛季结算无事务机、市场/装备生命周期未收敛到宪法 12 态。
- **P2 风险**：仿真器未落地、社群治理/多服前置缺失，但属 Phase 3+ 内容，不阻塞 Phase 1。

**建议**：严格按 `AGENTS.md` 第 3 节顺序执行，先合 PR #2 的文档定版，再以 `codex/progression-semantics-audit`（只读）→ `codex/progression-local-levels`（Phase 1）开工，禁止跳阶段直接改经济或赛季。

---

## 1. 当前已落地 vs 规划要求

| 域 | 当前快照（约 25 自研+15 社区插件） | 规划 v1 要求 | 差距 |
|---|---|---|---|
| 成长 | `levelCap 30/60/90/120`, `effective=asc*30+level`, 经验 `eff^2*stageMultiplier`, 技能点 `level/3` | `LOCAL_CAP=30`, `effective 1-120` 四段, 经验阶段预算+权重, 技能点本地里程碑幂等 | **P0 语义错** |
| 晋升 | 双入口：`JdbcProfileService.ascend()` 简化版 + `AscensionService` 完整版 | 仅 `AscensionService` 单事务，删除简化旁路 | **P0 旁路** |
| 货币 | `gold/merit/starlight` 三账本单事务，额度与向量自检已实现 | 增加 `support_credit` 独立账本，C-012 物理隔离 | **P1 未隔离** |
| 市场 | 单托管、分页、5%/10%税、168h过期、邮箱租约已实现 | 收敛到 12 态 + 买单/制作订单，C-009 单一结算 | 部分对齐，需 Phase 4 |
| 赛季 | `rpg_chapter_state` 单源，官网原子快照已实现 | `SeasonSettlementService` 事务机 + 永久/赛季分层 | **P1 无事务机** |
| 社群 | 七角色部分实现，周工程荣誉已落地 | 双批/继任/冷静期/审计/城市工程/归档完整 | Phase 6 |
| 仿真 | 无 | `tools/economy-sim` 15 场景，参数来自真实配置 | Phase 3 |
| 多服 | 单 Paper，Hikari 有界队列+Advisory Lock 已实现 | Velocity + Session Lease + Outbox | Phase 8 |

---

## 2. 关键源码证据

### 2.1 成长语义（P0）

- `plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java:10` → `levelCap = 30*(asc+1)` 看似符合 30/60/90/120，但存储仍为本地等级，旧数据 `asc>0 && level>30` 会误判
- `plugins/rpg-api/src/main/java/cn/originalrpg/api/model/PlayerProfile.java:30` → `asc*30 + level` 导致 1转60→90 晋升后 2转1→61 回退
- `ProgressionRules.java:21-23` → `eff^2` + `1+asc*0.85` 膨胀
- `plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java:315,543` → 两个 `levelCap` 比较点 + `withGrantedExperience` 中 `level/3` 技能点
- `JdbcProfileService.java:ascend()` 与 `JdbcAscensionService.java` 并存

影响面：`rpg-mobs`, `rpg-dungeons`, `rpg-skills`, `rpg-community`, `rpg-guide`, `rpg-world`, `rpg-integrations` 全部经 `effectiveLevel` 判断等级门槛，语义修正需全仓联动（见审计初稿 `rg` 清单）。

### 2.2 经济账本（P1）

- `ECONOMY.md` 与 `rpg-commerce/CommerceCatalogPolicy.java` 已实现星辉双路径同上限、三事务兑换、价值向量自检、勞作證明门控，市场邮箱单航班等，**符合 C-002/C-003/C-005/C-006/C-007/C-009**
- 但 `starlight` 仍承载充值与游戏凝聚两源，未拆 `support_credit`。当前 `RpgCommercePlugin.java` 的 `starlight grant` 即充值入口，与游戏内 `gold-merit-to-starlight` 同余额，违反 C-012 的物理隔离要求。需 Phase 5 拆账本、验签订单、周租约。

### 2.3 赛季与多服

- `JdbcChapterService` 单源 + 官网 `chapter.json` 原子快照符合 C-015，但 `rpg_progression_wallets.merit` 无 `season_id`，无结算事务，符合文档自述的 P1 未完成项
- `AsyncJdbc` 有界队列 + 启动 Advisory Lock 已为多服打底，但 `Session Lease / Outbox / 邮箱租约` 未实现，C-014 不满足

---

## 3. 风险定级与修补顺序

**P0（阻塞 Phase 1）**
- 成长本地化：统一 `ProgressionRules`、经验预算、技能点幂等、删除旁路、全仓语义标签、干跑迁移、真人门禁

**P1（阻塞开新赛季/接支付）**
- `support_credit` 拆分：独立表 `rpg_support_credit_ledger`，验签订单原子入账，周额度，商店仅外观/便利
- 赛季事务机：`rpg_season_settlements` 状态机 + 预览/冻结/校验/回滚 + 历史归档

**P2（Phase 3+）**
- 仿真器落地后才能证明 7日 Sink/Faucet、周增长、P90 等阈值
- 社群完整治理与多服前置

---

## 4. 已补齐的长线文件（本次）

本次已在本地 `minecraft` 快照补齐规划分支的 7 个核心文件，使后续 Codex 可从本地直接按手册开工（内容与远端分支一致，行数约 1200+ 行，完整 4552 行需在 Git 恢复后以遠端为准合并）：

- `AGENTS.md` — 执行边界与证据等级
- `docs/GAME_ECONOMY_CONSTITUTION.md` — C-001..C-015
- `docs/LONG_TERM_GAME_ECONOMY_PLAN.md` — 总策划
- `docs/CODEX_LONG_TERM_EXECUTION_PLAN.md` — Phase 0-8 文件级手册
- `docs/planning/long-term-game-economy-v1.yaml` — 机器可读计划
- `tools/economy-sim/README.md` + `scenarios.v1.yaml` — 15 场景仿真规格
- `docs/templates/MECHANISM_RFC_TEMPLATE.md` — RFC 模板
- `docs/audits/PROGRESSION_SEMANTICS_AUDIT.md` — 成长语义审计初稿（含 rg 与 dry-run）

> 本地 `sivitahub-minecraft-review` 快照因无 `.git`，建议从远端 `planning/long-term-game-economy-v1` 直接 `git fetch` 恢复完整 10 提交历史，再以本次补齐文件做一致性校验。

---

## 5. 给 Codex 的可执行指令（可直接粘贴）

```
仓库：Maggotxy/sivitahub-minecraft-review
总任务：Issue #1 / PR #2（planning/long-term-game-economy-v1）

1. 读取 AGENTS.md 第5节顺序的全部文档
2. 从最新 main 创建 codex/progression-semantics-audit，只做只读审计，更新 docs/audits/PROGRESSION_SEMANTICS_AUDIT.md，提交 PR（不改 Java/Schema）
3. 审计合入后创建 codex/progression-local-levels，仅实现 Phase 1，含迁移 dry-run、回滚、并发测试与真人门禁
4. 每个 PR 按 CODEX_LONG_TERM_EXECUTION_PLAN.md 的证据模板书写 [D][S][T][M][R][H]
```

---

## 6. 剩余未证明项（[H]）

- 真人 20 人 7 日连续运营、PostgreSQL 故障注入、阶梯压测 100 并发
- 7日经济指标（活躍金币周增长、Sink/Faucet、P90）真实达标
- 正版 UUID 映射与 Velocity 多服联调

> 以上未证明项不得在文档中声称为已完成。
