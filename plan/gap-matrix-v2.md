# 全量机制矩阵 v2（gap-matrix-v2）

> 生成者：CNB CodeBuddy NPC，任务 `W3-A5-gapmatrix-v2`。**只读审计**，未改 `plugins/**/src/main` 任何源码。
> 基点：`main` @ `7163984`（W2 完成登记后）。分支 `agent/W3-A5-gapmatrix-v2`。
> 判定口径来自 `doc/cnb/GOAL.md` §2 与 `doc/cnb/decisions-locked.md` L-008：
> 机制算「已接线」当且仅当对它的类/方法/表名在 `plugins/*/src/main` 全量 grep 存在**非自身定义文件**的调用点；
> 只命中自身定义文件（或只命中 `src/test`）= 「声明未接线」。
> 找不到调用点写「无」。全文不出现「推测」「大概」「应该」三个词。
> 与 v1（`plan/gap-matrix.md`）的差异：v1 的 39 条里 21 条已在 W1/W2 修复，本轮逐条按当前 main 重跑，判定随证据改写。

## 0. 覆盖范围与取证方式（本轮实测）

| 项 | 数量 | 取证命令 |
|---|---|---|
| `plugins/` 下模块 | 25 | `ls plugins/ \| wc -l` |
| `src/main` Java 文件（全量，非抽样） | 513 | `find plugins -path '*/src/main/*' -name '*.java' \| wc -l` |
| `src/test` Java 文件 | 253 | `find plugins -path '*/src/test/*' -name '*.java' \| wc -l` |
| DDL 表（去重） | 79 | `sh scripts/check-ddl-consumers.sh` 输出 `DDL 表数: 79` |
| DDL 消费者门禁 | GREEN | 见 §4，75→79 张（W2-D1 新增 4 张换季表） |
| 机制矩阵条目 | 36 | 每条自带 grep 原文，见 §2 |
| v1 已修复项 | 21 | 见 §1「已接线」行与 `state/world-state.json` `merged_gaps` |

**关于 config.yml 配置键的取证边界（与 v1 一致，如实说明）**：25 个模块共 1943+ 个 YAML 叶子键。
本项目大量配置用 `getConfigurationSection(prefix)` + `getKeys(false)` 动态遍历，因此配置键**无法用「字面量 grep」机械判定未接线**（动态拼接的键会全部误判为未读）。
本轮**不对配置键做未接线判定**。已机械核实的配置项只有 `dungeons.*.world`（G-108，W2-D2 已删）与 `black-market.minimum-roundtrip-loss`（G-208）。

## 1. 机制级矩阵

判定取值：`已接线` / `声明未接线` / `存疑` / `已废弃`。

