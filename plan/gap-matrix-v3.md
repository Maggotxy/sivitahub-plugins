# 全量机制矩阵 v3（gap-matrix-v3）

> 生成者：CNB CodeBuddy NPC，任务 `W5-A3-final-planner`（收口轮）。**只读审计 + 规划**，未改 `plugins/**/src/main` 任何源码。
> 基点：`main` @ `d11f7d8`。分支 `agent/W5-A3-final-planner`。
> 判定口径来自 `doc/cnb/GOAL.md` §2 与 `doc/cnb/decisions-locked.md` L-008：
> 机制算「已接线」当且仅当对它的类/方法/表名在 `plugins/*/src/main` + `tools/*/src/main` 全量 grep 存在**非自身定义文件**的调用点；
> 只命中自身定义文件、或只命中 `src/test`、或只有 DDL/枚举 = 「声明未接线」。
> 本文件正文不出现收口轮禁止的三个含糊词（自检断言在生成脚本里）。

## 与 v2（`plan/gap-matrix-v2.md`，基点 `7163984`）的差异

| 项 | v2 判定 | v3 判定 | 变化的依据（命令见 §2） |
|---|---|---|---|
| G-101 换季驱动入口 | 部分接线（`preview()`/`advance()` 零消费者） | **已接线** | E-05：`SeasonSettlementCommand.java:112` / `:163` |
| G-107 下半（技能点本地里程碑） | 存疑（未落地，`finalEffective / 3` 两处） | **已接线** | E-13：`finalEffective / 3` 零命中；改走 `LOCAL_LEVELS_PER_SKILL_POINT` |
| G-115 `isGovernance()` | 声明未接线 | **已接线** | E-08：`CommunityRolePower.java:50,53`、`CommunityGovernanceHandler.java:128` |
| G-110 的 `DESTROYED` | 声明未接线（只有审计只读） | **已接线** | E-16：`DestroyCommand.java:97,131,147,155` + `/destroy` 注册 |
| G-110 的 `SEASON_ATTUNED` | 声明未接线 | **声明未接线（不变）** | E-17：除 `EquipmentLifecycle.java` 外零命中 |
| G-302 伪测试类数 | 50 | **53** | E-26：口径含 `tools/` 后为 53，其中 3 个已行为化 ⇒ 剩 50 |
| G-109 零消费者表 | 4 张 | **4 张（不变）** | E-23 |
| G-206 数据包不在仓 | 声明未接线 | **声明未接线（不变）** | §2 E-24 段与 M-24 |
| **G-DDL-REG（新登记）** | 未登记 | **声明未接线** | E-14：`rpg_skill_point_grants` 在 `Database.java`/`SchemaManifest.java` 零命中，但 `JdbcProfileService.java:513,529` 真实读写 |
| **G-TEST-GATE（新登记）** | 未登记 | **真实缺口** | E-25：main @ d11f7d8 的实测门 `sn=cnb-0ck-1k3tugbvb` 在 test stage 红 |

## 0. 覆盖范围与取证方式（本轮实测）

| 项 | 数量 | 取证命令 |
|---|---|---|
| `plugins/` 下模块 | 25 | `ls plugins/ \| wc -l` |
| `src/main` Java 文件（全量，非抽样） | 522 | `find plugins -path '*/src/main/*' -name '*.java' \| wc -l` |
| `src/test` Java 文件 | 267 | `find plugins -path '*/src/test/*' -name '*.java' \| wc -l` |
| DDL 表（去重） | 79 | `sh scripts/check-ddl-consumers.sh` → `DDL 表数: 79` |
| DDL 消费者门禁 | GREEN | 同上，`DDL_CONSUMER_GATE=GREEN`，本地实测退出码 0 |
| 命令引用门禁 | GREEN | 同上，`CMD_REFERENCE_GATE=GREEN` / `已注册指令数: 43` |
| 机制矩阵条目 | 50 | 每条自带 grep 原文，见 §2 |

**本轮取证的两个硬限制（如实说明）**

1. **本容器没有 JDK**（E-28：`command -v java` 无输出、`/usr/lib/jvm` 不存在）。
   因此 compile / test / assemble 三门**不能在本容器本地复跑**，只能用 CNB 上已有的门运行记录作证据（E-25）。
   本地能复跑的只有 `sh scripts/check-ddl-consumers.sh`（纯文本判定，不需要 JDK）。
2. **配置键的取证边界（与 v1/v2 一致）**：25 个模块共 1900+ 个 YAML 叶子键，本项目大量用
   `getConfigurationSection(prefix)` + `getKeys(false)` 动态遍历，配置键**无法用字面量 grep 机械判定未接线**。
   本轮不对配置键做未接线判定；已机械核实的只有 `dungeons.*.world`（G-108）与 `black-market.minimum-roundtrip-loss`（G-208）。

## 1. 机制级矩阵

