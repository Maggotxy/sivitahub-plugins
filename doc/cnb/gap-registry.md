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
| G-109 | 75 张表里约 10 张**只有 DDL 没有消费者**：`rpg_season_settlements`、`rpg_session_leases`、`rpg_outbox`、`rpg_market_buy_orders`、`rpg_support_credit_accounts/ledger`、`rpg_community_approvals`、`rpg_progression_anomalies` | DDL 与 src/main 对账 | **DONE** W6-A1（写侧）+ W9-A2（读侧，见 G-ANOMALY-ACK） |
| G-ANOMALY-ACK | `ProgressionAnomalyService` 的 `pending` / `acknowledge` **零 src/main 调用点**：写侧已接线（`JdbcProfileService:602/:608`），读侧没有出口 ⇒ 宪法要求的 `[H]` 人工复核在运行期不可达，异常记录只进不出 | `grep -rn --include=*.java 'ProgressionAnomalyService' plugins \| grep '/src/main/'` → 仅定义与实现 | **DONE** W9-A2 —— `/rpgadmin anomaly list\|ack`（`AnomalyReviewCommand`）+ `reviewer/review_note/reviewed_at` 三列落库 + 幂等签署；`AnomalyReviewCommandTest`（5 例）/ `JdbcProgressionAnomalyServiceTest`（11 例）/ `ProgressionAnomalySchemaUpgradeTest`（2 例） |

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
| W2 | 15 个任务：G-107上/下、G-104、G-105、G-110..G-114、G-101、G-102、G-103、G-106、G-108、P2 文档订正 | **DONE** 2026-10-02：15/15 合入（3 个经冲突消解），**编译门 GREEN**（sn=`cnb-l7u-1k3tn45sp`）。G-106 只交付自检未落地 ⇒ 转 W3-A1 |

| W3 | G-106 落地 / 全量测试门+打包 / SEASON_ATTUNED+DESTROYED 两条退出路径 / 8 张豁免表判定 / 差集矩阵 v2 | **DISPATCHED** 2026-10-02，清单 `doc/cnb/tasks-w3.json` |

## I. W21 —— 两个「不是代码写错，而是流程约束没有机械判据守着」的交付物缺陷

> 语境：`G-302-RESIDUAL` 链最后一段（W18/W19/W20/W21-A1）实际收敛的是**交付物质量**，不是玩法接线。
> 本节登记的缺陷属同一类：**没有机械判据守着，就会复发**。

