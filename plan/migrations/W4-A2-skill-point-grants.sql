-- G-107 下半：技能点里程碑幂等流水表
--
-- 背景：本表是 JdbcProfileService.settleSkillPointMilestone() 的幂等依据。
-- 代码已在 agent/W4-A2-skillpoint-local-milestone 上落地，但 Database.java 的
-- 迁移清单（tasks-w4.json allowed_paths 之外）未登记，因此真机首次启动时该表
-- 不存在 ⇒ 任何经验授予都会以 "Table not found" 回滚。
--
-- 人工动作：在把本分支合入 main 之前，由人执行本文件（或把等价的 CREATE TABLE
-- 加进 Database.java 的 DDL 列表 + tools/db-migration/SchemaManifest）。
-- 本文件**不改任何既有行**，只建表。
--
-- 幂等性：IF NOT EXISTS，可重复执行。
--
-- 主键语义（关键，别改）：milestone_key = local-milestone:<玩家>:<转>:<档位序号>，
-- 即「这个玩家在这一段的这一档发过点没有」。
-- receipt 只是审计备注（副本 run_id / 事故 incident_key / profile:<uuid>），
-- **不得**进主键：把 receipt 混进主键会让同一档位换一条 receipt 跨过时重复发点。
-- 同时也**不得**把结算区间（from/to local level）写进一致性校验，
-- 那会把「本地 4->6 与 1->6 都跨第 6 档」这样的合法路径误判成键复用而整单回滚。

CREATE TABLE IF NOT EXISTS rpg_skill_point_grants (
    milestone_key VARCHAR(160) PRIMARY KEY,
    receipt VARCHAR(96) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    points INTEGER NOT NULL,
    ascension INTEGER NOT NULL,
    milestone_index INTEGER NOT NULL,
    to_local_level INTEGER NOT NULL,
    granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 审计查询用：按玩家看已发档位
CREATE INDEX IF NOT EXISTS idx_rpg_skill_point_grants_player
    ON rpg_skill_point_grants (player_uuid);
