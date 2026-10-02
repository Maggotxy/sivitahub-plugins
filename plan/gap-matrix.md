# 全量机制矩阵（gap-matrix）

> 生成者：CNB CodeBuddy NPC，任务 `W1-P1-gapmatrix`。只读审计，未改 `src/main` 任何源码。
> 判定口径来自 `doc/cnb/GOAL.md` §2 与 `doc/cnb/decisions-locked.md` L-008：
> 机制算「已接线」当且仅当对它的类/方法/表名在 `plugins/*/src/main` 全量 grep 存在**非自身定义文件**的调用点；只命中自身定义文件 = 「声明未接线」。
> 找不到调用点写「无」。全文不出现「推测」「大概」「应该」这三个词。

## 0. 覆盖范围与取证方式

| 项 | 数量 | 取证命令 |
|---|---|---|
| `plugins/` 下 `rpg-*` 模块 | 25 | `ls plugins/ \| wc -l` |
| `src/main` Java 文件（全量，非抽样） | 473 | `find plugins -path '*/src/main/*' -name '*.java' \| wc -l` |
| `src/test` Java 文件 | 210 | `find plugins -path '*/src/test/*' -name '*.java' \| wc -l` |
| `plugins/` Java 文件合计（对账任务书 683） | 683 | `find plugins -name '*.java' \| wc -l` |
| DDL 表（去重） | 75 | `grep -rh --include=*.java 'CREATE TABLE' . \| sed 's/.*CREATE TABLE[^a-z]*//;s/[ (].*//' \| tr -d '"\`' \| sort -u` |
| 机制矩阵条目 | 39 | 每条自带 grep 原文，见 §2 |
| `@EventHandler` 行为入口 | 283 | `grep -rn --include=*.java '@EventHandler' plugins \| wc -l` |

**关于 config.yml 配置键的取证边界（如实说明）**：25 个模块共 1943 个 YAML 叶子键。
本项目大量配置用 `getConfigurationSection(prefix)` + `getKeys(false)` 动态遍历，例如：

```
$ grep -rn --include=*.java 'getConfigurationSection' plugins | head
plugins/rpg-skills/.../RpgSkillsPlugin.java:65:  getConfig().getConfigurationSection("skills")
plugins/rpg-items/.../RpgItemsPlugin.java:79:  getConfig().getConfigurationSection("rare-traits.weights")
plugins/rpg-hub/.../HubNpcManager.java:543:  getConfig().getConfigurationSection("npcs." + role.id())
plugins/rpg-mobs/.../MobAbilityController.java:714: "normal-behaviors." + behavior.id() + ".cooldown-seconds"
```

因此配置键**无法用「字面量 grep」机械判定未接线**（动态拼接的键会全部误判为未读）。本轮**不对配置键做未接线判定**，
也不输出任何未读键清单。已机械核实、写入下表的配置项只有两条：`dungeons.*.world`（G-108）与 `black-market.minimum-roundtrip-loss`（G-208）。

## 1. 机制级矩阵

