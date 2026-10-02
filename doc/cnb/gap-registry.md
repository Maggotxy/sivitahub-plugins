# 差集登记 —— 需求 → 任务 id → 状态

> **差集 = 「设计说要有的」减去「src/main 里真的有消费者的」。**
> 本文件是 `2026-09-27` 那次全量代码核查（Qoder 会话 `e66e6e77`，7 路并行 + 人工复核关键项）
> 的落地版，**不完整**——第一轮 Planner NPC 的任务就是把差集补全为「全量机制 × 调用点」矩阵。
>
> 状态取值：`TODO` / `DISPATCHED` / `PR-OPEN` / `MERGED` / `VERIFIED` / `REJECTED` / `REQUIRES_HUMAN`

## A. P0 —— 已人工复核（不是子代理转述）

| id | 缺口 | 证据（file:line） | 状态 |
|---|---|---|---|
| G-001 | 五维「防御」+ 装备 `defense` 词条在 PVE/PVP **全部失效**，只对摔落/岩浆生效 | `rpg-combat/.../ExecuteListener.java:212-222` 只被 `:126` 的 `EntityDamageEvent` 调用；互砍走 `:130` 的 `EntityDamageByEntityEvent`（Bukkit 独立 HandlerList）。`DefensePolicyTest.java:25-26` 断言 ENTITY_ATTACK/PROJECTILE 应可防御 ⇒ 是接线 bug | **DONE** W1-A1 / PR#5 / `056b3e1` —— 接线到 `EntityDamageByEntityEvent`（`ExecuteListener.java:197`），幂等标记防双减，新增 `DefenseWiringTest`（4 例），并做负向对照（删掉调用点测试变红） |
| G-002 | Kill Switch 有表有注册点、**零消费者**，宪法 `C-005` 四件套缺一腿 | `rpg-core/.../persistence/MechanismRegistry.java:82 isEnabled()/:89 setEnabled()` 全仓零调用；只有 `RpgCorePlugin.java:101` 启动播种 | **DONE** W1-A2 / PR#7 / `cd097b8` —— 新增 `requireEnabled()` 闸门 + 7 个 faucet 接入 + `MechanismKillSwitchContractTest`（变异测试验证有效） |
| G-003 | 装备 HMAC 签名密钥**明文写进 config.yml**，无轮换 | `rpg-items/.../RpgItemsPlugin.java:106-124`（无 `RPG_ITEM_SIGNING_KEY` 时把密钥落盘） | **DONE** W1-A3 / PR#2 / `9aa2e2b` —— 新增 `EquipmentSigningMaterial`（env 唯一来源、缺失 fail-closed），支持当前+3 把退役密钥轮换，旧明文字段启动告警 |
| G-004 | 迁移 advisory lock **两把不是同一把**，离线工具可与在线节点并发 DDL | `tools/db-migration/.../MigrationEngine.java:205` 用 `0x5349564954414D47`；`rpg-core/.../Database.java:1064` 用 `(0x53495649,0x52504731)`。`ARCHITECTURE.md:67` 称「同一把锁」 | **DONE** W1-A4 / PR#6 / `ecae668` —— 工具侧改用 `pg_advisory_xact_lock(0x53495649, 0x52504731)`（与 `Database.java:20-21` 完全一致，已人工核对），删掉旧 bigint 形式，加 30s 等待上限 |

## B. P1 —— 「声明了但没接线」类

| id | 缺口 | 证据 | 状态 |
|---|---|---|---|
| G-101 | `SeasonSettlementService` **不存在**（宪法 `C-010` 未落地）；`rpg_progression_wallets` 无 `season_id` 列 | 只有 `SeasonSettlementState` 九态枚举，全仓唯一引用是它自己 | TODO |
| G-102 | Phase 8 多服前置四件套**只有 DDL**：`rpg_session_leases`、`rpg_outbox` 无 Java 引用 | 全仓 grep 零调用 | TODO |
| G-103 | Phase 6 七角色 / 双批是**枚举装饰**：`canApprove()` 零调用，`rpg_community_approvals` 无读写；`TREASURER/BUILDER/EVENT_HOST/RECRUITER` 零引用 | 全仓 grep 零调用 | TODO |
| G-104 | 邮箱交付冻结**漏 `InventoryCreativeEvent`**；游标槽不在标记扫描范围 ⇒ 断线时存在重复领取窗口 | `rpg-market/.../MarketCommand.java:1076-1085` | TODO |
| G-105 | 战斗总线被旁路：攻方增益在总线外加一次，随后又被攻方倍率**二次放大** | `rpg-skills/.../SkillsCommand.java:949,962` + `ExecuteListener.java:166` | TODO |
| G-106 | **血契按文档条件不可达成**：要求二转，但前置技能 `vanished-horizon` 要求 `minimum-ascension: 3` | `HiddenPathRules.java:18-20` + `rpg-skills/src/main/resources/config.yml:373-378` | TODO |
| G-107 | `AGENTS.md` 明令删除的 `ascend()` 旁路仍在；技能点仍按 `effectiveLevel/3`；「本地里程碑+幂等补偿」未实现 | `JdbcProfileService.java:304-326`（仅 `@Deprecated`）、`:569` | TODO |
| G-108 | 副本 `config.yml` 里 6 个 `world: dungeon_*` 键**无任何读取方**（实际世界名运行时生成） | 全仓 grep 零调用 | TODO |
| G-109 | 75 张表里约 10 张**只有 DDL 没有消费者**：`rpg_season_settlements`、`rpg_session_leases`、`rpg_outbox`、`rpg_market_buy_orders`、`rpg_support_credit_accounts/ledger`、`rpg_community_approvals`、`rpg_progression_anomalies` | DDL 与 src/main 对账 | TODO |