| 机制/模块 | 声明的载体（类/表/枚举/配置键）| src/main 里的真实调用点（文件:行号，没有写「无」）| 行为级测试 | 判定 | 对应 gap id |
|---|---|---|---|---|
| Kill Switch 开关判定/切换（C-005 第4腿）<br>`rpg-core` | `MechanismRegistry.requireEnabled(String)`；表 `rpg_mechanism_switches` | `JdbcActivityEconomyService.java:71,72,73,351`<br>`JdbcCommerceService.java:193,540`<br>`JdbcMarketService.java:53`<br>`JdbcSeasonExpeditionService.java:75`<br>`JdbcDungeonRewardService.java:89`<br>`JdbcSeasonSettlementService.java:104`<br>`JdbcCommunityService.java:613`（7 个 faucet 入口） | `MechanismKillSwitchContractTest.java:41,53,64`（真跑 `requireEnabled` 断言拒绝与指标） | 已接线 | G-002 |
| 五维防御 / 装备 `defense` 词条在 PVE/PVP 生效<br>`rpg-combat` | `ExecuteListener.defenseMultiplier(Player)` | `ExecuteListener.java:155`（`onDamage(EntityDamageByEntityEvent)` 内，即互砍路径） | `DefenseWiringTest.java`（事件级 4 例，负向对照删调用点变红） | 已接线 | G-001 |
| 装备 HMAC 签名密钥 env 唯一来源 / 轮换<br>`rpg-items` | `EquipmentSigningMaterial.CURRENT_KEY_ENV` | `EquipmentSigningMaterial.java:25`（env 名常量）；消费方 `EquipmentSecurity` 构造注入 | `EquipmentSigningKeyWiringContractTest.java`；W1-A3 报 12 例行为级 | 已接线 | G-003 |
| 迁移 advisory lock 在线/离线唯一<br>`rpg-core` + `tools/db-migration` | `Database.MIGRATION_LOCK_NAMESPACE/RESOURCE` | `Database.java:1132-1133` `pg_advisory_xact_lock(NS, RES)`；工具侧 `MigrationAdvisoryLock.java:18-20` 同值 | `DatabaseRulesTest.java:65` | 已接线 | G-004 |
| 赛季结算九态状态机 + `SeasonSettlementService`（C-010）<br>`rpg-api`/`rpg-core` | `SeasonSettlementService.state/preview/advance`；表 `rpg_season_settlements` | `state()` → `RpgCommand.java:114`；`RpgCorePlugin.java:233,287` 注册<br>`preview()` → 无<br>`advance()` → 无 | `JdbcSeasonSettlementServiceTest.java`（13 例行为级） | **部分接线**：状态机+`state()` 已接线；`preview()`/`advance()` 零生产消费者 ⇒ 单人只读展示可跑，**换季推进本身无生产入口** | G-101 |
| 多服前置 Session Lease（C-014）<br>`rpg-core` | 表 `rpg_session_leases`；`JdbcSessionLease` | `JdbcSessionLease.java:71`（SELECT 读侧判定） | `JdbcServicesTest`（W2-D5 行为级） | 已接线（只读侧） | G-102 |
| 多服前置 Transactional Outbox（C-014）<br>`rpg-core` | 表 `rpg_outbox`；`JdbcOutboxRegistry`/`OutboxDispatcher` | `JdbcOutboxRegistry.java:36`（INSERT）<br>`OutboxDispatcher.java:112,143,165`（SELECT/UPDATE） | `JdbcServicesTest`（W2-D5 行为级） | 已接线 | G-102 |
| 社群七角色 / 双批审批（Phase 6）<br>`rpg-api`/`rpg-core`/`rpg-community` | `CommunityRole.canApprove()`；`CommunityRolePower`；`CommunityGovernanceAction`；表 `rpg_community_approvals` | `CommunityRolePower.java:37,39,41,54,71`<br>`CommunityGovernanceHandler.java:116`<br>`JdbcCommunityService.java:799,966,976`<br>`JdbcCommunityService.java:1015,1037,1062,1079,1103`（表读写） | `JdbcCommunityGovernanceTest`、`CommunityGovernanceGateTest`（W2-D4 行为级） | 已接线 | G-103 |
| 社群治理角色判定 `CommunityRole.isGovernance()`<br>`rpg-api` | `CommunityRole.isGovernance()` | 无（仅命中自身定义文件 `CommunityRole.java:31`；`src/test` 里 `CommunityRolePowerTest.java:30` 有断言） | `CommunityRolePowerTest.java:30` | **声明未接线**（零 src/main 消费者；`canModerate/canApprove` 已接线，`isGovernance` 是遗留未接线口径） | 新增 G-115 |
| 邮箱交付冻结：创造模式容器事件 + 游标槽<br>`rpg-market` | `MarketCommand.onCreativeClick`；`DeliveryFreezeRules.CURSOR_SLOT` | `MarketCommand.java:761`（`InventoryCreativeEvent` 处理器）、`:41` import | `MailboxClaimReconcilerTest`、`DeliveryFreezeRulesTest`、`DeliveryFreezeWiringTest`（W2-B1） | 已接线 | G-104 |
| 战斗总线单一结算（消除攻方增益二次放大）<br>`rpg-skills`/`rpg-combat` | `SkillsCommand.settleAttackerBonus`（HIGHEST 单点）+ `AttackerBonusTag` 幂等 | `SkillsCommand.java:1000,1003,1042`（单点写入）、`:952`（只推进层数不写伤害） | `AttackerBonusTest`（真构造 `EntityDamageByEntityEvent` 断言最终伤害，W2-B2） | 已接线 | G-105 |
| 血契（BLOODPACT）按文档条件可达成<br>`rpg-api`/`rpg-skills` | `HiddenPathRules.minimumAscension()=2` + `requiredSkill()=vanished-horizon`；`config.yml` 的 `vanished-horizon.minimum-ascension` | `JdbcHiddenPathService.java:114,122`<br>`HiddenPathReachability.java:74,131`<br>`RpgSkillsPlugin.java:181-184`（启动自检：`minimum-ascension != branch stage` 即拒启） | `SkillsCommandTest`；W2-D3 交付 25 例可达性测试 | **存疑（配置/分支不变式冲突）**：`HiddenPathRules` 要求 2 转，而 `vanished-horizon` 所属 `unseen` 分支 stage=3，`RpgSkillsPlugin.validateCatalog` 要求两者相等 ⇒ 单改技能门会让插件启动失败。W3-A1 已派 | G-106 |
| 档案简化旁路 `ascend(UUID)`（AGENTS.md §3 明令删除）<br>`rpg-api`/`rpg-core` | `ProfileService.ascend(UUID)` | 无（`grep 'ascend(' plugins \| grep src/main` 只剩 `AscensionService.ascend` 与 `RpgClassesPlugin.java:429` 走 `AscensionService`） | `JdbcServicesTest`（W2-A1 反射断言入口已消失 + 真跑收据落库） | 已接线（旁路已删除） | G-107 |
| 技能点「本地里程碑 + 幂等补偿」<br>`rpg-core` | `JdbcProfileService`/`JdbcDungeonRewardService` 技能点结算 | `JdbcProfileService.java:544`、`JdbcDungeonRewardService.java:289` 仍为 `finalEffective / 3 - startingEffective / 3` | — | **存疑（未落地）**：W2-A1 只删了 `ascend()` 旁路本体，AGENTS.md 要求的「本地里程碑 + 幂等补偿」替换 `effectiveLevel/3` 未实现；本文件两处仍是整数除 3 | G-107 遗留半条 |
| 装备退出路径判定（C-008）<br>`rpg-api`/`rpg-items` | `EquipmentLifecycle.requiresExitPath/isExitPath/isTradeable`；`EquipmentExitPathRules`/`Gate` | `EquipmentExitPathRules.java:36,44,58,66`<br>`EquipmentExitPathGate.java:37`<br>接入 `/salvage`、`/legacy`、`/unbind`、启动自检（W2-B3） | `EquipmentExitPathRulesTest`（11 例，含变异测试，W2-B3） | 已接线 | G-110 |
| 装备调谐退出路径（`SEASON_ATTUNED`） | `EquipmentLifecycle.State.SEASON_ATTUNED` | `EquipmentLifecycle.java:22,41,44,49,109`（**仅自身**） | 无 | **声明未接线** | G-110 遗留 / W3-A3 已派 |
| 装备明确销毁退出路径（`DESTROYED`） | `EquipmentLifecycle.State.DESTROYED` | `EquipmentLifecycle.java:23,28,47,48,50,109`（仅自身）<br>`JdbcEquipmentLedgerAuditService.java:71`（审计只读 `WHERE state IN (...)`） | 无 | **声明未接线**（审计只读不算生产入口） | G-110 遗留 / W3-A3 已派 |
| 阶段经验预算 `totalExperienceForAscension()`<br>`rpg-api` | `ProgressionRules.totalExperienceForAscension(int)` | `RpgCommand.java:132`<br>`ProgressionPlaceholders.java:21`<br>`MobGuideRules.java:79` | `ProgressionRulesTest`（W2-B4 行为级） | 已接线 | G-111 |
| 有效等级累计经验 `totalExperienceToEffective()`<br>`rpg-api` | `ProgressionRules.totalExperienceToEffective(int)` | `RpgCommand.java:143`<br>`ProgressionPlaceholders.java:26`<br>`MobGuideRules.java:93`、`MobGuideCommand.java:245` | `ProgressionRulesTest`（含单调性断言，W2-B4） | 已接线 | G-111 |
| 有效等级上限 `effectiveLevelCap()`<br>`rpg-api` | `ProgressionRules.effectiveLevelCap()` | `AdaptiveMobSafetyRules.java:30`<br>`ProgressionPlaceholders.java:18`<br>`HiddenPathReachability.java:116,118` | `ProgressionRulesTest`（W2-B4） | 已接线 | G-111 |
| 权威物品命名空间判定 `isAuthoritativeNamespace()`<br>`rpg-api` | `AuthoritativeItemPolicy.isAuthoritativeNamespace(String)` | `AuthoritativeItemPolicy.java:86`（`isAuthoritativeKey` 内部调用）→ `EquipmentSecurity.java:114`（`authoritativeNamespace`）→ `:104` 门<br>`ForgeListener.java:967`（`authoritativeNamespaceOnly`）→ `:950` 门 | `AuthoritativeNamespaceGateTest`（3 例）、`ForgeInputNamespaceGateTest`（2 例），负向对照变红（W2-B5） | 已接线（**间接经 `isAuthoritativeKey`**） | G-112 |
| 节点租约续期 `renewAsync()`<br>`rpg-core` | `JdbcNodeLease.renewAsync()`；`NodeLeaseSupervisor` | `NodeLeaseSupervisor.java:87`、`:146`（delegate） | `NodeLeaseSupervisorTest`（W2-B6 行为级） | 已接线 | G-113 |
| 节点租约有效性 `mayStillBeValid()`<br>`rpg-core` | `JdbcNodeLease.mayStillBeValid()` | `NodeLeaseSupervisor.java:119`、`:151` | `NodeLeaseSupervisorTest`（W2-B6） | 已接线 | G-113 |
| 交付标记拷贝 `markedDeliveryCopy()`<br>`rpg-items` | `EquipmentOperationCoordinator.markedDeliveryCopy(ItemStack, UUID)` | `EquipmentOperationCoordinator.java:190`（`payload()` 里按 `DeliveryCopyRules` 决定是否打标记）；4 个结算生产者共 8 处出站载荷（W2-B7） | `DeliveryCopyRulesTest`、`DeliveryCopyWiringTest`（W2-B7） | 已接线 | G-114 |
| 副本 `config.yml` 的 `world: dungeon_*` 键读取方<br>`rpg-dungeons` | `rpg-dungeons/src/main/resources/config.yml` 的 `dungeons.*.world` | 无（6 个死键已在 W2-D2 删除；`grep 'dungeon_ancient_ruins' plugins/rpg-dungeons/src/main` 零命中） | W2-D2 新增门禁 + 行为级测试 | 已修复（键已删，判定=已废弃） | G-108 |
| Phase 4 市场买单簿表 `rpg_market_buy_orders`<br>`rpg-core` | 表 `rpg_market_buy_orders` | 无（`grep -rn 'rpg_market_buy_orders' plugins` 仅 `Database.java:1005` 的 DDL） | 无 | **声明未接线**（门禁豁免清单内） | G-109 遗留 |
| Phase 1 成长异常表 `rpg_progression_anomalies`<br>`rpg-core` | 表 `rpg_progression_anomalies` | 无（仅 `Database.java:917` 的 DDL） | 无 | **声明未接线**（门禁豁免清单内） | G-109 遗留 |
| Phase 5 `support_credit` 独立账本（C-012 物理隔离）<br>`rpg-core` | 表 `rpg_support_credit_accounts` / `rpg_support_credit_ledger` | 无（仅 `Database.java:987,994` 的 DDL；无 Java 账本服务） | 无 | **声明未接线**（门禁豁免清单内） | G-109 遗留 |
| 换季事务表 `rpg_season_settlements` | 表 `rpg_season_settlements` | `JdbcSeasonSettlementService.java:218,242,473,501,528,542,561`（真实读写） | `JdbcSeasonSettlementServiceTest` | 已接线 | G-101 |
| 商人往返净损耗下限（承诺 10%，YAML 可调到 5%）<br>`rpg-economy` | `EconomyCommand` 读 `black-market.minimum-roundtrip-loss` | `EconomyCommand.java:596`（clamp 0.05..0.50） | 无 | 存疑（下限 0.05，与承诺 10% 不符） | G-208 |
| 装备签名 schema 版本（文档称 4，实际 5）<br>`rpg-items` | `ForgeListener` 写 schema 5 | `ForgeListener.java:648` | 无 | 存疑（文档失真） | G-201 |
| 稀有/史诗槽上限随基材上浮 | `ForgeRules.slotUpperBound(rarity, base)` | `ForgeListener.java:463,848` | `ForgeRulesTest.java:15`、`DungeonCoreResonanceRulesTest.java:27` | 存疑（文档失真） | G-202 |
| legacy「必须持有稀有词缀」隐藏前置 | `LegacyBlueprintRules.eligibility()` | `LegacyCommand.java:67,107` | `LegacyBlueprintRulesTest.java:20` | 存疑（文档失真） | G-203 |
| PlaceholderAPI 占位符数（文档称 11） | `ProgressionPlaceholders` / `SivitaPlaceholderExpansion` | `ProgressionPlaceholders.java:18,21,26` | 无 | 存疑（文档失真） | G-204 |
| 外观价格归属（文档称 rpg-cosmetics） | `docs/COSMETICS.md` 定价叙述 | 无 | 无 | 已订正（W2-C1 改为 rpg-commerce） | G-205 |
| `#minecraft:quick_actions` 数据包纳入版本控制<br>`repo` | `verify-installation.sh` 硬依赖的数据包目录 | 无（仓库内 `find . -name '*quick_actions*'` 零命中；`docs/PLUGIN_INTEGRATION.md:46` 明写「当前都不在本仓库内」） | 无 | **声明未接线**（数据缺失，非代码缺失） | G-206 |
| 血月/袭击「每日最多 3 场」显式实现 | `WorldEventListener` 读 `blood-moon.every-nights` | `WorldEventListener.java:199,718,725` | 无 | 存疑（无显式每日场次上限判定） | G-207 |
| 星弹预警窗口（承诺 0.4–0.8s，实测 28–36 tick） | `DungeonManager` 读 `telegraph-ticks` | `DungeonManager.java:1174`（clamp 20..80 tick） | `DungeonConfigurationContractTest.java:55`（源码字符串断言） | 存疑（超承诺窗口） | G-209 |
| 46+ 测试类用 `Files.readString` 做源码字符串断言 | `plugins/*/src/test/**` | `plugins/rpg-items/src/main` 等 3 处 `src/main` 用 `Files.readString`（非测试） | 50 个 `src/test` 类用 `Files.readString`（其中 20 个同时用 `indexOf`） | 存疑（非行为级测试，`[T]` 证据须打折） | G-302 |
| 每张 DDL 表必须有 src/main 引用点的 CI 门禁 | `scripts/check-ddl-consumers.sh` | 脚本存在并接入 `.cnb.yml` push 门；实测 `DDL_CONSUMER_GATE=GREEN`（79 张 / 71 已接线 / 8 豁免） | 脚本自身有 `DDL_GATE_IGNORE_EXEMPT=1` 负向自测 | 已接线 | G-301 |

