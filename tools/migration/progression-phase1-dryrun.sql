-- Phase 1 成长语义本地化 dry-run 与迁移
-- 执行前必须在预发布/生产只读副本上运行以下查询，禁止用 level % 30 猜测映射

-- 1. 检测无法唯一映射的高风险档案（asc>0 且 level>30）
SELECT player_uuid, archetype, ascension, level, experience,
       'ANOMALY_LEVEL_OVER_LOCAL_CAP' AS reason
FROM rpg_profiles
WHERE ascension > 0 AND level > 30
ORDER BY ascension DESC, level DESC;

-- 2. 分布统计
SELECT ascension, level, COUNT(*) AS cnt
FROM rpg_profiles
GROUP BY ascension, level
ORDER BY ascension, level;

-- 3. 经验异常（负值或超预算）
SELECT player_uuid, ascension, level, experience
FROM rpg_profiles
WHERE experience < 0 OR experience > 10000000
ORDER BY experience DESC;

-- 4. 将异常档案写入人工清单（仅在确认后执行）
-- INSERT INTO rpg_progression_anomalies (player_uuid, ascension, level, experience, reason)
-- SELECT player_uuid, ascension, level, experience, 'LEVEL_OVER_30'
-- FROM rpg_profiles WHERE ascension > 0 AND level > 30
-- ON CONFLICT (player_uuid) DO NOTHING;

-- 5. 迁移后验证（新语义：所有 level 必须 1..30）
-- SELECT COUNT(*) FROM rpg_profiles WHERE level < 1 OR level > 30; -- 必须为 0
-- SELECT COUNT(*) FROM rpg_progression_anomalies; -- 人工复核数量

-- 回滚：rpg_profiles 的变更需通过备份恢复；rpg_progression_anomalies 可 DELETE
