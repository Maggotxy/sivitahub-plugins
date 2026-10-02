# Phase 2/3/5 增量证据（Round 2）

## Phase 2 Economy V2 — 12态生命周期
- `plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/EquipmentLifecycle.java` 完整12态 `CREATED/BOUND/UNBOUND/LISTED/SOLD/SALVAGED/SACRIFICED/LEGACY_EXTRACTED/SEASON_ATTUNED/DESTROYED/QUARANTINED/RESTORED`，`isValidTransition` 单一权威状态机，`isExitPath` 校验 C-008
- 现有 `rpg-items` 的 `BOUND/UNBOUND` 已对齐，新状态 `SOLD/SALVAGED/SACRIFICED/LEGACY_EXTRACTED` 将由市场/分解/献祭事务驱动

## Phase 3 仿真器
- `tools/economy-sim/run.py` 修复：`ROOT.parent.parent` 正确读取 `plugins/rpg-economy/src/main/resources/config.yml` SHA `0f08786b0e99`，15场景解析，S15 `support_credit_to_starlight` **FAIL_FAST** 已强制（C-012）
- 输出：`[S01..S14] NEEDS_ENGINE`（需真实 ledger 回放），`[S15] FAIL_FAST as expected`

## Phase 5 support_credit 隔离 + 赛季事务机
- `plugins/rpg-core/.../Database.java` 新增 `rpg_support_credit_accounts` / `rpg_support_credit_ledger`（独立账本，与 `starlight` 物理隔离，C-012）
- `tools/db-migration/.../SchemaManifest.java` 同步 `tables` + `uniqueKeys` (`rpg_support_credit_ledger:idempotency_key`)
- `plugins/rpg-api/.../SeasonSettlementState.java` 九态机 `DRAFT->...->COMPLETED/FAILED/ROLLED_BACK` 仅前向，`FAILED->ROLLED_BACK` 可回滚（C-010）
- `plugins/rpg-core/.../MechanismRegistry.java` Phase0 已初始化 7开关

## 验证
- `./gradlew test` **BUILD SUCCESSFUL**（104 tasks, 1 skipped）
- `./gradlew :tools:db-migration:test` **BUILD SUCCESSFUL**（新增唯一键校验通过）
- `python3 tools/economy-sim/run.py` 15场景 **1 FAIL_FAST enforced**

## 剩余（Round 3+）
- Phase 4 装备退出/市场V2买单、Phase 5 验签订单服务、Phase 6 社区治理 7角色、Phase 7 内容管线、Phase 8 Velocity Session Lease/Outbox 需各PR独立落地并补 [M][R][H]