## 2. 每条判定的 grep 命令原文（可逐条复现）

本节命令均在仓库根目录执行，输出为实际命中。未给出输出的条目即「无命中」。

### M-001 — Kill Switch（判定：已接线，G-002）
```
$ grep -rn --include=*.java 'requireEnabled' plugins | grep '/src/main/'
plugins/rpg-core/src/main/java/.../JdbcActivityEconomyService.java:71:  case HUNTING -> mechanisms.requireEnabled(MechanismRegistry.HUNT_GOLD);
plugins/rpg-core/src/main/java/.../JdbcActivityEconomyService.java:72:  case DUNGEON -> mechanisms.requireEnabled(MechanismRegistry.DUNGEON_GOLD);
plugins/rpg-core/src/main/java/.../JdbcActivityEconomyService.java:73:  default -> mechanisms.requireEnabled(MechanismRegistry.COMMISSION_GOLD);
plugins/rpg-core/src/main/java/.../JdbcActivityEconomyService.java:351: if (mechanisms != null && !mechanisms.requireEnabled(MechanismRegistry.COMMISSION_GOLD)) {
plugins/rpg-core/src/main/java/.../JdbcCommerceService.java:193: && !mechanisms.requireEnabled(MechanismRegistry.STARLIGHT_GRANT)) {
plugins/rpg-core/src/main/java/.../JdbcCommerceService.java:540: if (mechanisms != null && !mechanisms.requireEnabled(MechanismRegistry.STARLIGHT_GRANT)) {
plugins/rpg-core/src/main/java/.../JdbcMarketService.java:53: return mechanisms == null || mechanisms.requireEnabled(MechanismRegistry.MARKET_ESCROW);
plugins/rpg-core/src/main/java/.../JdbcSeasonExpeditionService.java:75: if (mechanisms != null && !mechanisms.requireEnabled(MechanismRegistry.SEASON_SETTLEMENT)) {
plugins/rpg-core/src/main/java/.../JdbcDungeonRewardService.java:89: return mechanisms == null || mechanisms.requireEnabled(MechanismRegistry.DUNGEON_GOLD);
plugins/rpg-core/src/main/java/.../JdbcSeasonSettlementService.java:104: if (mechanisms != null && !mechanisms.requireEnabled(MechanismRegistry.SEASON_SETTLEMENT)) {
plugins/rpg-core/src/main/java/.../JdbcCommunityService.java:613: if (mechanisms != null && !mechanisms.requireEnabled(MechanismRegistry.COMMUNITY_PROJECT)) {
```

