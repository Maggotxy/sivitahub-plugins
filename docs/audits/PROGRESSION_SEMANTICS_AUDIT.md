# 成长语义源码审计（Progression Semantics Audit）— 初稿（待 Codex 本地完整校验）

> 基线：`plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java` + `PlayerProfile.java`
> 目标模型（宪法 C-015）：`LOCAL_LEVEL_CAP=30, MAX_ASCENSION=3, effective 1-120`
> ```
> 0转 local 1-30 => effective 1-30
> 1转 local 1-30 => effective 31-60
> 2转 local 1-30 => effective 61-90
> 3转 local 1-30 => effective 91-120
> ```

## 1. 已确认的偏离

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