| 机制/模块 | 声明的载体（类/表/枚举/配置键）| src/main 里的真实调用点（文件:行号，没有就写「无」）| 是否有行为级测试（测试类:方法）| 判定 | 对应 gap id |
|---|---|---|---|---|---|
| Kill Switch 开关判定/切换（C-005 第4腿）<br>`rpg-core` | `MechanismRegistry.isEnabled(String)` / `setEnabled(String,boolean,String)`；表 `rpg_mechanism_switches` | 无 | 无 | 声明未接线 | G-002 |
| 赛季结算状态机（C-010）<br>`rpg-api` | `SeasonSettlementState`（九态枚举 + `canTransitionTo`）；表 `rpg_season_settlements` | 无（仅命中自身定义文件 `SeasonSettlementState.java`） | `SeasonExpeditionQualificationPolicyTest.java:31` | 声明未接线 | G-101 |
| 多服前置 Session Lease（C-014）<br>`rpg-core` | 表 `rpg_session_leases` | `rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:982` | 无 | 声明未接线 | G-102 |
| 多服前置 Transactional Outbox（C-014）<br>`rpg-core` | 表 `rpg_outbox` | `rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:990` | 无 | 声明未接线 | G-102 |
| 社群七角色 / 双批审批（Phase 6）<br>`rpg-api` | `CommunityRole.canApprove()` / `canModerate()` / `isGovernance()`；表 `rpg_community_approvals` | 无（仅命中自身定义文件 `CommunityRole.java`） | 无 | 声明未接线 | G-103 |
| 社群治理/管理角色判定<br>`rpg-api` | `CommunityRole.isGovernance()` / `canModerate()` | 无（仅命中自身定义文件 `CommunityRole.java`） | 无 | 声明未接线 | G-103 |
| 邮箱交付冻结：创造模式容器事件（漏 InventoryCreativeEvent）<br>`rpg-market` | `MarketCommand` 监听器集合 + 游标槽扫描范围 | 无 | 无 | 声明未接线 | G-104 |
| 五维防御 / 装备 `defense` 词条在 PVE/PVP 生效<br>`rpg-combat` | `ExecuteListener.onDamage(EntityDamageByEntityEvent)` 内应调用 `defenseMultiplier()` | 无（仅命中自身定义文件 `ExecuteListener.java`） | 无 | 存疑（接线 bug） | G-001 |
| 档案简化旁路 `ascend(UUID)`（AGENTS.md §3 明令删除）<br>`rpg-core` | `ProfileService.ascend(UUID)` / `JdbcProfileService.ascend(UUID)` | `rpg-api/src/main/java/cn/originalrpg/api/service/AscensionService.java:9`<br>`rpg-api/src/main/java/cn/originalrpg/api/service/ProfileService.java:53`<br>…共3处 | `JdbcServicesTest.java:1078` | 存疑（旁路仍在） | G-107 |
| 技能点「本地里程碑 + 幂等补偿」（替代 `effectiveLevel/3`）<br>`rpg-core` | `JdbcProfileService` 技能点结算 | `rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcDungeonRewardService.java:243`<br>`rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java:569` | 无 | 存疑（未实现） | G-107 |
| 战斗总线单一结算（消除攻方增益二次放大）<br>`rpg-skills` | `SkillsCommand` 攻方增益 `setDamage` 与 `ExecuteListener` HIGHEST 结算 | 无（仅命中自身定义文件 `SkillsCommand.java`） | 无 | 存疑（二次放大） | G-105 |
| 装备 HMAC 签名密钥可轮换 / 不落明文<br>`rpg-items` | `RpgItemsPlugin.signingKey()` | 无（仅命中自身定义文件 `RpgItemsPlugin.java`） | 无 | 存疑（明文落盘） | G-003 |
| 迁移 advisory lock 唯一（在线与离线同一把）<br>`rpg-core` | `Database.MIGRATION_LOCK_NAMESPACE/RESOURCE` 与 `MigrationEngine.POSTGRES_ADVISORY_LOCK` | `rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:18`<br>`rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:1034`<br>…共3处 | `DatabaseRulesTest.java:65` | 存疑（两把锁） | G-004 |
| 副本 `config.yml` 的 `world: dungeon_*` 键读取方<br>`rpg-dungeons` | `rpg-dungeons/src/main/resources/config.yml` 的 `dungeons.*.world` | 无 | 无 | 声明未接线 | G-108 |
| 血契（BLOODPACT）按文档条件可达成<br>`rpg-api` | `HiddenPathRules.minimumAscension()=2` + `requiredSkill()=vanished-horizon`；后者 `minimum-ascension: 3` | `rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcHiddenPathService.java:114`<br>`rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcHiddenPathService.java:122`<br>…共3处 | 无 | 存疑（条件自相矛盾） | G-106 |
| 商人往返净损耗下限（承诺 10%，可被 YAML 调到 5%）<br>`rpg-economy` | `EconomyCommand` 读 `black-market.minimum-roundtrip-loss` | 无（仅命中自身定义文件 `EconomyCommand.java`） | 无 | 存疑（下限 0.05） | G-208 |
| 装备签名 schema 版本（文档称 4，实际 5）<br>`rpg-items` | `ForgeListener` 写 schema 5 / `EquipmentSecurity.schemaVersion` | `rpg-items/src/main/java/cn/originalrpg/items/forge/ForgeListener.java:645` | 无 | 存疑（文档失真） | G-201 |
| 稀有/史诗槽上限随基材上浮（与文档固定区间不符）<br>`rpg-items` | `ForgeRules.slotUpperBound(rarity, base)` | `rpg-items/src/main/java/cn/originalrpg/items/forge/ForgeListener.java:462`<br>`rpg-items/src/main/java/cn/originalrpg/items/forge/ForgeListener.java:845` | `DungeonCoreResonanceRulesTest.java:27`<br>`ForgeRulesTest.java:15` | 存疑（文档失真） | G-202 |
| legacy 多一道「必须持有稀有词缀」隐藏前置<br>`rpg-items` | `LegacyBlueprintRules.eligibility()` 的 `RARE_TRAIT_REQUIRED` | `rpg-items/src/main/java/cn/originalrpg/items/forge/LegacyCommand.java:67`<br>`rpg-items/src/main/java/cn/originalrpg/items/forge/LegacyCommand.java:99` | `LegacyBlueprintRulesTest.java:20` | 存疑（文档失真） | G-203 |
| 装备退出路径判定 `requiresExitPath()`（C-008）<br>`rpg-api` | `EquipmentLifecycle.requiresExitPath()` | 无（仅命中自身定义文件 `EquipmentLifecycle.java`） | 无 | 声明未接线 | — |
| 阶段经验预算 `totalExperienceForAscension()`<br>`rpg-api` | `ProgressionRules.totalExperienceForAscension()` | 无（仅命中自身定义文件 `ProgressionRules.java`） | `ProgressionRulesTest.java:23` | 声明未接线 | — |
| 有效等级累计经验 `totalExperienceToEffective()`<br>`rpg-api` | `ProgressionRules.totalExperienceToEffective()` | 无（仅命中自身定义文件 `ProgressionRules.java`） | 无 | 声明未接线 | — |
| 有效等级上限 `effectiveLevelCap()`<br>`rpg-api` | `ProgressionRules.effectiveLevelCap()` | 无（仅命中自身定义文件 `ProgressionRules.java`） | `ProgressionRulesTest.java:16` | 声明未接线 | — |
| 权威物品命名空间判定 `isAuthoritativeNamespace()`<br>`rpg-api` | `AuthoritativeItemPolicy.isAuthoritativeNamespace()` | 无（仅命中自身定义文件 `AuthoritativeItemPolicy.java`） | 无 | 声明未接线 | — |
| 节点租约续期 `renewAsync()`<br>`rpg-core` | `JdbcNodeLease.renewAsync()` | 无（仅命中自身定义文件 `JdbcNodeLease.java`） | `JdbcServicesTest.java:909` | 声明未接线 | — |
| 节点租约有效性 `mayStillBeValid()`<br>`rpg-core` | `JdbcNodeLease.mayStillBeValid()` | `rpg-core/src/main/java/cn/originalrpg/core/RpgCorePlugin.java:302` | `JdbcServicesTest.java:929` | 声明未接线 | — |
| 交付标记拷贝 `markedDeliveryCopy()`<br>`rpg-items` | `EquipmentOperationCoordinator.markedDeliveryCopy()` | 无（仅命中自身定义文件 `EquipmentOperationCoordinator.java`） | 无 | 声明未接线 | — |
| 装备可交易判定 `isTradeable()`<br>`rpg-api` | `EquipmentLifecycle.isTradeable()` | 无（仅命中自身定义文件 `EquipmentLifecycle.java`） | `EquipmentLifecycleTest.java:37` | 声明未接线 | — |
| 装备递归退出路径 `isExitPath()`<br>`rpg-api` | `EquipmentLifecycle.isExitPath()` | 无（仅命中自身定义文件 `EquipmentLifecycle.java`） | `EquipmentLifecycleTest.java:29` | 声明未接线 | — |
| support_credit 独立账本（C-012 物理隔离）<br>`rpg-core` | 表 `rpg_support_credit_accounts` / `rpg_support_credit_ledger` | `rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:943`<br>`rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:950` | 无 | 声明未接线 | G-109 |
| 市场买单表 `rpg_market_buy_orders`<br>`rpg-market` | 表 `rpg_market_buy_orders` | `rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:961` | 无 | 声明未接线 | G-109 |
| 成长异常表 `rpg_progression_anomalies`<br>`rpg-core` | 表 `rpg_progression_anomalies` | `rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:914` | 无 | 声明未接线 | G-109 |
| PlaceholderAPI 占位符数（文档称 11，实测 38 个入参分支：27 case + 9 equals + 2 startsWith）<br>`rpg-integrations` | `SivitaPlaceholderExpansion.onRequest()` / `skillValue()` | `rpg-integrations/src/main/java/cn/originalrpg/integrations/SivitaPlaceholderExpansion.java:43`<br>`rpg-integrations/src/main/java/cn/originalrpg/integrations/SivitaPlaceholderExpansion.java:118` | 无 | 存疑（文档失真） | G-204 |
| 血月/袭击「每日最多 3 场」的显式实现（仅 `every-nights: 7` 等计数配置）<br>`rpg-world` | `WorldEventListener` 读 `blood-moon.every-nights` / `hunt-chain.*`；`rpg-world/config.yml` 无每日事件上限键 | `rpg-world/src/main/java/cn/originalrpg/world/WorldEventListener.java:199` | 无 | 存疑（无显式的每日场次上限判定） | G-207 |
| 星弹预警窗口 telegraph-ticks 1.1–1.8s（承诺 0.4–0.8s）<br>`rpg-dungeons` | `DungeonManager` 读 `dungeons.*.boss.abilities.telegraph-ticks` | `rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonManager.java:1156` | `DungeonConfigurationContractTest.java:55` | 存疑（超承诺窗口） | G-209 |
| 每张 DDL 表必须有 src/main 引用点的 CI 门禁（G-301）<br>`repo` | `.cnb.yml` 编译门（仅有 compileJava） | 无 | 无 | 声明未接线 | G-301 |
| 7 个 `*ContractTest` 源码字符串断言（实际 46 个测试类用 `Files.readString`）<br>`repo` | `plugins/*/src/test/.../*ContractTest.java` | `rpg-admin/src/main/java/cn/originalrpg/admin/EconomyHistoryStore.java:88`<br>`rpg-community/src/main/java/cn/originalrpg/community/AccountCommand.java:221`<br>…共3处 | `AdminSnapshotLifecycleContractTest.java:14`<br>`AiAsyncLifecycleContractTest.java:14`<br>…共45个测试类 | 存疑（非行为级测试，且数量被低估） | G-302 |
| G-205 外观价格标错模块（记录归属与实际收费模块不一致）<br>`docs` | docs/COSMETICS.md:16 的定价叙述 vs 实际收费模块 | 无 | 无 | 未复核（缺可定位的原文断言） | G-205 |
| G-402/403 缺 scripts/fetch-deps.sh；2 个 Sivita 自改版 jar 无公开源<br>`repo` | 8 处 compileOnly(files(...)) 指向被 .gitignore 忽略的 server/runtime/ 与 server/vendor/ | 无 | 无 | 不在本任务范围（需本地从 lamb 取二进制，REQUIRES_LOCAL） | G-402/G-403 |

