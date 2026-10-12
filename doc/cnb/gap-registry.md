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

### I.5 W27 最终复验（v12）—— `G-CACHE-STAMP-BEFORE-LOCK` **已闭环**

> context：`plan/gap-matrix-v12.md` + `plan/verdict-v12.json`，basis=**实测** `14b67f2`（= origin/main）。
> 结论：**done=true / remaining=0**。

| id | 登记 | v12 判定 | 证据（命令 / sn / 文件:行号） |
|---|---|---|---|
| **`G-CACHE-STAMP-BEFORE-LOCK`** | #78（`14b67f2`） | ✅ **已闭环** | 取号点已下沉到「已持有 `SELECT ... FOR UPDATE` 行锁之后」。① 新增 `plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/LockedTransaction.java`：`markLocked` 惰性一次取号（`:54-55` `if (version == 0L) version = nextVersion.getAsLong();`），`wrap`（`:44-51`）只做代理包装**不取号**。② 5 个发布点全改 `LockedTransaction.wrap`（`JdbcProfileService.java:841` / `JdbcAscensionService.java:284` / `JdbcDungeonRewardService.java:318` / `JdbcAdminIncidentCompensationService.java:723` / `JdbcAdminTestService.java:297`），触发点在 `FOR UPDATE` 之后（`JdbcProfileService.java:627 select→:633 markLocked`；`loadSnapshot:484/486→:492`），各服务经 `loadForUpdate`（`:81 / :116 / :127 / :53,:73,:103`）或 `loadSnapshot`（`:120/:124`）。③ **v11 的 eager 取号已彻底消失**：`grep -rn 'nextCommitVersion()' plugins/rpg-core/src/main` 只剩定义 `JdbcProfileService.java:527` + 6 处 javadoc/注释，**无一个调用点**。④ 三个缓存顺序回归测试都在且 CI PASS：`JdbcServicesTest.java:1035 readCacheIsNotRolledBackByALateCommit()` / `:1088 lateCommitStampCannotRollBackTheReadCache()` / `:1151 commitVersionMustBeTakenAfterTheRowLockIsHeld()`（新增，延迟「第 18 次含 `FOR UPDATE` 的 `executeQuery` 执行之前」）；断言**未放宽**（`:1205` `assertEquals(20, fromDb, "数据库里 20 次授予必须全部落账")` + `:1207` 缓存==DB；`:1181` 仍是 20 路并发 `grantExperience` + `allOf().join()`）。⑤ 顺带修正纯读路径：`JdbcAdminTestService.java:56` 的 `status` 改传 `LockedTransaction.NO_STAMP`（原来会把读到的行发进缓存）。 |
| **`G-FLAKY-GATE`** | W21-A1 | ✅ **闭环（恢复）** | 同 sha `14b67f2` 上两个构建**均 success**：`cnb-lpt-1k46f0u8b`（`api_trigger_gate`, main）+ `cnb-m3o-1k46f0gvf`（`push`, main）；判据行一致（`GATE_SELFTEST=PASS=132 FAIL=0` / `SUM tests=1105 failed=0 errors=0 skipped=2 modules=26` / `JAR_COUNT=56` / `BUILD_EXIT=0`）。v11 在 `f014b447` 上「先绿后红」的回退已恢复为「同 sha 连续两次全绿」。 |
| **`G-302-RESIDUAL` 系列**（含 -2/-3/-4） | W16/W18/W19 | ✅ **仍清零（判据未放宽）** | `sh scripts/check-source-string-tests.sh`（重定向取码）→ `SOURCE_STRING_TESTS=0` / `SOURCE_STRING_GATE=GREEN` / `EXIT=0`。独立复核 4 个类方法体（`DungeonTimeoutLifecycleContractTest` / `EquipmentExitPathReachabilityTest` / `SeasonAttunementSettlementWiringTest` / `DungeonEntryCommitAmbiguityContractTest`，读的是测试夹具 / 源规格 JSON / 进程 stdout / 真解析）；三组负向夹具现场造（`Fixture5WrappedAssertTest.java:11` / `Fixture6ConstPathTest.java:13` / `Fixture7TraversalTest.java:16`）各自 `EXIT=1` 变红（`SOURCE_STRING_TESTS=3` / `GATE=RED`）、删除后 `EXIT=0` GREEN、`git status --porcelain` 干净；反例 `LanguageBundleContractTest`（`Files.newInputStream` + SnakeYAML 真解析）**未**被判红；规格规模 `CHECKS=469 SPECS=79 SPEC_FILES=3` ≥ v8 的 `466/78/2`。 |
| **`G-DUP-WORLDSTATE`** | W21-A2 | ✅ 闭环（复核） | `plan/world-state.json` 不存在；`git ls-files` 只有 `state/world-state.json`；CI stage-1 `SINGLE_SOURCE_OF_TRUTH_GATE=GREEN` / `WORLD_STATE_FILES=1`，负向夹具 `=RED` / `WORLD_STATE_FILES=2`，还原后 GREEN。 |
| **`G-VERDICT-MATRIX-SPLIT`** | W21-A2 | ✅ 闭环（本轮自身即判据对象） | `plan/gap-matrix-v12.md` 前 20 行内 `> verdict: done=true remaining=0` + `> basis: 14b67f2`，与 `plan/verdict-v12.json` 的 `done`/`remaining`/`basis` **逐字一致**（按 `selftest-gates.sh` §11 `vmc_gate` 同口径自查通过：`VERDICT_MATRIX_CONSISTENCY_GATE=GREEN` / `N=12 done=true remaining=0 basis=14b67f2`）。 |

#### I.5.1 三轮收敛史（这条缺口最有价值的一条方法论）

给「异步回调里发布本地读缓存」做顺序守卫需要一个**提交版本号**，必须满足
「取号顺序 == 加锁顺序 == 提交顺序」。三轮各差一段：

| 轮 | 取号点 | 残留问题 | 机械证据 | 结论 |
|---|---|---|---|---|
| PR#73（`839639f`） | `connection.commit()` **之后** | 「提交后、取号前」被抢占 ⇒ 倒置 | `readCacheIsNotRolledBackByALateCommit()`（延迟第 20 次 `Connection.close`） | ❌ 窗口仍在 |
| PR#75（`f014b447`） | `inTransaction` 开头（**加锁之前**） | 「取号后、加锁前」被抢占 ⇒ 倒置 | `lateCommitStampCannotRollBackTheReadCache()`（延迟真实 `commit()` 返回之后）；同 sha 下游构建 `cnb-ok2-1k46acmji` test stage RED，message `expected: <20> but was: <19>` | ❌ **取号在临界区外，无论前后都留倒置窗口** |
| **PR#78（`14b67f2`）** | **已持有 `SELECT ... FOR UPDATE` 行锁之后**（`LockedTransaction`；`loadForUpdate`/`loadSnapshot` 在 `FOR UPDATE` 返回后触发 `markLocked`，版本值惰性取一次） | **倒置窗口归零** | `commitVersionMustBeTakenAfterTheRowLockIsHeld()` ⇒ 改前 `expected: <20> but was: <19>`，改后 PASS | ✅ 闭环 |

**方法论**：`取号在临界区之外`——无论放在 `commit()` 之后还是放在 `apply()` 之前——都**留倒置窗口**；
只有把它移进「已持行锁」这段临界区内部，才能让「取号顺序 == 加锁顺序 == 提交顺序」成为**代码事实**
而不是注释声明。判据也随之从「延迟 close / 延迟 commit 返回」收紧到「延迟 `FOR UPDATE` 执行之前」，
即精确打在「取号之后、加锁之前」那个窗口上。

#### I.5.2 三段证据（`.cnb/agent-out/W26-A1-cache-stamp-after-lock.json`）

| 段 | 内容 | 机械证据 |
|---|---|---|
| ① 改前 FAIL | 5 个 main 文件回退到 `f014b447`，跑新测试 `commitVersionMustBeTakenAfterTheRowLockIsHeld()` | `pre_fix_exit_code=1`，`<failure message="org.opentest4j.AssertionFailedError: 取号必须在持有行锁之后；… ==> expected: <20> but was: <19>">` |
| ② 改后 PASS | 恢复修复版 | `post_fix_exit_code=0`，`tests 1 failures 0 errors 0 -> PASS` |
| ③ 负向对照必红 | 把取号点插回 `inTransaction` 开头（取号在加锁之前） | `negative_control_attempt2.exit_code=1`（同一 `<failure>` 原文）；`revert_exit_code=0` 还原即绿 ⇒ 断言没被「等没了」（实际值 19 ≠ 20） |

#### I.5.3 十门在 M10 上全绿

`sn=cnb-lpt-1k46f0u8b`（`event=api_trigger_gate`，`targetRef=main`，`sha=14b67f2`，`status=success`，
13 stage 全 success）：`DOWNLOADER=ready` / `GATE_SELFTEST=GREEN(PASS=132 FAIL=0)` /
`DDL_CONSUMER_GATE=GREEN` / `DDL_REGISTRATION_GATE=GREEN` / `CMD_REFERENCE_GATE=GREEN` /
`RESOURCE_PACK_GATE=GREEN` / `ASSET_BASELINE_GATE=GREEN(UNREGISTERED=0 MISSING=0 MODIFIED=0)` /
`ASSET_REPRO_GATE=GREEN(REPRODUCIBLE=70 MISMATCH=0)` /
`COMPILE_GATE=GREEN(TASKS_DONE=28 BUILD_EXIT=0 SECONDS=108)` / `TEST_GATE=GREEN` /
`SUMMARY_GATE=GREEN(SUM tests=1105 failed=0 errors=0 skipped=2 modules=26)` /
`PACKAGE_GATE=GREEN(JAR_COUNT=56)` / `SINGLE_SOURCE_OF_TRUTH_GATE=GREEN(WORLD_STATE_FILES=1)` /
`VERDICT_MATRIX_CONSISTENCY_GATE=GREEN`。
同 sha 的 `cnb-m3o-1k46f0gvf`（`push`, main）亦 success 且判据行一致 ⇒ 同 sha 连续两次全绿。