判定取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口`。

| 机制/模块 | 声明的载体 | src/main 真实调用点 | 行为级测试 | 判定 | gap id |
|---|---|---|---|---|---|
| Kill Switch 开关判定/切换（C-005 第4腿）<br>`rpg-core` | `MechanismRegistry.requireEnabled(String)`；表 `rpg_mechanism_switches` | `JdbcActivityEconomyService.java:71,72,73,351`；`JdbcCommerceService.java:193,540`；`JdbcMarketService.java:53`；`JdbcSeasonExpeditionService.java:75`；`JdbcDungeonRewardService.java:89`；`JdbcSeasonSettlementService.java:104`；`JdbcCommunityService.java:613`（7 个 faucet 入口） | `MechanismKillSwitchContractTest`（W4-A4 已行为化：反射 + 真库断言关闭后换季被 BLOCKED 且零落库） | 已接线 | G-002 (W1-A2 / `cd097b8`) |
| 五维防御 / 装备 `defense` 词条在 PVE/PVP 生效<br>`rpg-combat` | `ExecuteListener.defenseMultiplier(Player)` | `ExecuteListener.java:155`（`onDamage(EntityDamageByEntityEvent)` 内，即互砍路径） | `DefenseWiringTest`（事件级 4 例，负向对照删调用点变红） | 已接线 | G-001 (W1-A1 / `056b3e1`) |
| 装备 HMAC 签名密钥 env 唯一来源 / 轮换<br>`rpg-items` | `EquipmentSigningMaterial.CURRENT_KEY_ENV` | `EquipmentSigningMaterial.java:25`（env 名常量）；消费方 `EquipmentSecurity` 构造注入 | `EquipmentSigningKeyWiringContractTest`；W1-A3 报 12 例行为级 | 已接线 | G-003 (W1-A3 / `9aa2e2b`) |
| 迁移 advisory lock 在线/离线唯一<br>`rpg-core` + `tools/db-migration` | `Database.MIGRATION_LOCK_NAMESPACE/RESOURCE` | `Database.java:20,21,1132,1133`；工具侧 `MigrationAdvisoryLock.java:18,20` 同值 | `DatabaseRulesTest.java:65` | 已接线 | G-004 (W1-A4 / `ecae668`) |
| 赛季结算九态状态机 + `SeasonSettlementService`（C-010）<br>`rpg-api`/`rpg-core`/`rpg-admin` | `SeasonSettlementService.state/preview/advance`；表 `rpg_season_settlements` | `state()` → `RpgCommand.java:114`、`RpgCorePlugin.java:287` 注册<br>**`preview()` → `rpg-admin/.../SeasonSettlementCommand.java:112`**<br>**`advance()` → `rpg-admin/.../SeasonSettlementCommand.java:163`**<br>入口注册 → `RpgAdminPlugin.java:85,705`；`plugin.yml` 的 `/rpgadmin season ...` usage | `SeasonSettlementCommandTest`、`SeasonSettlementWiringTest`（rpg-admin，行为级 H2）；`JdbcSeasonSettlementServiceTest`（13 例） | **已接线** | G-101 (W2-D1 + W4-A1 / `fa9d0ef`) |
| 多服前置 Session Lease（C-014）<br>`rpg-core` | 表 `rpg_session_leases`；`JdbcSessionLease` | `JdbcSessionLease.java:71`（SELECT 读侧判定） | `JdbcServicesTest`（W2-D5 行为级） | 已接线（只读侧） | G-102 (W2-D5) |
| 多服前置 Transactional Outbox（C-014）<br>`rpg-core` | 表 `rpg_outbox`；`JdbcOutboxRegistry`/`OutboxDispatcher` | `JdbcOutboxRegistry.java:36`（INSERT）；`OutboxDispatcher.java:112,143,165`（SELECT/UPDATE） | `JdbcServicesTest`（W2-D5 行为级） | 已接线 | G-102 (W2-D5) |
| 社群七角色 / 双批审批（Phase 6）<br>`rpg-api`/`rpg-core`/`rpg-community` | `CommunityRole.canApprove()`；`CommunityRolePower`；表 `rpg_community_approvals` | `CommunityRolePower.java:37,39,41,54,71`；`CommunityGovernanceHandler.java:116`；`JdbcCommunityService.java:799,966,976`；表读写 `:1015,1037,1062,1079,1103` | `JdbcCommunityGovernanceTest`、`CommunityGovernanceGateTest`（W2-D4 行为级） | 已接线 | G-103 (W2-D4) |
| 社群治理角色判定 `CommunityRole.isGovernance()`<br>`rpg-api` | `CommunityRole.isGovernance()` | `CommunityRolePower.java:50`（`case ASSIGN_ROLE ->`）、`:53`（`case GOVERNANCE_ACTION ->`）；`CommunityGovernanceHandler.java:128`（角色说明行键分组） | `CommunityRolePowerTest`；`JdbcCommunityGovernanceTest`（W4-A3 新增 3 例对谓词敏感） | **已接线** | G-115 (W4-A3 / `6271d4f`) |
| 邮箱交付冻结：创造模式容器事件 + 游标槽<br>`rpg-market` | `MarketCommand.onCreativeClick`；`DeliveryFreezeRules.CURSOR_SLOT` | `MarketCommand.java:761`（`InventoryCreativeEvent` 处理器）、`:41` import | `MailboxClaimReconcilerTest`、`DeliveryFreezeRulesTest`、`DeliveryFreezeWiringTest`（W2-B1） | 已接线 | G-104 (W2-B1) |
| 战斗总线单一结算（消除攻方增益二次放大）<br>`rpg-skills`/`rpg-combat` | `SkillsCommand.settleAttackerBonus`（HIGHEST 单点）+ `AttackerBonusTag` 幂等 | `SkillsCommand.java:1000,1003,1042`（单点写入）、`:952`（只推进层数不写伤害） | `AttackerBonusTest`（真构造 `EntityDamageByEntityEvent` 断言最终伤害，W2-B2） | 已接线 | G-105 (W2-B2) |
| 血契（BLOODPACT）按文档条件可达成<br>`rpg-api`/`rpg-skills` | `HiddenPathRules.minimumAscension()=2` + `requiredSkill()=vanished-horizon`；`config.yml` 的 `vanished-horizon.minimum-ascension` | `JdbcHiddenPathService.java:114,122`；`HiddenPathReachability.java:74,131`；`RpgSkillsPlugin.java:181-184`（启动自检：`minimum-ascension != branch stage` 即拒启） | `SkillsCommandTest`；W2-D3 交付 25 例可达性测试；W3-A1 升格为技能门 + 分支选择门自检 | 存疑（跨模块不变式冲突，**REQUIRES_HUMAN**） | G-106 (W3-A1 判为设计裁决) |
| 档案简化旁路 `ascend(UUID)`（AGENTS.md §3 明令删除）<br>`rpg-api`/`rpg-core` | `ProfileService.ascend(UUID)` | 无（`grep 'ascend(' plugins \| grep src/main` 只剩 `AscensionService.ascend` 与 `RpgClassesPlugin.java:429` 走 `AscensionService`） | `JdbcServicesTest`（W2-A1 反射断言入口已消失 + 真跑收据落库） | 已接线（旁路已删除） | G-107 上半 (W2-A1) |
| 技能点「本地里程碑 + 幂等补偿」<br>`rpg-api`/`rpg-core` | `ProgressionRules.LOCAL_LEVELS_PER_SKILL_POINT` / `skillPointMilestonesPassed` / `skillPointMilestoneSource`；表 `rpg_skill_point_grants` | `JdbcProfileService.java:476,481`（读常量 + 生成档位来源）；表真实读写 `JdbcProfileService.java:513`（INSERT ... ON CONFLICT）、`:529`（SELECT 重放判定） | `ProgressionRulesTest`（5 例）+ `JdbcServicesTest` 等（6 例），负向对照：改坏里程碑步长 → 3 例 RED | **已接线**（`grep 'finalEffective / 3'` 零命中） | G-107 下半 (W4-A2 / `66540ac`) |
| ★ **表 `rpg_skill_point_grants` 的 DDL 登记**<br>`rpg-core` + `tools/db-migration` | `Database.java` 建表语句 / `SchemaManifest` 迁移清单 | **无**：`grep -rn 'rpg_skill_point_grants' plugins/rpg-core/.../Database.java tools/db-migration/.../SchemaManifest.java` 零命中；而 `JdbcProfileService.java:513,529` 已在真实读写该表 | 无（没有任何测试断言这张表在 DDL 里存在） | **声明未接线（真实缺口）** | G-DDL-REG（本轮新登记；W5-A1 待做） |
| 装备退出路径判定（C-008）<br>`rpg-api`/`rpg-items` | `EquipmentLifecycle.requiresExitPath/isExitPath/isTradeable`；`EquipmentExitPathRules`/`Gate` | `EquipmentExitPathRules.java:36,44,58,66`；`EquipmentExitPathGate.java:37`；接入 `/salvage`、`/legacy`、`/unbind`、启动自检（W2-B3） | `EquipmentExitPathRulesTest`（11 例，含变异测试，W2-B3） | 已接线 | G-110 (W2-B3) |
| 装备明确销毁退出路径（`DESTROYED`）<br>`rpg-items` | `EquipmentOperationKind.DESTROY` → `EquipmentLifecycle.afterOperation` → `State.DESTROYED` | `DestroyCommand.java:97,131,147,155`（生产入口 `/destroy`）；`DestroyRules.java:24`；命令注册 `RpgItemsPlugin.java:89,93`；`plugin.yml` 的 `destroy:` | `DestroyEscrowEndToEndTest`、`DestroyOutboxIntegrationTest`、`DestroyGateTest`、`DestroyDeliveryRulesTest` | **已接线**（v2 判「声明未接线」已修正） | G-110 遗留 / W3-A3 (`298d268`, `fa3258b`) |
| 装备调谐退出路径（`SEASON_ATTUNED`） | `EquipmentLifecycle.State.SEASON_ATTUNED` | **无（除自身定义文件外零命中）**：`grep -rn --include=*.java 'SEASON_ATTUNED' plugins/*/src/main \| grep -v 'EquipmentLifecycle.java'` 零输出；`EquipmentOperationKind` 里没有任何 kind 产出该结果态 | 无 | **声明未接线**（已在 `EquipmentLifecycle.java:140-165` 登记 Phase 7 接入合同：5 条前置条件 + 依赖链 + C-010/C-012/C-009 约束） | G-110 遗留 / 待 Phase 7 |
| 阶段经验预算 `totalExperienceForAscension()`<br>`rpg-api` | `ProgressionRules.totalExperienceForAscension(int)` | `RpgCommand.java:132`；`ProgressionPlaceholders.java:21`；`MobGuideRules.java:79` | `ProgressionRulesTest`（W2-B4 行为级） | 已接线 | G-111 (W2-B4) |
| 有效等级累计经验 `totalExperienceToEffective()`<br>`rpg-api` | `ProgressionRules.totalExperienceToEffective(int)` | `RpgCommand.java:143`；`ProgressionPlaceholders.java:26`；`MobGuideRules.java:93`；`MobGuideCommand.java:245` | `ProgressionRulesTest`（含单调性断言，W2-B4） | 已接线 | G-111 (W2-B4) |
| 有效等级上限 `effectiveLevelCap()`<br>`rpg-api` | `ProgressionRules.effectiveLevelCap()` | `AdaptiveMobSafetyRules.java:30`；`ProgressionPlaceholders.java:18`；`HiddenPathReachability.java:116,118` | `ProgressionRulesTest`（W2-B4） | 已接线 | G-111 (W2-B4) |
| 权威物品命名空间判定 `isAuthoritativeNamespace()`<br>`rpg-api` | `AuthoritativeItemPolicy.isAuthoritativeNamespace(String)` | `AuthoritativeItemPolicy.java:86`（`isAuthoritativeKey` 内部调用）→ `EquipmentSecurity.java:114,104` 门；`ForgeListener.java:967,950`（`authoritativeNamespaceOnly`） | `AuthoritativeNamespaceGateTest`（3 例）、`ForgeInputNamespaceGateTest`（2 例），负向对照变红（W2-B5） | 已接线（**间接经 `isAuthoritativeKey`**） | G-112 (W2-B5) |
| 节点租约续期 / 有效性<br>`rpg-core` | `JdbcNodeLease.renewAsync()` / `mayStillBeValid()`；`NodeLeaseSupervisor` | `NodeLeaseSupervisor.java:87,99,119,146,151`；`JdbcNodeLease.java:139` | `NodeLeaseSupervisorTest`（W2-B6 行为级） | 已接线 | G-113 (W2-B6) |
| 交付标记拷贝 `markedDeliveryCopy()`<br>`rpg-items` | `EquipmentOperationCoordinator.markedDeliveryCopy(ItemStack, UUID)` | `EquipmentOperationCoordinator.java:178`（定义）、`:190`（`payload()` 内分发）；4 个结算生产者共 8 处出站载荷 | `DeliveryCopyRulesTest`、`DeliveryCopyWiringTest`（W2-B7） | 已接线 | G-114 (W2-B7) |
| 副本 `config.yml` 的 `world: dungeon_*` 键读取方<br>`rpg-dungeons` | `rpg-dungeons/src/main/resources/config.yml` 的 `dungeons.*.world` | 无（6 个死键已在 W2-D2 删除；`grep 'dungeon_ancient_ruins' plugins/rpg-dungeons/src/main` 零命中） | W2-D2 新增门禁 + 行为级测试 | 已废弃（键已删） | G-108 (W2-D2) |
| Phase 4 市场买单簿表 `rpg_market_buy_orders`<br>`rpg-core` | 表 `rpg_market_buy_orders` | 无（除 `Database.java:1005` 的 DDL 与 `SchemaManifest.java:89` 的登记外零命中） | 无 | **声明未接线**（门禁 PENDING 分类内，`docs/planning/deferred-tables.md#23`） | G-109 遗留 |
| Phase 1 成长异常表 `rpg_progression_anomalies`<br>`rpg-core` | 表 `rpg_progression_anomalies` | 无（除 `Database.java:917` 与 `SchemaManifest.java:80` 外零命中） | 无 | **声明未接线**（门禁 PENDING 分类内，`docs/planning/deferred-tables.md#24`；需要 [H] 门禁） | G-109 遗留 |
| Phase 5 `support_credit` 独立账本（C-012 物理隔离）<br>`rpg-core` | 表 `rpg_support_credit_accounts` / `rpg_support_credit_ledger` | 无（除 `Database.java:987,994` 与 `SchemaManifest.java:87,88,150` 的 DDL/契约登记外零命中，无 Java 账本服务） | 无 | **声明未接线**（门禁 PENDING 分类内，`docs/planning/deferred-tables.md#21,#22`） | G-109 遗留 |
| ★ **反向 DDL 门禁（代码引用的表必须在 DDL 里）**<br>`scripts/` | `scripts/check-ddl-consumers.sh` 的反向检查 | **不存在**：现有脚本只做「DDL → 消费者」一个方向（§4 实测），没有「src/main SQL 字面量 → DDL」这个方向 | 无 | **声明未接线（结构性门禁缺口）** | G-DDL-REG 的对策（W5-A1 ③） |
| 商人往返净损耗下限（承诺 10%，YAML 可调到 5%）<br>`rpg-economy` | `EconomyCommand` 读 `black-market.minimum-roundtrip-loss` | `EconomyCommand.java:596`（clamp 0.05..0.50） | 无 | 存疑（下限 0.05，与承诺 10% 不符；**REQUIRES_HUMAN** 设计取舍） | G-208 |
| 装备签名 schema 版本（文档称 4，实际 5）<br>`rpg-items` | `ForgeListener` 写 schema 5 | `ForgeListener.java:648` | 无 | 存疑（文档失真，W2-C1 已订正文档；**REQUIRES_HUMAN** 是否改行为） | G-201 |
| 稀有/史诗槽上限随基材上浮<br>`rpg-items` | `ForgeRules.slotUpperBound(rarity, base)` | `ForgeListener.java:463,848` | `ForgeRulesTest.java:15`、`DungeonCoreResonanceRulesTest.java:27` | 存疑（文档失真，**REQUIRES_HUMAN**） | G-202 |
| legacy「必须持有稀有词缀」隐藏前置<br>`rpg-items` | `LegacyBlueprintRules.eligibility()` | `LegacyCommand.java:67,107` | `LegacyBlueprintRulesTest.java:20` | 存疑（文档失真，**REQUIRES_HUMAN**） | G-203 |
| PlaceholderAPI 占位符数（文档称 11，实际 58）<br>`rpg-integrations` | `ProgressionPlaceholders` / `SivitaPlaceholderExpansion` | `ProgressionPlaceholders.java:18,21,26` | 无 | 存疑（文档失真，**REQUIRES_HUMAN**） | G-204 |
| 外观价格归属（文档称 rpg-cosmetics）<br>`docs` | `docs/COSMETICS.md` 定价叙述 | 无 | 无 | 已订正（W2-C1 改为 rpg-commerce） | G-205 |
| 副本 `config.yml` 的 `world: dungeon_*` 键读取方<br>`rpg-dungeons` | `rpg-dungeons/src/main/resources/config.yml` 的 `dungeons.*.world` | 无（6 个死键已在 W2-D2 删除；`grep 'dungeon_ancient_ruins' plugins/rpg-dungeons/src/main` 零命中） | W2-D2 新增门禁 + 行为级测试 | 已废弃（键已删） | G-108 (W2-D2) |
| Phase 4 市场买单簿表 `rpg_market_buy_orders`<br>`rpg-core` | 表 `rpg_market_buy_orders` | 无（除 `Database.java:1005` 的 DDL 与 `SchemaManifest.java:89` 的登记外零命中） | 无 | **声明未接线**（门禁 PENDING 分类内，`docs/planning/deferred-tables.md#23`） | G-109 遗留 |
| Phase 1 成长异常表 `rpg_progression_anomalies`<br>`rpg-core` | 表 `rpg_progression_anomalies` | 无（除 `Database.java:917` 与 `SchemaManifest.java:80` 外零命中） | 无 | **声明未接线**（门禁 PENDING 分类内，`docs/planning/deferred-tables.md#24`；需要 [H] 门禁） | G-109 遗留 |
| Phase 5 `support_credit` 独立账本（C-012 物理隔离）<br>`rpg-core` | 表 `rpg_support_credit_accounts` / `rpg_support_credit_ledger` | 无（除 `Database.java:987,994` 与 `SchemaManifest.java:87,88,150` 的 DDL/契约登记外零命中，无 Java 账本服务） | 无 | **声明未接线**（门禁 PENDING 分类内，`docs/planning/deferred-tables.md#21,#22`） | G-109 遗留 |
| ★ **反向 DDL 门禁（代码引用的表必须在 DDL 里）**<br>`scripts/` | `scripts/check-ddl-consumers.sh` 的反向检查 | **不存在**：现有脚本只做「DDL → 消费者」一个方向（§4 实测），没有「src/main SQL 字面量 → DDL」这个方向 | 无 | **声明未接线（结构性门禁缺口）** | G-DDL-REG 的对策（W5-A1 ③） |
| 商人往返净损耗下限（承诺 10%，YAML 可调到 5%）<br>`rpg-economy` | `EconomyCommand` 读 `black-market.minimum-roundtrip-loss` | `EconomyCommand.java:596`（clamp 0.05..0.50） | 无 | 存疑（下限 0.05，与承诺 10% 不符；**REQUIRES_HUMAN** 设计取舍） | G-208 |
| 装备签名 schema 版本（文档称 4，实际 5）<br>`rpg-items` | `ForgeListener` 写 schema 5 | `ForgeListener.java:648` | 无 | 存疑（文档失真，W2-C1 已订正文档；**REQUIRES_HUMAN** 是否改行为） | G-201 |
| `#minecraft:quick_actions` 数据包纳入版本控制<br>`repo` | `verify-installation.sh` 硬依赖的数据包目录 | 无（仓库内 `find . -path ./.git -prune -o -name '*quick_actions*' -print` 零命中；`docs/PLUGIN_INTEGRATION.md:46` 明写「当前都不在本仓库内」） | 无 | **声明未接线**（数据缺失，非代码缺失；**REQUIRES_HUMAN** VCS 决策） | G-206 |
| 血月/袭击「每日最多 3 场」显式实现<br>`rpg-world` | `WorldEventListener` 读 `blood-moon.every-nights` | `WorldEventListener.java:199,718,725` | 无 | 存疑（无显式每日场次上限判定，**REQUIRES_HUMAN**） | G-207 |
| 星弹预警窗口（承诺 0.4–0.8s，实测 28–36 tick）<br>`rpg-dungeons` | `DungeonManager` 读 `telegraph-ticks` | `DungeonManager.java:1174`（clamp 20..80 tick） | `DungeonConfigurationContractTest.java:55`（源码字符串断言） | 存疑（超承诺窗口，**REQUIRES_HUMAN**） | G-209 |
| ★ **质量门在 main 上的五门状态**<br>`.cnb.yml` | 准备下载器 → 门禁自测 → DDL 消费者门禁 → compile → test → assemble | **test 阶段红**：`sn=cnb-0ck-1k3tugbvb`（main @ d11f7d8）—— `TEST_GATE=RED(日志里看不到 Gradle 排除 env 标签的记录：-PexcludeTags 没生效)`，`EXCLUDED_TAG_SEEN_IN_LOG=0 ENV_TAG_LINE=0`，`ENV_ANNOTATIONS=9`，`SUM tests=1020 failed=0 errors=0 skipped=2 modules=26`，`SUMMARY_GATE=GREEN`；**assemble 因此被 skipped** | 门禁自测阶段本身 success（`scripts/selftest-gates.sh` 的 4 类负向对照都跑过） | **真实缺口**（判据错，不是代码错；W5-A4 待做） | G-TEST-GATE（本轮新登记） |
| 53 个测试类用 `Files.readString` 做源码字符串断言<br>`plugins/*/src/test` | `plugins/*/src/test/**` + `tools/*/src/test/**` | （口径是测试自身，无 src/main 消费者问题） | `grep -rl --include=*.java 'Files.readString' plugins tools | grep -c '/src/test/'` = **53**（其中 3 个 rpg-core 的已在 W4-A4 行为化 ⇒ 剩余 50 个） | 存疑（非行为级测试，`[T]` 证据须打折；**未落地**） | G-302（W4-A4 第一批 3 个 + W5-A2 待做） |
| 每张 DDL 表必须有 src/main 引用点的 CI 门禁<br>`scripts/` | `scripts/check-ddl-consumers.sh` | 脚本存在并接入 `.cnb.yml` push 门；本地实测 `DDL_CONSUMER_GATE=GREEN`（79 张 / 75 已接线 / 4 条 PENDING） | 脚本自身有 `DDL_GATE_IGNORE_EXEMPT=1` 负向自测，并被 `scripts/selftest-gates.sh` 覆盖 | 已接线 | G-301 (W1-A5 / `e8013ef`) |
| Slash 指令引用门禁（语言包引用的指令必须已注册）<br>`scripts/` | `scripts/check-ddl-consumers.sh` 第 6 节 | 本地实测 `CMD_REFERENCE_GATE=GREEN` / `已注册指令数: 43` / `指令豁免清单: 9 条` | 脚本自身有负向对照记录（脚本内注释） | 已接线 | 质量门第 5 门 |
| G-206 数据包与部署脚本不在版本控制（VCS 缺口）<br>`repo` | `server/bin/verify-installation.sh` 硬依赖的 `#minecraft:quick_actions` 数据包目录 | 无（`find . -path ./.git -prune -o -name '*quick_actions*' -print` 零命中；`docs/PLUGIN_INTEGRATION.md:46` 明写「当前都不在本仓库内」） | 无 | **声明未接线**（数据缺失，非代码缺失；**REQUIRES_HUMAN** VCS 决策） | G-206 |

## 2. 每条判定的 grep 命令原文（可逐条复现）

命令均在仓库根目录执行。命令块里给出的是**实际命中行**（超长路径用 `...` 缩写，为报告可读性）。

### E-01 — Kill Switch requireEnabled（判定：已接线，G-002）
```
$ grep -rn --include=*.java 'requireEnabled' plugins | grep '/src/main/'
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcActivityEconomyService.java:71:  case HUNTING -> mechanisms.requireEnabled(MechanismRegistry.HUNT_GOLD);
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcActivityEconomyService.java:72:  case DUNGEON -> mechanisms.requireEnabled(MechanismRegistry.DUNGEON_GOLD);
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcActivityEconomyService.java:73:  default -> mechanisms.requireEnabled(MechanismRegistry.COMMISSION_GOLD);
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcActivityEconomyService.java:351: ... requireEnabled(MechanismRegistry.COMMISSION_GOLD) ...
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcCommerceService.java:193: ... requireEnabled(MechanismRegistry.STARLIGHT_GRANT) ...
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcCommerceService.java:540: ... requireEnabled(MechanismRegistry.STARLIGHT_GRANT) ...
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcMarketService.java:53: return mechanisms == null || mechanisms.requireEnabled(MechanismRegistry.MARKET_ESCROW);
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcSeasonExpeditionService.java:75: ... requireEnabled(MechanismRegistry.SEASON_SETTLEMENT) ...
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcDungeonRewardService.java:89: return mechanisms == null || mechanisms.requireEnabled(MechanismRegistry.DUNGEON_GOLD);
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcSeasonSettlementService.java:104: ... requireEnabled(MechanismRegistry.SEASON_SETTLEMENT) ...
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcCommunityService.java:613: ... requireEnabled(MechanismRegistry.COMMUNITY_PROJECT) ...
```

### E-02 — 五维防御（判定：已接线，G-001）
```
$ grep -rn --include=*.java 'defenseMultiplier' plugins | grep '/src/main/'
plugins/rpg-combat/src/main/java/cn/originalrpg/combat/ExecuteListener.java:155:        event.setDamage(Math.max(0.0, event.getDamage() * defenseMultiplier(target)));
plugins/rpg-combat/src/main/java/cn/originalrpg/combat/ExecuteListener.java:244:    private double defenseMultiplier(Player player) {
```

### E-03 — 签名密钥 env 唯一来源（判定：已接线，G-003）
```
$ grep -rn --include=*.java 'RPG_ITEM_SIGNING_KEY' plugins | grep '/src/main/'
plugins/rpg-items/src/main/java/cn/originalrpg/items/security/EquipmentSigningMaterial.java:25:    public static final String CURRENT_KEY_ENV = "RPG_ITEM_SIGNING_KEY";
plugins/rpg-items/src/main/java/cn/originalrpg/items/security/EquipmentSigningMaterial.java:26:    /** 前缀环境变量名：退役密钥 ... */
```

### E-04 — 迁移 advisory lock（判定：已接线，G-004）
```
$ grep -rn --include=*.java 'MIGRATION_LOCK_NAMESPACE\|MIGRATION_LOCK_RESOURCE' plugins tools | grep '/src/main/'
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:20:    private static final int MIGRATION_LOCK_NAMESPACE = 0x53495649; // "SIVI"
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:21:    private static final int MIGRATION_LOCK_RESOURCE = 0x52504731; // "RPG1"
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:1132/1133: "SELECT pg_advisory_xact_lock(" + NS + ", " + RES + ")"
tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/MigrationAdvisoryLock.java:18/20: "SIVI"/"RPG1"
```

### E-05 — ★ 赛季结算三方法（判定：已接线，G-101 —— 本轮从「部分接线」改为「已接线」）
```
$ grep -rn --include=*.java 'settlements\.preview\|settlements\.advance' plugins/rpg-admin/src/main
plugins/rpg-admin/src/main/java/cn/originalrpg/admin/SeasonSettlementCommand.java:112:        settlements.preview(seasonId).whenComplete((preview, error) -> runMain(() -> {
plugins/rpg-admin/src/main/java/cn/originalrpg/admin/SeasonSettlementCommand.java:163:        settlements.advance(seasonId, nextSeasonId, next, sender.getName(), reason)

$ grep -rn --include=*.java 'SeasonSettlementCommand' plugins/rpg-admin/src/main
plugins/rpg-admin/src/main/java/cn/originalrpg/admin/RpgAdminPlugin.java:60:    private SeasonSettlementCommand seasonSettlements;
plugins/rpg-admin/src/main/java/cn/originalrpg/admin/RpgAdminPlugin.java:85:        seasonSettlements = SeasonSettlementCommand.of(
plugins/rpg-admin/src/main/java/cn/originalrpg/admin/RpgAdminPlugin.java:705:            return seasonSettlements == null ? List.of() : SeasonSettlementCommand.complete(args);

$ grep -n 'season' plugins/rpg-admin/src/main/resources/plugin.yml
11:    usage: /rpgadmin <economy|chapter ...|season <status|preview|advance <target> CONFIRM [next-season] <reason>>|incident ...|...
（三方法 state/preview/advance 现在都有非自身 src/main 调用点；`state()` 另在 RpgCommand.java:114）
```

### E-06 — Session Lease / Outbox（判定：已接线，G-102）
```
$ grep -rn --include=*.java 'rpg_session_leases' plugins | grep '/src/main/'
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcSessionLease.java:71:  "SELECT ... FROM rpg_session_leases"
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:1035:  CREATE TABLE IF NOT EXISTS rpg_session_leases (

$ grep -rn --include=*.java 'rpg_outbox' plugins | grep '/src/main/'
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcOutboxRegistry.java:36:  INSERT INTO rpg_outbox
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/OutboxDispatcher.java:112: FROM rpg_outbox
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/OutboxDispatcher.java:143: UPDATE rpg_outbox
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/OutboxDispatcher.java:165: UPDATE rpg_outbox
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:1047:  CREATE TABLE IF NOT EXISTS rpg_outbox (
```

### E-07 — 社群七角色 / 审批表（判定：已接线，G-103）
```
$ grep -rn --include=*.java 'canApprove' plugins | grep '/src/main/'
plugins/rpg-api/.../CommunityRole.java:26:    public boolean canApprove() {
plugins/rpg-api/.../CommunityRolePower.java:37,39,41,54,71（4 处消费 + 1 处默认）
plugins/rpg-community/.../CommunityGovernanceHandler.java:116
plugins/rpg-core/.../JdbcCommunityService.java:799,966,976

$ grep -rn --include=*.java 'rpg_community_approvals' plugins | grep '/src/main/'
plugins/rpg-core/.../JdbcCommunityService.java:1015,1037,1062,1079,1103（INSERT/SELECT×3/DELETE）
```

### E-08 — ★ isGovernance（判定：已接线，G-115 —— 本轮从「声明未接线」改为「已接线」）
```
$ grep -rn --include=*.java 'isGovernance' plugins | grep '/src/main/' | grep -v 'isGovernanceMember'
plugins/rpg-api/src/main/java/cn/originalrpg/api/model/CommunityRole.java:31:    public boolean isGovernance() {
plugins/rpg-api/src/main/java/cn/originalrpg/api/model/CommunityRolePower.java:50:            case ASSIGN_ROLE -> role.isGovernance();
plugins/rpg-api/src/main/java/cn/originalrpg/api/model/CommunityRolePower.java:51:            // 治理通道准入显式走 isGovernance()：这就是这个口径的唯一消费者。
plugins/rpg-api/src/main/java/cn/originalrpg/api/model/CommunityRolePower.java:53:            case GOVERNANCE_ACTION -> role.isGovernance();
plugins/rpg-community/src/main/java/cn/originalrpg/community/CommunityGovernanceHandler.java:128:        String lineKey = role.isGovernance() ? "community.governance.role-approver"

$ grep -rn --include=*.java 'rpg_community_approvals' plugins | grep '/src/main/'
（见 E-07）
```

### E-09 — 邮箱交付冻结（判定：已接线，G-104）
```
$ grep -rn --include=*.java 'InventoryCreativeEvent' plugins | grep '/src/main/'
plugins/rpg-market/.../MarketCommand.java:41: import org.bukkit.event.inventory.InventoryCreativeEvent;
plugins/rpg-market/.../MarketCommand.java:757/761: onCreativeClick(InventoryCreativeEvent event)
plugins/rpg-market/.../DeliveryFreezeRules.java:44
```

### E-10 — 战斗总线单一结算（判定：已接线，G-105）
```
$ grep -rn --include=*.java 'settleAttackerBonus\|onBattleTranceHit' plugins | grep '/src/main/'
plugins/rpg-skills/.../SkillsCommand.java:948,952,1000,1042
```

### E-11 — 血契可达性（判定：存疑 / REQUIRES_HUMAN，G-106）
```
$ grep -rn --include=*.java 'minimumAscension\|vanished-horizon' plugins | grep '/src/main/'
plugins/rpg-api/.../HiddenPathRules.java:18 (minimumAscension), :28 (case BLOODPACT -> "vanished-horizon")
plugins/rpg-api/.../HiddenPathReachability.java:74
plugins/rpg-core/.../JdbcHiddenPathService.java:114
plugins/rpg-skills/.../RpgSkillsPlugin.java:181,182,184（启动自检：minimum-ascension != branchStage ⇒ 拒启）
```

### E-12 — 档案简化旁路（判定：已接线，旁路已删除，G-107 上半）
```
$ grep -rn --include=*.java 'ascend(' plugins | grep '/src/main/'
plugins/rpg-api/.../AscensionService.java:9（注释）/ :13（接口）
plugins/rpg-classes/.../RpgClassesPlugin.java:429
plugins/rpg-core/.../JdbcAscensionService.java:60
（`ProfileService.ascend` / `JdbcProfileService.ascend` 已不存在）
```

### E-13 — ★ 技能点本地里程碑（判定：已接线，G-107 下半 —— 本轮从「存疑未落地」改为「已接线」）
```
$ grep -rn --include=*.java 'finalEffective / 3' plugins | grep '/src/main/'
（零命中）

$ grep -rn --include=*.java 'LOCAL_LEVELS_PER_SKILL_POINT\|skillPointMilestoneSource' plugins | grep '/src/main/'
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java:14:    public static final int LOCAL_LEVELS_PER_SKILL_POINT = 3;
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java:79:        return localLevel / LOCAL_LEVELS_PER_SKILL_POINT;
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java:111:    public static String skillPointMilestoneSource(int ascension, int localLevel) {
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java:117:                ascension, localLevel / LOCAL_LEVELS_PER_SKILL_POINT);
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java:476:        int perPoint = ProgressionRules.LOCAL_LEVELS_PER_SKILL_POINT;
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java:481:                    ProgressionRules.skillPointMilestoneSource(after.ascension(), milestone * perPoint)
```

### E-14 — ★ 表 rpg_skill_point_grants 在 DDL / SchemaManifest 里（判定：声明未接线 —— 本轮新登记 G-DDL-REG）
```
$ grep -rn 'rpg_skill_point_grants' plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/SchemaManifest.java
（零命中 —— 两个文件都没有这张表）

$ grep -rn --include=*.java 'rpg_skill_point_grants' plugins tools | grep -v '/src/test/'
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java:492:  （javadoc 提到）
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java:513:                INSERT INTO rpg_skill_point_grants (
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java:529:                FROM rpg_skill_point_grants WHERE source = ? AND idempotency_key = ?

⇒ 代码真实读写一张 DDL 里不存在的表。真机（或任何跑 migrate 的环境）启动后该表不存在，经验授予会以 Table not found 回滚。
```

### E-15 — 装备退出路径判定（判定：已接线，G-110）
```
$ grep -rn --include=*.java 'requiresExitPath\|isExitPath\|isTradeable' plugins | grep '/src/main/'
plugins/rpg-api/.../EquipmentLifecycle.java:107,116,120
plugins/rpg-items/.../EquipmentExitPathGate.java:37
plugins/rpg-items/.../EquipmentExitPathRules.java:36,44,66
```

### E-16 — ★ DESTROYED 生产入口（判定：已接线 —— 本轮从 v2 的「声明未接线」改为「已接线」）
```
$ grep -rn --include=*.java 'EquipmentOperationKind.DESTROY' plugins/*/src/main
plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/DestroyCommand.java:97:   ... EquipmentOperationKind.DESTROY)) {
plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/DestroyCommand.java:131:  ... EquipmentOperationKind.DESTROY)) {
plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/DestroyCommand.java:147:  operationId, player.getUniqueId(), EquipmentOperationKind.DESTROY,
plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/DestroyCommand.java:155:  if (DestroyRules.deliveryLane(EquipmentOperationKind.DESTROY)
plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/DestroyRules.java:24:  return kind == EquipmentOperationKind.DESTROY || kind == EquipmentOperationKind.SALVAGE;

$ grep -rn 'destroy' plugins/rpg-items/src/main/resources/plugin.yml plugins/rpg-items/src/main/java/cn/originalrpg/items/RpgItemsPlugin.java
plugin.yml:27:  destroy:
plugin.yml:30:    usage: /destroy [confirm]
plugin.yml:31:    permission: rpg.destroy
RpgItemsPlugin.java:89:  DestroyCommand destroy = new DestroyCommand(this, security, messages, operationSessions);
RpgItemsPlugin.java:93:  Objects.requireNonNull(getCommand("destroy"), "destroy command").setExecutor(destroy);
RpgItemsPlugin.java:98:  getServer().getPluginManager().registerEvents(destroy, this);

$ grep -rn --include=*.java 'DESTROYED' plugins/*/src/main | grep -v 'EquipmentLifecycle.java'
plugins/rpg-core/.../JdbcEquipmentLedgerAuditService.java:71: WHERE state IN ('QUARANTINED','RESTORED','SALVAGED','LEGACY_EXTRACTED','DESTROYED')
plugins/rpg-items/.../DestroyCommand.java:37,40（javadoc）
plugins/rpg-items/.../DestroyRules.java:7,10（javadoc）
plugins/rpg-items/.../DestroyedTerminalTicket.java:10,21: return PREFIX + EquipmentLifecycle.State.DESTROYED.name() + ":" + operationId;
plugins/rpg-items/.../EquipmentDurabilityListener.java:17（javadoc）
```

### E-17 — ★ SEASON_ATTUNED（判定：声明未接线 —— 与 v2 相同）
```
$ grep -rni --include=*.java 'seasonattun\|season_attuned\|attun' plugins/*/src/main | grep -v 'EquipmentLifecycle.java'
（零命中）

$ grep -rn --include=*.java 'SEASON_ATTUNED' plugins/*/src/main
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/EquipmentLifecycle.java:22,41,44,49,109（只命中自身定义文件）

$ sed -n '140,165p' plugins/rpg-api/.../EquipmentLifecycle.java
140:        // TODO(Phase 7 / codex/endgame-pipeline)：C-008 列出的第五条退出路径
141:        // 「赛季调谐」（SEASON_ATTUNED）在当前 Phase 下没有触发者。判定依据
...
159:        // 接入合同（Phase 7 必须逐条满足，否则不要删本 TODO）：
...
164:        return true;
```

### E-18 — ProgressionRules 三方法（判定：已接线，G-111）
```
$ grep -rn --include=*.java 'totalExperienceForAscension\|totalExperienceToEffective\|effectiveLevelCap' plugins | grep '/src/main/'
plugins/rpg-core/.../RpgCommand.java:132,143
plugins/rpg-integrations/.../ProgressionPlaceholders.java:18,21
plugins/rpg-mobs/.../AdaptiveMobSafetyRules.java:30
plugins/rpg-mobs/.../MobGuideRules.java:79,93
plugins/rpg-api/.../HiddenPathReachability.java:116
```

### E-19 — 权威命名空间（判定：已接线，间接经 isAuthoritativeKey，G-112）
```
$ grep -rn --include=*.java 'authoritativeNamespace' plugins | grep '/src/main/'
plugins/rpg-items/.../ForgeListener.java:950,967
plugins/rpg-items/.../EquipmentSecurity.java:104,114

$ grep -n 'AuthoritativeItemPolicy' plugins/rpg-items/src/main/java/cn/originalrpg/items/security/EquipmentSecurity.java
114:        return meta.getPersistentDataContainer().getKeys().stream()
115:                .allMatch(AuthoritativeItemPolicy::isAuthoritativeKey);
```

### E-20 — 节点租约（判定：已接线，G-113）
```
$ grep -rn --include=*.java 'renewAsync\|mayStillBeValid' plugins | grep '/src/main/'
plugins/rpg-core/.../NodeLeaseSupervisor.java:87,99,119
plugins/rpg-core/.../JdbcNodeLease.java:139
```

### E-21 — 交付标记拷贝（判定：已接线，G-114）
```
$ grep -rn --include=*.java 'markedDeliveryCopy' plugins | grep '/src/main/'
plugins/rpg-items/.../EquipmentOperationCoordinator.java:178,190
```

### E-22 — 副本 world 死键（判定：已废弃，G-108）
```
$ grep -rn --include=*.java 'dungeon_ancient_ruins' plugins/rpg-dungeons/src/main
（零命中 —— W2-D2 已删除 6 个 dungeons.*.world 死键）
```

### E-23 — ★ 四张零消费者表 + 门禁分类（判定：声明未接线 ×4，G-109 遗留）
```
$ sh scripts/check-ddl-consumers.sh
DDL 真相源: plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java
DDL 表数: 79
豁免清单: 4 条已登记（本次生效 4 条；脚本内 EXEMPT，可审计）

--- 待接线 PENDING（能力有效，消费者晚于 schema 落地）---
[待接线] rpg_market_buy_orders  <- Phase 4 市场 V2 买单簿，API 模型 MarketOrder 已存在，撮合事务未实现（C-009 单一托管）
[待接线] rpg_progression_anomalies  <- Phase 1 旧档案无法唯一映射时的人工复核清单，迁移写入口未实现，需 [H] 门禁
[待接线] rpg_support_credit_accounts  <- Phase 5 独立 support_credit 账本余额表，落库先行、验签订单服务未接线（宪法 C-012 物理隔离）
[待接线] rpg_support_credit_ledger  <- Phase 5 support_credit 幂等流水表，schema 契约含 idempotency_key 唯一键，写入方未实现

已注册指令数: 43
指令豁免清单: 9 条（脚本内 CMD_EXEMPT，可审计）
CMD_REFERENCE_GATE=GREEN
DDL_CONSUMER_GATE=GREEN
EXIT=0

$ grep -rn --include=*.java 'rpg_market_buy_orders' plugins tools | grep -v '/src/test/'
plugins/rpg-core/.../Database.java:1005:  CREATE TABLE IF NOT EXISTS rpg_market_buy_orders (
tools/db-migration/.../SchemaManifest.java:89:  "rpg_market_buy_orders",

$ grep -rn --include=*.java 'rpg_progression_anomalies' plugins tools | grep -v '/src/test/'
plugins/rpg-core/.../Database.java:917:  CREATE TABLE IF NOT EXISTS rpg_progression_anomalies (
tools/db-migration/.../SchemaManifest.java:80:  "rpg_progression_anomalies",

$ grep -rn --include=*.java 'rpg_support_credit' plugins tools | grep -v '/src/test/'
plugins/rpg-core/.../Database.java:987,994（两张表 DDL）
tools/db-migration/.../SchemaManifest.java:87,88,150
（除 DDL 与迁移登记外零消费者）
```

### E-24 — ★ 反向 DDL 门禁（判定：声明未接线 —— 结构性门禁缺口，G-DDL-REG 的对策）
```
$ grep -n 'INSERT INTO\|FROM rpg_\|UPDATE rpg_' scripts/check-ddl-consumers.sh
（零命中 —— 门禁脚本里没有任何「从代码侧提取表名再对照 DDL」的逻辑）

$ tail -60 scripts/check-ddl-consumers.sh | grep -n 'GATE='
CMD_REFERENCE_GATE=GREEN
DDL_CONSUMER_GATE=GREEN
（脚本只做两个方向：DDL→消费者、语言包→已注册指令；没有 代码→DDL 的方向）
```

### E-25 — ★ 质量门在 main 上的实测状态（判定：真实缺口，G-TEST-GATE —— 本轮新登记）
```
$ cnb pulls get-ci-logs --sn cnb-0ck-1k3tugbvb    # api_trigger_gate / main / d11f7d8
▸ 失败 Stage: test (status: error)
  SUM tests=1020 failed=0 errors=0 skipped=2 modules=26
  NO_XML modules=0  期望模块数=26
  SUMMARY_GATE=GREEN
  ENV_ANNOTATIONS=9
  EXCLUDED_TAG_SEEN_IN_LOG=0 ENV_TAG_LINE=0
  TEST_GATE=RED(日志里看不到 Gradle 排除 env 标签的记录：-PexcludeTags 没生效)
  Finished, code: 1, duration: 36.9s

  耗时概览：
  ✅ 准备下载器（必须是第一个 stage）: 20865ms (success)
  ✅ 门禁自测（负向对照）: 595ms (success)
  ✅ DDL 消费者门禁（G-301）: 116689ms (success)
  ✅ compile: 365688ms (success)
  ❌ test: 36963ms (error)
  ⏳ assemble: 0ms (skipped)

$ cnb pulls get-ci-logs --sn cnb-7ov-1k3tqin74   # 最后一次五门全绿的运行（W3-A2 分支）
所有流水线均无失败的 Stage / 整体构建状态: success
  ✅ DDL 消费者门禁（G-301）: 105224ms
  ✅ compile: 456400ms
  ✅ test: 32200ms
  ✅ assemble: 7609ms
$ git merge-base --is-ancestor 7b88e2f0 origin/main && echo ancestor
ancestor   # 7b88e2f0 是 main 的祖先 ⇒ 自那以后 main 上新增了 34 个提交，其中 7c39b86 重写了质量门与 scripts/*.py
```

### E-26 — G-302 源码字符串伪测试（判定：存疑 / 未落地）
```
$ grep -rl --include=*.java 'Files.readString' plugins tools | grep -c '/src/test/'
53
```

### E-27 — G-301 DDL 门禁 + 命令引用门禁（判定：已接线）
```
$ sh scripts/check-ddl-consumers.sh ; echo EXIT=$?
DDL 表数: 79 / 豁免清单: 4 条已登记
CMD_REFERENCE_GATE=GREEN
DDL_CONSUMER_GATE=GREEN
EXIT=0
```

### E-28 — ★ 本地无法复跑 compile/test/assemble（本轮取证限制）
```
$ command -v java || echo 'no java'
no java
$ ls /usr/lib/jvm 2>/dev/null || echo 'no jvm'
no jvm
$ echo $PATH
/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

⇒ compile/test/assemble 三门的证据只能来自 CNB 上已有的门运行（见 E-25），不能在本容器本地复跑。
```

## 3. DDL 表消费者矩阵（G-109 全量对账，本轮实测）

门禁脚本实测：**79 张表 / 75 张有真实消费者 / 4 张 PENDING**。

零消费者表（PENDING 分类内，逐条复现见 E-23）：
```
rpg_market_buy_orders          <- Phase 4 买单簿，撮合未实现
rpg_progression_anomalies      <- Phase 1 dry-run 冻结期预留，写入口未接线（需 [H] 门禁）
rpg_support_credit_accounts    <- Phase 5 独立账本，账本服务未接线
rpg_support_credit_ledger      <- Phase 5 独立账本流水，同上
```

对账 `doc/cnb/gap-registry.md` G-109 声明的 8 张（v2 已收敛到 4 张，本轮**不变**）：
`rpg_season_settlements`（9 处非 DDL 引用）、`rpg_session_leases`（3 处）、`rpg_outbox`（7 处）、
`rpg_community_approvals`（6 处）四条已接线；剩余 4 条即上列。

**本轮新增事实**：同一口径下还有一张表是**代码引用但 DDL 不存在**的反向缺口 —— `rpg_skill_point_grants`
（代码侧 3 处非测试引用，DDL 侧 0 处）。现有门禁只做「DDL → 消费者」一个方向，抓不到这类（见 E-14 / E-24）。

## 4. 结构性门禁实测

```
$ sh scripts/check-ddl-consumers.sh ; echo EXIT=$?
DDL 表数: 79 / 豁免清单: 4 条已登记（脚本内 EXEMPT，可审计）
CMD_REFERENCE_GATE=GREEN
DDL_CONSUMER_GATE=GREEN
EXIT=0
```

G-301（DDL 消费者门禁）与命令引用门禁都 GREEN；但**没有反向门禁**（代码引用的表必须存在于 DDL），
这正是 `rpg_skill_point_grants` 从缝里漏出去的原因。对策写在 `plan/tasks-w6.json` 的 `W5-A1-ddl-registration-gate` ③。

G-302 未落地：53 个测试类用 `Files.readString` 读源码做字符串断言（E-26）。
其中 rpg-core 的 3 个已在 W4-A4 行为化；其余全部分布在 `plugins/*/src/test/java/**/` 的
`*ContractTest.java` / `*WiringTest.java` 里。对策见 `W5-A2-source-string-tests-batch2`。

## 5. 质量门在「当前 main」上的状态（本轮最关键的一节）

五门 = compile / test / assemble / DDL 消费者 / 命令引用（另加「准备下载器」与「门禁自测」两个前置 stage）。

| 门 | 当前 main（`d11f7d8`，sn=`cnb-0ck-1k3tugbvb`） | 判据行原文 |
|---|---|---|
| 门禁自测（负向对照） | GREEN | stage success（`scripts/selftest-gates.sh`） |
| DDL 消费者门禁 | GREEN | `DDL_CONSUMER_GATE=GREEN` |
| 命令引用门禁 | GREEN | `CMD_REFERENCE_GATE=GREEN` |
| compile | GREEN | `COMPILE_GATE=GREEN`（stage success，365.7s） |
| **test** | **RED** | `TEST_GATE=RED(日志里看不到 Gradle 排除 env 标签的记录：-PexcludeTags 没生效)` |
| **assemble** | **NOT_RUN** | 上一阶段红 ⇒ `skipped` |

红的那条判据的实质（E-25）：26 个模块全跑了（`modules=26`，`NO_XML modules=0`），1020 个用例 0 失败，
`SUMMARY_GATE=GREEN`，9 个 `@Tag(env)` 测试一个都没执行；唯一红的是「要求 Gradle 在会话日志里回显
`excluding tests with tag: env` 这句」的自证性判据，实测 `EXCLUDED_TAG_SEEN_IN_LOG=0`。
⇒ 这是**判据错**，不是代码错；但它仍然让「五门全绿」这个 done 条件不成立，且 assemble 门整段没跑。

历史上最后一次五门全绿是 `sn=cnb-7ov-1k3tqin74`（分支 `agent/W3-A2-full-test-gate`，sha `7b88e2f0`）。
`7b88e2f0` 是 main 的祖先；自它之后 main 上新增 34 个提交，其中 `7c39b86` 重写了质量门与 `scripts/*.py`、
`d11f7d8` 重排了 stage 顺序。因此**不能**引用那次全绿作为「当前 main 全绿」的证据。

## 6. 与 `doc/cnb/gap-registry.md` 的对账（本轮）

| gap id | gap-registry 声明状态 | 本轮机械核验结果 | 偏差 |
|---|---|---|---|
| G-001..G-004 | DONE（W1） | **确认已接线**（E-01..E-04） | 无 |
| G-101 | TODO | **确认已接线**（E-05） | 已从「部分」补全 |
| G-102 | TODO | **确认已接线**（E-06） | 无 |
| G-103 | TODO | **确认已接线**（E-07） | 无 |
| G-104 | DONE（W2-B1） | **确认已接线**（E-09） | 无 |
| G-105 | DONE（W2-B2） | **确认已接线**（E-10） | 无 |
| G-106 | TODO | **确认仍冲突**（E-11） | 设计裁决，REQUIRES_HUMAN |
| G-107 | DONE | **两半都确认已接线**（E-12 / E-13） | 无 |
| G-108 | DONE（W2-D2） | **确认已修复**（E-22） | 无 |
| G-109 | TODO | **收敛到 4 张 PENDING**（E-23） | 数量不变 |
| G-110 | DONE | **`DESTROYED` 已接线**（E-16）；**`SEASON_ATTUNED` 仍无入口**（E-17） | 遗漏一条，已登记 Phase 7 合同 |
| G-111..G-114 | DONE | **全部确认已接线**（E-18..E-21） | 无 |
| G-115 | （v2 新登记） | **确认已接线**（E-08） | 无 |
| G-201..G-205 | TODO（P2 订正） | **确认已订正**（W2-C1） | 无 |
| G-206 | TODO | **确认仍缺失**（数据包与脚本都不在仓） | REQUIRES_HUMAN |
| G-207/G-208/G-209 | TODO | **确认仍为存疑**（文档已订正为实测值，行为未改） | REQUIRES_HUMAN |
| G-301 | DONE | **确认已接线**（E-27） | 无 |
| G-302 | TODO | **确认未落地**：53 个类（E-26） | 未完成，W4-A4 只做 3 个 |
| G-303 | DONE | v2 已产出；本轮产出 v3 | 无 |
| G-401/G-402/G-403 | DONE（W1-A0） | **确认已接线**（`libs/` + `fetch-deps.sh` + `check-file-deps.sh`） | 无 |
| **G-DDL-REG** | 未登记 | **声明未接线**（E-14） | ★ 新登记 |
| **G-TEST-GATE** | 未登记 | **真实缺口**（E-25） | ★ 新登记 |

## 7. 本轮新增结论（gap-registry 未登记）

1. **G-DDL-REG（新增）**：表 `rpg_skill_point_grants` 被 `JdbcProfileService.java:513,529` 真实读写，
   `Database.java` 与 `SchemaManifest.java` 零命中。真机启动后任何经验授予以 Table not found 回滚。
   这是 W4-A2 自己在 `blockers` 里报过的，W5-A1 是它的对策任务，但在本分支的 `.cnb/agent-out/` 下**没有对应证据文件** ⇒ 未交付。
2. **G-TEST-GATE（新增）**：质量门在**当前 main** 上不是全绿。test stage 红，assemble 被 skipped。
   红的是判据本身（依赖 Gradle 日志回显），但它同样否掉了 done=true 的第 2 个条件。对策写在 `W5-A4-test-gate-env-tag`。
3. **`SEASON_ATTUNED` 的定性**：不是接线 bug，而是 Phase 7 内容触发者未到。`EquipmentLifecycle.java:140-165` 已给出 5 条接入合同。
   在 Phase 7 之前，它应被当作**有意保留的待接入项**而不是欠债；但按 L-008 的机械口径，它仍然是「声明未接线」。
4. **`isAuthoritativeNamespace` 是间接接线**（与 v2 一致）：直接字面量 grep 会误判为未接线，实际消费点在 `isAuthoritativeKey` 内部。
5. **收口条件评估**：按收口轮要求逐条判，done=true 的第 2 条（五门全绿）不成立 ⇒ `done=false`。
   详细见 `plan/verdict-v3.json`。
