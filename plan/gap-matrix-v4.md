# 全量机制矩阵 v4（gap-matrix-v4）

> 生成者：CNB CodeBuddy NPC，任务 `W8-A1-final-verdict`（最终验收轮）。**只读审计 + 规划**，未改 `plugins/**/src/main` 任何源码。
> 基点：`main` @ `c63f446`（= 本任务分支的基点，与 `origin/main` 同一 commit，`git merge-base HEAD origin/main` 成立）。
> 判定口径来自 `doc/cnb/GOAL.md` §2 与 `doc/cnb/decisions-locked.md` L-008。
> 状态取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口`。

## 与 v3（`plan/gap-matrix-v3.md`，基点 `d11f7d8`）的差异

| 项 | v3 判定 | v4 判定 | 变化的依据 |
|---|---|---|---|
| G-DDL-REG（`rpg_skill_point_grants` 未登记） | 声明未接线 | **已接线** | V-08：`Database.java:398` CREATE TABLE + `:903` 索引；`SchemaManifest.java:38,130,180`；反向门禁 `DDL_REGISTRATION_GATE` 已存在并 GREEN |
| G-TEST-GATE（测试门判据错） | 真实缺口 | **已接线**（判据已修） | V-20：实测门 `cnb-ihu-1k3ug6u6p`（**sha=`c63f446`，即当前 main HEAD**）test stage success，`EXCLUDED_TAG_SEEN_IN_LOG=26 ENV_TAG_LINE=26`、`SUM tests=1081 failed=0 modules=26`（v3 时为 0） |
| G-109（4 张零消费者表） | 声明未接线 ×4 | **已接线** | V-03/V-04：门禁实测 **81 张表 / 0 条豁免 / 0 张零消费者**；W6-A1 已接线全部 4 张 |
| G-110 的 `SEASON_ATTUNED` | 声明未接线 | **已接线** | V-09：`EquipmentStateLedger.java:101-107,118,141` + `JdbcSeasonAttunementService.java:117` + `JdbcSeasonSettlementService.java:299-316,351`（换季结算逐玩家触发） |
| G-206（quick_actions 数据包不在仓） | 声明未接线 | **已接线** | V-19：`datapacks/sivita-navigation/` 已在仓；`sh scripts/install-datapacks.sh --check` 与 `sh scripts/check-repo-artifacts.sh` 均 exit 0 |
| G-302（源码字符串伪测试） | 存疑（53 个类） | **存疑（收窄到 2 个类）** | V-21：`Files.readString` 类从 53 降到 48；其中 25 个已改为**真实执行**外部门禁脚本，21 个含真行为断言，**仅 2 个是纯源码字符串断言** |
| G-106（血契） | 存疑 / REQUIRES_HUMAN | **已接线** | §6 对账：`HiddenPathRules.minimumAscension(BLOODPACT)` 已改 3 转，与 `vanished-horizon` 的前置一致 |
| G-207/G-208/G-209 | 存疑 / REQUIRES_HUMAN | **已接线** | V-22：`maximum-events-per-day` 读取方 ×2、`MINIMUM_ROUNDTRIP_LOSS_FLOOR` fail-closed、`MINIMUM_READABLE_TELEGRAPH_TICKS` 下限抬升，均有行为级测试 |
| 表数 | 79 | **81** | `sh scripts/check-ddl-consumers.sh` → `DDL 表数: 81` |
| 八门状态 | 五门（test 红） | **八门全绿（实测于当前 main HEAD `c63f446`）** | V-20 / V-23 |

## 0. 覆盖范围与取证方式（本轮实测）

| 项 | 数量 | 取证命令 |
|---|---|---|
| `plugins/` 下模块 | 25 | `ls plugins/ | wc -l` |
| `src/main` Java 文件 | 548 | `find plugins tools -path '*/src/main/*' -name '*.java' | wc -l` |
| `src/test` Java 文件 | 283 | `find plugins tools -path '*/src/test/*' -name '*.java' | wc -l` |
| DDL 表（去重） | **81** | `sh scripts/check-ddl-consumers.sh` → `DDL 表数: 81` |
| DDL 零消费者表 | **0** | 同上门禁（豁免 0 条）+ 独立脚本复核（V-04） |
| 代码引用但 DDL 无的表 | **0** | `DDL_REGISTRATION_GATE=GREEN`（V-03） |

**本轮取证的两个硬限制（如实说明）**

1. **本容器没有 JDK**（V-27：`command -v java` 无输出、`/usr/lib/jvm` 不存在）。
   因此 compile / test / assemble 三门**不能在本容器本地复跑**，只能用 CNB 上已有的门运行记录作证据（V-20 / V-23）。
   本地能复跑的：`check-ddl-consumers.sh` / `check-resource-pack.sh` / `selftest-gates.sh` /
   `check-source-contract.sh` / `check-config-fallbacks.sh` / `check-trusted-menu-holders.sh` /
   `check-repo-artifacts.sh` / `install-datapacks.sh --check` / `check-file-deps.sh`（纯文本判定，不需要 JDK）。
2. **配置键的取证边界**：25 个模块共 1900+ 个 YAML 叶子键，本项目大量用
   `getConfigurationSection(prefix)` + `getKeys(false)` 动态遍历，**无法用字面量 grep 判定未接线**。
   本轮只对**最近几波新增的配置键**做逐条读取方核对（V-22），不做全量配置键判定。

## 1. 机制级矩阵

| 机制/模块 | 声明的载体 | src/main 里的真实调用点 | 行为级测试 | 判定 | gap id |
|---|---|---|---|---|---|
| Kill Switch 开关判定/切换（C-005 第4腿）<br>`rpg-core` | `MechanismRegistry.requireEnabled`；表 `rpg_mechanism_switches` | 7 个 faucet 入口共 **14 处**（V-01） | `MechanismKillSwitchContractTest`（含 `disabledSeasonSettlementMechanismFailsClosedOnRealDatabase` 真库断言） | 已接线 | G-002 |
| 五维防御 / 装备 `defense` 词条在 PVE/PVP 生效<br>`rpg-combat` | `ExecuteListener.defenseMultiplier` | `ExecuteListener.java:155`（`EntityDamageByEntityEvent` 内） | `DefenseWiringTest`（事件级 4 例 + 负向对照） | 已接线 | G-001 |
| 装备 HMAC 签名密钥 env 唯一来源 / 轮换<br>`rpg-items` | `EquipmentSigningMaterial.CURRENT_KEY_ENV` | `EquipmentSigningMaterial.java:25`；`EquipmentSecurity` 构造注入 | `EquipmentSigningMaterialTest`（6 例：fail-closed / 轮换 / 明文检测） | 已接线 | G-003 |
| 迁移 advisory lock 在线/离线唯一<br>`rpg-core` + `tools/db-migration` | `Database.MIGRATION_LOCK_NAMESPACE/RESOURCE` | `Database.java:20,21,1132,1133`；`MigrationAdvisoryLock.java:18,20` 同值 | `MigrationAdvisoryLockTest`、`DatabaseRulesTest.java:65` | 已接线 | G-004 |
| 赛季结算九态状态机 + `SeasonSettlementService`（C-010）<br>`rpg-api`/`rpg-core`/`rpg-admin` | `SeasonSettlementService.state/preview/advance`；表 `rpg_season_settlements` | `RpgCommand.java:114`、`RpgCorePlugin.java:287` 注册；`SeasonSettlementCommand.java:112`（`preview`）、`:163`（`advance`）；`RpgAdminPlugin.java:85,705`；`plugin.yml` 的 `/rpgadmin season ...` | `SeasonSettlementCommandTest`、`SeasonSettlementWiringTest`、`JdbcSeasonSettlementServiceTest`（13 例） | 已接线 | G-101 |
| 多服前置 Session Lease（C-014）<br>`rpg-core` | 表 `rpg_session_leases`；`JdbcSessionLease` | `JdbcSessionLease.java:71`（SELECT 读侧判定） | `JdbcServicesTest` | 已接线 | G-102 |
| 多服前置 Transactional Outbox（C-014）<br>`rpg-core` | 表 `rpg_outbox`；`JdbcOutboxRegistry`/`OutboxDispatcher` | `JdbcOutboxRegistry.java:36`（INSERT）；`OutboxDispatcher.java:112,143,165` | `JdbcServicesTest` | 已接线 | G-102 |
| 社群七角色 / 双批审批（Phase 6）<br>`rpg-api`/`rpg-core`/`rpg-community` | `CommunityRolePower`；表 `rpg_community_approvals` | `CommunityRolePower.java:37,39,41,54,71`；`CommunityGovernanceHandler.java:116`；`JdbcCommunityService.java:799,966,976`；表读写 `:1015,1037,1062,1079,1103` | `JdbcCommunityGovernanceTest`、`CommunityGovernanceGateTest` | 已接线 | G-103 |
| 社群治理角色判定 `CommunityRole.isGovernance()`<br>`rpg-api` | `CommunityRole.isGovernance()` | `CommunityRolePower.java:50`、`:53`；`CommunityGovernanceHandler.java:128` | `CommunityRolePowerTest`；`JdbcCommunityGovernanceTest`（3 例对谓词敏感） | 已接线 | G-115 |
| 邮箱交付冻结：创造模式容器事件 + 游标槽<br>`rpg-market` | `MarketCommand.onCreativeClick`；`DeliveryFreezeRules.CURSOR_SLOT` | `MarketCommand.java:757,761`；`MarketCommand.java:41` import | `MailboxClaimReconcilerTest`、`DeliveryFreezeRulesTest`、`DeliveryFreezeWiringTest` | 已接线 | G-104 |
| 战斗总线单一结算（消除攻方增益二次放大）<br>`rpg-skills`/`rpg-combat` | `SkillsCommand.settleAttackerBonus` + `AttackerBonusTag` 幂等 | `SkillsCommand.java:948,952,1000,1042` | `AttackerBonusTest`（真构造 `EntityDamageByEntityEvent` 断言最终伤害） | 已接线 | G-105 |
| 血契（BLOODPACT）按文档条件可达成<br>`rpg-api`/`rpg-skills`/`rpg-classes` | `HiddenPathRules.minimumAscension()=3`（W6-A4 改）+ `requiredSkill()=vanished-horizon`；`config.yml` 的 `vanished-horizon.minimum-ascension: 3` | `JdbcHiddenPathService.java:114,122`；`HiddenPathReachability.java:74,131`；`RpgSkillsPlugin.java:181-184`（启动自检：`minimum-ascension != branch stage` 即拒启） | `SkillsCommandTest`；可达性测试；`RpgSkillsPlugin.validateCatalog` 自检 | **已接线** | G-106 |
| 档案简化旁路 `ascend(UUID)`（AGENTS.md §3 明令删除）<br>`rpg-api`/`rpg-core` | `ProfileService.ascend(UUID)` | 无（`grep 'ascend(' plugins | grep src/main` 只剩 `AscensionService.ascend` 与 `RpgClassesPlugin.java:429` 走 `AscensionService`） | `JdbcServicesTest`（反射断言入口已消失 + 真跑收据落库） | 已接线（旁路已删除） | G-107 上半 |
| 技能点「本地里程碑 + 幂等补偿」<br>`rpg-api`/`rpg-core` | `ProgressionRules.LOCAL_LEVELS_PER_SKILL_POINT` / `skillPointMilestoneSource`；表 `rpg_skill_point_grants` | `JdbcProfileService.java:476,481`；表真实读写 `:513`（INSERT ... ON CONFLICT）、`:529`（SELECT 重放判定） | `ProgressionRulesTest`（5 例）+ 6 例；负向对照改坏步长 → 3 例 RED | 已接线 | G-107 下半 |
| **表 `rpg_skill_point_grants` 的 DDL 登记**<br>`rpg-core` + `tools/db-migration` | `Database.java` 建表语句 / `SchemaManifest` 迁移清单 | `Database.java:398`（CREATE TABLE）、`:903`（CREATE INDEX `idx_rpg_skill_point_grants_player`）；`SchemaManifest.java:38`、`:130`（唯一键 `source,idempotency_key`）、`:180`（索引） | `SchemaManifestContractTest`；反向门禁 `DDL_REGISTRATION_GATE=GREEN` | **已接线** | G-DDL-REG |
| 装备退出路径判定（C-008）<br>`rpg-api`/`rpg-items` | `EquipmentLifecycle.requiresExitPath/isExitPath/isTradeable`；`EquipmentExitPathRules`/`Gate` | `EquipmentExitPathRules.java:36,44,58,66`；`EquipmentExitPathGate.java:37`；接入 `/salvage`、`/legacy`、`/unbind`、启动自检 | `EquipmentExitPathRulesTest`（11 例，含变异测试） | 已接线 | G-110 |
| 装备明确销毁退出路径（`DESTROYED`）<br>`rpg-items` | `EquipmentOperationKind.DESTROY` → `EquipmentLifecycle.afterOperation` → `State.DESTROYED` | `DestroyCommand.java:97,131,147,155`；`DestroyRules.java:24`；`RpgItemsPlugin.java:89,93`；`plugin.yml` 的 `destroy:` | `DestroyEscrowEndToEndTest`、`DestroyOutboxIntegrationTest`、`DestroyGateTest`、`DestroyDeliveryRulesTest` | 已接线 | G-110 |
| **装备调谐退出路径（`SEASON_ATTUNED`）**<br>`rpg-core`/`rpg-api` | `SeasonAttunementService` / `SeasonAttunementRules` / `EquipmentStateLedger.attune`；表 `rpg_season_attunements` | `JdbcSeasonSettlementService.java:351`（`attuneSeasonGear(...)`，在结算逐玩家循环内）、`:299-316`（失败关闭）；`JdbcSeasonAttunementService.java:117`（`ledger.attune`）；`EquipmentStateLedger.java:101-107,118,141`；`RpgCorePlugin.java:254-261`（`season-attunement.enabled` 默认 false，L-009） | `JdbcSeasonAttunementServiceTest`（13 例）、`SeasonAttunementSettlementWiringTest`（7 例：真跑一次结算断言调谐被调用且仅一次 / 未装配时零影响 / Kill Switch 关时零影响） | **已接线**（触发者 = 换季结算） | G-110 遗留 |
| 阶段经验预算 `totalExperienceForAscension()`<br>`rpg-api` | `ProgressionRules.totalExperienceForAscension(int)` | `RpgCommand.java:132`；`ProgressionPlaceholders.java:21`；`MobGuideRules.java:79` | `ProgressionRulesTest` | 已接线 | G-111 |
| 有效等级累计经验 `totalExperienceToEffective()`<br>`rpg-api` | `ProgressionRules.totalExperienceToEffective(int)` | `RpgCommand.java:143`；`ProgressionPlaceholders.java:26`；`MobGuideRules.java:93`；`MobGuideCommand.java:245` | `ProgressionRulesTest`（含单调性断言） | 已接线 | G-111 |
| 有效等级上限 `effectiveLevelCap()`<br>`rpg-api` | `ProgressionRules.effectiveLevelCap()` | `AdaptiveMobSafetyRules.java:30`；`ProgressionPlaceholders.java:18`；`HiddenPathReachability.java:116,118` | `ProgressionRulesTest` | 已接线 | G-111 |
| 权威物品命名空间判定<br>`rpg-api` | `AuthoritativeItemPolicy.isAuthoritativeNamespace(String)` | `AuthoritativeItemPolicy.java:86`（`isAuthoritativeKey` 内部调用）→ `EquipmentSecurity.java:104,114`；`ForgeListener.java:950,967` | `AuthoritativeNamespaceGateTest`（3 例）、`ForgeInputNamespaceGateTest`（2 例） | 已接线（间接经 `isAuthoritativeKey`） | G-112 |
| 节点租约续期 / 有效性<br>`rpg-core` | `JdbcNodeLease.renewAsync()` / `mayStillBeValid()`；`NodeLeaseSupervisor` | `NodeLeaseSupervisor.java:87,99,119,146,151`；`JdbcNodeLease.java:139` | `NodeLeaseSupervisorTest` | 已接线 | G-113 |
| 交付标记拷贝 `markedDeliveryCopy()`<br>`rpg-items` | `EquipmentOperationCoordinator.markedDeliveryCopy(ItemStack, UUID)` | `EquipmentOperationCoordinator.java:178`（定义）、`:190`（`payload()` 内分发）；4 个结算生产者共 8 处出站载荷 | `DeliveryCopyRulesTest`、`DeliveryCopyWiringTest` | 已接线 | G-114 |
| 副本 `config.yml` 的 `world: dungeon_*` 键读取方<br>`rpg-dungeons` | `dungeons.*.world` | 无（6 个死键已在 W2-D2 删除） | W2-D2 门禁 + 行为级测试 | 已废弃（键已删） | G-108 |
| Phase 4 市场买单簿<br>`rpg-core`/`rpg-api` | 表 `rpg_market_buy_orders`；`MarketBuyOrderService` / `JdbcMarketBuyOrderService` | `JdbcMarketBuyOrderService`（INSERT / SELECT ... FOR UPDATE / UPDATE / SELECT created_at）；装配 `RpgCorePlugin.java:209,287`；玩家入口 `MarketCommand.java:128`（`case "buyorder"`）、`:1067`、`:1074`；`plugin.yml:12` usage 含 `buyorder <place|list|cancel>` | `JdbcMarketBuyOrderServiceTest`（9 例：托管扣款 / 并发撮合唯一成功 / 撤单全额退回不得二次退款 / 过期只退一次） | **已接线** | G-109 |
| Phase 1 成长异常清单<br>`rpg-core` | 表 `rpg_progression_anomalies`；`ProgressionAnomalyService` / `JdbcProgressionAnomalyService` | 写入：`JdbcProfileService.java:602`（`UPDATE rpg_progression_anomalies`）、`:608`（`INSERT INTO rpg_progression_anomalies`），由 `:593 recordAnomalyIfUninterpretable` 在每次 `load()` 读到不可解释档案时触发；装配 `RpgCorePlugin.java:212,289`；**读/复核出口 `pending(UUID)` / `pending(int)` / `acknowledge(UUID,String)` 的 src/main 调用点 = 无** | `JdbcProgressionAnomalyServiceTest` | **已接线（写侧）/ 存疑（[H] 复核出口无生产消费者）** | G-ANOMALY-ACK |
| Phase 5 `support_credit` 独立账本（C-012 物理隔离）<br>`rpg-core` | 表 `rpg_support_credit_accounts` / `rpg_support_credit_ledger`；`SupportCreditService` / `JdbcSupportCreditService` | `JdbcSupportCreditService.java:146`（SELECT）、`:156`（INSERT）、`:166`（SELECT FOR UPDATE）、`:179`（UPDATE）、`:195`（INSERT INTO ledger）、`:118`（幂等 SELECT）；装配 `RpgCorePlugin.java:215,291`；玩家可见面 `RpgCommand.java:71,84,162`（`/rpg` 独立一行余额） | `JdbcSupportCreditServiceTest`（7 例：同幂等键重放一次 / 8 个禁止清单目标全部 CROSS_CURRENCY_FORBIDDEN 且零流水 / 4 路并发同键只有一次成功） | 已接线 | G-109 |
| 反向 DDL 门禁（代码引用的表必须在 DDL 里）<br>`scripts/` | `scripts/check-ddl-consumers.sh` §7 | 脚本 §7 存在，输出 `DDL_REGISTRATION_GATE`；接入 `.cnb.yml`「DDL 门禁（G-301 消费者 + G-302 注册，双向）」stage | `scripts/selftest-gates.sh` §4 逐条负向对照（缺表 → RED、`DELETE FROM` 同样抓到、`rpg_skill_point_grants` 已补进 DDL → GREEN） | 已接线 | G-DDL-REG 对策 |
| 每张 DDL 表必须有 src/main 引用点的 CI 门禁<br>`scripts/` | `scripts/check-ddl-consumers.sh` §1/§3-5 | 脚本接入 `.cnb.yml`；本地实测 `DDL_CONSUMER_GATE=GREEN`（81 张 / 0 条豁免 / 0 张零消费者） | `selftest-gates.sh` §4 + `DDL_GATE_IGNORE_EXEMPT=1` 负向自测 | 已接线 | G-301 |
| Slash 指令引用门禁（语言包引用的指令必须已注册）<br>`scripts/` | `scripts/check-ddl-consumers.sh` §6 | 本地实测 `CMD_REFERENCE_GATE=GREEN` / 已注册指令数 43 / 指令豁免 9 条 | 脚本内负向对照记录 | 已接线 | 质量门 |
| 源码契约门禁（`source-contract`）<br>`scripts/` | `scripts/check-source-contract.sh` + `scripts/source-contract-specs/all.json` | 本地实测 `SOURCE_CONTRACT_GATE=GREEN`（`SOURCE_CONTRACT_CHECKS=185 SPECS=25`）；**真实执行者 = 25 个 `*ContractTest` 用 `ProcessBuilder("sh", gate, SPEC_KEY, method)` 跑真脚本并断言 exit=0**（V-21） | `selftest-gates.sh` §4b（不可能满足的顺序契约 → RED，真实顺序 → GREEN） | 已接线 | G-302 |
| 配置回退门禁（`check-config-fallbacks`）<br>`scripts/` | `scripts/check-config-fallbacks.sh` | 本地实测 `CONFIG_FALLBACK_GATE=GREEN`；真实执行者 = `EnhancementFallbackContractTest`（`ProcessBuilder` 跑真脚本） | 同上 | 已接线 | G-302 |
| 受信菜单宿主门禁（`check-trusted-menu-holders`）<br>`scripts/` | `scripts/check-trusted-menu-holders.sh` | 本地实测 `TRUSTED_MENU_GATE=GREEN`；真实执行者 = `TrustedMenuBoundaryContractTest`（`ProcessBuilder` 跑真脚本） | 同上 | 已接线 | G-302 |
| 仓库产物引用门禁（G-206）<br>`scripts/` | `scripts/check-repo-artifacts.sh` | 本地实测 `REPO_ARTIFACT_GATE=GREEN`（exit=0）；由 `selftest-gates.sh` §5、§6 覆盖并断言接线 | `selftest-gates.sh` §5（把 `datapacks/` 藏起来 → RED） | 已接线 | G-206 |
| 数据包校验/部署入口<br>`scripts/` | `scripts/install-datapacks.sh` | 本地实测 `sh scripts/install-datapacks.sh --check` exit=0；由 `selftest-gates.sh` §4c/§6 覆盖 | `selftest-gates.sh` §4c（函数调用未注册命令 → RED） | 已接线 | G-206 |
| `#minecraft:quick_actions` 数据包纳入版本控制<br>`repo` | `datapacks/sivita-navigation/`（pack.mcmeta + `data/minecraft/tags/function/quick_actions.json` + 8 个 `quick/*.mcfunction`） | 目录在仓且 `install-datapacks.sh --check` / `check-repo-artifacts.sh` 均 exit 0（V-19） | 两个门禁的真实执行记录 | **已接线** | G-206 |
| 素材门禁（RESOURCE_PACK）<br>`.cnb.yml` + `scripts/check-resource-pack.sh` | 8 条判据（manifest / closure / textures / hud / journey / lang / compass / packmeta） | 本地实测 `RESOURCE_PACK_GATE=GREEN`，`RUN=8 SKIPPED=0`（V-18）；`.cnb.yml` stage「素材门禁（W7-A1）」 | `selftest-gates.sh` §7（14 条负向对照）+ §8（接线自证） | 已接线 | W7-A1 |
| HUD 尺寸唯一来源与实现一致性（W7-A4）<br>`docs/reference/hud-layout.json` + `resource-pack/tools/verify_hud_layout.py` | 轨 26×91 / 4×(18×18) 图标 / 29×26 徽章 / 1×87 法力条 | `HudRailSpec.slotCount()` 被 `SivitaPlaceholderExpansion.java:148` 消费；`verify_hud_layout.py` 被 `selftest-gates.sh` §5b 真实执行 | `selftest-gates.sh` §5b 4 条负向对照（唯一来源改 27 / 图标改 17 / build.sh 常量改 27 → 全 RED） | 已接线 | W7-A4 |
| 每日事件场次上限（G-207）<br>`rpg-world`/`rpg-economy` | `WorldEventRules.dailyEventAllowed` / `DailyEventCounter.tryClaim` | `WorldEventListener.java:204-205`（读 `blood-moon.maximum-events-per-day` 并调用规则）；`EconomicActivityListener.java:310`（`caps.RAID.maximum-events-per-day`） | `WorldEventRulesTest`（同一天第 4 场被拒 / 跨日重置 / cap=0 不限制）、`DailyEventCounterTest`（4 例，重放不重复占名额） | 已接线 | G-207 |
| 商人往返净损耗下限（承诺 10%）（G-208）<br>`rpg-economy` | `MerchantPricing.MINIMUM_ROUNDTRIP_LOSS_FLOOR` | `MerchantPricing.java:10`（下限 0.10）、`:27`（低于下限抛 `IllegalStateException`）；`EconomyCommand.java:597` 读取；`RpgEconomyPlugin.java:46` 启动校验 fail-closed | `MerchantPricingTest`（新增 3 例） | 已接线 | G-208 |
| 星弹预警窗口（G-209）<br>`rpg-dungeons` | `BossAttackRules.MINIMUM_READABLE_TELEGRAPH_TICKS` | `BossAttackRules.java:100`（下限 20 tick）、`:103`；`DungeonManager.java:1174-1176`；`config.yml` 6 处 `telegraph-ticks: 20` | `BossAttackRulesTest`（下限抬升 + 重绘次数断言）、`DungeonConfigurationContractTest`（绑定该常量） | 已接线 | G-209 |
| 外观定价归属（G-205）<br>`rpg-cosmetics`/`rpg-commerce` | `CosmeticCatalog` + `rpg-commerce/config.yml` points | `CosmeticCatalog.java:21`（3 条枚举）；`RpgCosmeticsPlugin.java:66`（marker 由目录提供）、`:134`（`validateCommerceCatalog` 启动对账） | `CosmeticCatalogTest`（4 例：目录完整性 / marker 同源 / 与 commerce 定价表逐条对账 / 无类初始化循环） | 已接线 | G-205 |
| 装备签名 schema 版本（G-201）<br>`rpg-items` | `ForgeListener` 写 schema 5 | `ForgeListener.java:648` | 文档已在 W2-C1 订正为 5；`docs/ARCHITECTURE.md` 与 `docs/MINECRAFT_26_2.md` 一致 | 已接线（文档与实现一致） | G-201 |
| 稀有/史诗槽上限随基材上浮（G-202）<br>`rpg-items` | `ForgeRules.slotUpperBound(rarity, base)` | `ForgeListener.java:463,848` | `ForgeRulesTest.java:15`、`DungeonCoreResonanceRulesTest.java:27`；文档已在 W7-A2 订正为公式 | 已接线 | G-202 |
| legacy「必须持有稀有词缀」前置（G-203）<br>`rpg-items` | `LegacyBlueprintRules.eligibility()` 的 `RARE_TRAIT_REQUIRED` | `LegacyCommand.java:67,107`；`EquipmentOperationKind.LEGACY` 消费者 `LegacyCommand.java:77,117,132` | `LegacyBlueprintRulesTest.java:20`；文档已在 W7-A2 订正 | 已接线 | G-203 |
| PlaceholderAPI 占位符数（G-204）<br>`rpg-integrations` | `ProgressionPlaceholders` / `SivitaPlaceholderExpansion` | `ProgressionPlaceholders.java:18,21,26`；`SivitaPlaceholderExpansion.java:148` | 文档已在 W2-C1 订正为 58 | 已接线（文档已订正） | G-204 |
| **质量门在 main 上的八门状态**<br>`.cnb.yml` | 准备下载器 → 门禁自测 → DDL 门禁（双向）→ 素材门禁 → compile → test → assemble | **八门全绿，实测于当前 main HEAD `c63f446`**（V-20）：`sn=cnb-ihu-1k3ug6u6p`（push 事件）11 格全 success：`GATE_SELFTEST=GREEN(PASS=67 FAIL=0)` / `RESOURCE_PACK_GATE=GREEN(RUN=8 SKIPPED=0)` / `DDL 表数: 81` / `DDL_CONSUMER_GATE=GREEN` / `DDL_REGISTRATION_GATE=GREEN` / `CMD_REFERENCE_GATE=GREEN` / `COMPILE_GATE=GREEN(BUILD_EXIT=0 SECONDS=105 TASKS_DONE=28)` / `TEST_GATE=GREEN(SUM tests=1081 failed=0 errors=0 skipped=2 modules=26, NO_XML=0, SUMMARY_GATE=GREEN, ENV_ANNOTATIONS=8, EXCLUDED_TAG_SEEN_IN_LOG=26 ENV_TAG_LINE=26)` / `PACKAGE_GATE=GREEN(JAR_COUNT=56 JAR_CHECKED=25 JAR_MISSING_PLUGIN_YML=0)` | 门禁自测阶段自身 67 条负向对照全过；同一 sha 的 api_trigger_gate `cnb-ves-1k3ug78vf` 在本次审计时 test 仍在跑，但不影响 push 门的结论（同一份 `.cnb.yml` 锚点、同一个 sha） | 已接线 |
| 成长异常 [H] 复核出口（`acknowledge`）<br>`rpg-core` | `ProgressionAnomalyService.pending(UUID)` / `pending(int)` / `acknowledge(UUID,String)` | **无**（`grep -rn 'ProgressionAnomalyService' plugins \| grep '/src/main/'` 只命中接口定义 / 实现 / 装配 / javadoc；三个读侧方法的调用点零） | `JdbcProgressionAnomalyServiceTest`（7 例，但断言的是 Java 方法本身，不是运行期可达的入口） | **存疑**（读侧能力运行期不可达；写侧已接线） | G-ANOMALY-ACK |
| 源码字符串断言残留（G-302）<br>`plugins/*/src/test` | `MobRewardAsyncLifecycleContractTest` / `PetRevivalLifecycleContractTest` | （口径是测试自身） | 两个类的每个 `@Test` 全部断言都是 `source.contains(...)` / `indexOf()` 跨文件比较；`grep -c 'ProcessBuilder\|check-source-contract'` 均为 0 | **存疑**（非行为级测试，`[T]` 证据须打折） | G-302 |

