# 延后表逐条判定（Deferred Tables Judgement）

> 适用范围：`scripts/check-ddl-consumers.sh`（G-301 DDL 消费者门禁）里登记的**豁免表**。
> 本页把「豁免」从模糊状态变成可审计事实：每张表给出 Phase / 设计意图（引文档原文）/ 当前无消费者的真实原因 / 建议处置。
>
> 判定基线：`2026-10-02`，分支 `agent/W3-A4-exempt-tables-judgement`。
> **W6-A1 更新（`agent/W6-A1-pending-tables-wiring`）**：§0 里剩下的 4 张 PENDING 表已全部接线，
> `scripts/check-ddl-consumers.sh` 的 EXEMPT 现为空；逐条接线事实见 §2 的「W6-A1 接线结果」小节。
> DDL 真相源：`plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java`（79 张 `CREATE TABLE IF NOT EXISTS rpg_*`）。
>
> **W3-A4 本任务只做判定与登记；接线由 W6-A1 完成（本页已同步）。**

## 0. 现状刷新：8 条豁免里 4 条已失效

`check-ddl-consumers.sh` 首跑（W1-A5，75 表）登记了 8 张豁免。此后第二波（W2）完成了 4 张表的接线，
但脚本里的豁免条目没有同步删除 —— 这正是本页要修掉的「模糊状态」。

| 表 | 首跑（W1）| 现在（W3）| 真实消费者 |
|---|---|---|---|
| `rpg_outbox` | 豁免 | **已接线** | `JdbcOutboxRegistry`（INSERT）、`OutboxDispatcher`（SELECT/UPDATE） |
| `rpg_session_leases` | 豁免 | **已接线** | `JdbcSessionLease`（SELECT，读侧） |
| `rpg_community_approvals` | 豁免 | **已接线** | `JdbcCommunityService`（INSERT/SELECT/DELETE，G-103） |
| `rpg_season_settlements` | 豁免 | **已接线** | `JdbcSeasonSettlementService`（INSERT/SELECT/UPDATE，G-101） |
| `rpg_support_credit_accounts` | 豁免 | **W6-A1 已接线** | `JdbcSupportCreditService`（INSERT/UPDATE/SELECT，C-012 独立账本余额） |
| `rpg_support_credit_ledger` | 豁免 | **W6-A1 已接线** | `JdbcSupportCreditService`（INSERT/SELECT，幂等流水） |
| `rpg_market_buy_orders` | 豁免 | **W6-A1 已接线** | `JdbcMarketBuyOrderService`（INSERT/SELECT/UPDATE，Phase 4 买单簿） |
| `rpg_progression_anomalies` | 豁免 | **W6-A1 已接线** | `JdbcProgressionAnomalyService` + `JdbcProfileService`（INSERT/UPDATE/SELECT/DELETE） |

> 表数从首跑 75 → 现在 79，是 W2 各 PR 新增了 4 张表（`rpg_mechanism_switches`、`rpg_season_settlement_gate`、
> `rpg_season_settlement_transitions`、`rpg_season_settlement_progress` 等），与本页判定无关。

## 1. 判定口径

每张表三个字段：

- **Phase**：来自 `AGENTS.md §3` 执行顺序 + `docs/planning/long-term-game-economy-v1.yaml` 的 phase id。
- **真实原因**：二选一 ——
  - `落库先行（schema-ahead）`：DDL 先落，服务/消费者属未来 Phase，设计仍然有效 ⇒ **保留待接线**。
  - `能力已废弃（abandoned）`：设计已被其它机制取代，或机制本身被锁死 ⇒ **保留 DDL 但显式标注已废弃**。
- **建议处置**：`接线` / `保留待接线` / `删表`。

> 注意：本任务**禁止删表、禁止改 DDL**。任何「删表」建议只写入本页与任务清单，落地需单独 PR + 人类评审。

## 2. 逐条判定

### 2.1 `rpg_support_credit_accounts` — Phase 5

- **设计意图（原文）**：`docs/GAME_ECONOMY_CONSTITUTION.md` C-012「现实付费隔离 —
  `support_credit`（现实付费积分）为独立账本，物理隔离于 `gold/merit/starlight`」；
  `docs/CODEX_LONG_TERM_EXECUTION_PLAN.md` Phase 5「独立 `support_credit` 账本 + 验签订单 + 周额度」。