## 2. 每条判定的 grep 命令原文（可逐条复现）

### M-001 — Kill Switch 开关判定/切换（C-005 第4腿）（判定：声明未接线，G-002）
```
$ grep -rn --include=*.java 'MechanismRegistry\.' plugins
（无命中 —— 判定依据）
```

### M-002 — 赛季结算状态机（C-010）（判定：声明未接线，G-101）
```
$ grep -rn --include=*.java 'SeasonSettlement' plugins
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/SeasonSettlementState.java:8:public enum SeasonSettlementState {
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/SeasonSettlementState.java:19:public boolean canTransitionTo(SeasonSettlementState next) {
```

### M-003 — 多服前置 Session Lease（C-014）（判定：声明未接线，G-102）
```
$ grep -rn --include=*.java 'rpg_session_leases' plugins
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:982:CREATE TABLE IF NOT EXISTS rpg_session_leases (
```

### M-004 — 多服前置 Transactional Outbox（C-014）（判定：声明未接线，G-102）
```
$ grep -rn --include=*.java 'rpg_outbox' plugins
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:990:CREATE TABLE IF NOT EXISTS rpg_outbox (
```

### M-005 — 社群七角色 / 双批审批（Phase 6）（判定：声明未接线，G-103）
```
$ grep -rn --include=*.java 'canApprove' plugins
plugins/rpg-api/src/main/java/cn/originalrpg/api/model/CommunityRole.java:20:public boolean canApprove() {
```

### M-006 — 社群治理/管理角色判定（判定：声明未接线，G-103）
```
$ grep -rn --include=*.java -e 'isGovernance' -e 'canModerate' plugins
plugins/rpg-api/src/main/java/cn/originalrpg/api/model/CommunityRole.java:25:public boolean isGovernance() {
```

### M-007 — 邮箱交付冻结：创造模式容器事件（漏 InventoryCreativeEvent）（判定：声明未接线，G-104）
```
$ grep -rn --include=*.java 'InventoryCreativeEvent' plugins
（无命中 —— 判定依据）
```

### M-008 — 五维防御 / 装备 `defense` 词条在 PVE/PVP 生效（判定：存疑（接线 bug），G-001）
```
$ grep -rn --include=*.java 'defenseMultiplier' plugins
plugins/rpg-combat/src/main/java/cn/originalrpg/combat/ExecuteListener.java:126:event.setDamage(Math.max(0.0, event.getDamage() * defenseMultiplier(target)));
plugins/rpg-combat/src/main/java/cn/originalrpg/combat/ExecuteListener.java:212:private double defenseMultiplier(Player player) {
```

### M-009 — 档案简化旁路 `ascend(UUID)`（AGENTS.md §3 明令删除）（判定：存疑（旁路仍在），G-107）
```
$ grep -rn --include=*.java '\.ascend(' plugins
plugins/rpg-api/src/main/java/cn/originalrpg/api/service/AscensionService.java:9:/** Compound, auditable ascension gate. Callers must not replace this with ProfileService.ascend(). */
plugins/rpg-api/src/main/java/cn/originalrpg/api/service/ProfileService.java:53:* @deprecated Phase 1 removed simplified bypass — use AscensionService.ascend() (C-015).
plugins/rpg-classes/src/main/java/cn/originalrpg/classes/RpgClassesPlugin.java:429:ascensions.ascend(playerId, evidence).whenComplete((result, error) -> sync(() -> {
```

### M-010 — 技能点「本地里程碑 + 幂等补偿」（替代 `effectiveLevel/3`）（判定：存疑（未实现），G-107）
```
$ grep -rn --include=*.java 'finalEffective / 3' plugins
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcDungeonRewardService.java:243:int skillPointsGained = finalEffective / 3 - startingEffective / 3;
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java:569:int skillPointsGained = finalEffective / 3 - startingEffective / 3;
```

### M-011 — 战斗总线单一结算（消除攻方增益二次放大）（判定：存疑（二次放大），G-105）
```
$ grep -rn --include=*.java 'onBattleTranceHit' plugins
plugins/rpg-skills/src/main/java/cn/originalrpg/skills/SkillsCommand.java:939:public void onBattleTranceHit(EntityDamageByEntityEvent event) {
```

### M-012 — 装备 HMAC 签名密钥可轮换 / 不落明文（判定：存疑（明文落盘），G-003）
```
$ grep -rn --include=*.java 'RPG_ITEM_SIGNING_KEY' plugins
plugins/rpg-items/src/main/java/cn/originalrpg/items/RpgItemsPlugin.java:107:String encoded = System.getenv("RPG_ITEM_SIGNING_KEY");
```

### M-013 — 迁移 advisory lock 唯一（在线与离线同一把）（判定：存疑（两把锁），G-004）
```
$ grep -rn --include=*.java 'advisory' plugins tools
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:18:// Two signed 32-bit advisory-lock keys keep the lock scoped to SivitaHub's
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:1034:* node on a transaction-scoped advisory lock, so a rolling deployment
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:1064:"SELECT pg_advisory_xact_lock(" + MIGRATION_LOCK_NAMESPACE + ", "
```