## 2. 逐条判定的 grep 命令原文（可逐条复现）

命令均在仓库根目录执行。

### V-01 — 基点确认 + 上版「声明未接线」六项复查（判定：全部已接线）
```
$ git rev-parse HEAD
c63f446a4834e126849454e5b17f3cf6076294db
$ git rev-parse origin/main
c63f446a4834e126849454e5b17f3cf6076294db
$ git merge-base HEAD origin/main && echo ancestor
c63f446a4834e126849454e5b17f3cf6076294db
ancestor

$ grep -rn --include=*.java 'requireEnabled' plugins | grep '/src/main/' | wc -l
14
```

### V-08 — `rpg_skill_point_grants` 已进 DDL 与 SchemaManifest（判定：已接线，G-DDL-REG）
```
$ grep -rn 'rpg_skill_point_grants' plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/SchemaManifest.java
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:398:                CREATE TABLE IF NOT EXISTS rpg_skill_point_grants (
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:903:                "CREATE INDEX IF NOT EXISTS idx_rpg_skill_point_grants_player ON rpg_skill_point_grants (player_uuid, granted_at)",
tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/SchemaManifest.java:38:            "rpg_skill_point_grants",
tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/SchemaManifest.java:130:        keys.put("rpg_skill_point_grants", List.of(List.of("source,idempotency_key")));
tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/SchemaManifest.java:180:        indexes.put("rpg_skill_point_grants", List.of(List.of("player_uuid", "granted_at")));
```

