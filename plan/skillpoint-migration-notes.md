# 技能点口径迁移说明（G-107 下半：本地里程碑 + 幂等补偿）

> 本文件只给**人工清单**，不含任何 UPDATE 语句。存量档案的口径修正必须由人拍板后单独 PR。
> 生效分支：`agent/W4-A2-skillpoint-local-milestone`；唯一实现：`ProgressionRules` 技能点本地里程碑段。

## 1. 改了什么

| 项 | 旧口径 | 新口径 |
|---|---|---|
| 发放依据 | `effectiveLevel / 3` 差分（每转生 3 有效级 1 点） | 本地等级里程碑（每转本地 3/6/.../30 各 1 点） |
| 跨段行为 | 0转30 → 1转31，整除差 `10-10=0`，**吞掉新段首档** | 换段后按新段本地等级重新累计，**不吞点** |
| 幂等 | 无（重放靠调用方业务 receipt） | 表 `rpg_skill_point_grants` 主键 `milestone_key = (玩家, 转, 档位)` |
| 每转总点数 | 不等（跨段点吞/多发） | 恒定 10 点（本地 3..30 共 10 档） |
| 0→3 转总点数 | 不恒定 | 恒定 40 点 |

### 段内点数对照（有意变更，无差异）

段内（同一转内）新旧口径**完全一致**，因此现有正常玩家在段内不会看到跳变：

| 本地等级 | 旧口径累计 | 新口径累计 | 差异 |
|---|---|---|---|
| 1 | 0 | 0 | 0 |
| 3 | 1 | 1 | 0 |
| 6 | 2 | 2 | 0 |
| 12 | 4 | 4 | 0 |
| 30（满段） | 10 | 10 | 0 |

### 跨段点数对照（有意的行为修正）

| 时点 | 旧口径（累计） | 新口径（累计） | 说明 |
|---|---|---|---|
| 0转 本地30 | 10 | 10 | 一致 |
| 1转 本地1（→有效31） | 10（`31/3=10`） | 10 | 一致 |
| 1转 本地2（→有效32） | 10 | 10 | 一致 |
| 1转 本地3（→有效33） | 11 | 11 | 一致 |
| 1转 本地30（→有效60） | 20 | 20 | 一致 |
| 2转 本地1（→有效61） | **20**（`61/3=20`） | 20 | 一致 |
| 2转 本地2（→有效62） | 20 | 20 | 一致 |
| 3转 本地30（→有效120） | 40 | 40 | 一致 |

> 结论：在「每转都走满本地 30 级」的路径上，新旧口径**数值相同**。
> 差异只出现在**跨段点落在同一整除格里**的路径：旧口径按有效等级整除，
> 换段后第一个里程碑可能出现「整除不进位」而被吞，例如
> 旧口径下 0转30(`10`) → 1转2(`32/3=10`) → 1转3(`33/3=11`) 这段里，
> 本地 2 经验已花掉但点数不动；新口径下本地 3 必定发点。
> 因此存量档案若曾走过「转生后立刻再升 1-3 级」的路径，旧口径可能少发了点。

## 2. 需要人工确认的存量档案（只读 dry-run）

```sql
-- 说明：本表上线前的存量档案没有任何里程碑流水行，
--      因此「已发档位」对存量档案一律为空，新口径会在玩家下次升级时
--      把该段内已过的档位一次性补上（这正是「不吞点」的补偿形式，
--      仍然是同一份规则 + 同一个幂等键，可重复执行且只生效一次）。
--      是否要为此做前置一次性补偿，见 §4。

-- 2.1 是否有档案的 skill_points 低于新口径应得值（跨段吞点嫌疑）
--     新口径应得 = sum(每段已过里程碑) + 初始 1 点
--     注意：技能点还被技能书消耗，因此 < 应得 只能作为「嫌疑」而不是结论。
SELECT p.player_uuid, p.ascension, p.level, p.skill_points,
       (p.ascension * 10 + p.level / 3) AS local_milestone_floor
FROM rpg_profiles p
WHERE p.skill_points < (p.ascension * 10 + p.level / 3);

-- 2.2 已消耗技能书点的档案（技能点差额的真正解释来源）
SELECT p.player_uuid, p.skill_points, COALESCE(u.spent, 0) AS book_points
FROM rpg_profiles p
LEFT JOIN (SELECT player_uuid, SUM(points_granted) AS spent
           FROM rpg_skill_book_uses GROUP BY player_uuid) u
  ON u.player_uuid = p.player_uuid
WHERE p.skill_points < 0;

-- 2.3 已有里程碑流水的档案（本功能上线后的新数据，不需要人工映射）
SELECT player_uuid, COUNT(*) AS milestones, SUM(points) AS granted
FROM rpg_skill_point_grants GROUP BY player_uuid;
```