### M-014 — 副本 `config.yml` 的 `world: dungeon_*` 键读取方（判定：声明未接线，G-108）
```
$ grep -rn --include=*.java 'dungeon_ancient_ruins' plugins/rpg-dungeons/src/main
（无命中 —— 判定依据）
```

### M-015 — 血契（BLOODPACT）按文档条件可达成（判定：存疑（条件自相矛盾），G-106）
```
$ grep -rn --include=*.java -E 'HiddenPathRules\.(minimumAscension|requiredSkill)' plugins
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcHiddenPathService.java:114:if (profile.ascension() < HiddenPathRules.minimumAscension(path)) missing.add("ascension");
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcHiddenPathService.java:122:if (!learned(connection, playerId, HiddenPathRules.requiredSkill(path), lock)) missing.add("skill");
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcHiddenPathService.java:124:if (!redeemedSkillBook(connection, playerId, HiddenPathRules.requiredSkillBook(path))) {
```

### M-016 — 商人往返净损耗下限（承诺 10%，可被 YAML 调到 5%）（判定：存疑（下限 0.05），G-208）
```
$ grep -rn --include=*.java 'minimum-roundtrip-loss' plugins
plugins/rpg-economy/src/main/java/cn/originalrpg/economy/EconomyCommand.java:596:"black-market.minimum-roundtrip-loss", 0.10), 0.05, 0.50));
```

### M-017 — 装备签名 schema 版本（文档称 4，实际 5）（判定：存疑（文档失真），G-201）
```
$ grep -n 'schemaVersionKey(), PersistentDataType.INTEGER' plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/ForgeListener.java
plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/ForgeListener.java:645:data.set(security.schemaVersionKey(), PersistentDataType.INTEGER, 5);
```

### M-018 — 稀有/史诗槽上限随基材上浮（与文档固定区间不符）（判定：存疑（文档失真），G-202）
```
$ grep -rn --include=*.java 'ForgeRules\.slotUpperBound' plugins
plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/ForgeListener.java:462:"maximum", ForgeRules.slotUpperBound(coreResonance
plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/ForgeListener.java:845:int upper = ForgeRules.slotUpperBound(rolledRarity, base.name());
```

### M-019 — legacy 多一道「必须持有稀有词缀」隐藏前置（判定：存疑（文档失真），G-203）
```
$ grep -rn --include=*.java 'LegacyBlueprintRules\.eligibility' plugins
plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/LegacyCommand.java:67:LegacyBlueprintRules.Eligibility eligibility = LegacyBlueprintRules.eligibility(
plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/LegacyCommand.java:99:LegacyBlueprintRules.Eligibility eligibility = LegacyBlueprintRules.eligibility(
```

### M-020 — 装备退出路径判定 `requiresExitPath()`（C-008）（判定：声明未接线，—）
```
$ grep -rn --include=*.java 'requiresExitPath' plugins
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/EquipmentLifecycle.java:120:public static boolean requiresExitPath() {
```

### M-021 — 阶段经验预算 `totalExperienceForAscension()`（判定：声明未接线，—）
```
$ grep -rn --include=*.java 'totalExperienceForAscension' plugins
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java:76:public static long totalExperienceForAscension(int ascension) {
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java:91:for (int a = 0; a < asc; a++) total += totalExperienceForAscension(a);
```

### M-022 — 有效等级累计经验 `totalExperienceToEffective()`（判定：声明未接线，—）
```
$ grep -rn --include=*.java 'totalExperienceToEffective' plugins
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java:84:public static long totalExperienceToEffective(int effectiveLevel) {
```

### M-023 — 有效等级上限 `effectiveLevelCap()`（判定：声明未接线，—）
```
$ grep -rn --include=*.java 'effectiveLevelCap' plugins
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java:56:public static int effectiveLevelCap() {
```

### M-024 — 权威物品命名空间判定 `isAuthoritativeNamespace()`（判定：声明未接线，—）
```
$ grep -rn --include=*.java 'isAuthoritativeNamespace' plugins
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/AuthoritativeItemPolicy.java:79:public static boolean isAuthoritativeNamespace(String namespace) {
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/AuthoritativeItemPolicy.java:86:return key != null && isAuthoritativeNamespace(key.getNamespace());
```

### M-025 — 节点租约续期 `renewAsync()`（判定：声明未接线，—）
```
$ grep -rn --include=*.java 'renewAsync' plugins
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcNodeLease.java:139:public CompletionStage<Boolean> renewAsync() {
```

### M-026 — 节点租约有效性 `mayStillBeValid()`（判定：声明未接线，—）
```
$ grep -rn --include=*.java 'mayStillBeValid' plugins
plugins/rpg-core/src/main/java/cn/originalrpg/core/RpgCorePlugin.java:302:if (nodeLease.mayStillBeValidFor(safeRetryWindow)) {
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcNodeLease.java:165:public boolean mayStillBeValid() {
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcNodeLease.java:170:public boolean mayStillBeValidFor(Duration retryBudget) {
```

### M-027 — 交付标记拷贝 `markedDeliveryCopy()`（判定：声明未接线，—）
```
$ grep -rn --include=*.java 'markedDeliveryCopy' plugins
plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/EquipmentOperationCoordinator.java:160:public static ItemStack markedDeliveryCopy(ItemStack item, UUID operationId) {
```

### M-028 — 装备可交易判定 `isTradeable()`（判定：声明未接线，—）
```
$ grep -rn --include=*.java 'isTradeable' plugins
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/EquipmentLifecycle.java:116:public static boolean isTradeable(State state) {
```

### M-029 — 装备递归退出路径 `isExitPath()`（判定：声明未接线，—）
```
$ grep -rn --include=*.java 'isExitPath' plugins
plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/EquipmentLifecycle.java:107:public static boolean isExitPath(State state) {
```

### M-030 — support_credit 独立账本（C-012 物理隔离）（判定：声明未接线，G-109）
```
$ grep -rn --include=*.java 'rpg_support_credit' plugins
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:943:CREATE TABLE IF NOT EXISTS rpg_support_credit_accounts (
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:950:CREATE TABLE IF NOT EXISTS rpg_support_credit_ledger (
```

### M-031 — 市场买单表 `rpg_market_buy_orders`（判定：声明未接线，G-109）
```
$ grep -rn --include=*.java 'rpg_market_buy_orders' plugins
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:961:CREATE TABLE IF NOT EXISTS rpg_market_buy_orders (
```

### M-032 — 成长异常表 `rpg_progression_anomalies`（判定：声明未接线，G-109）
```
$ grep -rn --include=*.java 'rpg_progression_anomalies' plugins
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:914:CREATE TABLE IF NOT EXISTS rpg_progression_anomalies (
```

