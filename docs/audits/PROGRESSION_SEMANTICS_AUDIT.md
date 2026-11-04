# 成长语义源码审计（Progression Semantics Audit）— 历史初稿与当前复核

> **当前状态（2026-11-03）**：下方第 1–7 节保留的是实施前历史初稿，不能据此判断当前代码仍存在这些缺陷。
> 对提交 `c83226ff87c8ecb868277d9ca5bce575b4684abc` 的本地源码复核确认：本地 30 级、有效 120 级、阶段经验预算、技能点里程碑幂等，以及删除简化晋升入口，均已有实现。
> 请先读[第 8 节：当前源码复核](#8-当前源码复核2026-11-03)与[当前综合审计](CURRENT_SOURCE_REVIEW.md)。本次仅建立源码证据，不继承旧文档中的测试通过或运行时结论。

> 基线：`plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java` + `PlayerProfile.java`
> 目标模型（宪法 C-015）：`LOCAL_LEVEL_CAP=30, MAX_ASCENSION=3, effective 1-120`
> ```
> 0转 local 1-30 => effective 1-30
> 1转 local 1-30 => effective 31-60
> 2转 local 1-30 => effective 61-90
> 3转 local 1-30 => effective 91-120
> ```

## 1. 历史初稿：当时确认的偏离

### 1.1 `ProgressionRules.levelCap(asc)` 返回 30/60/90/120

```java
public static int levelCap(int asc) { return 30*(asc+1); }
```

但存储语义仍为「本地等级」，调用点普遍以 `profile.level()` 直接比较 `levelCap`，在旧数据 `level>30` 时将误判为未满级。

### 1.2 `PlayerProfile.effectiveLevel() = asc*30 + level`

```java
public int effectiveLevel() { return asc * 30 + level; }
```

旧模型下 1转 Lv.60 → effective 90，晋升到 2转 Lv.1 → effective 61（**不升反降**）。
2转 Lv.90 → 150，晋升到 3转 Lv.1 → 91。三转 Lv.120 → 210，超出宣称 120。

### 1.3 经验曲线膨胀

```java
long eff = asc*30 + level;
stageMultiplier = 1 + asc*0.85;
exp = (90*eff*eff + 310*eff) * stageMultiplier
```

`eff^2` 叠加 `stageMultiplier` 使后期数量级膨胀，违背「阶段预算+归一化权重」。

### 1.4 技能点按 `level/3` 二次计算

`JdbcProfileService.withGrantedExperience` 中：
```java
int gained = level/3 - startingLevel/3
```
语义修正为本地里程碑后，若直接重算会重复/漏发。需改为「本地等级里程碑幂等补偿」。

### 1.5 双入口风险

- `ProfileService.ascend()` / `JdbcProfileService.ascend()`：仅检查职业/章节/等级
- `AscensionService.ascend()`：完整事务（章节/满级/分支/技能/副本/功勋/装备评分）

简化入口为未来误调用旁路，必须删除，仅保留完整服务。

## 2. 调用点清单（Codex 本地必须执行 `rg` 完整扫描）

执行（在仓库根）：

```bash
rg -n "ProgressionRules\.levelCap" plugins --no-heading
rg -n "ProgressionRules\.experienceToNextLevel" plugins --no-heading
rg -n "effectiveLevel\(\)" plugins --no-heading
rg -n "ascend\(" plugins --no-heading
rg -n "effectiveLevel|levelCap|ascension" plugins/rpg-mobs plugins/rpg-dungeons plugins/rpg-skills plugins/rpg-community plugins/rpg-guide plugins/rpg-world plugins/rpg-integrations --no-heading
rg -n "level" plugins/rpg-api/src/main/java/cn/originalrpg/api/model/PlayerProfile.java --no-heading
```

对每个命中记录：

| 文件:行 | 传入值 | 当前语义 | 目标语义 | 是否需改 | 迁移 | 测试 |
|---|---|---|---|---|---|---|
| `rpg-core/JdbcProfileService.java:543` | `profile.level()` vs `levelCap` | 旧有效等级比较 | local vs LOCAL_CAP | 是 | 需 | 晋升边界测试 |
| `rpg-api/PlayerProfile.effectiveLevel()` | `asc*30+level` | 旧有效 | `effectiveLevel(asc,local)` | 是 | 需 | 显示/战斗一致 |
| `rpg-mobs/AdaptiveMobListener.java:242-253` | `nearestProfile.effectiveLevel()` | 旧 | 保持有效但源改为新有效 | 是 | 否 | 怪物缩放回归 |
| `rpg-dungeons/DungeonCommand.java:182` | `effectiveLevel < min` | 旧 | 新有效 | 是 | 否 | 副本门槛 |
| `rpg-skills/SkillBookListener.java:79` | `effectiveLevel` | 旧 | 新有效 | 是 | 否 | 技能书门槛 |
| `rpg-community/*` | `effectiveLevel` | 旧 | 新有效 | 是 | 否 | 师徒/社群 |
| `rpg-guide/GuideCommand.java:*` | 多处 | 旧 | 区分 local/effective | 是 | 否 | 引导文案 |
| `rpg-integrations/Placeholder` | `sivita_level` 等 | 旧 | 需双占位符 `local/effective` | 是 | 否 | Placeholder |

> 完整表由 Codex 在 `codex/progression-semantics-audit` 分支补全，不得在此初稿外猜测行号。

## 3. 数据库只读检查（dry-run）

```sql
-- 无法唯一映射的高风险档案
SELECT player_uuid, archetype, ascension, level, experience
FROM rpg_profiles WHERE ascension > 0 AND level > 30;

-- 分布
SELECT ascension, level, COUNT(*) FROM rpg_profiles GROUP BY ascension, level ORDER BY ascension, level;

-- 经验异常（超过新曲线预算的需人工复核）
SELECT player_uuid, ascension, level, experience FROM rpg_profiles WHERE experience < 0 OR experience > 1000000;
```

禁止 `level % 30` 猜测映射。无法唯一映射的进入 `rpg_progression_anomalies` 人工清单。

## 4. 迁移要求（Phase 1 PR 必须包含）

- 新增 `ProgressionRules`：
  ```java
  LOCAL_LEVEL_CAP = 30
  MAX_ASCENSION = 3
  effectiveLevel(asc, local)
  experienceToNextLocalLevel(asc, local) // 阶段预算+权重
  totalExperienceForAscension(asc)
  ```
- `JdbcProfileService`：技能点改为本地里程碑 + 已发点幂等表
- 删除简化 `ascend()` 旁路
- 更新所有调用点语义标签：`local_level / effective_level / ascension / chapter_sequence`
- 迁移版本 + 前后摘要 + 回滚脚本 + 真人验收

## 5. 怪物/副本/技能/社群/引导/官网/AI 协同

- 怪物缩放：`effective -3 .. +6` 保持，但输入改为新有效
- 副本门槛：`effectiveLevel` 保持，但需重标定数值
- 技能书/隐藏路线：`effectiveLevel` 阈值保持，需确认 1转/2转边界不因重算而漂移
- Placeholder：新增 `%sivita_local_level%` 与 `%sivita_effective_level%`，旧 `%sivita_level%` 标记废弃
- 官网：快照需同时含 `local` 与 `effective`

## 6. 初稿局限

- GitHub 连接器未执行 Gradle / Paper / PostgreSQL 故障注入
- 未跑真实 `rg` 全仓与数据库副本
- 因此本稿仅为 Codex 本地审计的起点，后续 PR 必须附完整 `rg` 输出与 dry-run 结果

## 7. Codex 本地验收命令（Phase 1）

```bash
./gradlew :rpg-api:test --tests "*ProgressionRulesTest*"
./gradlew test
# 启动 PostgreSQL 副本后
psql -c "SELECT * FROM rpg_profiles WHERE ascension>0 AND level>30 LIMIT 20;"
```

## 8. 当前源码复核（2026-11-03）

### 8.1 基线与证据边界

- 审计基线：`c83226ff87c8ecb868277d9ca5bce575b4684abc`，本节行号均对应该提交。
- 已完成：本地生产 Java 源码扫描、关键服务与既有测试源码检查。
- 未完成：本节未运行完整 Gradle 测试、生产数据库 dry-run、Paper 真人流程、PostgreSQL 故障注入。
- 历史报告中的 `BUILD SUCCESSFUL`、局域网运行日志和阶段完成描述，属于其各自快照，不作为本基线新取得的 `[T][R][H]` 证据。
- 本节所述 2026-11-03 只读复核没有修改成长数值、数据库结构或玩家资产。2026-11-04 用户进一步授权的运行修复及迁移见第 9 节，不能将本段历史范围误读为后续实现仍然只读。

### 8.2 五项历史缺陷的当前状态

| 历史问题 | 当前源码事实 `[S]` | 证据与仍需验证项 |
|---|---|---|
| `levelCap` 随转数返回 30/60/90/120 | `LOCAL_LEVEL_CAP=30`，合法转数下 `levelCap()` 始终返回 30 | `ProgressionRules.java:8,64–68`；已有 `ProgressionRulesTest`，本次未重跑 |
| 有效等级超过 120、晋升后回退 | `effectiveLevel(ascension, localLevel)` 检查 0–3 转、1–30 本地等级；`PlayerProfile.effectiveLevel()` 委托此方法 | `ProgressionRules.java:53–60`、`PlayerProfile.java:30–32`；非法旧档仍需人工审查 |
| 经验由有效等级平方叠加转数倍率膨胀 | 四段名义预算 280k/450k/700k/1050k，29 次本地升级按权重分配；累计经验使用逐级实走和 | `ProgressionRules.java:31,138–189`；名义预算与取整后实走和允许有微小差异，不能混用 |
| 技能点重算导致重复或漏发 | 本地 3/6/.../30 共十个里程碑；幂等键包含玩家、转数、档位，数据库插入成功才加点 | `ProgressionRules.java:82–129`、`JdbcProfileService.java:565–623`；不是旧文档中的 `effective/3` 差分 |
| `ProfileService.ascend()` 简化旁路 | 接口和 `JdbcProfileService` 均已无该方法；职业命令调用 `AscensionService`，事务内检查条件并更新档案 | `RpgClassesPlugin.java:429`、`JdbcAscensionService.java:60–135`；接口 Javadoc 中残留的旧名称不是可调用入口 |

源码入口：
[ProgressionRules](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java)、
[PlayerProfile](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/model/PlayerProfile.java)、
[ProfileService](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/service/ProfileService.java)、
[JdbcProfileService](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java)、
[JdbcAscensionService](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcAscensionService.java)。

### 8.3 当前调用点清单

以下是生产源码扫描后的语义分组；测试文件单独检查，不计入扫描计数。`ascend(` 的六条命中包含一条 Javadoc，实际入口并未因此增加。

| 扫描表达式 | 命中行数 | 文件数 |
|---|---:|---:|
| `ProgressionRules\.levelCap` | 10 | 8 |
| `ProgressionRules\.experienceToNextLevel` | 5 | 4 |
| `effectiveLevel\(\)` | 29 | 14 |
| `\bascend\(` | 6 | 3 |

复核命令（在仓库根运行；只读）：

```bash
rg -n 'ProgressionRules\.levelCap' plugins -g '*.java' -g '!**/src/test/**'
rg -n 'ProgressionRules\.experienceToNextLevel' plugins -g '*.java' -g '!**/src/test/**'
rg -n 'effectiveLevel\(\)' plugins -g '*.java' -g '!**/src/test/**'
rg -n '\bascend\(' plugins -g '*.java' -g '!**/src/test/**'
```

| 文件与基线行号 | 当前语义 | 复核结果 |
|---|---|---|
| `rpg-core/.../JdbcProfileService.java:768–799` | 本地升级循环与本地 30 级封顶 | 已与新模型一致；最高转剩余 XP 见 8.5 |
| `rpg-core/.../JdbcDungeonRewardService.java:140–144` | 在副本事务内复用档案升级/里程碑结算 | 没有第二套等级计算；附近旧注释仍称 receipt 为档位幂等键，应以被调用实现为准 |
| `rpg-core/.../JdbcAscensionService.java:101,125–131` | 本地满级后晋升，新段本地等级为 1 | 有效等级衔接由统一规则保证 |
| `rpg-classes/.../RpgClassesPlugin.java:429,568` | 单一晋升服务、本地等级上限 | 未发现简化旁路调用 |
| `rpg-mobs/.../AdaptiveMobSafetyRules.java:33` | 最大开放转数 × 30 + 本地上限 | 这里得到 30/60/90/120 是有效上限，不能误改成固定 30 |
| `rpg-mobs/.../listener/AdaptiveMobListener.java:229–253,360` | 怪物缩放与掉落门槛使用有效等级 | 输入来自 `PlayerProfile.effectiveLevel()` |
| `rpg-mobs/.../MobGuideRules.java:70–75` | 有效等级用于怪物建议，本地等级用于满级/经验提示 | 两种语义已分开 |
| `rpg-mobs/.../DelayedMobRewardQueue.java:152,316` | 自定义掉落的等级差门槛 | 使用有效等级 |
| `rpg-mobs/.../ExperienceWriteBuffer.java:335,348` | 经验反馈的满级判断与下级所需经验 | 使用本地等级 |
| `rpg-dungeons/.../DungeonCommand.java:182` | 全队副本最低等级 | 使用有效等级 |
| `rpg-skills/.../SkillBookListener.java:79` | 技能书最低等级 | 使用有效等级 |
| `rpg-community/.../CommunityCommand.java:431`、`RpgCommunityPlugin.java:145` | 师徒资格 | 使用有效等级 |
| `rpg-community/.../AccountCommand.java:176` | 账号快照展示 | 使用有效等级 |
| `rpg-guide/.../GuideCommand.java:226–229,592,1356–1394,1555–1592` | 本地升级/晋升提示与有效等级副本门槛 | 语义按用途分开，仍需双语客户端流程验收 |
| `rpg-world/.../WorldEventListener.java:324,412,829` | 技能掉落、战斗等级差与队伍最低等级 | 使用有效等级 |
| `rpg-economy/.../EconomicActivityListener.java:206,460` | 狩猎资格与审计来源 | 使用有效等级 |
| `rpg-integrations/.../SivitaPlaceholderExpansion.java:103–107` | `local_level` 与 `effective_level` 两套明确显示字段 | 旧 `level` 仍是本地等级别名；不应解释为有效等级 |
| `rpg-core/.../command/RpgCommand.java:132–148` | 本地满级、阶段经验与全程累计经验 | 使用统一规则；官网不在本仓库，未验证网站展示 |

### 8.4 迁移状态与建议验证

当前已有 `rpg_progression_anomalies` 数据表、`JdbcProgressionAnomalyService` 和只读迁移脚本；不能再以“需要新增异常表”概括当前缺口。`JdbcProfileService.java:701–733` 会记录不可解释档案，且清除旧复核签名，避免旧结论替新异常背书。

本轮未连接生产数据库，**没有得出异常档案数为零的结论**。对授权的数据库副本，先按第 3 节执行只读统计，再以当前 `ProgressionRules.classifyAnomaly` 口径复核；固定 `experience > 1000000` 只是历史筛选条件，不能替代按转数的当前规则。继续禁止 `level % 30` 猜测迁移。

当前 Gradle 项目路径带 `:plugins:` 前缀；第 7 节保留的历史命令不是本仓库正确路径。当前针对性命令为：

```bash
./gradlew :plugins:rpg-api:test --tests '*ProgressionRulesTest*'
./gradlew :plugins:rpg-core:test --tests '*JdbcServicesTest*' --tests '*JdbcProgressionAnomalyServiceTest*'
```

上述命令要求项目指定的 JDK 25 与依赖；列出命令不代表本次已执行。

### 8.5 仍需单独回归的当前边界

最高转满级后的经验处理存在静态口径不一致，需在成长 Phase 的独立变更中验证：

- `JdbcProfileService.java:784–790` 对三转本地 30 级使用 `Long.MAX_VALUE`，不会像较低转数那样钳制剩余 XP；后续合法经验仍可累计。
- `ProgressionRules.java:250–251` 对所有合法等级使用“本转逐级经验和 + 1”作为异常检测上界。
- 因此三转满级后持续累计的合法 XP 可能进入 `experience-jump` 人工复核队列。这是源码推导，**本轮未做 JDBC/真人复现**，不能混同为已经发生的数据事故。
- 建议用正常 `grantExperience` 与副本结算分别覆盖三转 30 级后累计经验的边界，再明确满级经验究竟是丢弃、封顶还是历史累计；在设计决定前不自动修复旧档。

本轮已完成的是更新审计证据与语义说明，不改变上述运行行为。

## 9. 2026-11-04 授权运行修复与实际验证

**本节是第 8 节历史只读结论之后的状态更新。** 用户已明确要求完善实际可玩的插件，成长修复已在当前工作分支实现；第 8.5 节的“本轮未做 JDBC 复现”和“不改变运行行为”仅描述此前的审计轮次，不能再当作当前完成状态。详细的 25 模块覆盖与证据边界见 [完整玩法审查](FULL_PLUGIN_GAMEPLAY_REVIEW.md)，部署与迁移检查见 [成长收据迁移说明](../operations/PROGRESSION_RECEIPT_MIGRATION.md)。

### 9.1 已修复的成长边界

- `ProgressionRules.classifyAnomaly` 保留非法转数、本地等级及负 XP 检查；三转本地 30 级接受权威档案服务已经允许累计的非负 XP，其余阶段仍按阶段容量检测异常。没有清零历史经验，也没有改变每转 30 级、三转封顶和有效等级 120 的规则。
- 阶段累计经验采用饱和加法，避免合法大额经验在汇总展示时溢出为负数。正常经验写入、重新读取、最终满级以及技能点不重复授予均有回归覆盖。
- `ProfileService.grantExperienceOnce` 与 `JdbcProfileService` 使用稳定的授予 ID，将 `rpg_experience_grants` 收据、现有权威档案 XP 与技能点变化放在同一数据库事务。相同 ID、玩家和数额的重试不重复授予；相同 ID 的不同内容明确拒绝。此表记录调用幂等身份，不构成第二套等级账本。
- `ExperienceWriteBuffer` 冻结待写批次时分配稳定 ID，失败重试及关闭排空沿用原 ID；后来到达的经验使用独立批次。已覆盖“事务提交成功但调用返回异常，重试仍只增加一次”的真实 JDBC 故障注入。

### 9.2 真实迁移中发现并修复的启动缺陷

此前许多 JDBC 测试直接手写测试表结构，未执行 `Database.migrate()`；因此第 8 节中“已有表和迁移服务”不能证明空库可以启动。本轮直接执行真实迁移发现并修复两处缺陷：赛季结算转换索引先于目标表建立；技能点表 DDL 仍使用旧 `source/idempotency_key` 形状，而当前授点写入使用 `milestone_key` 形状。

当前迁移在空库建立与写入端一致的技能点表，并在正确位置创建赛季索引。旧技能点表只在确认没有审计记录时允许重建；有旧记录、未知列形状或缺少权威主键时明确停止迁移，保留原记录和档案，要求人工核对。没有通过取模、猜测 receipt 或删除历史记录来制造幂等身份。回滚及 H2 DDL 的隐式提交边界已写入迁移说明。

### 9.3 实际执行证据与剩余边界

2026-11-04 根代理串行运行了 `rpg-core`、`rpg-mobs` 与 `db-migration` 目标测试，构建成功；JUnit XML 确认 `DatabaseMigrationIntegrationTest` 3 项、`JdbcExperienceGrantServiceTest` 4 项、`ExperienceWriteBufferTest` 2 项、`JdbcProgressionAnomalyServiceTest` 12 项全部通过。真实迁移测试覆盖空库、再次启动、空旧表升级、有旧记录拒绝自动升级，以及迁移后的实际经验和技能点授予。此前同分支 `ProgressionRulesTest` 17 项也已通过。

这些结果属于 **T：自动化测试证据**。实际数据库为 H2（包含 PostgreSQL 兼容模式）；两项需要真实 PostgreSQL 环境的条件测试跳过，未宣称真实 PostgreSQL、Paper 进程或真人联机通过。最高级经验不会因本次口径修复而自动改写历史异常审计记录；仍需按运维流程复核。

稳定授予收据解决的是已冻结批次在提交结果不确定时的重复写入。尚未提交的自然怪经验和物品仍在进程内存中，进程被强制终止可能丢失待发奖励；本次没有实现持久化击杀意图日志，不能把幂等重试的通过解释为已经保证硬退出恢复。