### V-09 — `SEASON_ATTUNED` 已有生产触发者（判定：已接线，G-110 遗留）
```
$ grep -rn --include=*.java 'SEASON_ATTUNED' plugins/*/src/main | grep -v 'EquipmentLifecycle.java'
plugins/rpg-api/src/main/java/cn/originalrpg/api/model/SeasonAttunementRecord.java:27: public static final EquipmentLifecycle.State REQUIRED_STATE = EquipmentLifecycle.State.SEASON_ATTUNED;
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/SeasonAttunementRules.java:28-29
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/EquipmentStateLedger.java:101,102,103,106,107,118,141
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcSeasonSettlementService.java:292（javadoc）
plugins/rpg-api/src/main/java/cn/originalrpg/api/service/SeasonAttunementService.java:18（javadoc）
plugins/rpg-api/src/main/java/cn/originalrpg/api/model/EquipmentOperationKind.java:15（注释）
（上游 v3 的判定依据「除 EquipmentLifecycle.java 外零命中」现已不成立）

$ grep -rn --include=*.java '\.attune(' plugins | grep '/src/main/'
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcSeasonAttunementService.java:117:                ledger.attune(connection, itemId, ownerId, seasonId);

$ grep -n 'attun' plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcSeasonSettlementService.java
59:    private final SeasonAttunementService attunements;
299:    private long attuneSeasonGear(
302:        if (attunements == null) return 0;
307:            records = attunements.attuneForSettlement(oldSeasonId, ownerId, settlementId)
313:            throw new SQLException("Season attunement step failed closed for " + playerUuid, failure);
351:                    attuneSeasonGear(connection, settlementId, seasonId, playerId);

$ sed -n '345,353p' plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcSeasonSettlementService.java
                    writeSettlementLedger(connection, newSeasonId, seasonId, playerId, amount);
                    zeroSeasonMerit(connection, playerId, seasonId, amount);
                    moveWalletToNewSeason(connection, playerId, newSeasonId);
                    attuneSeasonGear(connection, settlementId, seasonId, playerId);
                    recordProgress(connection, settlementId, playerId, amount);
                    settled++;
（351 处在逐玩家结算循环内 ⇒ 触发者是换季结算，不是玩家指令）

$ grep -n 'season-attunement' -A6 plugins/rpg-core/src/main/resources/config.yml
115:season-attunement:
116-  # C-008 第五条退出路径：赛季调谐。触发者是换季结算（C-010），不是玩家指令
119-  # L-009：能力接线但不启用 —— 默认关。关闭时结算一次查询都不做，旧行为零变化。
120-  enabled: false
```