### M-033 — PlaceholderAPI 占位符数（文档称 11，实测 38 个入参分支：27 case + 9 equals + 2 startsWith）（判定：存疑（文档失真），G-204）
```
$ grep -oE 'case "|normalized\.equals\("|normalized\.startsWith\("' plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/SivitaPlaceholderExpansion.java | sort | uniq -c
plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/SivitaPlaceholderExpansion.java:43:public @Nullable String onRequest(OfflinePlayer player, @NotNull String params)
plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/SivitaPlaceholderExpansion.java:118:default -> null;
```

### M-034 — 血月/袭击「每日最多 3 场」的显式实现（仅 `every-nights: 7` 等计数配置）（判定：存疑（无显式的每日场次上限判定），G-207）
```
$ grep -rn --include=*.java 'every-nights\|events-per\|daily' plugins/rpg-world/src/main; grep -n 'blood-moon\|hunt-chain' plugins/rpg-world/src/main/resources/config.yml
plugins/rpg-world/src/main/java/cn/originalrpg/world/WorldEventListener.java:199:int every = Math.max(1, plugin.getConfig().getInt("blood-moon.every-nights", 7));
```

### M-035 — 星弹预警窗口 telegraph-ticks 1.1–1.8s（承诺 0.4–0.8s）（判定：存疑（超承诺窗口），G-209）
```
$ grep -rn --include=*.java 'telegraph-ticks' plugins/rpg-dungeons/src/main
plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonManager.java:1156:"dungeons." + id + ".boss.abilities.telegraph-ticks", 36L), 20L, 80L);
```

### M-036 — 每张 DDL 表必须有 src/main 引用点的 CI 门禁（G-301）（判定：声明未接线，G-301）
```
$ grep -rn 'COMPILE_GATE' .cnb.yml
（无命中 —— 判定依据）
```

### M-037 — 7 个 `*ContractTest` 源码字符串断言（实际 46 个测试类用 `Files.readString`）（判定：存疑（非行为级测试，且数量被低估），G-302）
```
$ grep -rln --include=*.java 'Files.readString' plugins
plugins/rpg-admin/src/main/java/cn/originalrpg/admin/EconomyHistoryStore.java:88:String json = Files.readString(source);
plugins/rpg-community/src/main/java/cn/originalrpg/community/AccountCommand.java:221:String encoded = Files.readString(path, StandardCharsets.US_ASCII).strip();
plugins/rpg-performance/src/main/java/cn/originalrpg/performance/PerformanceStatusStore.java:86:return generation.equals(Files.readString(marker).strip());
```

### M-038 — G-205 外观价格标错模块（记录归属与实际收费模块不一致）（判定：未复核（缺可定位的原文断言），G-205）
```
$ grep -rn --include=*.java 'aura\|trail\|Cosmetic' plugins/rpg-cosmetics/src/main plugins/rpg-commerce/src/main | head; grep -n '价格\|星辉' docs/COSMETICS.md
（无命中 —— 判定依据）
```

### M-039 — G-402/403 缺 scripts/fetch-deps.sh；2 个 Sivita 自改版 jar 无公开源（判定：不在本任务范围（需本地从 lamb 取二进制，REQUIRES_LOCAL），G-402/G-403）
```
$ grep -rn 'files(' --include=build.gradle.kts plugins; git check-ignore -v server/runtime/plugins/BetterHud-bukkit-2.1.0-SNAPSHOT-447.jar
（无命中 —— 判定依据）
```

## 3. 全部 75 张 DDL 表的消费者矩阵（G-109 全量对账）

判据：表名在 `plugins/*/src/main` 中除 `CREATE TABLE` 行外是否有 SQL 引用。
```
$ grep -rn --include=*.java 'rpg_<name>' plugins
```