## C. P2 —— 文档失真（订正即可，不改行为）

| id | 缺口 | 状态 |
|---|---|---|
| G-201 | 装备实际签 schema **5** 而文档称 4 | TODO |
| G-202 | 稀有/史诗槽上限随基材上浮，与文档固定区间不符 | TODO |
| G-203 | legacy 多一道「必须持有稀有词缀」的隐藏前置 | TODO |
| G-204 | 占位符文档 11 实际 58；分支文档 18 实际 30；表数 62 实际 75 | TODO |
| G-205 | 外观价格标错模块 | TODO |
| G-206 | `#minecraft:quick_actions` 数据包**没纳入版本控制**却被 `verify-installation.sh` 硬依赖 | TODO |
| G-207 | 血月/袭击「每日最多 3 场」没有显式实现，只是上限除法的副作用 | TODO |
| G-208 | 商人净损失 10% 可被 YAML 调到 5% | TODO |
| G-209 | 星弹预警窗口 1.1–1.8s，超出承诺的 0.4–0.8s | TODO |

## D. 结构性门禁（掐死「声明未接线」这个模式）

| id | 缺口 | 状态 |
|---|---|---|
| G-301 | **每张 DDL 表必须在 `src/main` 里有引用点** —— 加一条 CI 门禁，否则该模式会持续复发 | **DONE** W1-A5 / PR#1 / `e8013ef` —— `scripts/check-ddl-consumers.sh` 接入 push 流水线；首跑 GREEN：75 张表 / 67 已接线 / 8 张已登记豁免 |
| G-302 | 7 个 `*ContractTest` 用 `Files.readString + indexOf` 断言**源码字符串顺序**，不执行行为，重构即失效 ⇒ `[T]` 级证据要打折；需替换为行为级测试 | TODO |
| G-303 | 全量差集矩阵：**每个机制 × 是否有消费者 × 是否有行为测试**，作为后续所有波次的唯一输入 | **DONE** W1-P1 / PR#3 / `fceea06` —— `plan/gap-matrix.md`（488 行：39 条机制矩阵 + 75 张表消费者矩阵 + 每条 grep 原文）+ `plan/tasks-w2.json` |

## E. 不可逆 / 需人类

| id | 事项 | 说明 |
|---|---|---|
| H-001 | 原版世界方形边界**仍缺 5435 个四角区块**，需无人维护窗口跑 `maintain-world-coverage.sh` | 生产机写操作，`REQUIRES_HUMAN` |
| H-002 | 本地镜像 `D:\Development\sivitahub-server` 无法编译（工程要 Java 25 + Gradle 9.1.0，本机只有 JDK 17/21） | 已由 CNB 云编译解决，见 `.cnb.yml` |
| H-003 | 上游不是 git 仓库 ⇒ 本地 git 基线 `daa9287` 是**唯一**能看出远程改了什么的依据 | 同步后跑 `git status --porcelain` + `git diff --ignore-cr-at-eol --numstat` |

## F. 构建自包含性（2026-10-02 编译门首验实测暴露）

| id | 缺口 | 证据 | 状态 |
|---|---|---|---|
| G-401 | **构建不自包含**：8 处 `compileOnly(files(...))` 指向 `server/runtime/plugins/` 与 `server/vendor/`，而这两条路径被 `.gitignore` 忽略 ⇒ **干净克隆无法编译** | 编译门首验 sn=`cnb-4r1-1k3tb2rk8`：`BUILD_EXIT=1 SECONDS=101`，首个失败点 `plugins/rpg-integrations/.../BetterHudGameplayHudService.java:4 error: package kr.toxicity.hud.api does not exist` | **DONE** W1-A0 / PR#4 / `80fafb6` —— 8 处 `compileOnly(files(...))` 全部改指 `libs/`；实测 fetch-deps 7/7 双校验通过 |
| G-402 | 缺 `scripts/fetch-deps.sh`：第三方 jar 没有可复现的公开拉取路径（对照 Cube Recall 的 `fetch-deps.sh` + sha1 校验） | 8 个依赖见下表 | **DONE** W1-A0 —— `scripts/fetch-deps.sh` + `scripts/check-file-deps.sh`（含反向验证） |
| G-403 | 其中 **2 个是 Sivita 自改版，公开源不存在**，只能由本地从 `lamb` 取回后作为 vendor 二进制入库 | `MyPet-4.0.0-Sivita.jar`、`worldguard-bukkit-7.0.18-Sivita.jar` | **DONE 2026-10-02**：已由本地从 lamb 取回并入库 `libs/vendor/`（commit `02486f1`，sha1 见 `libs/vendor/README.md`） |