### V-03 — DDL 双向门禁实测（判定：81 表全部有消费者 + 零反向缺口）
```
$ sh scripts/check-ddl-consumers.sh ; echo EXIT=$?
DDL 真相源: plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java
DDL 表数: 81
豁免清单: 0 条已登记（本次生效 0 条；脚本内 EXEMPT，可审计）

已注册指令数: 43
指令豁免清单: 9 条（脚本内 CMD_EXEMPT，可审计）
CMD_REFERENCE_GATE=GREEN
反向检查扫描文件数: 548
代码引用的 rpg_* 表名: 81 个（去重后）
反向豁免清单: 0 条（脚本内 DDL_TABLE_EXEMPT，可审计）
DDL_REGISTRATION_GATE=GREEN
DDL_CONSUMER_GATE=GREEN
EXIT=0
```

### V-04 — 独立复核「零消费者表」（判定：0 张）
```
$ python3 # 对 81 张表逐张 grep，排除 DDL / SchemaManifest 自身
import subprocess
tables=[l.strip() for l in open('/tmp/w/ddl_tables.txt') if l.strip()]
for t in tables:
    p=subprocess.run(['grep','-rn','--include=*.java',t,'plugins','tools'],cwd='/workspace',capture_output=True,text=True)
    lines=[l for l in p.stdout.splitlines() if '/src/main/' in l]
    non=[l for l in lines if 'Database.java' not in l and 'SchemaManifest.java' not in l]
    if len(lines)==0: print('NO_SQL_ANYWHERE:',t)
    elif len(non)==0: print('ZERO_CONSUMER:',t)
--- 输出 ---
总表 81 有问题 0

$ find plugins tools -path '*/src/main/*' -name '*.java' -print0 | xargs -0 grep -ohE "(FROM|INTO|UPDATE|JOIN|TABLE|EXISTS)[[:space:]]+rpg_[a-z_]+" | grep -oE 'rpg_[a-z_]+' | sort -u | wc -l
81
$ comm -23 /tmp/w/code_tables.txt /tmp/w/ddl_tables.txt
（空 —— 代码引用但 DDL 无的表：0）
```