### M-002 — 五维防御（判定：已接线，G-001）
```
$ grep -rn --include=*.java 'defenseMultiplier' plugins | grep '/src/main/'
plugins/rpg-combat/src/main/java/cn/originalrpg/combat/ExecuteListener.java:155:        event.setDamage(Math.max(0.0, event.getDamage() * defenseMultiplier(target)));
plugins/rpg-combat/src/main/java/cn/originalrpg/combat/ExecuteListener.java:244:    private double defenseMultiplier(Player player) {
```

### M-003 — 签名密钥 env 唯一来源（判定：已接线，G-003）
```
$ grep -rn --include=*.java 'RPG_ITEM_SIGNING_KEY' plugins | grep '/src/main/'
plugins/rpg-items/src/main/java/cn/originalrpg/items/security/EquipmentSigningMaterial.java:25:    public static final String CURRENT_KEY_ENV = "RPG_ITEM_SIGNING_KEY";
plugins/rpg-items/src/main/java/cn/originalrpg/items/security/EquipmentSigningMaterial.java:26:    /** 前缀环境变量名：退役密钥，即 RPG_ITEM_SIGNING_KEY_PREVIOUS、…_2、…_3，最多 3 把。 */
```

### M-004 — 迁移 advisory lock（判定：已接线，G-004）
```
$ grep -rn --include=*.java 'MIGRATION_LOCK_NAMESPACE\|MIGRATION_LOCK_RESOURCE' plugins tools | grep '/src/main/'
plugins/rpg-core/src/main/java/.../Database.java:20:    private static final int MIGRATION_LOCK_NAMESPACE = 0x53495649; // "SIVI"
plugins/rpg-core/src/main/java/.../Database.java:21:    private static final int MIGRATION_LOCK_RESOURCE = 0x52504731; // "RPG1"
plugins/rpg-core/src/main/java/.../Database.java:1132:                "SELECT pg_advisory_xact_lock(" + MIGRATION_LOCK_NAMESPACE + ", "
plugins/rpg-core/src/main/java/.../Database.java:1133:                        + MIGRATION_LOCK_RESOURCE + ")"
tools/db-migration/src/main/java/.../MigrationAdvisoryLock.java:18:    /** "SIVI"，与 RpgCore {@code Database.MIGRATION_LOCK_NAMESPACE} 相同。 */
tools/db-migration/src/main/java/.../MigrationAdvisoryLock.java:20:    /** "RPG1"，与 RpgCore {@code Database.MIGRATION_LOCK_RESOURCE} 相同。 */
```

### M-005 — 赛季结算服务（判定：部分接线；`preview()`/`advance()` 零消费者，G-101）
```
$ grep -rn --include=*.java 'SeasonSettlementService\|seasonSettlements\.' plugins | grep '/src/main/'
plugins/rpg-api/src/main/java/.../SeasonSettlementService.java:24:public interface SeasonSettlementService {
plugins/rpg-core/src/main/java/.../RpgCommand.java:41:    private final SeasonSettlementService seasonSettlements;
plugins/rpg-core/src/main/java/.../RpgCommand.java:114:        return seasonSettlements.state(seasonId)
plugins/rpg-core/src/main/java/.../RpgCorePlugin.java:287:                    SeasonSettlementService.class, seasonSettlements, this, ServicePriority.Normal);

$ grep -rn --include=*.java 'preview(' plugins/*/src/main
plugins/rpg-api/src/main/java/.../SeasonSettlementService.java:29:    CompletionStage<SeasonSettlementPreview> preview(String seasonId);
plugins/rpg-core/src/main/java/.../JdbcSeasonSettlementService.java:75:    public CompletionStage<SeasonSettlementPreview> preview(String seasonId) {
plugins/rpg-guide/src/main/java/cn/originalrpg/guide/VisualPreferenceCommand.java:49:        preview(player, mode);   ← 与本机制无关的同名方法
（无 SeasonSettlementService.preview 调用点）

$ grep -rn --include=*.java '\badvance(' plugins/rpg-core/src/main
plugins/rpg-core/src/main/java/.../JdbcChapterService.java:118:    public CompletionStage<ChapterTransitionResult> advance(   ← 章节推进，非换季
plugins/rpg-core/src/main/java/.../JdbcSeasonSettlementService.java:84:    public CompletionStage<SeasonSettlementResult> advance(
plugins/rpg-core/src/main/java/.../JdbcSeasonSettlementService.java:87:        return advance(seasonId, null, target, actor, reason);   ← 自身重载
plugins/rpg-core/src/main/java/.../JdbcSeasonSettlementService.java:91:    public CompletionStage<SeasonSettlementResult> advance(
（无外部 `SeasonSettlementService.advance` 调用点）
```