### I.6 W29 最终复验（v13）—— PR#80 构建输入改动在 M12 上重判 + 三条已登记缺陷的闭环

> context：`plan/gap-matrix-v13.md` + `plan/verdict-v13.json`，basis=**实测** `c74249e`（= `HEAD` = `origin/main`，两者相等）。
> 结论：**done=true / remaining=0**。
> 与 v12 的差别：v12 判 done 时 PR#80（构建输入改动）**还不存在** ⇒ 必须在
> M12=`c74249e`（PR#80 合入后的 main HEAD）上重判。

| id | 登记 | v13 判定 | 证据（命令 / sn / 文件:行号） |
|---|---|---|---|
| **`MECHANICS-SPEC-BINDING`**（玩法规格↔代码 机械绑定） | PR#80（`c74249e`） | ✅ **已建立并闭环** | `scripts/mechanics-spec.json` **137 条**（`config:78` / `behavior:56` / `java-const:3`；`unverifiable` 显式登记 11 条）；`sh scripts/check-mechanics-spec.sh` → `MECHANICS_SPEC_GATE=GREEN` / `ENTRIES=137` / `BY_KIND=config:78,behavior:56,java-const:3` / `WEAK=0`；覆盖 `docs/GAME_DESIGN.md` 规格正文 15 节里的 **14 节**（`核心循环` 是纯叙述，已在生成报告的「未登记任何条目的节」显式说明）；`scripts/selftest-gates.sh §12` **6 组负夹具**（config 改坏 / behavior 指向不存在测试 / java-const 改坏 / 去掉 `behavior_ref` 使 `WEAK` 变 1 / 后 7 节新条目改坏 / 报告陈旧 `STALE`），每组带「还原后必须 GREEN」；CI `cnb-cf6-1k46peo58` stage-1 逐条真跑（`[00:09:44] [OK] 负夹具①…` 至 `[00:09:45] [OK] ⑥ 报告陈旧能被判出来`）。**我独立做的负向对照**（不采信 NPC）：`sh scripts/check-mechanics-spec.sh --spec /tmp/mxfix/spec.broken.json`（把 `M-CORE-003` 的 config 载体 `expect` 从 `[2,3,4,5]` 改成 `[2,3,4,9]`）→ `EXIT=1` / `MECHANICS_SPEC_GATE=RED` / `[FAIL] M-CORE-003` / `期望 [2,3,4,9]` / `实际 ['2','3','4','5']`；还原 → `EXIT=0` `GREEN` |
| **报告可重生成**（该层的配套） | PR#80 | ✅ **闭环** | `python3 scripts/gen-mechanics-spec-report.py --check` → `MECHANICS_SPEC_REPORT=FRESH`（`EXIT=0`）；`--write` 幂等（输出「报告节已是最新，未改动」，`git status --porcelain` 为空）；`docs/GAME_DESIGN.md` 的 `<!-- BEGIN mechanics-spec-report (generated) -->` 在 `:176` / `END` 在 `:270`，块内数字由脚本生成（「**禁止手写数字**」原文在 `:179`）；CI stage-1 `[00:09:45] [OK] 判据行 MECHANICS_SPEC_REPORT=FRESH` + 负夹具⑥ `STALE（1）(exit=1)` |
| **`G-MECHANICS-SPEC-CARRIER-MISSING-GREEN`**（门禁对载体文件缺失判 GREEN） | PR#80 顺带修 | ✅ **闭环** | 根因：`resolve_config` 返回 `None` ⇒ 后续 `len(None)` 抛 `TypeError` 被吞成 `exit 0`（**假绿**）。现 `scripts/check-mechanics-spec.sh:711-715` 显式 `failures.append((eid, claim, misses[0], rel))`，注释原文「载体文件不存在/为空：必须 RED，不能因为 `len(None)` 抛异常而被 `set -eu` 之外的形式静默放过（实测：缺文件时 python 抛 `TypeError`，退出码被吞成 0 ⇒ 假绿）」。**我的负向对照**：把一条 config 载体的 `path` 改成 `plugins/rpg-skills/src/main/resources/DOES-NOT-EXIST.yml` → `sh scripts/check-mechanics-spec.sh --spec /tmp/mxmissing/scripts/mechanics-spec.json` → `EXIT=1` / `MECHANICS_SPEC_GATE=RED` / `[FAIL] M-CORE-003` / `实际 config 文件不存在或为空：…DOES-NOT-EXIST.yml` |
| **`G-MECHANICS-SPEC-PYYAML-DEPENDENCY`**（门禁依赖 PyYAML 而门禁镜像无 PyYAML/pip） | PR#80 顺带修 | ✅ **闭环（stdlib-only）** | 根因：门禁 `import yaml`，而真实门禁镜像 `eclipse-temurin:25-jdk` + apt `python3` **既无 PyYAML 也无 pip**（探针 sn=`cnb-ta0-1k46mi9rr`：`ModuleNotFoundError: No module named 'yaml'` / `PYYAML=MISSING` / `PIP=MISSING`）⇒ **NPC 容器 GREEN、门禁容器 RED**。现改为纯 stdlib 的最小 YAML 子集解析器。机械证据：`grep -nE '^[[:space:]]*(import\|from)[[:space:]]+yaml' scripts/check-mechanics-spec.sh` → **zero hits**（`exit=1` / `0` 行）；隔离垫片（把 `dist-packages`/`site-packages` 摘出 `sys.path`）下 `python3 -c 'import yaml'` → `EXIT=1` / `ModuleNotFoundError`（探针先自证垫片有效，否则对照是空跑），同垫片下门禁 → `EXIT=0` 且 4 行判据与有 PyYAML 时 `diff` **IDENTICAL**；CI stage-1 `[00:09:45] [OK] 判据 12d：check-mechanics-spec.sh 不含 import yaml（静态零命中）(exit=0)` / `[OK] 负夹具⑦：夹具脚本插入 import yaml（1 处）→ 静态判据必须报 1 (exit=1)` / `[OK] 还原后静态判据回 0（不是恒红）(exit=0)`。⚠️ 本 NPC 容器**有** PyYAML 6.0 ⇒「本容器跑绿」**不是**证据 |
| **`G-CACHE-STAMP-BEFORE-LOCK`** | W26（#78 / `14b67f2`） | ✅ **仍闭环**（独立复核代码事实，不采信 PR 描述） | `grep -rn 'nextCommitVersion()' plugins/rpg-core/src/main/java/` → **零个 eager 调用**（只剩 1 个定义 `JdbcProfileService.java:527` + 7 处 javadoc 引用）；`LockedTransaction.java:52-55` `markLocked()` 有 `if (version == 0L)` 守卫（惰性一次取号），`wrap`（`:41-46`）只做代理包装**不取号**；5/5 发布点 `LockedTransaction.wrap`（`JdbcProfileService:841` / `JdbcAscensionService:284` / `JdbcDungeonRewardService:318` / `JdbcAdminIncidentCompensationService:723` / `JdbcAdminTestService:297`），触发点在 `FOR UPDATE` 之后（`:484 FOR UPDATE → :492 markLocked`；`:667 SELECT … FOR UPDATE → :633 markLocked`；新建行分支 `:649`）；`JdbcAdminTestService.java:56` 纯读路径改传 `LockedTransaction.NO_STAMP`；3 个回归测试在（`JdbcServicesTest.java:1035/:1088/:1151`）且断言未放宽（仍断言 DB==缓存值 + 20 路并发未串行化）；CI `SUM tests=1105 failed=0 errors=0 skipped=2 modules=26` |
| **`G-302-RESIDUAL` 系列**（含 `-2`/`-3`/`-4`） | W16/W18/W19 | ✅ **仍清零（判据未放宽，本轮独立复跑）** | `bash -c "sh scripts/check-source-string-tests.sh > … 2>&1; echo EXIT=\$?"` → `EXIT=0` / `SOURCE_STRING_TESTS=0` / `SOURCE_STRING_GATE=GREEN`（**重定向取码，不走管道**）；`--json` → `{"hits": [], "count": 0}`。独立抽样复核 **4 个类方法体**（`DungeonTimeoutLifecycleContractTest`（全类只命中 `:19/:20/:28` 注释，`:50/:51` 真构造 `DungeonManager`）/ `EquipmentExitPathReachabilityTest`（`:332/:334/:340/:344` 读 spec JSON + 进程 stdout）/ `SeasonAttunementSettlementWiringTest`（`:243/:250` stdout、`:299` `newInputStream` 真解析）/ `DungeonEntryCommitAmbiguityContractTest`（`:65/:70/:77` stdout + 仓库根））⇒ 读的**不是生产源码正文**。反向验证 **3 组**夹具现场造（`Fixture5WrappedAssertTest.java:12` 内联 `assertTrue(readString(Path.of("plugins/...")).contains(x))` / `Fixture6ConstPathTest.java:8` `readString(Path.of(<常量>))` / `Fixture7TraversalTest.java:11` `Files.walk(root.resolve("plugins").resolve(MODULE).resolve(MAIN))` + `readString(f).contains(id)` 聚合成 `List` 后 `anyMatch`）各自 `EXIT=1` 变红（`SOURCE_STRING_TESTS=3` / `GATE=RED`）、删除后 `EXIT=0` `GREEN`、收尾 `git status --porcelain` 为空；反例 `LanguageBundleContractTest`（`Files.newInputStream` + SnakeYAML 真解析）**未**被判红；规格规模 `CHECKS=469 SPECS=79 SPEC_FILES=3` ≥ v8 的 `466/78/2`。**已知形态边界**（如实登记，见 §I.6.1） |
| **`G-DUP-WORLDSTATE`** | W21-A2 | ✅ 闭环（复核） | `plan/world-state.json` 不存在；`git ls-files \| grep world-state` 只有 `state/world-state.json`（`count=1`）；CI stage-1 `SINGLE_SOURCE_OF_TRUTH_GATE=GREEN` / `WORLD_STATE_FILES=1`，负向夹具 `=RED` / `WORLD_STATE_FILES=2`，还原后 `=GREEN` |
| **`G-VERDICT-MATRIX-SPLIT`** | W21-A2 | ✅ 闭环（**本轮自身即判据对象**） | `plan/gap-matrix-v13.md` 前 20 行内 `> verdict: done=true remaining=0`（`:3`）+ `> basis: c74249e`（`:4`），与 `plan/verdict-v13.json` 的 `done`/`remaining`/`basis` **逐字一致**；用 `scripts/selftest-gates.sh` 的 `vmc_gate()` **原函数**自查（`. /tmp/vmc.sh; vmc_gate "$PWD"`）→ `VERDICT_MATRIX_CONSISTENCY_GATE=GREEN` / `N=13 done=true remaining=0 basis=c74249e` |

