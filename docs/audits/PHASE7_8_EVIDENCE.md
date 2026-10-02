# Phase 7/8 增量证据（Round 4）

## Phase 7 非副本终局与内容管线
- `docs/CONTENT_PIPELINE.md`：家族机制（遗迹/硫泉/风暴）→ 关卡预热池（6热+10补）→ 美术32×32 → 数值轮换18周 → 审计闭包；禁止低概率/超长经验
- 管线产出：`SeasonExpeditionService` 18组合已在代码中，待美术批量接入现有 113 纹理管线

## Phase 8 正版UUID与多服（C-014）
- `plugins/rpg-core/.../Database.java` 新增 `rpg_session_leases`（player→node 租约）+ `rpg_outbox`（Transactional Outbox）
- 既有 `rpg_node_leases 60s` + `MechanismRegistry` + `Hikari advisory lock 30s` 已满足“多服前置”四件套：Session Lease / 邮箱租约 / Outbox / Advisory Lock
- `tools/db-migration/.../SchemaManifest.java` 同步 2 新表
- Velocity 方案：`original-rpg.service` 已预留 `RPG_NODE_ID` 与 `proxy-protocol` 链路，待 bxj 248 部署第二 Paper 后端

## 验证
- `./gradlew :plugins:rpg-core:compileJava :tools:db-migration:compileJava` **BUILD SUCCESSFUL**
- `./gradlew test` **BUILD SUCCESSFUL 104 tasks**（含新增表校验）
- 运行链路：`haproxy-lan/edge PROXYv2` + `FRP TLS` + `Paper proxy-protocol` 已在线验证（latest.log 11/11 NPC）

## 剩余仅 [H]
- 20人7日真人、Postgres故障注入、阶梯70/100人压测需在 248 专机预发布环境执行，已具备可验证状态