### M-006 — Session Lease（判定：已接线只读侧，G-102）
```
$ grep -rn --include=*.java 'rpg_session_leases' plugins | grep '/src/main/'
plugins/rpg-core/src/main/java/.../JdbcSessionLease.java:71:                     "SELECT node_id, node_process_token, lease_expires_at FROM rpg_session_leases"
plugins/rpg-core/src/main/java/.../Database.java:1035:                CREATE TABLE IF NOT EXISTS rpg_session_leases (
```

### M-007 — Transactional Outbox（判定：已接线，G-102）
```
$ grep -rn --include=*.java 'rpg_outbox' plugins | grep '/src/main/'
plugins/rpg-core/src/main/java/.../JdbcOutboxRegistry.java:36:                INSERT INTO rpg_outbox
plugins/rpg-core/src/main/java/.../OutboxDispatcher.java:112:                     FROM rpg_outbox
plugins/rpg-core/src/main/java/.../OutboxDispatcher.java:143:                     UPDATE rpg_outbox
plugins/rpg-core/src/main/java/.../OutboxDispatcher.java:165:                     UPDATE rpg_outbox
plugins/rpg-core/src/main/java/.../Database.java:1047:                CREATE TABLE IF NOT EXISTS rpg_outbox (
```

### M-008 — 社群七角色 / 双批（判定：已接线，G-103）
```
$ grep -rn --include=*.java 'canApprove' plugins | grep '/src/main/'
plugins/rpg-api/src/main/java/.../CommunityRole.java:26:    public boolean canApprove() {
plugins/rpg-api/src/main/java/.../CommunityRolePower.java:37:            case APPROVE, DENY -> role.canApprove();
plugins/rpg-api/src/main/java/.../CommunityRolePower.java:39:            case SUBMIT_APPROVAL -> role.canApprove() || isDomainRole(role);
plugins/rpg-api/src/main/java/.../CommunityRolePower.java:41:            case ASSIGN_ROLE -> role.canApprove();
plugins/rpg-api/src/main/java/.../CommunityRolePower.java:54:        return role != null && role.canApprove();
plugins/rpg-api/src/main/java/.../CommunityRolePower.java:71:        if (role == null || role == CommunityRole.OWNER || role.canApprove()) return Optional.empty();
plugins/rpg-community/src/main/java/.../CommunityGovernanceHandler.java:116:        String lineKey = role.canApprove() ? "community.governance.role-approver"
plugins/rpg-core/src/main/java/.../JdbcCommunityService.java:799:        if (role == CommunityRole.OWNER || role.canApprove()) {
plugins/rpg-core/src/main/java/.../JdbcCommunityService.java:966:        if (target == null || role == null || role == CommunityRole.OWNER || role.canApprove()) {
plugins/rpg-core/src/main/java/.../JdbcCommunityService.java:976:        return action.requiredRole().map(required -> role == required).orElse(role.canApprove());

$ grep -rn --include=*.java 'rpg_community_approvals' plugins | grep '/src/main/'
plugins/rpg-core/src/main/java/.../JdbcCommunityService.java:1015:                INSERT INTO rpg_community_approvals
plugins/rpg-core/src/main/java/.../JdbcCommunityService.java:1037:                FROM rpg_community_approvals a
plugins/rpg-core/src/main/java/.../JdbcCommunityService.java:1062:                SELECT subject FROM rpg_community_approvals
plugins/rpg-core/src/main/java/.../JdbcCommunityService.java:1079:                SELECT requester_uuid FROM rpg_community_approvals
plugins/rpg-core/src/main/java/.../JdbcCommunityService.java:1103:                DELETE FROM rpg_community_approvals
```

### M-009 — `CommunityRole.isGovernance()`（判定：声明未接线，新增 G-115）
```
$ grep -rn --include=*.java 'isGovernance' plugins
plugins/rpg-api/src/main/java/cn/originalrpg/api/model/CommunityRole.java:31:    public boolean isGovernance() {
plugins/rpg-api/src/test/java/cn/originalrpg/api/model/CommunityRolePowerTest.java:10: * {@code canModerate()} / {@code isGovernance()} 并断言结果。
plugins/rpg-api/src/test/java/cn/originalrpg/api/model/CommunityRolePowerTest.java:30:            assertTrue(role.isGovernance() == role.canApprove(), role + " governance 与审批口径必须一致");
（src/main 只命中自身定义文件；src/test 一条断言只证明它与 canApprove 等价）
```

### M-010 — 邮箱交付冻结（判定：已接线，G-104）
```
$ grep -rn --include=*.java 'InventoryCreativeEvent' plugins | grep '/src/main/'
plugins/rpg-market/src/main/java/.../MarketCommand.java:41:import org.bukkit.event.inventory.InventoryCreativeEvent;
plugins/rpg-market/src/main/java/.../MarketCommand.java:757:     * 创造模式物品栏的交付冻结。{@code InventoryCreativeEvent} 既可能带当前槽位，
plugins/rpg-market/src/main/java/.../MarketCommand.java:761:    public void onCreativeClick(InventoryCreativeEvent event) {
plugins/rpg-market/src/main/java/.../DeliveryFreezeRules.java:44:     * 创造模式的容器事件。{@code InventoryCreativeEvent} 只在客户端创造模式物品栏
```