### V-07 — `rpg_progression_anomalies` 的消费者构成（判定：写侧已接线 / [H] 出口无生产消费者）
```
$ grep -rn 'ProgressionAnomalyService' plugins --include=*.java | grep '/src/main/'
plugins/rpg-api/.../service/ProgressionAnomalyService.java:22（接口定义）
plugins/rpg-core/.../persistence/JdbcProgressionAnomalyService.java:6,37,43（实现）
plugins/rpg-core/.../persistence/JdbcProfileService.java:590（javadoc 提到）
plugins/rpg-core/.../RpgCorePlugin.java:14,50,212,213,289（装配 + ServicePriority 注册）
（除此之外无 src/main 调用点 —— pending(UUID) / pending(int) / acknowledge 三个方法零消费者）

$ sed -n '593,610p' plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java
    private void recordAnomalyIfUninterpretable(Connection connection, PlayerProfile profile) throws SQLException {
        String reason = ProgressionRules.classifyAnomaly(
                profile.ascension(), profile.level(), profile.experience());
        if (reason == null) return;
        ...
                UPDATE rpg_progression_anomalies
                SET ascension = ?, level = ?, experience = ?, reason = ?, detected_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ?
        ...
                INSERT INTO rpg_progression_anomalies
                (player_uuid, ascension, level, experience, reason)
                VALUES (?, ?, ?, ?, ?)

$ grep -rn --include=*.java 'acknowledge' plugins | grep '/src/main/' | grep -i 'anomal'
（零命中 —— 复核出口没有生产调用点）

$ grep -rn --include=*.java 'ProgressionAnomalyService' plugins | grep -v '/src/main/'
plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/JdbcProgressionAnomalyServiceTest.java:31,34,81
（只有测试类自身）
```

### V-18 — 素材门禁 + 门禁自测本地实测（判定：已接线）
```
$ sh scripts/check-resource-pack.sh ; echo EXIT=$?
RESOURCE_PACK_GATE: 校验 /workspace/resource-pack
  [OK  ] manifest      物品定义 81 / 可达模型 119 / 可达纹理 115
  [OK  ] closure       115 张物品 PNG 全部 32x32 二值 alpha
  [OK  ] textures      U+E000..U+E00A 十一码位 ↔ 99x9 图集；U+E020..U+E04F 四十八码位 ↔ 432x9 图集
  [OK  ] hud           U+E100..U+E107 八码位 ↔ 八张 32x32 旅程图
  [OK  ] journey       en_us/zh_cn 键集合一致（各 18 键）
  [OK  ] lang          33 段方向分发 ↔ 32 帧模型 + 32 帧纹理
  [OK  ] compass       pack.mcmeta 精确声明资源格式 88
  [OK  ] packmeta
  RUN=8 SKIPPED=0
RESOURCE_PACK_GATE=GREEN
EXIT=0

$ sh scripts/selftest-gates.sh ; echo EXIT=$?
===== 门禁自测汇总：PASS=67 FAIL=0 =====
GATE_SELFTEST=GREEN
EXIT=0
```

### V-19 — G-206 数据包已在仓（判定：已接线）
```
$ ls datapacks/ datapacks/sivita-navigation/
datapacks/: sivita-navigation
datapacks/sivita-navigation/: data  pack.mcmeta

$ find datapacks -type f | sort
datapacks/sivita-navigation/pack.mcmeta
datapacks/sivita-navigation/data/minecraft/tags/function/quick_actions.json
datapacks/sivita-navigation/data/sivita/function/quick/all_systems.mcfunction
datapacks/sivita-navigation/data/sivita/function/quick/current_objective.mcfunction
datapacks/sivita-navigation/data/sivita/function/quick/home.mcfunction
datapacks/sivita-navigation/data/sivita/function/quick/hub.mcfunction
datapacks/sivita-navigation/data/sivita/function/quick/objective_navigation.mcfunction
datapacks/sivita-navigation/data/sivita/function/quick/skills.mcfunction
datapacks/sivita-navigation/data/sivita/function/quick/tasks.mcfunction
datapacks/sivita-navigation/data/sivita/function/quick/wilderness.mcfunction

$ sh scripts/install-datapacks.sh --check ; echo EXIT=$?
install-datapacks: --check 只校验仓库内数据包，未写任何文件
EXIT=0

$ sh scripts/check-repo-artifacts.sh ; echo EXIT=$?
EXIT=0
```

### V-20 — 八门在**当前 main HEAD `c63f446`** 上的实测记录（判定：八门全绿）
```
$ cnb build get-build-status --repo xzqkjmnbvc2026/sivitahub --sn cnb-ihu-1k3ug6u6p
status: 200
data:
  status: success
  pipelinesStatus:
    "cnb-ihu-1k3ug6u6p-001":
      name: 全量质量门（编译 → 测试 → 打包）
      status: success
      stages[11]{status,duration,name,id}:
        success,5154,Prepare,prepare
        success,17209,准备下载器（必须是第一个 stage）,stage-0
        success,392404,门禁自测（负向对照）,stage-1
        success,77680,DDL 门禁（G-301 消费者 + G-302 注册，双向）,stage-2
        success,426,素材门禁（W7-A1）,stage-3
        success,133883,compile,stage-4
        success,28363,test,stage-5
        success,5480,assemble,stage-6
        success,860,BeforeEnd,beforeEnd
        success,833,End,end

$ cnb build get-build-logs --repo xzqkjmnbvc2026/sivitahub --sn cnb-ihu-1k3ug6u6p
      sn: cnb-ihu-1k3ug6u6p
      status: success
      event: push
      targetRef: main
      sourceRef: main
      sha: c63f446a4834e126849454e5b17f3cf6076294db     # ★ 与当前 main HEAD / 本任务基点同一 commit
      commitTitle: "feat(cnb): 第八波（最终验收轮）+ 安装目标模式 Stop hook"

# 逐 stage 判据行（exit code 为唯一判据）
$ cnb build get-build-stage --repo ... --sn cnb-ihu-1k3ug6u6p --pipelineId ...-001 --stageId stage-1
GATE_SELFTEST=GREEN
PASS=67 FAIL=0

$ cnb build get-build-stage ... --stageId stage-2
DDL 表数: 81
DDL_CONSUMER_GATE=GREEN
DDL_REGISTRATION_GATE=GREEN
CMD_REFERENCE_GATE=GREEN

$ cnb build get-build-stage ... --stageId stage-3
RESOURCE_PACK_GATE=GREEN
RUN=8 SKIPPED=0

$ cnb build get-build-stage ... --stageId stage-4
BUILD_EXIT=0 SECONDS=105
TASKS_DONE=28
COMPILE_GATE=GREEN

$ cnb build get-build-stage ... --stageId stage-5
SUM                          tests=1081 failed=0 errors=0 skipped=2 modules=26
NO_XML                       modules=0
SUMMARY_GATE=GREEN
ENV_ANNOTATIONS=8
EXCLUDED_TAG_SEEN_IN_LOG=26 ENV_TAG_LINE=26
TEST_GATE=GREEN
# 对比 v3 的 sn=cnb-0ck-1k3tugbvb（sha=d11f7d8）：EXCLUDED_TAG_SEEN_IN_LOG=0 ENV_TAG_LINE=0 ⇒ TEST_GATE=RED
# 判据已在 W5-A4 修好，本轮在同一份 .cnb.yml 上实测为 GREEN

$ cnb build get-build-stage ... --stageId stage-6
JAR_COUNT=56
JAR_CHECKED=25 JAR_MISSING_PLUGIN_YML=0
PACKAGE_GATE=GREEN
```

**同 sha 的 api_trigger_gate（同一套 YAML 锚点）在本次审计时仍在跑，不影响结论**：
```
$ cnb build get-build-status --repo ... --sn cnb-ves-1k3ug78vf      # event=api_trigger_gate, sha=c63f446
      status: pending
        success,3563,Prepare
        success,60377,准备下载器（必须是第一个 stage）
        success,562533,门禁自测（负向对照）
        success,107056,DDL 门禁（G-301 消费者 + G-302 注册，双向）
        success,584,素材门禁（W7-A1）
        success,160040,compile
        start,0,test
        pending,0,assemble ...
```
（push 与 api_trigger_gate 共用同一份 `.cnb.yml` 锚点 `.quality_gate`，参考记录见本文件 V-26。）