**禁止**用 `level % 30` 猜测旧档案的本地等级；无法唯一映射的进
`rpg_progression_anomalies`（Phase 1 已预留表，写入口未实现，见
`docs/planning/deferred-tables.md#24`）。

## 3. 补偿路径（本 PR 已实现，可重复执行且只生效一次）

- 表：`rpg_skill_point_grants`
  - 主键（唯一键）：`milestone_key`
  - `milestone_key` 形如 `local-milestone:<玩家>:<转>:<档位序号>`
  - `receipt`（审计列，**不参与去重**）：副本用 `run_id`；直接授予经验用 `profile:<uuid>`；事故补偿用 `incident_key`
  - 档位序号 = 本地等级 / 3，所以本地 3 -> 序号 1；序号 0 对应本地 1-2，永远不发点
- 写入路径：`JdbcProfileService.grantExperienceInTransaction(...)` /
  `JdbcProfileService.grantExperience(...)` → `settleSkillPointMilestones(...)`
- 行为：`INSERT ... ON CONFLICT DO NOTHING` 主键命中即「这一档已发过」，
  返回 0 ⇒ 不加点、不覆写（不再比较结算区间字段：把 `from/to` 等级写进一致性校验
  会把「本地 1→6」与「本地 4→6」这种都跨第 6 档的合法路径误判成键复用而整单回滚）
- **禁止**内存补偿：只有落库成功才 `Math.addExact` 到 `PlayerProfile.skillPoints`
- **没有第二套等级账本**：技能点仍然写同一个 `rpg_profiles.skill_points`；
  `rpg_skill_point_grants` 只是「发过哪些档位」的可审计依据
- **DDL 尚未进迁移清单**：`Database.java` 的建表列表与 `tools/db-migration/SchemaManifest`
  不在本任务 allowed_paths 内，因此本分支单独提供
  `plan/migrations/W4-A2-skill-point-grants.sql`（人工执行，`IF NOT EXISTS`，可重复跑）。
  合入 main 前必须由人把等价 DDL 补进 `Database.java`，否则真机首次经验授予会以
  「Table not found」回滚。

## 4. 人工决策项（需要人拍板，本 PR 不擅自 UPDATE）

1. 是否对 §2.1 查出的嫌疑档案做一次性补偿？补偿必须走事故补偿入口
   （`JdbcAdminIncidentCompensationService`，`incident_key` 作为幂等键），
   不允许直接 `UPDATE rpg_profiles`。
2. 补偿的 `source` 前缀约定（例如 `local-milestone:backfill:<批次号>`）需要人类确认，
   以便日后能把「迁移补偿」与「正常升级」区分开。
3. 对于同时消耗过技能书的档案，补偿点数需要人工核对，不能机械取差额。

## 5. 回滚

- 代码回滚：删除 `ProgressionRules` 技能点段落 + 两处调用点改动即可回到旧口径。
- 数据回滚：`rpg_skill_point_grants` 是**只增的审计表**，回滚代码后可以保留不清空；
  若要清空必须人工确认（清空后重放保护会失效，同一 receipt 可能二次发点）。
- DDL 回滚：`DROP TABLE rpg_skill_point_grants` —— 需要同时清空
  `tools/db-migration/SchemaManifest` 的登记，否则迁移校验会报缺表。