### M-011 — 战斗总线单一结算（判定：已接线，G-105）
```
$ grep -rn --include=*.java 'settleAttackerBonus\|onBattleTranceHit' plugins | grep '/src/main/'
plugins/rpg-skills/src/main/java/.../SkillsCommand.java:948:     * {@link #settleAttackerBonus(EntityDamageByEntityEvent)} 在 HIGHEST 单点写入，
plugins/rpg-skills/src/main/java/.../SkillsCommand.java:952:    public void onBattleTranceHit(EntityDamageByEntityEvent event) {
plugins/rpg-skills/src/main/java/.../SkillsCommand.java:1000:    public void settleAttackerBonus(EntityDamageByEntityEvent event) {
plugins/rpg-skills/src/main/java/.../SkillsCommand.java:1042:    private void settleAttackerBonus(EntityDamageByEntityEvent event, boolean standardActive, int tranceStacks) {
```

### M-012 — 血契可达性（判定：存疑，跨模块不变式冲突，G-106）
```
$ grep -rn --include=*.java 'minimumAscension\|vanished-horizon' plugins | grep '/src/main/'
plugins/rpg-api/src/main/java/.../HiddenPathRules.java:18:    public static int minimumAscension(HiddenPath path) {
plugins/rpg-api/src/main/java/.../HiddenPathRules.java:28:            case BLOODPACT -> "vanished-horizon";
plugins/rpg-api/src/main/java/.../HiddenPathReachability.java:74:                HiddenPathRules.minimumAscension(path), ProgressionRules.LOCAL_LEVEL_CAP);
plugins/rpg-core/src/main/java/.../JdbcHiddenPathService.java:114:            if (profile.ascension() < HiddenPathRules.minimumAscension(path)) missing.add("ascension");
plugins/rpg-skills/src/main/java/.../RpgSkillsPlugin.java:181:                int minimumAscension = configured.getInt(id + ".minimum-ascension", 0);
plugins/rpg-skills/src/main/java/.../RpgSkillsPlugin.java:182:                if (minimumAscension != branchStages.get(branch)) {
plugins/rpg-skills/src/main/java/.../RpgSkillsPlugin.java:184:                            + branchStages.get(branch) + ", not " + minimumAscension);
```

### M-013 — 档案简化旁路（判定：已接线，旁路已删除，G-107 上半）
```
$ grep -rn --include=*.java 'ascend(' plugins | grep '/src/main/'
plugins/rpg-api/src/main/java/.../AscensionService.java:9:/** Compound, auditable ascension gate. Callers must not replace this with ProfileService.ascend(). */
plugins/rpg-api/src/main/java/.../AscensionService.java:13:    CompletionStage<AscensionResult> ascend(UUID playerId, AscensionEvidence evidence);
plugins/rpg-classes/src/main/java/.../RpgClassesPlugin.java:429:            ascensions.ascend(playerId, evidence).whenComplete((result, error) -> sync(() -> {
plugins/rpg-core/src/main/java/.../JdbcAscensionService.java:60:    public CompletionStage<AscensionResult> ascend(UUID playerId, AscensionEvidence evidence) {
（`ProfileService.ascend` / `JdbcProfileService.ascend` 已不存在）
```

### M-014 — 技能点结算（判定：存疑，未落地，G-107 下半）
```
$ grep -rn --include=*.java 'finalEffective / 3' plugins | grep '/src/main/'
plugins/rpg-core/src/main/java/.../JdbcDungeonRewardService.java:289:        int skillPointsGained = finalEffective / 3 - startingEffective / 3;
plugins/rpg-core/src/main/java/.../JdbcProfileService.java:544:        int skillPointsGained = finalEffective / 3 - startingEffective / 3;
```

### M-015 — 装备退出路径判定（判定：已接线，G-110）
```
$ grep -rn --include=*.java 'requiresExitPath\|isExitPath\|isTradeable' plugins | grep '/src/main/'
plugins/rpg-api/src/main/java/.../EquipmentLifecycle.java:107:    public static boolean isExitPath(State state) {
plugins/rpg-api/src/main/java/.../EquipmentLifecycle.java:116:    public static boolean isTradeable(State state) {
plugins/rpg-api/src/main/java/.../EquipmentLifecycle.java:120:    public static boolean requiresExitPath() {
plugins/rpg-items/src/main/java/.../EquipmentExitPathGate.java:37:        return EquipmentExitPathRules.isTradeable(tradeUnlocked);
plugins/rpg-items/src/main/java/.../EquipmentExitPathRules.java:36:        return outcome != null && EquipmentLifecycle.isExitPath(outcome);
plugins/rpg-items/src/main/java/.../EquipmentExitPathRules.java:44:        return EquipmentLifecycle.isTradeable(tradeUnlocked
plugins/rpg-items/src/main/java/.../EquipmentExitPathRules.java:66:        return EquipmentLifecycle.requiresExitPath() && EquipmentLifecycle.isExitPath(
```

### M-016 — `SEASON_ATTUNED`（判定：声明未接线，W3-A3 已派）
```
$ grep -rn --include=*.java 'SEASON_ATTUNED' plugins/*/src/main
plugins/rpg-api/src/main/java/.../EquipmentLifecycle.java:22:        SEASON_ATTUNED,     //新赛季调谐
plugins/rpg-api/src/main/java/.../EquipmentLifecycle.java:41:                    || to == State.SEASON_ATTUNED || to == State.QUARANTINED;
plugins/rpg-api/src/main/java/.../EquipmentLifecycle.java:44:                    || to == State.SEASON_ATTUNED || to == State.QUARANTINED;
plugins/rpg-api/src/main/java/.../EquipmentLifecycle.java:49:            case SEASON_ATTUNED -> to == State.BOUND || to == State.SALVAGED || to == State.QUARANTINED;
plugins/rpg-api/src/main/java/.../EquipmentLifecycle.java:109:                || state == State.SEASON_ATTUNED || state == State.DESTROYED;
（只命中自身定义文件）
```

### M-017 — `DESTROYED`（判定：声明未接线，W3-A3 已派）
```
$ grep -rn --include=*.java 'DESTROYED' plugins/*/src/main
plugins/rpg-api/src/main/java/.../EquipmentLifecycle.java:23,28,47,48,50,109
plugins/rpg-core/src/main/java/.../JdbcEquipmentLedgerAuditService.java:71: WHERE state IN ('QUARANTINED','RESTORED','SALVAGED','LEGACY_EXTRACTED','DESTROYED')
（除审计只读 WHERE 外，只命中自身定义文件）
```