#### I.6.1 一条如实登记的「未采样」与一条「已知边界」（都不粉饰）

1. **`G-FLAKY-GATE` 本轮未采样**：`cnb build get-build-logs --sha c74249e46370f2c18359373615146aa978c72618`
   只返回 **1 个** `main` 构建（`cnb-cf6-1k46peo58`, `api_trigger_gate`, `success`）。
   ⇒ v12 在 `14b67f2` 上的「同 sha 连续两次全绿」**不可沿用**；本轮**不**断言「同 sha 两次全绿」。
   但也不把「只采到一个 GREEN」说成有问题 —— 该缺陷的原始形态是「同 sha 上须有一个 RED」
   （见 §I.4：`f014b447` 上先绿后红）。本轮**无任何 RED 观测**（未复发，也未被证伪）。
   处理：登记进 `plan/verdict-v13.json` 的 `risks[0]`，**不**列入 `needs_human`
   （它不是「只能由人做的事」，调度器在 `c74249e` 上多触发一次构建即可把该项从「未采样」变为「已复核」）。
2. **`G-302` 遍历判据的已知形态边界**：形态 ④ 的命中要求「`Files.walk` 的实参里同时出现
   `plugins|tools` 与 `src/main`」，或「类里同时有 `"plugins"|"tools"` 与 `"src/main"` 两个独立字面量」。
   我造的夹具⑦ 第一版写成 `Path.of("plugins/rpg-core/src/main")`（单个内联字面量，两段在同一字符串里）
   ⇒ 判 **GREEN（未命中）**；改成 `root.resolve("plugins").resolve(MODULE).resolve(MAIN)` 两段独立字面量
   ⇒ `EXIT=1` **RED**。判定**不构成缺口**的三条机械理由：
   (1) 该形态不是历史上漂移过的形态（`DungeonTimeoutLifecycleContractTest` /
   `EquipmentExitPathReachabilityTest` / `SeasonAttunementSettlementWiringTest` 三个原形态都在命中范围内）；
   (2) CI stage-1 §4d 的夹具①（内联）/②（helper + 常量路径 + `indexOf`）/④（遍历聚合）各带退出码断言，
   判据**每次推送**都被负向对照；
   (3) 当前 `SOURCE_STRING_TESTS=0` 是**方法体事实**（4 类逐条复核），不依赖该边界是否可绕。
   改判据属构建输入，本任务不允许改 ⇒ 登记在 `plan/gap-matrix-v13.md §6.5` 与 `verdict-v13.json` 的 `risks[1]`。

#### I.6.2 十门在 M12 上全绿

`sn=cnb-cf6-1k46peo58`（`event=api_trigger_gate`，`targetRef=main`，`sourceRef=main`，
`sha=c74249e46370f2c18359373615146aa978c72618`，`status=success`，
`pipelineSuccessCount=1 / pipelineFailCount=0`，13 stage `success` + `DebugDetection` `skipped`，
每个 `success` stage 的 duration 均非零）：

`DOWNLOADER=ready` / `GATE_SELFTEST=GREEN(PASS=167 FAIL=0)` /
`DDL_CONSUMER_GATE=GREEN(81 表, 豁免 0)` / `DDL_REGISTRATION_GATE=GREEN(551 文件, 反向豁免 0)` /
`CMD_REFERENCE_GATE=GREEN(43 指令, 豁免 9)` / `RESOURCE_PACK_GATE=GREEN(RUN=8 SKIPPED=0)` /
`ASSET_BASELINE_GATE=GREEN(252↔252, UNREGISTERED=0 MISSING=0 MODIFIED=0)` /
`ASSET_REPRO_GATE=GREEN(REPRODUCIBLE=70 MISMATCH=0)` /
`COMPILE_GATE=GREEN(TASKS_DONE=28 BUILD_EXIT=0 SECONDS=120)` /
`TEST_GATE=GREEN(TEST_EXIT=0 SUMMARY_EXIT=0 SECONDS=60)` /
`SUMMARY_GATE=GREEN(SUM tests=1105 failed=0 errors=0 skipped=2 modules=26, NO_XML modules=0)` /
`PACKAGE_GATE=GREEN(JAR_COUNT=56, JAR_CHECKED=25 JAR_MISSING_PLUGIN_YML=0)` /
`SINGLE_SOURCE_OF_TRUTH_GATE=GREEN(WORLD_STATE_FILES=1)` /
`VERDICT_MATRIX_CONSISTENCY_GATE=GREEN(N=12 done=true remaining=0 basis=14b67f2)` /
**`MECHANICS_SPEC_GATE=GREEN(ENTRIES=137 / BY_KIND=config:78,behavior:56,java-const:3 / WEAK=0)`** /
**`MECHANICS_SPEC_REPORT=FRESH`**。

本任务**不改** `.cnb.yml`，也不新增 stage —— 新增门禁的接线方式是
`scripts/selftest-gates.sh` **自己调用**它们（`§12`），而 `.cnb.yml:71` 早已执行
`sh scripts/selftest-gates.sh` ⇒ 每次推送都真跑（判据来自 `selftest-gates.sh` 的
接线自证 §12b：`grep -c -F "scripts/check-mechanics-spec.sh"` ≥ 1 且
`scripts/mechanics-spec.json` ≥ 1，CI 日志原文 `[00:09:45] [OK] selftest-gates.sh 覆盖 scripts/check-mechanics-spec.sh（17 处）`）。

### I.7 W30 最终复验（v14）—— 矩阵 basis 对齐 main HEAD + 四条独立复核

> context：`plan/gap-matrix-v14.md` + `plan/verdict-v14.json`，basis=**实测** `7c23a35a`（= `HEAD` = `origin/main`，两者相等）。
> 结论：**done=true / remaining=0**。
> 与 v13 的差别：v13 的 `basis=c74249e` **字面不等于**当时 main HEAD `7c23a35`（差 6 个非构建输入文件）。
> 本轮把 basis 前移到分叉时的 main HEAD，并在**该 basis 上**亲自重跑四条（修掉 v13「素材层证据是在更早 basis 上核对」的缺口）。

| id | v14 判定 | 证据（命令 / sn / 文件:行号） |
|---|---|---|
| **矩阵 basis 字面 == main HEAD**（本轮首要目的） | ✅ **达成** | `git rev-parse HEAD` == `git rev-parse origin/main` == `7c23a35a8bd7fe5c5f5f627d08bcb22e4610b7c9`（`EQUAL=true`）；`plan/gap-matrix-v14.md:3` `> verdict: done=true remaining=0` + `:4` `> basis: 7c23a35a`，与 `plan/verdict-v14.json` 逐字一致；用 `scripts/selftest-gates.sh` 的 `vmc_gate()` **原函数**自查 → `VERDICT_MATRIX_CONSISTENCY_GATE=GREEN` / `N=14 done=true remaining=0 basis=7c23a35a` |
| **素材层四条**（本轮 basis 上亲自重跑） | ✅ **成立** | (a) 23/23 脚本有真实调用点（含 `check-mechanics-spec.sh` → `scripts/selftest-gates.sh:1711/:1717`、`gen-mechanics-spec-report.py` → `:1941/:1972`）；(b) `python3 scripts/check-asset-baseline.py` → `EXIT=0` / 252↔252 / `UNREGISTERED=0 MISSING=0 MODIFIED=0` / `ASSET_BASELINE_GATE=GREEN`（⚠️ `sh <该py>` 会 `EXIT=2` 语法错，按 stage-4 真实方式用 python3）；(c) 归一 `assets/` 前缀后 `same_set=True`（`manifest-only=0`/`prov-only=0`）、`$needsHumanDecision=[]`、六类合计 252 且与逐条重算一致；(d) items 81 == runtime 去注释键 81（键集合相等），`--check {closure,compass,hud,journey,lang,packmeta,textures}` 七项全 `exit=0` |
| **`G-302-RESIDUAL` 系列**（含 -2/-3/-4） | ✅ **仍清零（判据未放宽）** | `SOURCE_STRING_GATE=GREEN` / `SOURCE_STRING_TESTS=0` / `EXIT=0`（重定向取码）；4 类方法体抽样（`DungeonTimeoutLifecycleContractTest` 全类只命中 `:19` 注释 / `EquipmentExitPathReachabilityTest:333-340` 读规格 JSON + 进程 stdout / `SeasonAttunementSettlementWiringTest:243/:250/:299` stdout + SnakeYAML 真解析 / `DungeonEntryCommitAmbiguityContractTest:65-77` stdout + 仓库根）；三组负夹具⑤⑥⑦现场造（`:12` / `:7` / `:19`）各自 `EXIT=1`、合并 `SOURCE_STRING_TESTS=3` / `GATE=RED`，删除后 `EXIT=0` GREEN，收尾 `git status --porcelain` 为空；反例 `LanguageBundleContractTest` 未被判红；规格 `CHECKS=469 SPECS=79 SPEC_FILES=3` ≥ v8 的 `466/78/2` |
| **机制规格绑定层** | ✅ **闭环（本轮独立负向对照）** | `MECHANICS_SPEC_GATE=GREEN` / `ENTRIES=137`（与 M13 一致）/ `WEAK=0`；`gen-mechanics-spec-report.py --check` → `MECHANICS_SPEC_REPORT=FRESH`；`grep -nE '^\s*(import\|from)\s+yaml'` **零命中**；**我的独立负向对照**：把 `M-CORE-003` 的 config 载体 `expect` 从 `[2,3,4,5]` 改成 `[2,3,4,9]` ⇒ `EXIT=1` / `GATE=RED` / `[FAIL] M-CORE-003`；还原 ⇒ `EXIT=0` GREEN |
| **`G-CACHE-STAMP-BEFORE-LOCK`** | ✅ **仍闭环** | eager `nextCommitVersion()` 调用**为零**（只剩定义 `JdbcProfileService.java:527` + 7 处 javadoc）；`LockedTransaction.wrap:41-46` 只包装不取号；`markLocked:54-55` 惰性一次取号；5/5 发布点 `LockedTransaction.wrap`；3 个回归测试在（`JdbcServicesTest.java:1035/:1088/:1151`） |
| **`G-FLAKY-GATE`** | ✅ **同 sha 两次构建均 success（RED=0）**（补上 v13 的「未采样」） | `7c23a35a` 上 `cnb-7e9-1k46rf4i2`(api_trigger_gate) + `cnb-1e5-1k46rem0u`(push) **两个 main 构建都 success**，判据行逐条一致（`GATE_SELFTEST=GREEN PASS=167 FAIL=0` / `BUILD_EXIT=0` / `SUM tests=1105 failed=0` / `JAR_COUNT=56`） |
| **`G-DUP-WORLDSTATE`** | ✅ 闭环（复核） | `plan/world-state.json` 不存在；`git ls-files \| grep world-state` 只有 `state/world-state.json`（count=1）；CI stage-1 `SINGLE_SOURCE_OF_TRUTH_GATE=GREEN` / `WORLD_STATE_FILES=1` |
| **`G-VERDICT-MATRIX-SPLIT`** | ✅ 闭环（**本轮自身即判据对象**） | `plan/gap-matrix-v14.md` 前 20 行内 `> verdict: done=true remaining=0` + `> basis: 7c23a35a`，与 `plan/verdict-v14.json` 的 `done`/`remaining`/`basis` 逐字一致；`vmc_gate()` 原函数自查 GREEN |

