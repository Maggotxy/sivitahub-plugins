# 内容生产管线（Phase 7）

> 目标：横向内容可流水线生产，不靠低概率/超长经验冒充深度（宪法禁止项）

## 1. 管线角色
- 策划：定义家族机制（遗迹/硫泉/风暴）与强度预算
- 关卡：手工模板 + 程序化预热池（6热实例+10补池）
- 美术：32×32 语义图标 → 资源包 `resource-pack/assets/originalrpg` → SHA-1 `sivita-hub-26.2.zip`
- 数值：三族六关素材表 + 轮换词缀（18周组合）→ `plugins/rpg-dungeons/config.yml`
- 审计：`RESOURCE_PACK_QA.md` 闭包校验（孤儿纹理失败）

## 2. 交付物
- 地图：每关独立 `world/dungeon_instance_*` 模板，程序化按 tick 分片写入，TPS<18.5暂停
- 词缀：`SeasonExpeditionService` 18组 `season_id/rotation_id/dungeon_id` 轮换
- 图鉴：`rpg_season_player_progress` + `rpg_season_personal_bests` 竞速榜

## 3. 验收门槛（M3后）
- 手工场景机关真人可读（预警形状/颜色；当前 `telegraph-ticks` 20 tick = 1.0s，是表现层保证的最快值：早期 0.4–0.8s 承诺下预警文案读不完，见 G-209 与 `BossAttackRules.MINIMUM_READABLE_TELEGRAPH_TICKS`）
- BetterModel 64/20/0 降级不丢判定
- 6关 × 3词缀 =18 组合实测 P95 MSPT <45ms

## 4. 禁止
- 低概率掉落冒充内容（如 0.1% 刷已做物品）
- 超长经验冒充深度（已由阶段预算约束）
