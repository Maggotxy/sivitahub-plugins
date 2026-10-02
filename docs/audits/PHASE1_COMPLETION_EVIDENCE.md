# Phase 1 成长语义本地化 — 完成证据（[D][S][T][M][R][H]）

> 分支：本地直接落地（对应 `codex/progression-local-levels`） | 目标模型：`LOCAL_CAP=30 / MAX_ASCENSION=3 / effective 1-120`

## [D] Design
- 宪法 C-015、总策划四段模型、执行手册 Phase 1、手册 yaml `local_level_cap:30 max_ascension:3 post_ascension_vertical_cap:120`
- 经验改为 `stage_budget + normalized_weight(1..29)`：`STAGE_BUDGETS 280k/450k/700k/1050k =2.48M`，`base=90*l^2+310*l`，`needed=budget*base/sum`，`max(100)`，避免 `eff^2*stageMultiplier` 膨胀
- 技能点：`effective/3` 里程碑差值，非 `local/3` 重算
- 旁路：保留 `ProfileService.ascend()` deprecated 指向 `AscensionService`，生产强制走完整事务

## [S] Source
- `plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java`：新增 `LOCAL_LEVEL_CAP, POST_ASCENSION_VERTICAL_CAP, effectiveLevel(asc,local), experienceToNextLocalLevel, totalExperienceForAscension, totalExperienceToEffective`
- `plugins/rpg-api/src/main/java/cn/originalrpg/api/model/PlayerProfile.java`：`effectiveLevel()` 委托 `ProgressionRules.effectiveLevel`，新增 `localLevel()`
- `plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java`：`withGrantedExperience` 全改为本地推进 + effective里程碑技能点，`ascend()` 保留deprecated兼容
- `plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcDungeonRewardService.java`：同模型修正
- `plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java`：新增 `rpg_progression_anomalies`, `rpg_mechanism_switches`, `rpg_season_settlements`
- `tools/db-migration/.../SchemaManifest.java` 同步
- `plugins/rpg-core/.../MechanismRegistry.java` Phase0 Kill Switch
- `plugins/rpg-integrations/.../SivitaPlaceholderExpansion.java`：新增 `%sivita_local_level%` `%sivita_effective_level%`（`%sivita_level%` 保留为local别名）
- `plugins/rpg-mobs` 等调用点：`AdaptiveMobSafetyRules.maximumPlayerLevel` 现在 `asc*30+LOCAL_CAP`（30/60/90/120），`MobGuideRules` 仍以 `effective -3..+6` 缩放

## [T] Test
```
./gradlew test  # 104 tasks, BUILD SUCCESSFUL
:plugins:rpg-api:test — ProgressionRulesTest 更新：30/30/30, effective 1/30/31/60/120, 1转Lv1>0转Lv30, weight区分
:plugins:rpg-core:test — JdbcAdminTestServiceTest 更新为 LOCAL_CAP/effective 120
:plugins:rpg-mobs:test — AdaptiveMobSafetyRulesTest 36/66/96/126, MobGuideRulesTest 829/329
:tools:db-migration:test — SchemaManifestContractTest 更新3新表
```

## [M] Simulation
- 仿真规格 `tools/economy-sim/scenarios.v1.yaml` 15场景已定版，Phase 1 不直接改经济产销，但经验曲线已满足“阶段预算”可仿真输入；后续 Phase 3 将以真实 `config.yml` 跑 15 场景

## [R] Runtime
- `database.migrate()` 启动时建3新表，`RpgCorePlugin` 初始化 `MechanismRegistry`（`economy.faucet.*`, `market.escrow`, `season.settlement` 等 7开关）
- `server/runtime/logs/latest.log` 启动 18.975s，11/11 Hub NPC，6副本预热，Paper loopback + HAProxy PROXYv2 链路正常
- 本地H2 dry-run：`tools/migration/progression-phase1-dryrun.sql` 检测 `asc>0 AND level>30`，禁止 `level%30` 猜测，需人工清单

## [H] Human
- 待真人：创角→0转30满→1转1验证 effective 31>30；2转/3转回归；怪物/副本门槛与旧档 dry-run 人工复核

## 剩余未证明（转 Phase 2-8）
- Phase 2 Economy V2 12态收敛、Phase 3 仿真器执行、Phase 4 装备退出/市场V2、Phase 5 support_credit物理隔离、Phase 6-8 需独立PR与7日运营指标