| id | 缺口 | 证据（命令 / sn / 文件:行号） | 状态 |
|---|---|---|---|
| G-DUP-WORLDSTATE | **两份账本不一致**：`state/world-state.json` 与 `plan/world-state.json` 在**同一个 commit `6500a21`** 里被同一个 NPC 写成不同内容 ⇒ 两个 agent 分别读两份会得出不同结论，交接协议直接失效 | **同轮同作者**：两份的 `round` 都是 24、`updated_by` 都是 `CNB CodeBuddy NPC（W17-A1-verdict-v8）`。**字节数**：plan 40076B / md5 `fbf8a434cacd8693947a5f55f2012e62`；state 35064B / md5 `ace966486ed360318aaa4939ec43e130`（`git show 6500a21:plan/world-state.json \| wc -c` / `md5sum`）。**差异字段 7 个**：`assets` / `gates` / `status` / `next_action` / `open_questions_for_human` / `wave_plan` / `evidence`（`python3` 逐键比对，命令见 `.cnb/agent-out/W21-A2-ledger-and-guards.json` 的 evidence.merge_field_diff）。**根因原文**：`.cnb/agent-out/W10-A1-final-verdict-v5.json:153`——「`state/world-state.json` 不在本任务 allowed_paths（`plan/**` 与 `.cnb/agent-out/**`）内，本轮**未改**它：跨 agent 交接账本的更新以 `plan/world-state.json` 为准，需要落到 `state/` 的动作交回调度器。」⇒ 派单白名单与 `doc/cnb/agent-protocol.md` 声明的账本路径不一致，NPC 只能就地造第二份。 | **DONE** W21-A2：`plan/world-state.json` 已删除；漂移内容按并集并入 `state/world-state.json`（`_ledger` 字段记录合并字段与被替换旧值；plan 侧独有的 6 条 evidence 顺延为 E-057..E-062，一条未丢）；协议加硬规则（`doc/cnb/agent-protocol.md` §4.1）；`plan/README.md` 写明并入；门禁 `SINGLE_SOURCE_OF_TRUTH_GATE`（`scripts/selftest-gates.sh` §11 判据 1，正负夹具各带退出码断言） |
| G-VERDICT-MATRIX-SPLIT | **verdict 与矩阵结论自相矛盾**：`plan/gap-matrix-v8.md` §0 写「G-302-RESIDUAL 清零（0 个类 / 0 个方法）/ done=true / remaining=0」，同轮 `plan/verdict-v8.json` 写 `done=false` / `remaining=1`（`remaining_ids=["G-302-RESIDUAL-2"]`） | `plan/gap-matrix-v8.md` §0 结论表（原文行保留）vs `plan/verdict-v8.json` 的 `done`/`remaining`/`remaining_ids`；两文件同属 W17-A1 交付。危害：审阅者只读矩阵会得出「已清零」的相反结论。 | **DONE** W21-A2：**不改历史结论原文**，只补 `> verdict: done=false remaining=1` 机器行 + `> basis-substring: 77112113` + §0 旁勘误（以 JSON 为准）；门禁 `VERDICT_MATRIX_CONSISTENCY_GATE`（`scripts/selftest-gates.sh` §11 判据 2，三态夹具：缺结论行 RED / 数值不一致 RED / basis 不在头部 RED / 一致 GREEN） |
| G-FLAKY-GATE | **门禁假红（flaky）**：同一 sha `b6d38bff562ed59268ff5381847154a5e6976982` 上两次 `api_trigger_gate` 先红后绿 —— sn=`cnb-tn4-1k4141s0e`（stage test=error，`rpg-admin` `failed=1`）vs sn=`cnb-h3n-1k414popl`（全绿） | 失败用例 `cn.originalrpg.admin.AnomalyReviewCommandTest#acknowledgeWithoutConfirmOrSignatureIsRefusedAndWritesNothing`，断言消息 `[] ==> expected: <true> but was: <false>`；根因 = `awaitQuiet()` 用「连续 15 轮 × 2ms = 30ms 无新消息」当异步链收尾判据（时间启发式），CI 高负载下两跳异步链某一跳 >30ms 才落地即提前返回。 | **DONE** W21-A1（本分支前序任务）：`awaitChainSettled()` 确定性等待「在飞任务归零」+ 硬超时，仅改 `plugins/rpg-admin/src/test/**`；证据 `.cnb/agent-out/W21-A1-fix-flaky-test-gate.json`（含 6× CPU hog 下 30/30 绿、负向对照真变红、`tests=1101 failed=0 modules=26`） |

### I.3 W23 最终复验（v10）—— 三条已登记缺陷的闭环与 G-302-RESIDUAL 系列清零

> context：`plan/gap-matrix-v10.md` + `plan/verdict-v10.json`，basis=**实测** `1ace5ab`（= origin/main）。
> 结论：**done=true / remaining=0**。