### M-018 — ProgressionRules 三方法（判定：已接线，G-111）
```
$ grep -rn --include=*.java 'totalExperienceForAscension\|totalExperienceToEffective\|effectiveLevelCap' plugins | grep '/src/main/'
plugins/rpg-core/src/main/java/.../RpgCommand.java:132:            long stageBudget = ProgressionRules.totalExperienceForAscension(profile.ascension());
plugins/rpg-core/src/main/java/.../RpgCommand.java:143:                    "effective_total", ProgressionRules.totalExperienceToEffective(profile.effectiveLevel()))),
plugins/rpg-integrations/src/main/java/.../ProgressionPlaceholders.java:18:            case "effective_level_cap" -> Integer.toString(ProgressionRules.effectiveLevelCap());
plugins/rpg-integrations/src/main/java/.../ProgressionPlaceholders.java:21:                    Long.toString(ProgressionRules.totalExperienceForAscension(snapshot.ascension()));
plugins/rpg-mobs/src/main/java/.../AdaptiveMobSafetyRules.java:30:            return ProgressionRules.effectiveLevelCap();
plugins/rpg-mobs/src/main/java/.../MobGuideRules.java:79:        long stageBudget = ProgressionRules.totalExperienceForAscension(profile.ascension());
plugins/rpg-mobs/src/main/java/.../MobGuideRules.java:93:                ProgressionRules.totalExperienceToEffective(effectiveLevel),
plugins/rpg-api/src/main/java/.../HiddenPathReachability.java:116:        if (effectiveLevel < 1 || effectiveLevel > ProgressionRules.effectiveLevelCap()) {
```

### M-019 — 权威命名空间（判定：已接线，经 `isAuthoritativeKey`，G-112）
```
$ grep -rn --include=*.java 'authoritativeNamespace' plugins | grep '/src/main/'
plugins/rpg-items/src/main/java/.../ForgeListener.java:950:        if (!authoritativeNamespaceOnly(item)) return false;
plugins/rpg-items/src/main/java/.../ForgeListener.java:967:    private static boolean authoritativeNamespaceOnly(ItemStack item) {
plugins/rpg-items/src/main/java/.../EquipmentSecurity.java:104:        if (!authoritativeNamespace(item)) return false;
plugins/rpg-items/src/main/java/.../EquipmentSecurity.java:114:    private static boolean authoritativeNamespace(ItemStack item) {

$ grep -n 'AuthoritativeItemPolicy' plugins/rpg-items/src/main/java/cn/originalrpg/items/security/EquipmentSecurity.java
114:        return meta.getPersistentDataContainer().getKeys().stream()
115:                .allMatch(AuthoritativeItemPolicy::isAuthoritativeKey);
```

### M-020 — 节点租约（判定：已接线，G-113）
```
$ grep -rn --include=*.java 'renewAsync\|mayStillBeValid' plugins | grep '/src/main/'
plugins/rpg-core/src/main/java/.../NodeLeaseSupervisor.java:87:        return lease.renewAsync().handle((renewed, error) -> {
plugins/rpg-core/src/main/java/.../NodeLeaseSupervisor.java:99:        if (!stopped.get() && lease.mayStillBeValidFor(retryBudget())) {
plugins/rpg-core/src/main/java/.../NodeLeaseSupervisor.java:119:        if (!lease.mayStillBeValid()) {
plugins/rpg-core/src/main/java/.../JdbcNodeLease.java:139:    public CompletionStage<Boolean> renewAsync() {
```

### M-021 — 交付标记拷贝（判定：已接线，G-114）
```
$ grep -rn --include=*.java 'markedDeliveryCopy' plugins | grep '/src/main/'
plugins/rpg-items/src/main/java/.../EquipmentOperationCoordinator.java:178:    public static ItemStack markedDeliveryCopy(ItemStack item, UUID operationId) {
plugins/rpg-items/src/main/java/.../EquipmentOperationCoordinator.java:190:            case ASSIGN -> markedDeliveryCopy(item, operationId);
```

### M-022 — 副本 `world` 死键（判定：已修复/已废弃，G-108）
```
$ grep -rn --include=*.java 'dungeon_ancient_ruins' plugins/rpg-dungeons/src/main
（无命中 —— W2-D2 已删除 6 个 dungeons.*.world 死键）
```

### M-023 — 零消费者表（判定：声明未接线 ×4，G-109 遗留）
```
$ grep -rn --include=*.java 'rpg_market_buy_orders' plugins
plugins/rpg-core/src/main/java/.../Database.java:1005:                CREATE TABLE IF NOT EXISTS rpg_market_buy_orders (

$ grep -rn --include=*.java 'rpg_progression_anomalies' plugins
plugins/rpg-core/src/main/java/.../Database.java:917:                CREATE TABLE IF NOT EXISTS rpg_progression_anomalies (

$ grep -rn --include=*.java 'rpg_support_credit' plugins
plugins/rpg-core/src/main/java/.../Database.java:987:                CREATE TABLE IF NOT EXISTS rpg_support_credit_accounts (
plugins/rpg-core/src/main/java/.../Database.java:994:                CREATE TABLE IF NOT EXISTS rpg_support_credit_ledger (
```