#### I.7.1 十门在 M13 上全绿（回读核对）

`sn=cnb-7e9-1k46rf4i2`（`event=api_trigger_gate`，`targetRef=main`，`sourceRef=main`，
`sha=7c23a35a8bd7fe5c5f5f627d08bcb22e4610b7c9`，`status=success`，`pipelineSuccessCount=1 / pipelineFailCount=0`，
13 stage success + `DebugDetection` skipped）：

`DOWNLOADER=ready` / `GATE_SELFTEST=GREEN(PASS=167 FAIL=0)` / `DDL_CONSUMER_GATE=GREEN` /
`DDL_REGISTRATION_GATE=GREEN` / `CMD_REFERENCE_GATE=GREEN` / `RESOURCE_PACK_GATE=GREEN(RUN=8 SKIPPED=0)` /
`ASSET_BASELINE_GATE=GREEN(UNREGISTERED=0 MISSING=0 MODIFIED=0)` / `ASSET_REPRO_GATE=GREEN(REPRODUCIBLE=70 MISMATCH=0)` /
`COMPILE_GATE=GREEN(TASKS_DONE=28 BUILD_EXIT=0 SECONDS=122)` / `TEST_GATE=GREEN(TEST_EXIT=0 SUMMARY_EXIT=0 SECONDS=59)` /
`SUMMARY_GATE=GREEN(SUM tests=1105 failed=0 errors=0 skipped=2 modules=26)` /
`PACKAGE_GATE=GREEN(JAR_COUNT=56，JAR_CHECKED=25 JAR_MISSING_PLUGIN_YML=0)` /
`SINGLE_SOURCE_OF_TRUTH_GATE=GREEN(WORLD_STATE_FILES=1)` / `VERDICT_MATRIX_CONSISTENCY_GATE=GREEN` /
`MECHANICS_SPEC_GATE=GREEN(ENTRIES=137 / BY_KIND=config:78,behavior:56,java-const:3 / WEAK=0)` /
`MECHANICS_SPEC_REPORT=FRESH`。

**本轮唯一未闭合项 = 0**。**REQUIRES_HUMAN 三项**：H-001（生产机写操作）/ U-003（真实 Paper + 真实客户端握手）/
W7-A2 四条 `[R]` 级 unproven。

### I.8 W31 最终复验（v15）—— 矩阵 basis 对齐 main HEAD + 四组独立重跑

> context：`plan/gap-matrix-v15.md` + `plan/verdict-v15.json`，basis=**实测** `c241979`（= `HEAD` = `origin/main`，两者相等）。
> 结论：**done=true / remaining=0**。
> 与 v14 的差别：v14 的 basis=`7c23a35a`；本轮 basis 前移到分叉时的 main HEAD `c241979`，
> 且 **M14 的 sha 就是本轮 basis 本身** ⇒ 十门证据的构建输入与判定 basis 同集合。

| id | v15 判定 | 证据（命令 / sn / 文件:行号） |
|---|---|---|
| **矩阵 basis 字面 == main HEAD**（本轮首要目的） | ✅ **达成** | `git rev-parse HEAD` == `git rev-parse origin/main` == `c2419799d93c751c5c5e486db6cb12dbe5f721dc`（`EQUAL=true`）；`plan/gap-matrix-v15.md:3` `> verdict: done=true remaining=0` + `:4` `> basis: c241979`，与 `plan/verdict-v15.json` 逐字一致；`vmc_gate()` **原函数**自查 → `GREEN` / `N=15 done=true remaining=0 basis=c241979` |
| **素材层四条**（本轮 basis 上亲自重跑） | ✅ **成立** | (a) 23/23 脚本有真实调用点（含 `check-mechanics-spec.sh` → `scripts/selftest-gates.sh:1711/:1717/:1811`、`gen-mechanics-spec-report.py` → `:1941/:1972`）；(b) `python3 scripts/check-asset-baseline.py` → `EXIT=0` / 252↔252 / `UNREGISTERED=0 MISSING=0 MODIFIED=0` / `ASSET_BASELINE_GATE=GREEN`（⚠️ `sh <该py>` 会语法错，按 stage-4 真实调用方式用 python3）；(c) 归一 `assets/` 前缀后 `same_set=True`（`manifest-only=0`/`prov-only=0`）、`$needsHumanDecision=[]`、六类合计 252 且与逐条重算一致；(d) items 81 == runtime 去注释键 81（键集合相等），`--check {closure,compass,hud,journey,lang,packmeta,textures}` 七项全 `exit=0` |
| **`G-302-RESIDUAL` 系列**（含 -2/-3/-4） | ✅ **仍清零（判据未放宽）** | `SOURCE_STRING_GATE=GREEN` / `SOURCE_STRING_TESTS=0` / `EXIT=0`（重定向取码）；4 类方法体抽样（`DungeonTimeoutLifecycleContractTest` 全类只命中 `:19` 注释 / `EquipmentExitPathReachabilityTest:330-344` 读规格 JSON + 进程 stdout / `SeasonAttunementSettlementWiringTest:224/:242-258/:299` 真解析 + stdout / `DungeonEntryCommitAmbiguityContractTest:62-77` stdout）；三组负夹具⑤⑥⑦现场造（`:8` / `:14` / `:19`）各自 `EXIT=1`、合并 `SOURCE_STRING_TESTS=3` / `GATE=RED`，删除后 `EXIT=0` GREEN，收尾 `git status --porcelain` 为空；反例 `LanguageBundleContractTest` 未被判红；规格 `CHECKS=469 SPECS=79 SPEC_FILES=3` ≥ v8 的 `466/78/2` |
| **机制规格绑定层** | ✅ **闭环（本轮独立负向对照）** | `MECHANICS_SPEC_GATE=GREEN` / `ENTRIES=137`（与 M14 一致）/ `WEAK=0`；`gen-mechanics-spec-report.py --check` → `MECHANICS_SPEC_REPORT=FRESH`；`grep -nE '^\s*(import\|from)\s+yaml'` **零命中**；**我的独立负向对照**：把 `M-CORE-003` 的 config 载体 `expect` 从 `[2,3,4,5]` 改成 `[2,3,4,9]` ⇒ `EXIT=1` / `GATE=RED` / `[FAIL] M-CORE-003`；还原 ⇒ `EXIT=0` GREEN |
| **`G-CACHE-STAMP-BEFORE-LOCK`** | ✅ **仍闭环** | eager `nextCommitVersion()` 调用**为零**（只剩定义 `JdbcProfileService.java:527` + 7 处 javadoc）；`LockedTransaction.wrap:40-46` 只包装不取号；`markLocked:54-55` 惰性一次取号；5/5 发布点 `LockedTransaction.wrap`（`:841`/`:284`/`:318`/`:723`/`:297`）；3 个回归测试在（`JdbcServicesTest.java:1035/:1088/:1151`）且断言未放宽 |
| **`G-DUP-WORLDSTATE`** | ✅ 闭环（复核） | `plan/world-state.json` 不存在；`git ls-files \| grep world-state` 只有 `state/world-state.json`（count=1）；CI stage-1 `SINGLE_SOURCE_OF_TRUTH_GATE=GREEN` |
| **`G-VERDICT-MATRIX-SPLIT`** | ✅ 闭环（**本轮自身即判据对象**） | `plan/gap-matrix-v15.md` 前 20 行内 `> verdict: done=true remaining=0` + `> basis: c241979`，与 `plan/verdict-v15.json` 逐字一致；`vmc_gate()` 原函数自查 GREEN |
| **`G-FLAKY-GATE`** | ⚠️ **本轮单点采样（无 RED，不断言两点全绿）** | `c241979` 上权威构建 `cnb-jpu-1k46t3ft2`（`api_trigger_gate`, main）`success`，`pipelineSuccessCount=1 / pipelineFailCount=0`；test stage success / `SUMMARY_GATE=GREEN`。本轮**无任何 RED 观测** ⇒ 未复发也未被证伪；**不**沿用在 `7c23a35a` 上的两点采样。记入 `plan/verdict-v15.json` 的 `risks`，**不**列入 `needs_human` |