| id | v10 判定 | 证据（命令 / sn / 文件:行号） |
|---|---|---|
| `G-FLAKY-GATE` | **闭环** | `plugins/rpg-admin/.../AnomalyReviewCommandTest.java`：`grep awaitQuiet` 只剩 `:283` **注释**；`:239` `awaitChainSettled()`（`observer.onIdle().await(...)` 在飞归零 + `:246` `isBusy()` 复位静默计数 + `:264` 硬超时 `throw new AssertionError("异步链未收尾…")`）；`:294` 永久回归测试 `slowAsyncHopStillYieldsTheExpectedBroadcast`（每跳 `LockSupport.parkNanos(100ms)`），`:322` 断言仍是 `admin.anomaly-not-pending`（未删断言、未放宽期望、未把异步改同步）。`.cnb/agent-out/W21-A1-fix-flaky-test-gate.json`：`post_fix_30_runs`=6 hog 下 30/30 rc=0（落盘 `.cnb/agent-out/evidence/post-fix-30runs.txt` 收尾 `FAILS=0 TOTAL=30`）；`negative_control.sabotage_rc=1` 且实际值**非空** ⇒ 断言没被『等没了』。**假红缓解的直接证据**：同 sha `1ace5ab` 两次连续全绿（`cnb-785-1k41j43k2` + `cnb-q65-1k41k0gtr`），改前为「先红后绿」（`b6d38bf`：`cnb-tn4-1k4141s0e` stage-7 error `SUM tests=1101 failed=1` vs `cnb-h3n-1k414popl` success；`334fb54`：`cnb-d93-1k41bqhbe` error vs `cnb-esl-1k41cpau5` success） |
| `G-DUP-WORLDSTATE` | **闭环** | `test ! -e plan/world-state.json` 成立；`git ls-files | grep world-state` 只有 `state/world-state.json`；`cnb-785-1k41j43k2` stage-1 判据行 `SINGLE_SOURCE_OF_TRUTH_GATE=GREEN` / `WORLD_STATE_FILES=1`；负向夹具 RED（`两份账本内容不一致：state=48679B plan=45B`），`state` 缺失亦 RED |
| `G-VERDICT-MATRIX-SPLIT` | **闭环（本轮自身即判据对象）** | `plan/gap-matrix-v10.md` 前 20 行内 `> verdict: done=true remaining=0` + `> basis: 1ace5ab`，与 `plan/verdict-v10.json` 的 `done`/`remaining`/`basis` **逐字一致**（按 `selftest-gates.sh` §11 `vmc_gate` 同口径自查通过）；CI stage-1 `VERDICT_MATRIX_CONSISTENCY_GATE=GREEN`（改前打印 `N=8 done=false remaining=1 basis=77112113` ⇒ 门禁真的在数版本，不是恒绿） |
| **`G-302-RESIDUAL-2`** | **闭环** | `DungeonTimeoutLifecycleContractTest`：`grep -n 'readString|readAllBytes|Files\.'` 只命中 `:19`（注释）；3 个 `@Test` 用 `DungeonBukkitTestHarness.newManager(scheduler)` 真构造 `DungeonManager`，断言 `scheduler.callbacks/cancelled`、`instanceSessions` 代际、`instanceTimeouts.containsKey` 等可观察状态 |
| **`G-302-RESIDUAL-3`**（判据反向失效①） | **闭环**（#67） | 夹具⑤ `assertTrue(Files.readString(Path.of("plugins/.../X.java")).contains("x"))` → `EXIT=1` RED；夹具⑥ `Files.readString(Path.of(<常量>))` → `EXIT=1` RED；删除后 GREEN |
| **`G-302-RESIDUAL-4`**（判据反向失效②） | **闭环**（#69） | 豁免已撤：`reads_production_source_via_traversal()` 在（`scripts/check-source-string-tests.sh:217`），`productionReferences` helper 已删除（全仓只剩 3 处注释）；夹具⑦ `Files.walk(root.resolve("plugins/rpg-core/src/main"))`+`readString(f).contains(id)` 聚合成列表 `anyMatch` → `EXIT=1` RED；夹具⑧ `resolve` 拼接变体 → RED |
| **`G-302-RESIDUAL`**（总） | **清零** | `sh scripts/check-source-string-tests.sh`（重定向取码）→ `SOURCE_STRING_TESTS=0` / `SOURCE_STRING_GATE=GREEN` / `EXIT=0`；`--json` → `{"hits": [], "count": 0}`。**判据未放宽**：独立复核 4 个类方法体（读的是测试夹具/源规格 JSON/进程 stdout/配置解析，不是生产源码正文）；反例 `LanguageBundleContractTest`（`Files.newInputStream` + SnakeYAML 真解析）**未**被判红；规格规模 `CHECKS=469 SPECS=79 SPEC_FILES=3` ≥ v8 的 `462/78/2`（`SPEC_FILES` 上升因 #69 新建 `rpg-core.json`） |

⇒ 「声明未接线」= 0 条；「可派工的实现缺口」= 0 条。剩余问题**只有** `REQUIRES_HUMAN`（H-001 / U-003 / W7-A2 四条 `[R]`）。

### I.1 两条新门禁的负向对照（现场造、现场删、断言退出码）