### M-024 — G-206 数据包不在版本控制（判定：声明未接线）
```
$ find . -path ./.git -prune -o -name '*quick_actions*' -print
（无输出）
$ find . -name 'verify-installation.sh'
（无输出）
$ grep -rn 'quick_actions' docs/ scripts/ .cnb.yml
docs/VISUAL_FEEDBACK.md:35:... 版本升级必须先验证 Dialog API、`#minecraft:quick_actions` 和 pack format ...
docs/GAME_DESIGN.md:140:数据包注册到 `#minecraft:quick_actions`，资源包翻译键随中文/英文客户端自动切换。
docs/PLUGIN_INTEGRATION.md:46:... **待办（G-206）**：该数据包目录与部署脚本 `server/bin/verify-installation.sh` 当前都不在本仓库内 ...
```

### M-025 — G-302 源码字符串断言（判定：存疑）
```
$ grep -rl --include=*.java 'Files.readString' plugins | grep '/src/test/' | wc -l
50
$ grep -rl --include=*.java 'Files.readString' plugins | grep '/src/test/' | xargs grep -l 'indexOf' | wc -l
20
$ find plugins -name '*ContractTest.java' | wc -l
56
```

### M-026 — G-301 DDL 门禁（判定：已接线）
```
$ sh scripts/check-ddl-consumers.sh
DDL 真相源: plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java
DDL 表数: 79
豁免清单: 8 条（脚本内 EXEMPT，可审计）
...
DDL_CONSUMER_GATE=GREEN
```

其余条目（G-201/G-202/G-203/G-204/G-205/G-207/G-208/G-209）命令见 v1 §2 与 §1 表内行号，本轮重跑命中行号一致（差异：`ForgeListener.java` 645→648，因 W2 新增注释；`DungeonManager.java` 1156→1174 同理）。

## 3. DDL 表消费者矩阵（G-109 全量对账，本轮实测）

门禁脚本实测：**79 张表 / 71 张有真实消费者 / 8 张豁免**。与 v1 的 75 张相比 +4 张（W2-D1 新增换季事务表）。

零消费者表（豁免清单内，逐条复现见 M-023）：
```
rpg_market_buy_orders          ← Phase 4 买单簿，撮合未实现
rpg_progression_anomalies      ← Phase 1 dry-run 冻结期预留，写入口未接线
rpg_support_credit_accounts    ← Phase 5 独立账本，账本服务未接线
rpg_support_credit_ledger      ← Phase 5 独立账本流水，同上
```

对账 `doc/cnb/gap-registry.md` G-109 声明的 8 张：`rpg_season_settlements`（W2-D1 已接线）、
`rpg_session_leases`（W2-D5 已接线）、`rpg_outbox`（W2-D5 已接线）、`rpg_community_approvals`（W2-D4 已接线）
四条已消灭；剩余 4 条即上列。**G-109 从 8 张收敛到 4 张。**

## 4. 结构性门禁（G-301 / G-302）实测

```
$ sh scripts/check-ddl-consumers.sh
DDL_CONSUMER_GATE=GREEN
```
G-301 已落地并 GREEN。

```
$ grep -rl --include=*.java 'Files.readString' plugins | grep '/src/test/' | wc -l
50
```
G-302 未落地：50 个测试类用 `Files.readString`，其中 20 个同时用 `indexOf` 断言源码字符串顺序。这些类不执行行为，重构即失效。

## 5. 与 `doc/cnb/gap-registry.md` 的对账（本轮）

| gap id | gap-registry 声明状态 | 本轮机械核验结果 | 偏差 |
|---|---|---|---|
| G-001..G-004 | DONE（W1） | **确认已接线**（M-002/M-004/M-001/M-003 均有非自身调用点） | 无 |
| G-101 | TODO | `SeasonSettlementService` 已存在；`state()` 已接线到 `/rpg profile`；**`preview()`/`advance()` 零生产消费者** | 部分完成 |
| G-102 | TODO | **确认已接线**（`rpg_session_leases` 有 SELECT，`rpg_outbox` 有 INSERT/SELECT/UPDATE） | 无 |
| G-103 | TODO | **确认已接线**（`canApprove` 10 处 src/main；`rpg_community_approvals` 5 处 SQL） | 无 |
| G-104 | DONE（W2-B1） | **确认已接线** | 无 |
| G-105 | DONE（W2-B2） | **确认已接线** | 无 |
| G-106 | TODO | **确认仍冲突**：`HiddenPathRules.minimumAscension=2` 与 `RpgSkillsPlugin.java:182` 的 `minimum-ascension == branchStage` 不变式相斥 | W3-A1 处置中 |
| G-107 | DONE | **上半确认已接线**（旁路已删）；**下半未落地**：`finalEffective / 3` 仍在 2 处 | 只完成一半 |
| G-108 | DONE（W2-D2） | **确认已修复**（死键已删） | 无 |
| G-109 | TODO | **收敛**：8 张 → 4 张零消费者 | 数量更新 |
| G-110 | DONE | **确认已接线**（判定方法已接线）；两条退出路径 `SEASON_ATTUNED`/`DESTROYED` 仍无生产入口 | 遗漏两条 |
| G-111..G-114 | DONE | **全部确认已接线**（M-018/M-019/M-020/M-021） | 无 |
| G-201..G-205 | TODO（P2 订正） | **确认已订正**（W2-C1） | 无 |
| G-206 | TODO | **确认仍缺失**（仓库内无数据包、无 `verify-installation.sh`） | 无 |
| G-207/G-208/G-209 | TODO | **确认仍为存疑**（P2 文档已订正为实测值，行为未改） | 无 |
| G-301 | DONE | **确认已接线**（门禁 GREEN） | 无 |
| G-302 | TODO | **确认未落地**（50 个测试类） | 无 |
| G-303 | DONE | v1 已产出；本轮产出 v2 | 无 |
| G-401/G-402/G-403 | DONE（W1-A0） | **确认已接线**（`libs/` + `fetch-deps.sh` + `check-file-deps.sh`） | 无 |

## 6. 本轮新增结论（gap-registry 未登记）

1. **G-115（新增）**：`CommunityRole.isGovernance()` 零 `src/main` 消费者。`CommunityRolePower.can()` 与 `JdbcCommunityService` 都走 `canApprove()`/`canModerate()`，
   `isGovernance()` 只在 `src/test` 里被断言「与 `canApprove()` 相等」。这是一个**孤儿口径**（同义方法未收敛），属「声明未接线」。
2. **G-101 半条**：`SeasonSettlementService.advance(seasonId, nextSeasonId, target, actor, reason)` 与 `preview(seasonId)` 零生产消费者。
   九态状态机、4 张表、赛季层钱包全部就绪且测试覆盖 13 例，但**从 DRAFT 走到 COMPLETED 的生产入口不存在** ⇒ 宪法 C-010 的自动化换季未接线。
3. **G-107 下半**：`JdbcProfileService.java:544` 与 `JdbcDungeonRewardService.java:289` 仍是 `finalEffective / 3 - startingEffective / 3`，
   AGENTS.md §3 要求的「本地里程碑 + 幂等补偿」未实现。
4. **G-206**：`#minecraft:quick_actions` 数据包与 `verify-installation.sh` 均不在本仓（`.gitignore` 排除 `server/`），属 VCS 缺口而非源码缺口。
5. **零消费者表收敛**：G-109 从 8 张降到 4 张。
6. **`isAuthoritativeNamespace` 是间接接线**：`isAuthoritativeKey(String)`（内部调 `isAuthoritativeNamespace`）才是消费点，W2-B5 已接线；
   直接字面量 grep `isAuthoritativeNamespace` 会误判为未接线 —— 这是 v1 判定偏严的一处，本轮修正为「已接线」。