- **真实原因**：`落库先行`。`docs/audits/PHASE2_3_5_EVIDENCE.md` 原文自述「新增 `rpg_support_credit_accounts` /
  `rpg_support_credit_ledger`（独立账本……）」，同页「剩余（Round 3+）」列明「Phase 5 验签订单服务……需各 PR 独立落地」。
  即：**表已落，账本服务与验签订单消费者尚未写**。
- **建议处置**：`保留待接线`。这是宪法 C-012 的物理载体，删掉会让隔离要求无处落地。
- **判定证据**：
  ```bash
  $ grep -rn --include='*.java' 'rpg_support_credit_accounts' plugins tools
  plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:987:                CREATE TABLE IF NOT EXISTS rpg_support_credit_accounts (
  tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/SchemaManifest.java:87:            "rpg_support_credit_accounts",
  ```
  → 仅 DDL 定义 + 迁移清单，`src/main` 零 SQL 消费者。

### 2.2 `rpg_support_credit_ledger` — Phase 5

- **设计意图（原文）**：同 2.1（C-012）；`docs/audits/LONG_TERM_ALIGNMENT_REVIEW.md:62`
  「`support_credit` 拆分：独立表 `rpg_support_credit_ledger`，验签订单原子入账，周额度，商店仅外观/便利」。
- **真实原因**：`落库先行`。`SchemaManifest` 已同步唯一键 `idempotency_key`
  （`keys.put("rpg_support_credit_ledger", List.of(List.of("idempotency_key")))`），
  说明**幂等账本的 schema 契约已定，但写入方（验签订单服务）尚未实现**。
- **建议处置**：`保留待接线`。与 2.1 成对，接线时两者必须同一 PR（余额表 + 流水表同事务，符合宪法「余额表 + `idempotency_key` + 审计流水」）。
- **判定证据**：
  ```bash
  $ grep -rn --include='*.java' 'rpg_support_credit_ledger' plugins tools
  plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:994:                CREATE TABLE IF NOT EXISTS rpg_support_credit_ledger (
  tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/SchemaManifest.java:88:            "rpg_support_credit_ledger",
  tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/SchemaManifest.java:150:        keys.put("rpg_support_credit_ledger", List.of(List.of("idempotency_key")));
  ```

### 2.3 `rpg_market_buy_orders` — Phase 4

- **设计意图（原文）**：`docs/CODEX_LONG_TERM_EXECUTION_PLAN.md` Phase 4「落分解/献祭/传承/调谐，**市场增加买单与制作订单**」；
  `docs/LONG_TERM_GAME_ECONOMY_PLAN.md:37`「单一托管结算，`item_id` 唯一索引，`PREPARED/ACTIVE` 互斥，**买单与制作订单共用账本**」。
- **真实原因**：`落库先行`。`docs/audits/PHASE4_6_EVIDENCE.md` 自述「新增 `rpg_market_buy_orders`
  （order_id PK, buyer, matcher, price, quantity, status）」，同页「剩余」列明「Phase 4 买单撮合事务……需后续 PR 补 [M][R][H]」。
  API 侧模型已存在（`plugins/rpg-api/.../model/MarketOrder.java`：`PREPARED/ACTIVE/FILLED/CANCELLED/EXPIRED`），
  但**没有任何服务读写该表**（`plugins/rpg-market` 全模块对该表零命中）。
- **建议处置**：`保留待接线`。注意宪法 C-009「单一结算」约束：接线时必须复用现有市场税/托管模型，不得建第二套市场。
- **判定证据**：
  ```bash
  $ grep -rn --include='*.java' 'rpg_market_buy_orders' plugins tools
  plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:1005:                CREATE TABLE IF NOT EXISTS rpg_market_buy_orders (
  tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/SchemaManifest.java:89:            "rpg_market_buy_orders",
  ```

### 2.4 `rpg_progression_anomalies` — Phase 1