#### I.8.1 十门在 M14 上全绿（回读核对）

`sn=cnb-jpu-1k46t3ft2`（`event=api_trigger_gate`，`targetRef=main`，`sourceRef=main`，
`sha=c2419799d93c751c5c5e486db6cb12dbe5f721dc`，`status=success`，
`pipelineSuccessCount=1 / pipelineFailCount=0`，13 stage `success` + `DebugDetection` `skipped`，
每个 success stage 的 duration 均非零）：

`DOWNLOADER=ready` / `GATE_SELFTEST=GREEN(PASS=167 FAIL=0)` /
`DDL_CONSUMER_GATE=GREEN` / `DDL_REGISTRATION_GATE=GREEN` / `CMD_REFERENCE_GATE=GREEN` /
`RESOURCE_PACK_GATE=GREEN` / `ASSET_BASELINE_GATE=GREEN` / `ASSET_REPRO_GATE=GREEN` /
`COMPILE_GATE=GREEN(BUILD_EXIT=0 SECONDS=123)` / `TEST_GATE=GREEN` /
`SUMMARY_GATE=GREEN(SUM tests=1105 failed=0 errors=0 skipped=2 modules=26)` /
`PACKAGE_GATE=GREEN(JAR_COUNT=56)` / `SINGLE_SOURCE_OF_TRUTH_GATE=GREEN` /
`VERDICT_MATRIX_CONSISTENCY_GATE=GREEN`（stage-1 打印 `N=14 done=true remaining=0 basis=7c23a35a`，
即 M14 构建时仓库最新矩阵仍为 v14；本轮 v15 一致性按矩阵 §10 自查 GREEN） /
**`MECHANICS_SPEC_GATE=GREEN(ENTRIES=137 / BY_KIND=config:78,behavior:56,java-const:3 / WEAK=0)`** /
**`MECHANICS_SPEC_REPORT=FRESH`**。

**本分支自身不含构建输入**：`git diff --name-only <M14> HEAD` 只含
`plan/**`、`state/**`、`doc/cnb/**`、`.cnb/agent-out/**`。

**本轮唯一未闭合项 = 0**。**REQUIRES_HUMAN 三项**：H-001（生产机写操作）/ U-003（真实 Paper + 真实客户端握手）/
W7-A2 四条 `[R]` 级 unproven。

### I.9 W34 最终复验（v16）—— 两条已闭环缺陷的登记（各附机械证据）+ W33 未交付

> context：`plan/gap-matrix-v16.md` + `plan/verdict-v16.json`，basis=**实测** `0237548`（= `HEAD` = `origin/main`，两者相等）。
> 结论：**done=false / remaining=1 = `W33-RUNTIME-SMOKE`**（可派工的实现缺口，见下 §I.9.3）。
> 与 v15 的差别：v15 的 basis=`c241979`；本轮 basis 前移到分叉时的 main HEAD `0237548`，
> 且 **M18 的 sha 就是本轮 basis 本身** ⇒ 十门证据的构建输入与判定 basis 同集合。
> 本轮 main 上有**新的构建输入改动**（PR#84 + `0237548`）⇒ `tests` 1105→1106、`PASS` 167→181、
> env 测试 8→7，v15 的判据行**不可沿用**。

| id | 缺陷 | 机械证据（命令 / sn / 文件:行号） | 状态 |
|---|---|---|---|
| **`G-ENV-TAG-OVERBROAD`** | **env 标签过宽**：`plugins/rpg-guide/.../HelpTopicTest.java` 的 `everyCustomHelpModelIsDeclaredByTheRuntimeResourcePack()` 唯一依赖 `resource-pack/runtime-item-models.txt` —— 该文件**在本仓**（W12–W14 已入库），却被打了 `@Tag(env)` 被门禁 `-PexcludeTags=env` 排除 ⇒ **从来没跑过**，且没有任何机制会说「这个标签打错了」。过宽的豁免 = 本该生效的检查**静默失效**。 | **修前 vs 修后**：`sh scripts/list-env-tests.sh \| wc -l` → 8 ⇒ **7**；CI `plugins/rpg-guide tests=57`（56→57，M18 stage-7 回读）；全量 `SUM tests=1105` ⇒ **1106**。**防复发判据**（PR#84 / W32-A2）：新增 `scripts/check-env-tag-justified.sh` —— 每个 `@Tag(env)` 方法必须**至少有一个它引用的路径在本仓不存在**，否则 RED；抽不到路径字面量计 `UNJUSTIFIED_UNKNOWN` 并判 RED（「抽不到就放过」正是最危险的失效形态）。判据行 `ENV_TAG_JUSTIFIED_GATE` / `ENV_TAG_METHODS` / `ENV_TAG_UNJUSTIFIED`。**M18 stage-1 回读**：`[OK  ] 真实仓库 ENV_TAG_JUSTIFIED_GATE=GREEN / ENV_TAG_METHODS=7 / ENV_TAG_UNJUSTIFIED=0`；`[OK  ] 负夹具（把 A1 去掉的标签加回去）→ 1 (exit=1)`；`[OK  ] RED 输出点名 HelpTopicTest#everyCustomHelpModelIsDeclaredByTheRuntimeResourcePack`；`[OK  ] 夹具恢复（标签去掉）→ 0`；`selftest-gates.sh` 覆盖本脚本（5 处）且 `.cnb.yml` 执行 `sh scripts/selftest-gates.sh`（§12d 接线自证）。**我 v16 独立负向对照**（不采信 NPC）：在 `/tmp/envtagfix16` 整树副本上先跑基线（7 方法 GREEN），加回该标签 ⇒ `ENV_TAG_METHODS=8` / `ENV_TAG_UNJUSTIFIED=1` / `GATE=RED` 并点名该方法（`全部引用路径都在本仓: resource-pack/runtime-item-models.txt`）；还原 ⇒ `GREEN`。 | **DONE** PR#84（`76ac736` + `c371bf7` + `eeac9e4`） |
| **`G-GATE-FIXTURE-NEEDS-GIT`** | **§12c 夹具依赖 `git` 而门禁镜像无 `git`**：新增的 §12c 夹具用了 `git archive HEAD` / `git status --porcelain`，而真实门禁镜像（`eclipse-temurin:25-jdk` + apt `curl`/`coreutils`/`python3`）**没有 git** ⇒ `git: not found` → `tarfile.ReadError: empty file` ⇒ **M17 上门禁 error、main 变红**。这是「NPC 容器 GREEN ≠ 门禁容器 GREEN」的第二次实测复现（第一次是 PyYAML，见 §I.6）。 | **修法（commit `0237548`，调度器修）**：① 整树副本改用 python `shutil.copytree`（跳过 `.git`）—— 不再依赖 git；② 残留判据改用 `plugins/` 的 **sha256 清单前后比对**；③ 新增 **§12f 回归判据**：`scripts/**` 不得对「git」发起调用（注释除外）。**M18 stage-1 回读**：`[OK  ] 判据 12f：scripts/ 层没有对「git」的调用（门禁镜像无 git） (exit=0)`；`[OK  ] env 标签判据的夹具未在真实仓库留下残留（plugins/ 的 sha256 清单前后一致）`；`===== 门禁自测汇总：PASS=181 FAIL=0 =====` / `GATE_SELFTEST=GREEN`。⇒ 门禁在载体不再依赖 git 的前提下恢复全绿，且回归判据锁住复发。 | **DONE** `0237548`（M18 十门全绿即闭环证据） |

#### I.9.1 v16 独立重跑的五组证据（不采信调度器结论）

| 组 | 判据行（实测原文） |
|---|---|
| 素材层四条 | (a) 24/24 scripts 有真实调用点（新增 `check-env-tag-justified.sh` → `selftest-gates.sh:2027/:2038/:2097/:2135`）；(b) `python3 scripts/check-asset-baseline.py` → `EXIT=0` / 252↔252 / `UNREGISTERED=0 MISSING=0 MODIFIED=0`；(c) provenance 与 manifest 归一 `assets/` 前缀后 `same_set=True`（`manifest-only=0`/`prov-only=0`）、`$needsHumanDecision=[]`、六类合计 252（match=True）；(d) items 81 == runtime 去注释键 81 且键集合相等，七项 `--check` 全 `exit=0` |
| G-302 系列 | `SOURCE_STRING_TESTS=0` / `SOURCE_STRING_GATE=GREEN` / `EXIT=0`（重定向取码）；4 类方法体抽样；三组负夹具 `EXIT=1` / `SOURCE_STRING_TESTS=3` / `GATE=RED` → 删除 → `GREEN`；反例 `LanguageBundleContractTest` 未被判红；`CHECKS=469 SPECS=79 SPEC_FILES=3` ≥ 466/78/2 |
| 机制规格绑定层 | `MECHANICS_SPEC_GATE=GREEN` / `ENTRIES=137` / `WEAK=0`；`MECHANICS_SPEC_REPORT=FRESH`；`grep import yaml` 零命中；**我的独立负向对照**：`M-CORE-003` 的 config `expect` `[2,3,4,5]`→`[2,3,4,9]` ⇒ `EXIT=1` / `GATE=RED` / 点名 `M-CORE-003`；还原 ⇒ `GREEN` |
| `G-CACHE-STAMP-BEFORE-LOCK` | `LockedTransaction.java` 存在；`wrap(:40-46)` 只包装不取号；`markLocked(:54-55)` `if (version == 0L)` 惰性一次取号；5/5 发布点 `LockedTransaction.wrap`；触发点在 `FOR UPDATE` 之后（`JdbcProfileService:484→:492`、`:667→:633`、`:648→:649`）；eager `nextCommitVersion()` 调用**为零**；3 个回归测试在（`JdbcServicesTest.java:1035/:1088/:1151`）且断言未放宽 |
| env 标签正当性层（本轮新增） | `ENV_TAG_JUSTIFIED_GATE=GREEN` / `ENV_TAG_METHODS=7` / `ENV_TAG_UNJUSTIFIED=0`；`list-env-tests.sh` 恰好 7 条；**我的独立负向对照**：加回 W32-A1 去掉的标签 ⇒ `RED` 且点名；还原 ⇒ `GREEN` |