### V-23 — 早前读到的「pending」已消解（如实记录过程）
```
# 本次审计的第一轮读取（约 14:30Z）：
$ cnb build get-build-status --repo ... --sn cnb-ihu-1k3ug6u6p
      status: pending
        success,5154,Prepare
        success,17209,准备下载器（必须是第一个 stage）
        success,392404,门禁自测（负向对照）
        start,0,DDL 门禁（G-301 消费者 + G-302 注册，双向）
        pending,0,素材门禁（W7-A1） / compile / test / assemble

# 同一 sn 的第二次读取（约 14:45Z）：
      status: success
        11 格全 success（见 V-20）
# ⇒ 第一次读到 pending 是构建尚未跑完，不是失败。

$ git diff --stat c4f1a9b752ab032ddd265c09f776ef33b4ee6c2b c63f446a4834e126849454e5b17f3cf6076294db
 .workbuddy/settings.json | 16 ++++++++++++++++
 doc/cnb/tasks-w8.json    | 17 +++++++++++++++++
 2 files changed, 33 insertions(+)
# ⇒ c63f446 相对上一轮功能性提交 c4f1a9b 只新增两个文档/配置类文件，
#   未触碰 plugins/、.cnb.yml、scripts/、resource-pack/、datapacks/

$ cnb build get-build-stage --repo ... --sn cnb-rbm-1k3u8m981 --pipelineId ...-001 --stageId stage-4
▸ 失败 Stage: compile (status: error)
  错误信息: 任务超过(10m0s)没有任何输出而被强制关闭
  ...
  check-file-deps: OK
  ===== compileJava 尝试 1/3 =====
  Finished, code: -1, duration: 14m 37s
# ⇒ ffa84be 与 a768c51 那两条 error 都是 CNB Runner 的无输出静默超时，不是编译错误
```

### V-26 — 八门的三个运行记录横向对照（证明判据的稳定性）
```
$ cnb build get-build-status --repo ... --sn cnb-vp6-1k3u7q139    # api_trigger_gate, sha=5c0616f（main 祖先）
      status: success   11 格全 success
        compile 131660ms / test 35314ms / assemble 7926ms
        TEST_GATE=GREEN   SUM tests=1063 failed=0 modules=26
        COMPILE_GATE=GREEN BUILD_EXIT=0 SECONDS=119 TASKS_DONE=28
        PACKAGE_GATE=GREEN JAR_COUNT=56 JAR_CHECKED=25 JAR_MISSING_PLUGIN_YML=0

$ cnb build get-build-status --repo ... --sn cnb-i4l-1k3u7pn7a    # push, sha=5c0616f
      status: success   11 格全 success

$ cnb build get-build-status --repo ... --sn cnb-ihu-1k3ug6u6p    # push, sha=c63f446（★ 当前 HEAD）
      status: success   11 格全 success
        TEST_GATE=GREEN   SUM tests=1081 failed=0 modules=26
# ⇒ 三份不同的运行、两个不同的 sha，八门判据行都稳定为 GREEN；
#   test 用例数从 1063 → 1081 是 W7-A2 新增行为级测试带来的增量。
```

### V-21 — G-302 源码字符串伪测试精确判定（判定：存疑，收窄到 2 个类）
```
$ grep -rl --include=*.java 'Files.readString' plugins tools | grep -c '/src/test/'
48

$ grep -rl --include=*.java 'Files.readString' plugins tools | grep '/src/test/' | xargs grep -l 'src/main' 2>/dev/null | wc -l
20

# 逐类分类（脚本判定）
$ python3 -
纯源码字符串断言类（真伪测试）: 2
  tests-asserts=22 plugins/rpg-mobs/src/test/java/cn/originalrpg/mobs/MobRewardAsyncLifecycleContractTest.java
  tests-asserts=29 plugins/rpg-pets/src/test/java/cn/originalrpg/pets/PetRevivalLifecycleContractTest.java

$ grep -n 'assert' plugins/rpg-mobs/src/test/java/cn/originalrpg/mobs/MobRewardAsyncLifecycleContractTest.java | tail -4
        assertTrue(experience.contains("current == expected && online"));
        assertTrue(queue.contains("current == session && current.isOnline()"));
        assertTrue(plugin.indexOf("delayedRewards.close()") < plugin.indexOf("experienceBuffer.shutdown()"));

$ grep -c 'check-source-contract\|ProcessBuilder' plugins/rpg-mobs/src/test/java/cn/originalrpg/mobs/MobRewardAsyncLifecycleContractTest.java
0
$ grep -c 'ProcessBuilder' plugins/rpg-pets/src/test/java/cn/originalrpg/pets/PetRevivalLifecycleContractTest.java
0
# ⇒ 这两个类既没有真行为断言，也没有执行外部门禁脚本，是 G-302 的真实残留。

# 对照组：25 个类的「跑真脚本」证据
$ sed -n '36,49p' plugins/rpg-classes/src/test/java/cn/originalrpg/classes/ClassAsyncLifecycleContractTest.java
    private static void assertContractHolds(String method) throws Exception {
        Path root = repositoryRoot();
        Path gate = root.resolve("scripts/check-source-contract.sh");
        assertTrue(Files.isRegularFile(gate), "缺少契约门禁脚本: " + gate);
        Process process = new ProcessBuilder("sh", gate.toString(), SPEC_KEY, method)
                .directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "契约门禁未在 120 秒内结束");
        assertEquals(0, process.exitValue(), "源码契约门禁失败（" + method + "）：\n" + output);
    }

# 分类计数（本轮口径，与 v3 的「53」口径不同，已在 §7 说明）
$ for g in check-source-contract check-config-fallbacks check-trusted-menu-holders; do echo -n "$g: "; grep -rl "$g" plugins/*/src/test/java --include=*.java | wc -l; done
check-source-contract: 25
check-config-fallbacks: 1
check-trusted-menu-holders: 1
```

### V-22 — 最近几波新增配置键的读取方（判定：全部有读取方）
```
$ grep -rn 'maximum-events-per-day' plugins/*/src/main/java
plugins/rpg-economy/src/main/java/cn/originalrpg/economy/EconomicActivityListener.java:310: Math.clamp(plugin.getConfig().getInt("caps.RAID.maximum-events-per-day", 3), 0, 64),
plugins/rpg-world/src/main/java/cn/originalrpg/world/WorldEventListener.java:204: "blood-moon.maximum-events-per-day", 3), 0, 64);

$ grep -rn 'season-attunement\|seasonAttunementEnabled' plugins/rpg-core/src/main/java
plugins/rpg-core/src/main/java/cn/originalrpg/core/RpgCorePlugin.java:252: boolean seasonAttunementEnabled = getConfig()
plugins/rpg-core/src/main/java/cn/originalrpg/core/RpgCorePlugin.java:253:         .getBoolean("season-attunement.enabled", false);

$ grep -rn 'minimum-roundtrip-loss\|MINIMUM_ROUNDTRIP_LOSS_FLOOR' plugins/*/src/main/java
plugins/rpg-economy/.../EconomyCommand.java:597: plugin.getConfig().getDouble("black-market.minimum-roundtrip-loss", 0.10));
plugins/rpg-economy/.../MerchantPricing.java:10: static final BigDecimal MINIMUM_ROUNDTRIP_LOSS_FLOOR = new BigDecimal("0.10");
plugins/rpg-economy/.../MerchantPricing.java:27: if (value.compareTo(MINIMUM_ROUNDTRIP_LOSS_FLOOR) < 0) {

$ grep -rn 'telegraph-ticks' plugins/rpg-dungeons/src/main/java
plugins/rpg-dungeons/.../DungeonManager.java:1176: "dungeons." + id + ".boss.abilities.telegraph-ticks", 36L));

$ grep -rn 'dailyEventAllowed' plugins/rpg-world/src/main/java
plugins/rpg-world/.../WorldEventRules.java:29（纯规则）
plugins/rpg-world/.../WorldEventListener.java:205（调用）
```