- **设计意图（原文）**：`docs/CODEX_LONG_TERM_EXECUTION_PLAN.md`（Phase 1 迁移要求）
  「禁止 `level % 30` 猜测，无法唯一映射进 `rpg_progression_anomalies`」；
  逐字引用自 `docs/audits/PROGRESSION_SEMANTICS_AUDIT.md:98`，
  同页 §3「无法唯一映射的进入 `rpg_progression_anomalies` 人工清单」；
  该表列含 `reason`/`detected_at`，定位是**迁移期间的人工复核清单**。
- **真实原因**：`落库先行`（更准确说是「一次性迁移工具的目标表尚未写」）。
  Phase 1 的语义迁移（`ascension>0 AND level>30` 的旧档案映射）必须在**真实玩家库**上跑，
  本仓只有 schema 与审计文档，**迁移脚本/写入口从未实现**。
  因此它不是「能力废弃」，而是「`[D]` 级设计待 `[T]`/`[R]` 落地」。
- **建议处置**：`保留待接线`，但**接线时点应绑定到真人验收窗口**（`AGENTS.md §6`：`[H]` 不可省）。
  若人类最终决定不做存量档案迁移（例如生产库经 dry-run 确认无异常行），则应改为 `ABANDONED`，见 §4。
- **判定证据**：
  ```bash
  $ grep -rn --include='*.java' 'rpg_progression_anomalies' plugins tools
  plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:917:                CREATE TABLE IF NOT EXISTS rpg_progression_anomalies (
  tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/SchemaManifest.java:80:            "rpg_progression_anomalies",
  ```

## 3. 处置建议汇总

| 表 | Phase | 真实原因 | 建议处置 | 目标波次 |
|---|---|---|---|---|
| `rpg_support_credit_accounts` | 5 | 落库先行 | **已接线（W6-A1）** | 完成 |
| `rpg_support_credit_ledger` | 5 | 落库先行 | **已接线（W6-A1，与上表同 PR）** | 完成 |
| `rpg_market_buy_orders` | 4 | 落库先行 | **已接线（W6-A1）** | 完成 |
| `rpg_progression_anomalies` | 1 | 落库先行 | **已接线（W6-A1 写侧 / W9-A2 读侧）**，[H] 复核出口运行期可达 | 完成（真人复核留待 [H]） |

**无一张判定为「删表」；无一张判定为「能力已废弃」。**

### 3.1 W6-A1 接线结果（本轮实测）

4 张表全部从 PENDING 移除，门禁 EXEMPT 清空（`DDL 表数: 79` / `豁免清单: 0 条`）。逐条证据：

```bash
$ grep -rn --include='*.java' 'rpg_support_credit_accounts' plugins | grep '/src/main/'
plugins/rpg-core/src/main/java/.../JdbcSupportCreditService.java:146  "SELECT balance FROM rpg_support_credit_accounts WHERE player_uuid = ?"
plugins/rpg-core/src/main/java/.../JdbcSupportCreditService.java:156  INSERT INTO rpg_support_credit_accounts (player_uuid, balance) VALUES (?, 0)
plugins/rpg-core/src/main/java/.../JdbcSupportCreditService.java:166  SELECT balance FROM rpg_support_credit_accounts WHERE player_uuid = ? FOR UPDATE
plugins/rpg-core/src/main/java/.../JdbcSupportCreditService.java:179  UPDATE rpg_support_credit_accounts

$ grep -rn --include='*.java' 'rpg_market_buy_orders' plugins | grep '/src/main/'
plugins/rpg-core/src/main/java/.../JdbcMarketBuyOrderService.java   （INSERT/SELECT FOR UPDATE/UPDATE ×多个调用点）

$ grep -rn --include='*.java' 'rpg_progression_anomalies' plugins | grep '/src/main/'
plugins/rpg-core/src/main/java/.../JdbcProgressionAnomalyService.java  （INSERT/UPDATE/SELECT/DELETE）
plugins/rpg-core/src/main/java/.../JdbcProfileService.java             （读路径条件写：只在读到异常档案时落行）
```