| 判据 | 夹具 | 命令与退出码 |
|---|---|---|
| `SINGLE_SOURCE_OF_TRUTH_GATE` | ①`state` 缺失 ②假 `plan/world-state.json`（内容不同）③删除后还原 | 见 `.cnb/agent-out/W21-A2-ledger-and-guards.json` 的 `evidence.negative_controls.gate1`：`exit=1 / exit=1 / exit=0`；夹具在 `mktemp -d` 下，跑完真实仓库无残留 |
| `VERDICT_MATRIX_CONSISTENCY_GATE` | ①矩阵缺结论行 ②`remaining` 与 JSON 不一致 ③`basis` 不在前 20 行 ④改成一致 | 见同一 JSON 的 `evidence.negative_controls.gate2`：`exit=1 / exit=1 / exit=1 / exit=0` |

### I.2 接线证明（「脚本存在」不等于「脚本被调用」）

- `scripts/selftest-gates.sh` §11 在 `.cnb.yml` 的「门禁自测（负向对照）」stage 里被执行：`.cnb.yml:71` 原文 `sh scripts/selftest-gates.sh`（stage 声明在 `:63`，**本任务未改 `.cnb.yml`**）。
- 判据行原文：`SINGLE_SOURCE_OF_TRUTH_GATE=GREEN` / `WORLD_STATE_FILES=1`；`VERDICT_MATRIX_CONSISTENCY_GATE=GREEN` / `N=8 done=false remaining=1 basis=77112113`。
- 完整自测：`sh scripts/selftest-gates.sh` → `===== 门禁自测汇总：PASS=132 FAIL=0 =====` / `GATE_SELFTEST=GREEN`。

### I.4 W25 最终复验（v11）—— 两条新缺陷的闭环复核：**均未闭环**

> context：`plan/gap-matrix-v11.md` + `plan/verdict-v11.json`，basis=**实测** `f014b447`（= origin/main）。
> 结论：**done=false / remaining=1 = `G-CACHE-STAMP-BEFORE-LOCK`**。

