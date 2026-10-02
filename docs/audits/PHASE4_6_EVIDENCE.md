# Phase 4/6 增量证据（Round 3）

## Phase 4 市场 V2 — 买单/制作订单（C-009 单一托管）
- `plugins/rpg-api/src/main/java/cn/originalrpg/api/model/MarketOrder.java`：`PREPARED/ACTIVE/FILLED/CANCELLED/EXPIRED`，买单为托管金币，制作订单复用同一结算与税模型
- `plugins/rpg-core/.../Database.java` 新增 `rpg_market_buy_orders`（order_id PK, buyer, matcher, price, quantity, status）
- `tools/db-migration/.../SchemaManifest.java` 同步 2 新表

## Phase 6 社群治理 — 7角色
- `plugins/rpg-api/.../CommunityRole.java` 扩展：`OWNER/TREASURER/BUILDER/EVENT_HOST/RECRUITER` 保留 `OWNER=LEADER, OFFICER=DEPUTY` 别名，向后兼容 `OWNER/OFFICER/MEMBER` 测试
- `canModerate()` 含 TREASURER，`canApprove()` 双批：LEADER/DEPUTY/TREASURER
- `plugins/rpg-core/.../Database.java` 新增 `rpg_community_approvals`（双人审批、继任、冷静期审计用）

## 验证
- `./gradlew :plugins:rpg-api:compileJava :plugins:rpg-core:compileJava` BUILD SUCCESSFUL
- `./gradlew test` BUILD SUCCESSFUL 104 tasks
- 现有 `JdbcCommunityServiceTest` 仍以 `OWNER/OFFICER` 断言通过，新角色通过 `LEADER/DEPUTY` 别名兼容

## 剩余
- Phase 4 买单撮合事务、Phase 6 双批/继任/解散冷静期/公共仓库审计逻辑、Phase 7 内容管线、Phase 8 Velocity Session Lease/Outbox 需后续 PR 补 [M][R][H]