| 表 | 真实调用点（src/main） | 行为级测试 | 接入方式 |
|---|---|---|---|
| `rpg_support_credit_accounts` | `JdbcSupportCreditService`（余额读/建/锁/更新） | `JdbcSupportCreditServiceTest`（7 例） | `SupportCreditService` 注册进 `RpgCorePlugin`；`/rpg` 单独一行展示余额 |
| `rpg_support_credit_ledger` | `JdbcSupportCreditService`（幂等插入 + 重放查询） | 同上 | 幂等键唯一约束即去重依据 |
| `rpg_market_buy_orders` | `JdbcMarketBuyOrderService`（挂/撮合/撤/过期/查询） | `JdbcMarketBuyOrderServiceTest`（9 例） | `MarketBuyOrderService` 注册进 `RpgCorePlugin`；`/market buyorder` 三子命令 |
| `rpg_progression_anomalies` | `JdbcProgressionAnomalyService` + `JdbcProfileService` 读路径 | `JdbcProgressionAnomalyServiceTest`（8 例） | `ProgressionAnomalyService` 注册进 `RpgCorePlugin`；判定口径唯一实现在 `ProgressionRules.classifyAnomaly` |

**C-012 物理隔离的机械证明**：`JdbcSupportCreditService` 里没有任何一句 SQL 引用
`rpg_accounts` / `rpg_merit_ledger` / `rpg_credit_ledger`；`SupportCreditTarget` 的禁止清单
（装备/材料/强化辅助物/经验/体力/掉率/赛季层调谐/战力）在开事务**之前**就被拒，
被拒请求连一行流水都不落（`JdbcSupportCreditServiceTest.forbiddenTargetIsRejectedBeforeAnyLedgerWrite`）。

**C-009 单一托管的机械证明**：`JdbcMarketBuyOrderService` 不新建余额/流水/投递表 ——
买单托管写的是 `rpg_accounts`，成交流水写的是 `rpg_ledger`，交付写的是 `rpg_market_deliveries`，
与卖单 `JdbcMarketService` 完全同一套表（`JdbcMarketBuyOrderServiceTest.fillingSettlesTaxedPayoutAndDeliversThroughTheSameMailbox`）。

**[H] 门禁的落地方式**（W9-A2 补齐读侧）：`acknowledge(playerId, reviewer, note)` 是唯一签署路径，
`reviewer` 为空即拒绝（没有署名的复核不算复核），通过后把 `reviewer` / `review_note` /
`reviewed_at` 写在原行上。自动流程只写不签，且**不据此发补偿**（C-013）。

W9-A2 之前，写侧已接线但**读侧零调用点**：接口的三个读/签署方法只有定义与自身实现，
异常记录只进不出，[H] 门禁在运行期不可达。现在读侧由 `AnomalyReviewCommand`
（`/rpgadmin anomaly list|ack`）接线，权限沿用 `rpgadmin` 命令级 `rpg.admin`；
完整闭环图与幂等口径见 `docs/ECONOMY.md` §「[H] 复核闭环」。


四张表全部是**能力仍然有效、只是消费者晚于 schema 落地**的「落库先行」。

> 这正是门禁应当区分两个状态的原因：`PENDING`（待接线，计划内）与 `ABANDONED`（已废弃，计划外）
> 在设计上完全不同 —— 前者应当在未来被删掉豁免条目，后者应当被长期保留并标注不可用。

## 4. 已废弃分类（`ABANDONED`）当前为空

本次逐条判定**没有得出「能力已废弃」的表**。为落实任务要求 ③，
`scripts/check-ddl-consumers.sh` 仍然新增了显式分类槽位 `ABANDONED`：
命中该分类的表会以 `[已废弃]` 而非 `[待接线]` 出现在门禁输出里，
并且**同样计入 `DDL_CONSUMER_GATE=RED` 的未接线计数**（豁免不等于放行，只是分类可审计）。

未来若出现以下情形，应把表移入 `ABANDONED`：
- 设计被另一机制取代（例：若 `rpg_progression_anomalies` 被通用 ops 异常表取代）；
- 机制被 `decisions-locked.md` 锁死不再实施；
- 真人验收确认不需要该能力。

## 5. 维护约定

1. 表接线后**必须**从脚本豁免清单删除对应条目 —— 删除动作与消费者 PR 同一提交，避免本页 §0 的「失效豁免」再次出现。
2. 新增豁免**必须**同时在本页开一节，写清 Phase / 设计意图原文 / 真实原因 / 建议处置 / grep 证据。
3. 判定为 `ABANDONED` 的表，在脚本里用 `分类|表名|理由|文档锚点` 四段式登记，禁止静默删除豁免。