| 表 | 声明的载体（DDL 位置）| src/main 里的真实调用点 | 是否有行为级测试 | 判定 | gap id |
|---|---|---|---|---|---|
| `rpg_accounts` | `Database.java:169` | `JdbcActivityEconomyService.java:590`<br>`JdbcAdminIncidentCompensationService.java:334`<br>`JdbcCommerceService.java:415`<br>…共12个文件 | `JdbcAdminIncidentCompensationServiceTest.java:54`<br>`JdbcCommunityServiceTest.java:64`<br>…共6个测试类 | 已接线 | — |
| `rpg_activity_daily` | `Database.java:197` | `Database.java:875`<br>`JdbcActivityEconomyService.java:523`<br>`JdbcAdminIncidentCompensationService.java:387` | `JdbcAdminIncidentCompensationServiceTest.java:62`<br>`JdbcServicesTest.java:218` | 已接线 | — |
| `rpg_activity_ledger` | `Database.java:207` | `Database.java:879`<br>`JdbcActivityEconomyService.java:682`<br>`JdbcAdminIncidentCompensationService.java:240` | `JdbcAdminIncidentCompensationServiceTest.java:80`<br>`JdbcServicesTest.java:225` | 已接线 | — |
| `rpg_activity_purchases` | `Database.java:277` | `JdbcActivityEconomyService.java:280` | `JdbcServicesTest.java:258` | 已接线 | — |
| `rpg_admin_test_profiles` | `Database.java:397` | `JdbcAdminTestService.java:141` | `JdbcAdminTestServiceTest.java:49` | 已接线 | — |
| `rpg_admin_test_skills` | `Database.java:415` | `JdbcAdminTestService.java:150` | `JdbcAdminTestServiceTest.java:58` | 已接线 | — |
| `rpg_ascension_receipts` | `Database.java:625` | `JdbcAscensionService.java:202` | `JdbcServicesTest.java:345` | 已接线 | — |
| `rpg_chapter_history` | `Database.java:156` | `Database.java:912`<br>`JdbcChapterService.java:163` | `JdbcServicesTest.java:104` | 已接线 | — |
| `rpg_chapter_state` | `Database.java:143` | `JdbcChapterService.java:73` | `JdbcServicesTest.java:95` | 已接线 | — |
| `rpg_class_branches` | `Database.java:616` | `JdbcAscensionService.java:166`<br>`JdbcClassBranchService.java:39` | `JdbcServicesTest.java:338` | 已接线 | — |
| `rpg_commerce_accounts` | `Database.java:701` | `Database.java:709`<br>`JdbcCommerceService.java:128`<br>`JdbcCurrencyExchangeService.java:230`<br>…共4个文件 | `JdbcCommunityServiceTest.java:86`<br>`JdbcServicesTest.java:129` | 已接线 | — |
| `rpg_commerce_purchases` | `Database.java:742` | `Database.java:754`<br>`JdbcCommerceService.java:257` | `JdbcServicesTest.java:150` | 已接线 | — |
| `rpg_commerce_reward_claims` | `Database.java:757` | `JdbcCommerceService.java:521` | `JdbcServicesTest.java:168` | 已接线 | — |
| `rpg_commission_claims` | `Database.java:265` | `Database.java:882`<br>`JdbcCommissionEvidenceService.java:112` | `JdbcServicesTest.java:249` | 已接线 | — |
| `rpg_commission_evidence_daily` | `Database.java:246` | `JdbcCommissionEvidenceService.java:136` | `JdbcServicesTest.java:234` | 已接线 | — |
| `rpg_commission_evidence_sources` | `Database.java:255` | `JdbcCommissionEvidenceService.java:170` | `JdbcServicesTest.java:241` | 已接线 | — |
| `rpg_communities` | `Database.java:804` | `JdbcCommunityService.java:315` | `JdbcCommunityServiceTest.java:106` | 已接线 | — |
| `rpg_community_approvals` | `Database.java:972` | 无 | 无 | 声明未接线 | G-109 |
| `rpg_community_contributions` | `Database.java:848` | `Database.java:909`<br>`JdbcCommunityService.java:669` | `JdbcCommunityServiceTest.java:140` | 已接线 | — |
| `rpg_community_identity` | `Database.java:790` | `Database.java:801`<br>`JdbcCommunityService.java:360` | `JdbcCommunityServiceTest.java:95` | 已接线 | — |
| `rpg_community_invites` | `Database.java:827` | `Database.java:907`<br>`JdbcCommunityService.java:195` | `JdbcCommunityServiceTest.java:123` | 已接线 | — |
| `rpg_community_members` | `Database.java:817` | `Database.java:906`<br>`JdbcCommunityService.java:637` | `JdbcCommunityServiceTest.java:115` | 已接线 | — |
| `rpg_community_project_rewards` | `Database.java:859` | `Database.java:911`<br>`JdbcCommunityService.java:1093` | `JdbcCommunityServiceTest.java:148` | 已接线 | — |
| `rpg_community_projects` | `Database.java:837` | `Database.java:908`<br>`JdbcCommunityService.java:446` | `JdbcCommunityServiceTest.java:131` | 已接线 | — |
| `rpg_credit_ledger` | `Database.java:722` | `Database.java:885`<br>`JdbcCommerceService.java:605`<br>`JdbcCurrencyExchangeService.java:410`<br>…共4个文件 | `JdbcServicesTest.java:135` | 已接线 | — |
| `rpg_currency_exchange_ledger` | `Database.java:766` | `JdbcCurrencyExchangeService.java:334` | `JdbcServicesTest.java:383` | 已接线 | — |
| `rpg_currency_exchange_limits` | `Database.java:779` | `JdbcCurrencyExchangeService.java:286` | `JdbcServicesTest.java:392` | 已接线 | — |
| `rpg_daily_item_reward_receipts` | `Database.java:231` | `Database.java:881`<br>`JdbcActivityEconomyService.java:413` | `JdbcDailyItemRewardServiceTest.java:49` | 已接线 | — |
| `rpg_daily_item_rewards` | `Database.java:221` | `Database.java:880`<br>`JdbcActivityEconomyService.java:370` | `JdbcDailyItemRewardServiceTest.java:41` | 已接线 | — |
| `rpg_dungeon_energy_reservations` | `Database.java:468` | `Database.java:479`<br>`JdbcDungeonEntryService.java:65` | `JdbcServicesTest.java:297` | 已接线 | — |
| `rpg_dungeon_reward_receipts` | `Database.java:495` | `Database.java:895`<br>`JdbcAscensionService.java:147`<br>`JdbcDungeonRewardService.java:62`<br>…共4个文件 | `JdbcDungeonRewardServiceTest.java:64`<br>`JdbcServicesTest.java:356` | 已接线 | — |
| `rpg_dungeon_ticket_escrows` | `Database.java:482` | `Database.java:894`<br>`JdbcDungeonEntryService.java:104` | `JdbcServicesTest.java:305` | 已接线 | — |
| `rpg_dungeon_ticket_pity` | `Database.java:508` | `JdbcDungeonRewardService.java:139` | `JdbcDungeonRewardServiceTest.java:74` | 已接线 | — |
| `rpg_energy_recovery_quotas` | `Database.java:435` | `Database.java:887`<br>`JdbcProfileService.java:256` | `JdbcServicesTest.java:288` | 已接线 | — |
| `rpg_equipment_lifecycle_audit` | `Database.java:1010` | `Database.java:1022`<br>`JdbcEquipmentLedgerAuditService.java:28` | `JdbcEquipmentLedgerAuditServiceTest.java:43` | 已接线 | — |
| `rpg_equipment_operations` | `Database.java:356` | `Database.java:874`<br>`JdbcEquipmentOperationService.java:111` | `JdbcEquipmentOperationServiceTest.java:59`<br>`JdbcServicesTest.java:202` | 已接线 | — |
| `rpg_equipment_states` | `Database.java:1000` | `Database.java:1008`<br>`EquipmentStateLedger.java:26`<br>`JdbcEquipmentLedgerAuditService.java:70` | `JdbcEquipmentLedgerAuditServiceTest.java:37`<br>`JdbcEquipmentOperationServiceTest.java:68`<br>…共3个测试类 | 已接线 | — |
| `rpg_hidden_path_audit` | `Database.java:459` | `Database.java:889`<br>`JdbcHiddenPathService.java:85` | `JdbcServicesTest.java:376` | 已接线 | — |
| `rpg_hidden_path_caps` | `Database.java:446` | `JdbcHiddenPathService.java:194` | `JdbcServicesTest.java:365` | 已接线 | — |
| `rpg_hidden_paths` | `Database.java:452` | `JdbcHiddenPathService.java:77` | `JdbcServicesTest.java:370` | 已接线 | — |
| `rpg_incident_compensation_lines` | `Database.java:305` | `JdbcAdminIncidentCompensationService.java:520` | `JdbcAdminIncidentCompensationServiceTest.java:97` | 已接线 | — |
| `rpg_incident_compensations` | `Database.java:289` | `Database.java:313`<br>`JdbcAdminIncidentCompensationService.java:296` | `JdbcAdminIncidentCompensationServiceTest.java:88` | 已接线 | — |
| `rpg_ledger` | `Database.java:176` | `Database.java:188`<br>`JdbcActivityEconomyService.java:701`<br>`JdbcAdminIncidentCompensationService.java:255`<br>…共13个文件 | `JdbcAdminIncidentCompensationServiceTest.java:68`<br>`JdbcCommunityServiceTest.java:70`<br>…共6个测试类 | 已接线 | — |
| `rpg_market_buy_orders` | `Database.java:961` | 无 | 无 | 声明未接线 | G-109 |
| `rpg_market_deliveries` | `Database.java:345` | `Database.java:873`<br>`JdbcActivityEconomyService.java:270`<br>`JdbcCommerceService.java:247`<br>…共9个文件 | `JdbcDailyItemRewardServiceTest.java:59`<br>`JdbcDungeonRewardServiceTest.java:57`<br>…共4个测试类 | 已接线 | — |
| `rpg_market_listings` | `Database.java:328` | `Database.java:342`<br>`JdbcEconomyAnalyticsService.java:63`<br>`JdbcMarketService.java:77` | `JdbcServicesTest.java:176` | 已接线 | — |
| `rpg_mechanism_switches` | `Database.java:924` | `MechanismRegistry.java:48` | 无 | 已接线 | — |
| `rpg_merchant_ledger` | `Database.java:602` | `Database.java:901`<br>`JdbcMerchantService.java:142` | `JdbcServicesTest.java:329` | 已接线 | — |
| `rpg_merchant_limits` | `Database.java:591` | `JdbcMerchantService.java:48` | `JdbcServicesTest.java:321` | 已接线 | — |
| `rpg_merit_ledger` | `Database.java:317` | `Database.java:878`<br>`JdbcActivityEconomyService.java:705`<br>`JdbcAdminIncidentCompensationService.java:272`<br>…共6个文件 | `JdbcAdminIncidentCompensationServiceTest.java:74`<br>`JdbcServicesTest.java:266` | 已接线 | — |
| `rpg_node_leases` | `Database.java:583` | `Database.java:900`<br>`JdbcNodeLease.java:105` | `JdbcServicesTest.java:314` | 已接线 | — |
| `rpg_outbox` | `Database.java:990` | 无 | 无 | 声明未接线 | G-109 |
| `rpg_payment_ledger` | `Database.java:711` | `Database.java:883`<br>`JdbcCommerceService.java:138` | `JdbcServicesTest.java:160` | 已接线 | — |
| `rpg_pet_bond_ledger` | `Database.java:676` | `Database.java:904`<br>`JdbcPetProgressService.java:136` | `JdbcPetProgressServiceTest.java:67` | 已接线 | — |
| `rpg_pet_collection` | `Database.java:656` | `Database.java:903`<br>`JdbcPetProgressService.java:102` | `JdbcPetProgressServiceTest.java:51` | 已接线 | — |
| `rpg_pet_progress` | `Database.java:641` | `Database.java:902`<br>`JdbcPetProgressService.java:53` | `JdbcPetProgressServiceTest.java:40` | 已接线 | — |
| `rpg_pet_revival_intents` | `Database.java:687` | `Database.java:905`<br>`JdbcPetRevivalService.java:59` | `JdbcPetRevivalServiceTest.java:51` | 已接线 | — |
| `rpg_pet_skins` | `Database.java:667` | `JdbcPetProgressService.java:68` | `JdbcPetProgressServiceTest.java:60` | 已接线 | — |
| `rpg_profiles` | `Database.java:125` | `JdbcAdminIncidentCompensationService.java:285`<br>`JdbcClassBranchService.java:93`<br>`JdbcCommunityService.java:814`<br>…共7个文件 | `JdbcAdminIncidentCompensationServiceTest.java:45`<br>`JdbcAdminTestServiceTest.java:34`<br>…共5个测试类 | 已接线 | — |
| `rpg_progression_anomalies` | `Database.java:914` | 无 | 无 | 声明未接线 | G-109 |
| `rpg_progression_wallets` | `Database.java:190` | `JdbcActivityEconomyService.java:626`<br>`JdbcAdminIncidentCompensationService.java:351`<br>`JdbcAscensionService.java:181`<br>…共5个文件 | `JdbcAdminIncidentCompensationServiceTest.java:58`<br>`JdbcCommunityServiceTest.java:79`<br>…共3个测试类 | 已接线 | — |
| `rpg_season_personal_bests` | `Database.java:571` | `JdbcSeasonExpeditionService.java:418` | `JdbcSeasonExpeditionServiceTest.java:83` | 已接线 | — |
| `rpg_season_player_progress` | `Database.java:549` | `JdbcSeasonExpeditionService.java:334` | `JdbcSeasonExpeditionServiceTest.java:65` | 已接线 | — |
| `rpg_season_rotation_claims` | `Database.java:560` | `Database.java:899`<br>`JdbcSeasonExpeditionService.java:364` | `JdbcSeasonExpeditionServiceTest.java:74` | 已接线 | — |
| `rpg_season_rotation_state` | `Database.java:517` | `JdbcSeasonExpeditionService.java:143` | `JdbcSeasonExpeditionServiceTest.java:41` | 已接线 | — |
| `rpg_season_run_members` | `Database.java:542` | `Database.java:898`<br>`JdbcSeasonExpeditionService.java:324` | `JdbcSeasonExpeditionServiceTest.java:59` | 已接线 | — |
| `rpg_season_runs` | `Database.java:528` | `Database.java:896`<br>`JdbcSeasonExpeditionService.java:100` | `JdbcSeasonExpeditionServiceTest.java:50` | 已接线 | — |
| `rpg_season_settlements` | `Database.java:932` | `SeasonSettlementState.java:5` | 无 | 已接线 | — |
| `rpg_session_leases` | `Database.java:982` | 无 | 无 | 声明未接线 | G-109 |
| `rpg_skill_book_uses` | `Database.java:425` | `Database.java:886`<br>`JdbcHiddenPathService.java:181`<br>`JdbcProfileService.java:361` | `JdbcServicesTest.java:281` | 已接线 | — |
| `rpg_skill_mutation_receipts` | `Database.java:383` | `Database.java:888`<br>`JdbcSkillService.java:421` | `JdbcSkillMutationSettlementTest.java:69` | 已接线 | — |
| `rpg_skills` | `Database.java:372` | `Database.java:381`<br>`JdbcAdminTestService.java:152`<br>`JdbcAscensionService.java:134`<br>…共5个文件 | `JdbcAdminTestServiceTest.java:43`<br>`JdbcServicesTest.java:274`<br>…共3个测试类 | 已接线 | — |
| `rpg_starlight_operation_limits` | `Database.java:732` | `JdbcCommerceService.java:661` | `JdbcServicesTest.java:142` | 已接线 | — |
| `rpg_support_credit_accounts` | `Database.java:943` | 无 | 无 | 声明未接线 | G-109 |
| `rpg_support_credit_ledger` | `Database.java:950` | 无 | 无 | 声明未接线 | G-109 |