#### I.9.2 十门在 M18 上全绿（回读核对）

`sn=cnb-8rl-1k4db6u49`（`event=api_trigger_gate`，`targetRef=main`，`sha=0237548269545974520f073a3ff254a9885004de`，
`status=success`，`pipelineSuccessCount=1 / pipelineFailCount=0`，13 stage `success` + `DebugDetection` `skipped`，
每个 success stage 的 duration 均非零）：

`DOWNLOADER=ready` / `GATE_SELFTEST=GREEN(PASS=181 FAIL=0)` / `DDL_CONSUMER_GATE=GREEN(81 表, 豁免 0)` /
`DDL_REGISTRATION_GATE=GREEN(扫描 551 文件, 反向豁免 0)` / `CMD_REFERENCE_GATE=GREEN(43 指令, 豁免 9)` /
`RESOURCE_PACK_GATE=GREEN(RUN=8 SKIPPED=0)` / `ASSET_BASELINE_GATE=GREEN(252↔252)` /
`ASSET_REPRO_GATE=GREEN(REPRODUCIBLE=70 MISMATCH=0)` / `COMPILE_GATE=GREEN(BUILD_EXIT=0)` /
`TEST_GATE=GREEN(TEST_EXIT=0 SUMMARY_EXIT=0 SECONDS=67)` /
`SUMMARY_GATE=GREEN(SUM tests=1106 failed=0 errors=0 skipped=2 modules=26, NO_XML modules=0 期望 26)` /
`PACKAGE_GATE=GREEN(JAR_COUNT=56, JAR_CHECKED=25 JAR_MISSING_PLUGIN_YML=0)` /
`SINGLE_SOURCE_OF_TRUTH_GATE=GREEN(WORLD_STATE_FILES=1)` / `VERDICT_MATRIX_CONSISTENCY_GATE=GREEN` /
**`MECHANICS_SPEC_GATE=GREEN(ENTRIES=137 / BY_KIND=config:78,behavior:56,java-const:3 / WEAK=0)`** /
**`MECHANICS_SPEC_REPORT=FRESH`** / **`ENV_TAG_JUSTIFIED_GATE=GREEN(ENV_TAG_METHODS=7 / ENV_TAG_UNJUSTIFIED=0)`**。

同 sha `0237548` 上另一个 main 构建 `cnb-l5u-1k4db6aug`（`push`）亦 `success` ⇒
`G-FLAKY-GATE` 本轮 **RED=0**（v11 的「同 sha 先绿后红」形态未复现）。

#### I.9.3 未交付：`W33-RUNTIME-SMOKE`（**可派工**实现缺口，不属 REQUIRES_HUMAN）

调度器尝试为「真实 Paper 运行时冒烟」建立 harness（W33），**未交付**，机械证据：

```
$ git log --oneline origin/main..origin/agent/W33-A1-runtime-smoke
(空 —— 零提交)
$ git diff --name-only origin/main origin/agent/W33-A1-runtime-smoke
(空 —— 无差异)
$ git log -1 --format='%H' origin/agent/W33-A1-runtime-smoke
0237548269545974520f073a3ff254a9885004de       # == main HEAD，分支指针未动
$ cnb pulls list-pulls --repo xzqkjmnbvc2026/sivitahub --state open
total: 0                                        # 无任何 open PR
$ grep -rln 'W33' plan/ .cnb/agent-out/ doc/cnb/ state/
(空 —— 无任何产出文件登记)
```

已知失败模式：**构建超过 60 分钟超时**。

判定：这**构成一个可派工的实现缺口**（不需要人类操作 —— 只需在 CNB 上做出能真实启动 Paper /
采集运行时证据的 harness，并把耗时收敛到 CI 预算内）⇒ `verdict-v16` 判 `done=false` / `remaining=1`，
派单见 `plan/tasks-w34.json`（`W34-A2-runtime-smoke`）。**不**把它当成已完成的证据，
也**不**把它塞进 `needs_human`（`needs_human` 只许列 H-001 / U-003 / W7-A2 的 4 条）。

### I.10 W35/W36 真实 Paper 运行时冒烟：harness 落仓 + 锁清单来源不一致修复 + 预检前移

> context：W33 未交付（§I.9.3）⇒ W35 拆小重派（`doc/cnb/tasks-w35.json`），
> 交付优先级写明「先把三样交付物写完并 commit+push，再去实跑」。
> 结论：harness 已落仓（PR#86 / `f4e4f43`）；**首次实跑 RED**，根因是锁清单里一条自相矛盾，
> 已修（W36），并把这类纯静态错误前移到脚本开头的预检。

#### I.10.1 W35 交付（已落仓）

| 交付物 | 说明 |
|---|---|
| `scripts/run-paper-smoke.sh` | 真实 Paper 26.2 + 25 个自研插件 + 15 个社区插件，`mktemp -d` 建服务端（不在仓库里生成 `server/`），等 `Done (` → 写 `stop` → 分类统计异常行 → 打印判据行 |
| `.cnb.yml` 新增 `api_trigger_smoke` | 独立事件，**不接进十门**（十门是静态/单元层，冒烟要外网下载 + 真跑服务端） |
| `docs/RUNTIME_SMOKE.md` | 判据行、GREEN 三条件、已知边界（明确不覆盖的东西） |
| `scripts/runtime-smoke-plugins.lock.json` | 15 个社区插件锁清单（W33 派单 goal 内嵌 JSON 落盘） |
| `scripts/runtime-smoke-own-plugins.txt` + `scripts/gen-runtime-smoke-own-plugins.py` | 25 个自研插件的模块→插件名清单（从 `plugin.yml` 机械生成，数量不等于 25 即非零退出） |

PR = #86（`f4e4f43`）。

#### I.10.2 缺陷 `G-SMOKE-LOCK-SOURCE-MISMATCH`：锁清单一条的来源自相矛盾（**已修**）

| 项 | 内容 |
|---|---|
| **现象** | 首次实跑 `sn=cnb-kus-1k4djem3f` 在「15 个社区插件」这一步 RED：`[smoke] 有 1 个社区插件不可用：` / `[smoke]    - AuthMeReloaded（https://cdn.modrinth.com/data/9js4IEHC/versions/Kpko24V8/AuthMe-6.0.0-Paper.jar 下载/校验失败）`。**其余 14/15 全 OK** |
| **根因** | 该条把 **Sivita 自改版**的 `sha512` 配了**公网 Modrinth 上游版**的 `url` —— 两个来源对不上，校验必然失败。不是网络问题，也不是上游删构建 |
| **机械证据** | `sha512sum lamb:/home/lamb/minecraft/server/vendor/authme/AuthMe-6.0.0-Sivita-Paper.jar` = `5134afaa3fed32d384a077b3d6ffe3e0b2fe08353d365df2867f65dbecf5a3f53745355330b48312c52a197864a203a92437d3ce24e22a0770f81c5f28bfe4f1` **= 锁清单里写的值**；生产机 `runtime/plugins/AuthMe-6.0.0-Paper.jar` 同 sha512（1131386 字节，8月 8 02:14）⇒ 锁清单的值取自自改版，url 却指向上游 |
| **修法** | 该条 `url` → `local: libs/vendor/AuthMe-6.0.0-Sivita-Paper.jar`。**sha512 一个字节没动**（保持「与生产机一致」这个更强的性质），只把来源改对 |
| **入库** | `libs/vendor/AuthMe-6.0.0-Sivita-Paper.jar` + `libs/vendor/AuthMe-6.0.0-Sivita.patch` 入库；`.gitignore` 已有 `!libs/vendor/*.jar` 放行 |
| **溯源（可复现）** | 上游 `AuthMe/AuthMeReloaded` tag `6.0.0` / commit `2ff4970ad405d083f858198a1cd0a3e9f6bda587`；补丁 13 个文件（4 主代码 + 4 测试在 `authme-core`，2 主代码 + 2 测试在 `authme-paper-common`，+ `pom.xml`）；构建身份可机检 `plugin.yml` 里 `version: 6.0.0-bSIVITA1`；`-Dproject.build.outputTimestamp=<commit 时间戳>` ⇒ 逐字节可复现。完整说明见 `libs/vendor/README.md` |
| **为什么不是「把断言放宽」** | 社区插件不可用是**真缺口**（少装一个插件就少一类真实环境）。放宽成「社区插件缺失不判 RED」会让冒烟失去「与生产机同一套插件」这个性质 —— 所以是修清单，不是改判据 |
| **顺带修的三处不实声明** | ① 锁清单的 `generated_by: scripts/gen-runtime-smoke-plugin-lock.py` 指向一个**从未落仓**的文件（典型「声明了但没接线」）→ 改为 `provenance` 说明手工落盘 ② `run-paper-smoke.sh:61` 注释写 `gen-runtime-smoke-manifest.py`，实际文件是 `gen-runtime-smoke-own-plugins.py` ③ `libs/vendor/README.md` 缺 BetterHud 行，且开头「只用于 `compileOnly`（编译期），**不参与运行时加载**」与事实相反（4 个 vendor jar 同时也在冒烟运行时清单里） |