### V-17 — 最近几波新引入类的零消费者复检（判定：无新零消费者）
```
$ for c in JdbcMarketBuyOrderService JdbcSeasonAttunementService DailyEventCounter HudRailSpec JdbcSupportCreditService JdbcProgressionAnomalyService; do \
    echo "--- $c"; grep -rn --include=*.java "$c" plugins/*/src/main | grep -v "/$c.java"; done
--- JdbcMarketBuyOrderService
plugins/rpg-core/.../RpgCorePlugin.java:48（import）:209（构造）:287（ServicePriority 注册）
--- JdbcSeasonAttunementService
plugins/rpg-core/.../RpgCorePlugin.java:65（import）:254（构造条件）:255（构造）:261（注册）
plugins/rpg-api/.../EquipmentLifecycle.java:154（接入合同注释）
--- DailyEventCounter
plugins/rpg-economy/.../EconomicActivityListener.java:102: private final DailyEventCounter raidEvents = new DailyEventCounter();
--- HudRailSpec
plugins/rpg-integrations/.../SivitaPlaceholderExpansion.java:145（注释）:148（HudRailSpec.slotCount()）
--- JdbcSupportCreditService
plugins/rpg-core/.../RpgCorePlugin.java:51（import）:215,216（构造）:291（注册）
--- JdbcProgressionAnomalyService
plugins/rpg-core/.../RpgCorePlugin.java:50（import）:212,213（构造）:289（注册）

$ grep -n 'buyorder' plugins/rpg-market/src/main/resources/plugin.yml plugins/rpg-market/src/main/java/cn/originalrpg/market/MarketCommand.java
plugin.yml:12: usage: /market [...|buyorder <place|list|cancel>|...]
MarketCommand.java:128: case "buyorder" -> buyOrder(player, args);
MarketCommand.java:1067: ... "buyorder" ...
MarketCommand.java:1074: if (args.length == 2 && args[0].equalsIgnoreCase("buyorder")) {
```

### V-24 — 枚举零消费者巡检（判定：无新零消费者）
```
$ for k in SALVAGE LEGACY_EXTRACT DESTROY SEASON_ATTUNEMENT; do echo -n "$k: "; grep -rn --include=*.java "EquipmentOperationKind.$k" plugins/*/src/main | wc -l; done
SALVAGE: 4
LEGACY_EXTRACT: 0        # 枚举里没有 LEGACY_EXTRACT，只有 LEGACY（见下）
DESTROY: 5
SEASON_ATTUNEMENT: 0     # 刻意：W6-A2 明确「不经玩家操作 outbox」，由 EquipmentExitPathReachabilityTest 显式断言

$ grep -rn --include=*.java 'EquipmentOperationKind.LEGACY\b' plugins/*/src/main
plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/LegacyCommand.java:77
plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/LegacyCommand.java:117
plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/LegacyCommand.java:132

$ grep -rn "SACRIFICED" plugins --include=*.java | grep -v 'EquipmentLifecycle.java'
plugins/rpg-api/src/test/java/cn/originalrpg/api/rules/EquipmentLifecycleTest.java:30
plugins/rpg-items/src/test/java/cn/originalrpg/items/forge/EquipmentExitPathReachabilityTest.java:88,96,103
# SACRIFICED 在 rpg-api 之外零命中：C-008 十二条状态机里的预留态。

$ grep -rn "SACRIFICED\|献祭" docs/CODEX_LONG_TERM_EXECUTION_PLAN.md docs/LONG_TERM_GAME_ECONOMY_PLAN.md
docs/CODEX_LONG_TERM_EXECUTION_PLAN.md:57: 落分解/献祭/传承/调谐，市场增加买单与制作订单
docs/LONG_TERM_GAME_ECONOMY_PLAN.md:29: ...→ SALVAGED → SACRIFICED → LEGACY_EXTRACTED → ...
# 三份文档（W7-A2 版本）都把「献祭」定位成 /legacy 的语义别名，实现上是 LEGACY 操作；
# SACRIFICED 是状态机里的预留态，由 EquipmentExitPathReachabilityTest:103 的
# assertEquals(List.of(LIFECYCLE_ONLY), productionReferences("SACRIFICED")) 显式承认「当前无入口」。
```

### V-25 — 侧门禁本地实测（判定：全部 GREEN）
```
$ sh scripts/check-config-fallbacks.sh ; echo EXIT=$?
CONFIG_FALLBACK_GATE=GREEN
EXIT=0
$ sh scripts/check-source-contract.sh --all ; echo EXIT=$?
SOURCE_CONTRACT_CHECKS=185 SPECS=25
SOURCE_CONTRACT_GATE=GREEN
EXIT=0
$ sh scripts/check-trusted-menu-holders.sh ; echo EXIT=$?
TRUSTED_MENU_GATE=GREEN
EXIT=0
$ sh scripts/check-repo-artifacts.sh ; echo EXIT=$?
REPO_ARTIFACT_GATE=GREEN
EXIT=0
$ sh scripts/check-file-deps.sh ; echo EXIT=$?
check-file-deps: OK
EXIT=0
```

### V-27 — 本地取证限制（如实说明）
```
$ command -v java || echo 'no java'
no java
$ ls /usr/lib/jvm 2>/dev/null || echo 'no jvm'
no jvm
$ python3 -V
Python 3.11.2
⇒ compile / test / assemble 三门不能在本容器本地复跑，只能引用 CNB 上的门运行记录（V-20 / V-23）。
```

## 3. DDL 表消费者矩阵（G-109 全量对账，本轮实测）

```
$ sh scripts/check-ddl-consumers.sh
DDL 表数: 81
豁免清单: 0 条已登记（本次生效 0 条）
--- （无「待接线 PENDING」段、无「已废弃 ABANDONED」段、无「未接线表」段）
DDL_REGISTRATION_GATE=GREEN
DDL_CONSUMER_GATE=GREEN
EXIT=0
```

**81 张表逐张独立复核（V-04）结论：零消费者 = 0 张，代码引用但 DDL 无 = 0 张。**
不再复述 v3 的 4 条 PENDING（W6-A1 已全部接线；反向豁免清空已在 `scripts/check-ddl-consumers.sh:265-284` 留审计注释）。

## 4. 结构性门禁实测

| 门禁 | 脚本 | 本地实测 | 在 CI 里的执行者 |
|---|---|---|---|
| 门禁自测（负向对照） | `scripts/selftest-gates.sh` | `GATE_SELFTEST=GREEN (PASS=67 FAIL=0)` | 门禁自测（负向对照）stage |
| DDL 消费者（正向） | `scripts/check-ddl-consumers.sh` §1-5 | `DDL_CONSUMER_GATE=GREEN`（81 张） | DDL 门禁（双向）stage |
| DDL 注册（反向） | `scripts/check-ddl-consumers.sh` §7 | `DDL_REGISTRATION_GATE=GREEN`（81 个） | DDL 门禁（双向）stage |
| 指令引用 | `scripts/check-ddl-consumers.sh` §6 | `CMD_REFERENCE_GATE=GREEN`（43 条已注册） | DDL 门禁（双向）stage |
| 素材 | `scripts/check-resource-pack.sh` | `RESOURCE_PACK_GATE=GREEN`（RUN=8） | 素材门禁（W7-A1）stage |
| 源码契约 | `scripts/check-source-contract.sh` + 25 个测试类真实执行 | `SOURCE_CONTRACT_GATE=GREEN`（185 checks / 25 specs） | test stage |
| 配置回退 | `scripts/check-config-fallbacks.sh` + 1 个测试类真实执行 | `CONFIG_FALLBACK_GATE=GREEN` | test stage |
| 受信菜单 | `scripts/check-trusted-menu-holders.sh` + 1 个测试类真实执行 | `TRUSTED_MENU_GATE=GREEN` | test stage |
| 仓库产物（G-206） | `scripts/check-repo-artifacts.sh` | `REPO_ARTIFACT_GATE=GREEN` | 门禁自测 stage（§6 接线自证） |
| 数据包校验（G-206） | `scripts/install-datapacks.sh --check` | exit=0 | 门禁自测 stage（§4c） |
| HUD 尺寸唯一来源（W7-A4） | `resource-pack/tools/verify_hud_layout.py` | exit=0 | 门禁自测 stage（§5b） |
| 编译期依赖自包含 | `scripts/fetch-deps.sh` + `scripts/check-file-deps.sh` | `check-file-deps: OK` | compile stage |

## 5. 质量门在「当前 main」上的状态

八个 stage = 准备下载器 / 门禁自测 / DDL 门禁（双向）/ 素材门禁 / compile / test / assemble。

| 门 | `sn=cnb-vp6-1k3u7q139`（main 祖先 `5c0616f`） | 判据行原文 |
|---|---|---|
| 准备下载器 | success | `DOWNLOADER=curl(ready)` / `DOWNLOADER=ready` |
| 门禁自测（负向对照） | success | `===== 门禁自测汇总：PASS=67 FAIL=0 =====` / `GATE_SELFTEST=GREEN` |
| DDL 门禁（双向） | success | `DDL_REGISTRATION_GATE=GREEN` / `DDL_CONSUMER_GATE=GREEN` / `CMD_REFERENCE_GATE=GREEN` |
| 素材门禁（W7-A1） | success | `RESOURCE_PACK_GATE=GREEN` / `RUN=8 SKIPPED=0` |
| compile | success | `COMPILE_GATE=GREEN` / `BUILD_EXIT=0 SECONDS=119` / `TASKS_DONE=28` / `ATTEMPTS=1` |
| test | success | `TEST_GATE=GREEN` / `SUM tests=1063 failed=0 errors=0 skipped=2 modules=26` / `NO_XML modules=0` / `SUMMARY_GATE=GREEN` / `EXCLUDED_TAG_SEEN_IN_LOG=26 ENV_TAG_LINE=26` |
| assemble | success | `PACKAGE_GATE=GREEN` / `JAR_COUNT=56` / `JAR_CHECKED=25 JAR_MISSING_PLUGIN_YML=0` |