| id | 登记 | v11 判定 | 证据（命令 / sn / 文件:行号） |
|---|---|---|---|
| **读缓存被「晚到的旧快照」回滚** | #73（`839639f`） | ❌ **未闭环** | 版本号守卫在（`Committed<T>` + `publish()` 的 `version > existing.version()`，`JdbcProfileService.java:497-500`），两条回归测试也在：`JdbcServicesTest.java:1035 readCacheIsNotRolledBackByALateCommit()` / `:1088 lateCommitStampCannotRollBackTheReadCache()`；作者证据 `.cnb/agent-out/W24-A1-cache-publish-order.json`（改前 FAIL `expected: <20> but was: <19>` / 改后 PASS / 负向对照必红）。**但**：「取号顺序 == 锁顺序」未成为代码事实 —— 5 个发布点的 `inTransaction` 都是「**先** `nextCommitVersion()` → 再 `operation.apply(connection)` → 再 `commit()` → 提交成功才写 `versionStamp`」（`JdbcProfileService.java:796/797/798/799`、`JdbcAscensionService.java:285/286/287/288`、`JdbcDungeonRewardService.java:318/319/320/321`、`JdbcAdminIncidentCompensationService.java:726/727/728/729`、`JdbcAdminTestService.java:297/298/299/300`），而 `SELECT ... FOR UPDATE` 是在 `apply` **内部**发出的（`JdbcProfileService.java:627` 由 `loadOrCreate/loadForUpdate` 调用）⇒ **取号发生在加锁之前**，`JdbcProfileService.java:779` 注释声称的「在仍持有 `SELECT ... FOR UPDATE` 行锁时取号」与代码顺序不符。 |
| **缓存发布倒置窗口** | #75（`f014b447`） | ❌ **未闭环** | 同上一件事的另一个说法：取号点只是从 `commit()` 之后前移到 `apply()` 之前，仍在行锁之外 ⇒ 倒置窗口未归零，只是方向由「旧盖新」变为「更新的快照被更小的号挡住」（`publish` 拒绝更高版本的数据，`get()` 返回更旧值）。**实测**：同一 sha `f014b447` 的两个构建 —— `cnb-4f2-1k46ad3pv`（`api_trigger_gate`, main）`success` / `FAILURE_XML_COUNT=0` / `rpg-core tests=320 failed=0`；`cnb-ok2-1k46acmji`（`push`, main）`error` / `test stage error` / `FAILURE_XML_COUNT=1` / `SUM tests=1104 failed=1` / 失败用例 `cn.jdbc…JdbcServicesTest#lateCommitStampCannotRollBackTheReadCache()`，`message` 原文 `org.opentest4j.AssertionFailedError: 取号必须在 commit 之前（持锁时），缓存不得被倒置的版本号回滚（实际缓存值见左） ==> expected: <20> but was: <19>`。两构建 `ENV_ANNOTATIONS=8` / `EXCLUDED_TAG_SEEN_IN_LOG=26` 一致、测试总数一致（1104 / 26 模块）⇒ 非环境差异、非模块漏跑。 |
| **`G-FLAKY-GATE`** | W21-A1 | ⚠️ **回退（复发）** | 改后**没有**取得「同 sha 连续两次全绿」：`f014b447` 上 `cnb-4f2-1k46ad3pv` GREEN、`cnb-ok2-1k46acmji` test stage RED；同 sha RED 计数 = 1。对照改前「先红后绿」（`b6d38bf`：`cnb-tn4-1k4141s0e` test error / `cnb-h3n-1k414popl` success；`334fb54`：`cnb-d93-1k41bqhbe` test error / `cnb-esl-1k41cpau5` success），本轮是「先绿后红」，形态同源。⇒ v10 在 `1ace5ab` 上的「已闭环」不可沿用到新 basis。test stage 配置本身已收敛（`.cnb.yml:245 -Dorg.gradle.workers.max=2`，同 stage 调用不含 `-Dorg.gradle.parallel=true`；`:284 print('FAILURE_XML_COUNT=%d' % n)` 段落存在）。 |
| **`G-302-RESIDUAL` 系列**（含 -2/-3/-4） | W16/W18/W19 | ✅ **仍清零** | `sh scripts/check-source-string-tests.sh`（重定向取码）→ `SOURCE_STRING_TESTS=0` / `SOURCE_STRING_GATE=GREEN` / `EXIT=0`；`--json` → `{"hits": [], "count": 0}`。**判据未放宽**：独立复核 4 个类方法体（`DungeonTimeoutLifecycleContractTest` / `EquipmentExitPathReachabilityTest` / `SeasonAttunementSettlementWiringTest` / `DungeonEntryCommitAmbiguityContractTest`，读的是测试夹具 / 源规格 JSON / 进程 stdout / 真解析）；三组负向夹具现场造（`Fixture5WrappedAssertTest.java:11` / `Fixture6ConstPathTest.java:13` / `Fixture7TraversalTest.java:18`）各自 `EXIT=1` 变红、删除后 `EXIT=0` GREEN、`git status --porcelain` 干净；反例 `LanguageBundleContractTest`（`Files.newInputStream` + SnakeYAML 真解析）**未**被判红；规格规模 `CHECKS=469 SPECS=79 SPEC_FILES=3` ≥ v8 的 `466/78/2`。 |
| **`G-DUP-WORLDSTATE`** | W21-A2 | ✅ 闭环（复核） | `plan/world-state.json` 不存在；`git ls-files` 只有 `state/world-state.json`；CI stage-1 `SINGLE_SOURCE_OF_TRUTH_GATE=GREEN` / `WORLD_STATE_FILES=1`，负向夹具 `=RED` / `WORLD_STATE_FILES=2`，还原后 GREEN。 |
| **`G-VERDICT-MATRIX-SPLIT`** | W21-A2 | ✅ 闭环（本轮自身即判据对象） | `plan/gap-matrix-v11.md` 前 20 行内 `> verdict: done=false remaining=1` + `> basis: f014b447`，与 `plan/verdict-v11.json` 的 `done`/`remaining`/`basis` **逐字一致**（按 `selftest-gates.sh` §11 `vmc_gate` 同口径自查通过）；CI stage-1 `VERDICT_MATRIX_CONSISTENCY_GATE=GREEN`。 |

**新增缺口 `G-CACHE-STAMP-BEFORE-LOCK`**（可派工）：读缓存发布版本号的取号点仍在行锁之外。
修正方向：把 `nextCommitVersion()` 真正移到「已持有 `SELECT ... FOR UPDATE` 行锁之后」，
使「取号顺序 == 锁顺序 == 提交顺序」成为代码事实；验收以两条回归测试在门禁并行度（`workers.max=2`）
与 8x CPU hog 下稳定绿 + 负向对照必红为准。派单见 `plan/tasks-w25.json`（W25-A2）。