#### I.10.3 W36 新增：锁清单静态预检 + **常驻**负向夹具

这条缺陷纯静态可查，却花了一次约 10 分钟的云上冒烟才发现。所以把它前移：

| 项 | 内容 |
|---|---|
| 脚本 | `scripts/check-smoke-lock.py`（stdlib，只用 `json`/`hashlib`/`os`/`tempfile`） |
| 判据行 | `SMOKE_LOCK_CHECK=PASS\|FAIL` / `SMOKE_LOCK_ENTRIES` / `SMOKE_LOCK_LOCAL` / `SMOKE_LOCK_PROBLEMS` / `SMOKE_LOCK_SELFTEST=PASS=n FAIL=m` |
| 查什么 | 清单可解析；`plugins` 非空数组；每条恰好有 `url` 或 `local` 之一；`sha512` 是 128 位十六进制串；`name` / `file` 不重复；`local` 指向的文件**确实在仓库里**且 sha512 对得上 |
| 负向夹具 | 10 组**常驻**变异（`--selftest`），且**要求红在预期原因上**，不只是红 |
| 接线 | `run-paper-smoke.sh` 开头先跑 `--selftest` 再查真实清单 —— 只查真实清单只能证明「现在没报错」，不能证明「判据还认得出错」。两步都只读本地文件，毫秒级、不碰网络 |
| 本地实测 | `SMOKE_LOCK_ENTRIES=15 / SMOKE_LOCK_LOCAL=5 / SMOKE_LOCK_PROBLEMS=0 / SMOKE_LOCK_CHECK=PASS`；`SMOKE_LOCK_SELFTEST=PASS=10 FAIL=0`；`sh -n scripts/run-paper-smoke.sh` → 通过 |
| **夹具抓到我自己的缺陷** | 第一版「sha512 与清单不符」夹具把 `local` 指向一个**不存在**的文件 ⇒ 先撞上「local 文件不存在」分支，sha512 比较**根本没跑到**。10 组里 1 组「红了但红在别的原因上」= 等于没测到。修法：该夹具的 `local` 必须指向**确实存在**的文件（取清单里真实的 local 路径，退化时用 `README.md` 顶上）。这正是 §L 那条规矩（「每加一条判据都要配负向夹具」）的又一次实测 —— 而且证明「只判退出码不看原因」是不够的 |

#### I.10.4 缺陷 `G-SMOKE-MISSING-FETCH-DEPS`：harness 没跑 `fetch-deps`，`assemble` 必红（**已修**）

| 项 | 内容 |
|---|---|
| **现象** | 修完 §I.10.2 后重跑（`sn=cnb-3gl-1k4dlibvp`，stage 耗时 3093s ≈ 51.5 min）仍 RED，但失败点**前移到 assemble**：`GRADLE_ASSEMBLE=FAILED rc=1`，`> Task :plugins:rpg-classes:compileJava FAILED`，4 个错误 —— `package fr.xephi.authme.api.v3 does not exist` / `symbol: variable AuthMeApi` / `package fr.xephi.authme.events does not exist` / `symbol: class LogoutEvent`（`BUILD FAILED in 1m 49s`，17 actionable tasks） |
| **根因** | `libs/*.jar` 被 `.gitignore` 忽略，靠 `scripts/fetch-deps.sh` 拉到本地；十门的**两处**编译门都在 gradle 之前跑它（`.cnb.yml:179` 与 `:458` 的 `sh scripts/fetch-deps.sh` + `sh scripts/check-file-deps.sh`）。**harness 漏了这一步** ⇒ 干净克隆里 `libs/AuthMe-6.0.0-Paper.jar` 不存在 ⇒ `compileOnly(files(rootProject.file("libs/AuthMe-6.0.0-Paper.jar")))` 解不出符号 |
| **为什么第一轮没暴露** | 第一轮实跑在更早的「15 个社区插件」就 RED 了（§I.10.2），**根本没走到 assemble**。修好锁清单才把它顶出来 —— 「一次只暴露一个缺陷」是串联链路的常态，所以「修完必须重跑」不是仪式 |
| **修法** | assemble 之前加 `sh scripts/fetch-deps.sh`（失败即 RED，打印日志尾部）与 `sh scripts/check-file-deps.sh`（与编译门同口径、同镜像 `eclipse-temurin:25-jdk`），保证「冒烟能编」与「编译门能编」的前置条件**一致** |
| **同族规矩（值得记）** | **凡是要跑 gradle 的地方，都要先 `fetch-deps` + `check-file-deps`。** 「本地能编」不等于「干净克隆能编」—— 本地 `libs/` 里那些 jar 早就下过了，所以本地永远不会暴露这个缺陷 |
| **顺带（诊断能力）** | 新增**分阶段计时**：shell 侧 `tick()`、python 侧 `mark()`，每阶段打一行 `---- <阶段>（自脚本启动 <n>s）----`。理由：这个 stage 能跑到 51 分钟，没有分段时间就只能猜钱花在哪一步 —— 调优前先看这些行，不要凭直觉改 |

#### I.10.5 取证工具缺陷 `G-GATE-LOG-TRUNCATED`：`hits` 为空不是「没打判据行」，是**证据被截掉了**（**已修**）

| 项 | 内容 |
|---|---|
| **现象** | `plan/smoke-run-w36.json` 的 `hits` 为**空数组** —— 一行判据行都没抽到。表面看像「脚本没打判据行」，实际脚本明明打了 |
| **真因** | `scripts/run_gate.py` 调用 `collect_stage_logs(tail_chars=6000)`，只保留每个 stage 的**末尾 6000 字符**。这个 stage 跑 51 分钟、gradle 打了上万行 ⇒ 开头的 `SMOKE_LOCK_*` 全被截掉。**是取证工具把证据截没了**，不是判据没打 |
| **修法** | ① `run_gate.py` 把 `--tail-chars` 提为参数，默认 `2000000`，注释写明「判据行可能出现在 stage 的任意位置（开头的前置检查、末尾的汇总），别调小」；② `KEY_PATTERNS` 补 `SMOKE_LOCK_CHECK` / `_ENTRIES` / `_LOCAL` / `_PROBLEMS` / `_SELFTEST` |
| **教训** | 「判据行没抽到」有**两种**可能：脚本没打，或取证工具截掉了。先看 `.stages.txt` 的实际长度与内容，再下结论 —— 否则会把工具缺陷误判成产品缺陷（本轮差点如此） |

#### I.10.6 缺陷 `G-SMOKE-CRITERION-UNRETRIEVABLE`：退出码 GREEN，但**判据行取不回来**（**未修，需人类决策**）

> 这一条是 §I.10.5 修好之后才浮出来的**更深一层**问题。
> §I.10.5 修的是「本地取证脚本把自己截断了」；这一条是**平台侧交付的日志窗口本身就是有界的**，
> 本地脚本无论怎么调 `--tail-chars` 都拿不到。

| 项 | 内容 |
|---|---|
| **现象** | 第二轮冒烟 `sn=cnb-3h3-1k4dosi86`（sha `761b416`，即**已含 §I.10.4 修复**的版本）整体 `status=success`，stage-1 末行是 `Finished, code: 0, duration: 44m 39s`，`error` 字段为空。**但整段 stage 日志里一行 `PAPER_SMOKE_GATE=` / `PAPER_STARTED=` / `PAPER_PLUGINS_ENABLED=` / `PAPER_OWN_ERRORS=` 都取不到**，连我自己新加的 `FETCH_DEPS=OK` / `CHECK_FILE_DEPS=OK` / `开始 gradle assemble` 也取不到 |
| **交付窗口是有界的（实测）** | 连续 3 次调 `stage_detail(stage-1)`，返回恒为 **186 行 / 10720 字符**，末行固定在 `插件 12/15 BetterModel ... 92%`。**窗口从 stage 开头起算，到窗口上限截断**，不会给尾部 |
| **为什么第一轮能拿到尾部** | 第一轮 `sn=cnb-3gl-1k4dlibvp` 是**失败**（`code: 1`），平台侧同样返回 363 行 / 21171 字符 —— 但它的 stage **提前结束**（失败即 `SystemExit(1)`，没跑到 50 分钟），总输出短，所以「有界窗口」正好覆盖到了结尾的 `GRADLE_ASSEMBLE=FAILED` 与 4 条编译错误。**这不是尾巴，是「短到能被完整交付」** |
| **两轮对比（决定性）** | ① 失败轮 363 行里**有** `GRADLE_ASSEMBLE=FAILED`；② 成功轮 186 行里**没有**任何 `PAPER_*`。若只看 `hits`，会得出「成功轮什么都没打」的错误结论 —— 实际是窗口覆盖不到 |
| **为什么这是真缺口** | `run-paper-smoke.sh` 的 GREEN 判据（`PAPER_STARTED=YES` 且 25/25 enabled 且 `PAPER_OWN_ERRORS=0`）**只以判据行为准**。现在退出码说 GREEN，但判据行落在交付窗口之外 ⇒ **GREEN 无法被机械引用**。按本仓纪律「只认机械判据」，这等于 **`[R] Runtime` 仍然拿不到** —— 与「脚本返回 0」不是一回事 |
| **为什么不能靠放宽判据绕过** | 若把「退出码 0」直接当 `PAPER_SMOKE_GATE=GREEN`，就等于**用平台的 stage 状态冒充脚本判据**。而 stage 状态的语义只到「这条命令没非零退出」，不含「25 个插件真 enabled / 自研插件零异常」这两个关键性质 —— 恰恰是冒烟存在的理由（4 条 `[R]` unproven 全挂在这上面） |
| **可选的修法（举二留一，需人类定）** | ① **脚本自己把判据行落仓**：跑完把 `PAPER_*` 写进一个文件并 `git commit + push` 到一个证据分支（RNB 里已有先例：`run_gate.py` 触发后回读 `plan/*.json`）。代价：冒烟 job 需要 push 权限，且要处理并发写同一分支。② **把判据行前移到 stage 早段打印**：脚本一进 assemble 前就 `echo PAPER_SMOKE_GATE=PENDING` 之类占位、最终值另起一个**极短的新 stage**（只 cat 证据文件）—— 让判据行落在一个「总输出很短、必然能被完整交付」的 stage 里。代价：改动 `.cnb.yml` 增加一个 stage |
| **本轮的处置** | **不擅自选**。已把两轮完整证据（`plan/smoke-run-w36.json` 失败轮 / `plan/smoke-run-w36b.json*` 成功轮）落仓，并在 `plan/verdict-v17.json` 里把该条登记为 **`REQUIRES_HUMAN`**：在人类选定修法前，**冒烟不得被引用为 `[R]` 证据**，「4 条 `[R]` unproven」状态维持不变 |