### 8 个文件依赖全清单（`compileOnly(files(...))`）

| # | jar | 引用方 | 公开源 |
|---|---|---|---|
| 1 | `server/runtime/plugins/BetterHud-bukkit-2.1.0-SNAPSHOT-447.jar` | rpg-integrations | Modrinth `betterhud`（dev build） |
| 2 | `server/runtime/plugins/AuthMe-6.0.0-Paper.jar` | rpg-classes / rpg-guide / rpg-hub | GitHub AuthMe releases |
| 3 | `server/vendor/essentialsx/EssentialsX-2.22.1-dev-382de4f.jar` | rpg-essentials | GitHub EssentialsX CI |
| 4 | `server/vendor/mypet/MyPet-4.0.0-Sivita.jar` | rpg-pets | ⛔ **自改版，无公开源** |
| 5 | `server/runtime/plugins/worldguard-bukkit-7.0.18-Sivita.jar` | rpg-hub | ⛔ **自改版，无公开源** |
| 6 | `server/runtime/plugins/worldedit-bukkit-7.4.4.jar` | rpg-hub | maven.enginehub.org |
| 7 | `server/runtime/plugins/Citizens-2.0.43-b4231.jar` | rpg-story | Citizens CI |
| 8 | `server/runtime/plugins/BetonQuest-3.1.0.jar` | rpg-story | GitHub BetonQuest releases |

## G. 波次进度

| 波 | 内容 | 状态 |
|---|---|---|
| W1 | A0 fetch-deps（阻塞项）+ P1 差集矩阵 + A1..A5（4 条 P0 + DDL 门禁） | **DONE** 2026-10-02：7/7 PR 合入、越界 0、**编译门 GREEN**（sn=`cnb-slt-1k3te83u2`，BUILD_EXIT=0 / 28 tasks / 26 子工程） |

## H. W2 对账结果（**派发前必须先做这一步**）

`plan/tasks-w2.json`（W1-P1 产出）**不可直接派**：Planner 的分支基点早于 W1 的合并点，
它的清单里有 5 项是 W1 已经合完的（实测教训见 `state/world-state.json` E-014）。

| W2 任务 | 与 W1 的关系 | 处置 |
|---|---|---|
| W2-A1-killswitch-executor | = W1-A2，已合入 `cd097b8` | **丢弃** |
| W2-A2-ddl-consumer-gate | = W1-A5，已合入 `e8013ef` | **丢弃** |
| W2-C2-damage-bus-single-settlement | 其 G-001 部分 = W1-A1，已合入 `056b3e1` | **改写**：只保留 G-105（攻方增益二次放大） |
| W2-D1-signing-key-fail-closed | = W1-A3，已合入 `9aa2e2b` | **丢弃** |
| W2-D2-migration-lock-unify | = W1-A4，已合入 `ecae668` | **丢弃** |
| W2-B1-phase1-remove-ascend-bypass | 新增（G-107 上半） | **保留** |
| W2-B2-skillpoint-milestone | 新增（G-107 下半，deps B1） | **保留** |
| W2-C1-mailbox-freeze-creative | 新增（G-104） | **保留** |
| W2-E1-docs-accuracy | 新增（P2 订正，deps D2→需改成无 deps） | **保留**（去掉对 D2 的依赖，D2 已合入） |

**W2 还应该补进 planner 未覆盖、但 gap-matrix 已实证的「声明未接线」项**（这些才是「完成全部玩法机制细节」的主体）：

| 新 id | 缺口 | 来源 |
|---|---|---|
| G-110 | `EquipmentLifecycle.requiresExitPath()` / `isTradeable()` / `isExitPath()` 零消费者（宪法 C-008 装备退出路径未接线） | gap-matrix §2 |
| G-111 | `ProgressionRules.totalExperienceForAscension()` / `totalExperienceToEffective()` / `effectiveLevelCap()` 零消费者 | gap-matrix §2 |
| G-112 | `AuthoritativeItemPolicy.isAuthoritativeNamespace()` 零消费者 | gap-matrix §2 |
| G-113 | `JdbcNodeLease.renewAsync()` / `mayStillBeValid()` 零消费者 | gap-matrix §2 |
| G-114 | `EquipmentOperationCoordinator.markedDeliveryCopy()` 零消费者 | gap-matrix §2 |
| G-101/102/103/106/108 | 赛季结算服务缺失 / session-lease+outbox 只有 DDL / 社群七角色枚举装饰 / 血契条件不可达成 / 副本 world 键无读取方 | gap-matrix §2 |