**本轮采信的八门证据 = `sn=cnb-ihu-1k3ug6u6p`（`push` 事件，`sha=c63f446a4834e126849454e5b17f3cf6076294db`，
**与当前 `main` HEAD 和本任务基点同一 commit**，`status=success`，11 格全 success）。**

旁证（不做主要依据）：同一 sha 的 `api_trigger_gate` `cnb-ves-1k3ug78vf` 在本次审计时
已跑完 Prepare / 准备下载器 / 门禁自测（562533ms success）/ DDL 门禁（107056ms success）/
素材门禁（584ms success）/ compile（160040ms success），`test` 处于 running 状态。
push 与 api_trigger_gate 共用同一个 YAML 锚点 `.quality_gate`（`.cnb.yml:39-42`），
所以两者的判据完全相同；`cnb-vp6-1k3u7q139`（`sha=5c0616f`）与 `cnb-i4l-1k3u7pn7a`（同 sha）两次运行
也都全绿（V-26），判据行的稳定性已横向对照。

早前在 `ffa84be` / `a768c51` 上读到的两条 `compile error`（V-23）是 **CNB Runner 的
「任务超过(10m0s)没有任何输出而被强制关闭」**，不是编译错误：同一份内容在 push 门里是 success。
本容器无 JDK（V-27），无法本地复跑 compile/test/assemble，这一点如实记录，未以任何方式替代门运行记录。

## 6. 与 `doc/cnb/gap-registry.md` 的对账（本轮）

| gap id | gap-registry 声明状态 | 本轮机械核验结果 | 偏差 |
|---|---|---|---|
| G-001..G-004 | DONE（W1） | 确认已接线 | 无 |
| G-101/G-102/G-103 | TODO | 确认已接线 | 无 |
| G-104/G-105 | DONE（W2） | 确认已接线 | 无 |
| G-106 | TODO | **确认已接线**（血契已改三转，与 `vanished-horizon` 前置一致） | ★ 上版判 REQUIRES_HUMAN，本轮关闭 |
| G-107 | DONE | 两半都确认已接线 | 无 |
| G-108 | DONE | 确认已修复（死键已删） | 无 |
| G-109 | TODO | **确认 4 张全部已接线**（买单簿 / 异常写入口 / support_credit 双表） | ★ 上版 4 张 PENDING，本轮关闭 |
| G-110 | DONE | `DESTROYED` 已接线；**`SEASON_ATTUNED` 也已接线**（换季结算触发） | ★ 上版遗留，本轮关闭 |
| G-111..G-114 | DONE | 全部确认已接线 | 无 |
| G-115 | 新登记（v2） | 确认已接线 | 无 |
| G-201..G-205 | TODO（P2 订正） | 确认已订正（G-201/202/203 行为已与文档一致） | 无 |
| G-206 | TODO | **确认已接线**（数据包入仓 + 两条门禁 + 接线自证） | ★ 上版 REQUIRES_HUMAN，本轮关闭 |
| G-207/G-208/G-209 | TODO | **确认已按文档承诺值落地为显式行为** | ★ 上版 REQUIRES_HUMAN，本轮关闭 |
| G-301 | DONE | 确认已接线 | 无 |
| G-302 | TODO | **收窄到 2 个类**（`MobRewardAsyncLifecycleContractTest` / `PetRevivalLifecycleContractTest`） | 未完成 |
| G-303 | DONE | v2/v3 已产出；本轮产出 v4 | 无 |
| G-401/G-402/G-403 | DONE（W1-A0） | 确认已接线 | 无 |
| G-DDL-REG | 未登记（v3 新登记） | **确认已接线** | ★ 上版声明未接线，本轮关闭 |
| G-TEST-GATE | 未登记（v3 新登记） | **确认已接线**（判据已修，`EXCLUDED_TAG_SEEN_IN_LOG=26`） | ★ 上版真实缺口，本轮关闭 |
| **G-ANOMALY-ACK** | 未登记 | **存疑**：`ProgressionAnomalyService.pending(UUID)` / `pending(int)` / `acknowledge(UUID,String)` 在 `src/main` 零消费者；写侧（`JdbcProfileService.java:602/:608`）已接线 | ★ 本轮新登记 |

## 7. 本轮新增结论（gap-registry 未登记）

1. **G-ANOMALY-ACK（新增，存疑）**：`rpg_progression_anomalies` 的**写侧**已接线
   （`JdbcProfileService.recordAnomalyIfUninterpretable` 在每次 `load()` 读到不可解释档案时 UPSERT 一行），
   但 **[H] 复核出口 `acknowledge(playerId, reviewer)` 在 `src/main` 里没有任何调用点**，
   `pending(UUID)` / `pending(int)` 同样零消费者。
   按 `GOAL.md` §2 的机械口径，「表有真实消费者」这条已满足，所以它**不**构成「声明未接线」；
   但 `ProgressionAnomalyService` 的读侧能力在运行期确实不可达。
   ⇒ 判为**存疑**，列入 `verdict-v4.json` 的 `needs_human`（需要人决定：补一个管理面入口，还是把读侧标记成 Phase 1 的 [H] 人工走 SQL）。
2. **SACRIFICED 不是缺口**：三份文档已把「献祭」落成 `/legacy` 的语义（`EquipmentOperationKind.LEGACY` 有 3 处 src/main 消费者），
   `SACRIFICED` 是状态机里的预留态，`EquipmentExitPathReachabilityTest:103` 用
   `assertEquals(List.of(LIFECYCLE_ONLY), productionReferences("SACRIFICED"))` 显式承认「当前无入口」。
   把 12 态枚举里的预留态当成缺口属于**造假缺口**，本轮判为「有意保留的预留态」，不计入 remaining。
3. **`SEASON_ATTUNEMENT` 枚举值零消费者不是缺口**：W6-A2 的接线刻意不经玩家操作 outbox
   （`EquipmentLifecycle.afterOperation` 的 `case SEASON_ATTUNEMENT -> null`，以及
   `EquipmentExitPathReachabilityTest:121-127` 的断言）。触发者是换季结算，已核实（V-09）。
4. **G-302 的收窄方式需要说明口径**：v3 的口径是「用了 `Files.readString` 的测试类」= 53；
   本轮实测该口径下是 48（W5-A2 第二批已把 25 个类改为**用 `ProcessBuilder` 真实执行门禁脚本并断言 exit code**，
   这比源码字符串断言强 —— 断言的是真脚本的真退出码 —— 但仍不执行被测业务行为）。
   按「含真行为断言才算 [T]」的严格口径逐类统计后，剩余 **2 个类**
   （`MobRewardAsyncLifecycleContractTest`、`PetRevivalLifecycleContractTest`）的每个 `@Test`
   全部断言都是 `source.contains(...)` / `indexOf()` 的跨文件比较，
   **没有任何行为断言、也不执行任何外部脚本**，是本轮唯一的 G-302 残留。
   另有 21 个混合类（既有真行为断言，也附带源码字符串断言），按 `[T]` 打折但不计为伪测试；
   3 个类（`HudRailSpecTest` / `PerformanceStatusStoreTest` / `StoryJourneyOrderContractTest`）
   用 `Files.readString` 读的是测试自己产出的文件或清单，是真行为 IO，不计为伪测试。
5. **收口条件评估**：done=true 的三条逐一核验 ——
   ① 差集矩阵里的「声明未接线」项：**0 条**（唯一存疑是 G-ANOMALY-ACK 的读侧，属「存疑」不属「声明未接线」）；
   ② 八门在当前 main 上全绿：**成立**（`sn=cnb-ihu-1k3ug6u6p`，`sha=c63f446` = 当前 main HEAD，
      11 格全 success，判据行见 V-20）；
   ③ 剩余问题只有 REQUIRES_HUMAN：**不成立** ——
      G-302 的 2 个纯源码字符串断言测试（`MobRewardAsyncLifecycleContractTest` /
      `PetRevivalLifecycleContractTest`）是**可派工的实现工作**，不是需要人拍板的取舍；
      `ProgressionAnomalyService` 的读侧零消费者（G-ANOMALY-ACK）需要人决定「补管理面入口 vs 标记成 [H] 人工走 SQL」，
      属 REQUIRES_HUMAN，但它同时也不是「声明未接线」。
   ⇒ 第 ③ 条不成立 ⇒ **`done=false`**。`remaining=2`，`remaining_ids=["G-302(2 个纯源码字符串断言测试)", "G-ANOMALY-ACK(读侧零消费者，需人类选出口)"]`。
   详细见 `plan/verdict-v4.json`。