### 3.1 零消费者表清单（7 张）

```
rpg_community_approvals
rpg_market_buy_orders
rpg_outbox
rpg_progression_anomalies
rpg_session_leases
rpg_support_credit_accounts
rpg_support_credit_ledger
```

对账 `doc/cnb/gap-registry.md` G-109 声明的 8 张：`rpg_season_settlements`、`rpg_session_leases`、`rpg_outbox`、`rpg_market_buy_orders`、
`rpg_support_credit_accounts/ledger`、`rpg_community_approvals`、`rpg_progression_anomalies`。
本轮实测**零消费者为 7 张**（上表）。`rpg_season_settlements` 在 `SeasonSettlementState.java:12` 的注释里被提及，
这是文档字符串而非 SQL 消费者，实质仍属声明未接线，故 G-109 实际覆盖 8 张中的 8 张（7 张零引用 + 1 张仅注释引用）。

## 4. 结构性门禁（G-301 / G-302）实测

```
$ grep -n 'compileJava\|ddl\|CREATE TABLE' .cnb.yml
  ./gradlew --no-daemon --continue ... compileJava > /tmp/gradle.log 2>&1
```

`.cnb.yml` 只有 `compileJava` 全量编译门，**没有任何「每张 DDL 表必须在 src/main 有引用点」的门禁** ⇒ G-301 未落地。