#### I.10.7 顺带发现（我方 harness 的**心跳缺口**，非阻塞）

| 项 | 内容 |
|---|---|
| **现象** | `run-paper-smoke.sh` 为满足 CNB「10 分钟无输出即杀」（实测 `sn=cnb-80a-1k4dhivkj`）而设计「插件下载逐条打分 + 每 5 秒读 gradle 增量」，但 `fetch-deps.sh` 与 `check-file-deps.sh` 是**子进程、输出重定向到文件**，成功时**一行都不吐到 stdout** |
| **量化** | `fetch-deps.sh` 只在**全部就绪**时打一行（`echo "fetch-deps: 全部就绪…"`，见 `scripts/fetch-deps.sh:241`）；它内部逐个下载的过程**不进 stdout**。`check-file-deps.sh` 同理（输出进 `cf.stdout`，只在失败时 `say`） |
| **风险** | 这两个脚本在冷缓存下要下载 5 个公开源 artifact。若单次下载慢到超过 10 分钟且期间零输出，**整个 stage 会被平台看门狗杀掉** —— 表现为「莫名其妙地红/断」，而根因与产品代码无关。**本轮未触发**（实测 `fetch-deps` 段落没出现长静默），所以只记账、不改 |
| **建议修法（记账待办）** | 把 `fetch-deps.sh` / `check-file-deps.sh` 改成 `stdout=PIPE` 逐行转发 + `say("fetch| ...")` 前缀，或在调用前后各打一行 `mark()` 保证心搏（后者更省事，但长静默期内仍无输出） |

### I.11 W36-A5 最终复验（v18）—— 十门被 **CNB 根组织 CPU 配额** 阻塞（`BLOCKED_EXTERNAL`）+ 素材可复现性门禁首次本地取得

本轮目的是在**当前 main**（`d2cffd2`）上取得十门判据行。结果三条：**十门被平台配额阻塞**（新增缺口）、
**素材可复现性门禁首次取得 GREEN**（v17 的欠账补齐）、**顺带修复一条判据可移植性缺陷**。

#### I.11.1 缺口 `CNB-QUOTA-EXHAUSTED`（`BLOCKED_EXTERNAL`，**外部阻塞，非产品缺陷**）

| 项 | 内容 |
|---|---|
| **现象** | `start_build(api_trigger_gate, branch=main, sha=d2cffd2)` 返回 `{"success":true}`（**受理成功**），但约 4 秒后 `build_status` 即为 `error`；`Prepare` stage `status=error`，**其余十门 stage 全部 `skipped`**；`collect_stage_logs` 的 `hits=[]`（空） |
| **根因（`Prepare` stage 末尾 `content` 原文）** | `Pipeline prepare error: Root Group's events CPU core-hours are insufficient for pre-freezing(Freezing time:5.00 min,equivalent to 0.67 core-hours). Contact the root group administrator to extend.` / 中文：`根组织的云原生构建-CPU配额已不够预冻结(冻结时间：5.00 min，折合0.67核时)，请联系根组织管理员提升配额。` |
| **作用域 = 根组织级（实测）** | 三次触发（`api_trigger_gate` ×2：`cnb-fef-1k4ot1qvq` / `cnb-u8e-1k4ot4o4n`；`api_trigger_smoke` ×1：`cnb-r2i-1k4ot3gs2`）**报同一句话** ⇒ 与事件、分支、流水线无关，是**全局配额** |
| **为什么改 `.cnb.yml` 没用** | `Prepare` 是**平台自带 stage**（拉 runner / 起 git-clone / 起 network-bus / 预冻结配额），**不在** `.cnb.yml` 的 stage 列表里。改 job `timeout` / stage 顺序 / `if:` 条件**全部无效** |
| **为什么不能重试绕过** | 配额是组织级共享资源；本轮连续 3 次同错 ⇒ 重试只会继续消耗并刷屏，**无意义**。应停手、升级到管理员 |
| **处置** | 判 `BLOCKED_EXTERNAL`（**不是**产品缺陷，**不是**可派工实现缺口），升级对象 = CNB 根组织管理员（提升 `云原生构建-CPU` 配额） |
| **副产品（有用的）** | `Prepare` 的 git-clone 日志打印了它真正 checkout 的 sha：`$ git rev-parse HEAD` → `d2cffd27632ba9a623c957397fe168bca753ff7f`。⇒ **即使十门没跑，也能机械证明「平台确实按我给的 basis 拉到了代码」**（比只写「我请求了 d2cffd2」强得多） |
| **教训** | `start_build` 返回 `success` **只表示「受理」**，不表示「构建已开始」。**触发后第一件事是看 `Prepare` 的 `status`**：`error` + 配额文案 ⇒ 立刻停手，别去 `collect_stage_logs` 抽判据行（只会拿到一叠 `skipped`，白等一轮）。这条已进 `cnb-workflow` skill 硬规则 14 与 `references/pitfalls.md` §X |

#### I.11.2 素材可复现性门禁（`ASSET_REPRO_GATE`）—— **本会话首次取得**

v17 §8 明写「本容器无 JDK，素材可复现性门禁未本地复跑」，只以旧 stage-5 判据行为依据。本轮在干净 LF 树上亲自重跑：

```
ASSET_REPRO: 比对 70 张（4 个生成器）
  逐像素一致: 70
  REPRODUCIBLE=70 MISMATCH=0 MISSING=0 UNREGISTERED=0
ASSET_REPRO_GATE=GREEN
[repro] 罗盘模型 JSON：一致 32/32
```

⇒ 取得机械判据行。两个「张数」口径必须分清（**不互相冒充**）：
- **70** = `resource-pack/asset-reproducible.json` 的覆盖数（有可编译 Java 生成器、且产物在仓库里）；
- **129** = `resource-pack/asset-provenance.json` 的 `$counts.generator`（声明为生成器派生的条目总数）。

#### I.11.3 缺陷 `ASSET-REPRO-JAVAC-ENCODING`（**已修**）

| 项 | 内容 |
|---|---|
| **现象** | 在本机（Temurin 17 / `zh_CN`，javac 默认源编码 GBK）跑 `sh scripts/check-asset-reproducible.sh`，`javac` 报 58 个「编码 GBK 的不可映射字符」，退出码非 0 ⇒ `ASSET_REPRO_GATE` 整门**假红** |
| **真因** | 脚本调 `javac` 时**未指定源编码**。生成器源码含 UTF-8 中文注释，而 `javac` 默认源编码**随平台走**。CNB 的 `eclipse-temurin:25-jdk` 默认 UTF-8，所以这个问题在 CI **从未暴露** |
| **为什么这是真缺陷** | 一个「只在 UTF-8 平台 GREEN」的判据是**不可移植的假门禁** —— 换一台默认非 UTF-8 的机器就恒红，会被当成「环境噪音」而放宽，最终判据失效 |
| **修法** | `javac` 加 `-encoding UTF-8`；4 处 `java` 运行加 `-Dfile.encoding=UTF-8`。分支 `codex/asset-repro-encoding-fix`（`6308da5`） |
| **效果（实测）** | 加前：58 个不可映射字符错误、EXIT≠0；加后：4 个生成器全部编译通过、逐像素比对 GREEN、EXIT=0 |
| **教训** | 判据脚本里调外部编译器/解释器时，**任何依赖「平台默认值」的隐含参数都要显式化**（源编码、区域、时区、默认字符集）。CNB 上 GREEN 不等于「判据是对的」，可能只是「CI 镜像的默认值恰好对」 |

#### I.11.4 本轮本地九层判据行（basis 实际为 `6308da5` = `d2cffd2` + I.11.3 修复）

| 层 | 判据行 | 值 |
|---|---|---|
| (a) | `scripts/*.sh\|*.py` 计数 | `27` |
| (b) | `ASSET_BASELINE_GATE` | `GREEN`（252 张 / UNREGISTERED=0 MISSING=0 MODIFIED=0） |
| (c) | provenance/manifest 一致 | `252 张一致`；`$needsHumanDecision=[]` |
| (d) | `RESOURCE_PACK_GATE` | `GREEN`（RUN=8 SKIPPED=0） |
| (e) | `SOURCE_STRING_GATE` | `GREEN`（TESTS=0） |
| (f) | `SOURCE_CONTRACT_CHECKS` | `469 SPECS=79 SPEC_FILES=3` |
| (g) | `MECHANICS_SPEC_GATE` | `GREEN`（ENTRIES=137 / WEAK=0） |
| (h) | `ENV_TAG_JUSTIFIED_GATE` | `GREEN`（7/7/0） |
| (i) | `ASSET_REPRO_GATE` | `GREEN`（REPRODUCIBLE=70 MISMATCH=0） |

⇒ **本地九层 GREEN ≠ 十门 GREEN**：两者跑在不同镜像与 stage 编排里。本轮**没有**在 `d2cffd2` 上取得任何来自 CNB 流水线的 `*_GATE=` 行。