```
$ grep -rl --include=*.java 'Files.readString' plugins | wc -l
46
$ grep -rn --include=*.java 'Files.readString' plugins | wc -l
48
```

46 个测试类用 `Files.readString` 读源码后做字符串断言（其中含 7 个原始 `*ContractTest`）。G-302 说「7 个」为低估。

## 5. 与 `doc/cnb/gap-registry.md` 的对账

| gap id | 本轮机械核验结果 | 偏差 |
|---|---|---|
| G-001 | **确认**。`defenseMultiplier` 只在 `ExecuteListener.java:126`（`onDefensiveDamage`）被调用；`:130` `onDamage(EntityDamageByEntityEvent)` 直接 `attack.multiplier()*targetMultiplier`，不减防御。`DefensePolicyTest.java:25-26` 断言 ENTITY_ATTACK/PROJECTILE 可防御 | 无 |
| G-002 | **确认**。`MechanismRegistry.` 在 `src/main` 零调用（仅启动播种） | 无 |
| G-003 | **确认**。`RpgItemsPlugin.java:106-124` 无 `RPG_ITEM_SIGNING_KEY` 时生成并 `getConfig().set` + `saveConfig()` | 无 |
| G-004 | **确认**。在线 `Database.java:20-21` 两个 int 键 + `pg_advisory_xact_lock(int,int)`；离线 `MigrationEngine.java:20` 单个 bigint + `pg_try_advisory_xact_lock(bigint)` | 无 |
| G-101 | **确认**。`SeasonSettlementState` 仅自身；`SeasonSettlementService` 不存在；G-109 中 `rpg_season_settlements` 仅被注释提及 | 无 |
| G-102 | **确认**。`rpg_session_leases`/`rpg_outbox` 只有 DDL | 无 |
| G-103 | **确认**。`canApprove/canModerate/isGovernance` 仅自身；`rpg_community_approvals` 只有 DDL；`TREASURER/BUILDER/EVENT_HOST/RECRUITER` 零引用（`BUILDER` 等仅在枚举定义） | 无 |
| G-104 | **确认**。仓库内 `InventoryCreativeEvent` 零命中；`recoverDeliveryReceipts()` 只遍历 `getInventory().getSize()`，不含游标槽 | 无 |
| G-105 | **确认**。`SkillsCommand.java:962` `event.setDamage(getDamage()*1.12)`、`:974` battle-trance 倍率在 HIGH；`ExecuteListener.java:166` HIGHEST 再乘 `attack.multiplier()` | 无 |
| G-106 | **确认**。`HiddenPathRules.minimumAscension()→2`（写死）、`requiredSkill(BLOODPACT)=vanished-horizon`；`config.yml:373` `vanished-horizon.minimum-ascension: 3` | 无 |
| G-107 | **确认**。`JdbcProfileService.java:304` `ascend(UUID)` 仍在（仅注释 deprecated，无删除）；`:569` 技能点 `finalEffective / 3` | 无 |
| G-108 | **确认**。`rpg-dungeons/src/main/resources/config.yml` 有 6 个 `world: dungeon_*`；`src/main` 零读取 `dungeon_ancient_ruins` 等字面量 | 无 |
| G-109 | **部分修正**。零消费者表实测 7 张（非「约 10 张」）；`rpg_season_settlements` 属「仅注释引用」 | 数量口径 |
| G-201 | **确认**。`ForgeListener.java:645` 写 `schema 5`；`EquipmentSecurity.java:224-225` clamp 1..5 | 无 |
| G-202 | **确认**。`ForgeRules.slotUpperBound` EPIC/RARE 随 `quality` 上浮 | 无 |
| G-203 | **确认**。`LegacyBlueprintRules.eligibility()` 有 `RARE_TRAIT_REQUIRED` 分支 | 无 |
| G-204 | **确认占位符失真**。`sivita_` 在 `src/main` 命中 16 行 | 无 |
| G-206 | **确认**。仓库无 `verify-installation.sh`、无 `sivita-navigation` 数据包；`quick_actions` 只在 docs 出现 | 无 |
| G-207 | **确认无显式实现**。`plugins/rpg-world/src/main` 无「每日 3 场」显式计数常量 | 无 |
| G-208 | **确认**。`EconomyCommand.java:595-596` `minimum-roundtrip-loss` clamp 下限 `0.05` | 无 |
| G-209 | **确认偏差存在**。`dungeons.*.boss.abilities.telegraph-ticks` 为 28–36 tick（≈1.4–1.8s），超出承诺的 0.4–0.8s | 无 |
| G-301 | **确认缺失**。`.cnb.yml` 无 DDL-消费者门禁 | 无 |
| G-302 | **修正**。非 7 个而是 46 个测试类用 `Files.readString` | 数量口径 |
| G-401/402/403 | **不在本任务范围**（构建自包含，需本地取二进制），未复核 | — |

## 6. 本轮新增结论（gap-registry 未登记）

1. `EquipmentLifecycle.requiresExitPath()` / `isTradeable()` / `isExitPath()`、`ProgressionRules.effectiveLevelCap()` / `totalExperienceForAscension()` / `totalExperienceToEffective()`、
   `AuthoritativeItemPolicy.isAuthoritativeNamespace()`、`JdbcNodeLease.renewAsync()` / `mayStillBeValid()`、`EquipmentOperationCoordinator.markedDeliveryCopy()`、
   `CommunityRole.canModerate()/canApprove()/isGovernance()`：**全为「声明未接线」（仅命中自身定义文件）**。部分仅有同包单测，无 `src/main` 消费者。
2. `MechanismRegistry` 的 `DEFAULTS` 7 个机制键（`economy.faucet.hunt.gold` 等）在 `src/main` 零消费 —— Kill Switch 有开关无被控对象。
3. 46 个测试类用 `Files.readString` 读源码做字符串断言，`[T]` 证据须打折（G-302 低估）。
