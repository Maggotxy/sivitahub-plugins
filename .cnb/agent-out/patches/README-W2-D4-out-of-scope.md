# W2-D4 越界部分存档（等人类裁定 allowed_paths）

任务 `W2-D4-community-role-consumers` 的 `allowed_paths` 只有：
`plugins/rpg-community/**`、`plugins/rpg-api/**`、`.cnb/agent-out/**`。

但 G-103 的验收要求「审批记录落 `rpg_community_approvals`」，而这张表与
它的唯一持久化实现 `JdbcCommunityService` 都在 `rpg-core`。因此按现行
`allowed_paths` **无法**满足「审批记录落库」这一条。

已实现并通过验证的部分（本地已跑绿，见 `../W2-D4-community-role-consumers.json`
的 evidence）原本落在这些越界文件里，现按纪律 revert，完整文件存档在同目录
`out-of-scope/` 下，人类放宽 paths 后可直接覆盖回去：

- `plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java`
  （`rpg_community_approvals` 加 subject / requester_uuid / idempotency_key 列 + 唯一索引）
- `plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcCommunityService.java`
  （七角色能力判定 + 双批审批事务 + 审计 INSERT/SELECT/DELETE + 两个事件发布）
- `plugins/rpg-core/src/main/java/cn/originalrpg/core/RpgCorePlugin.java`
  （注册 CommunityGovernanceService / CommunityRoleAssignmentService + governance-enabled 开关）
- `plugins/rpg-core/src/main/resources/lang/zh_cn.yml`、`en_us.yml`（治理文案键）
- `plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/JdbcCommunityGovernanceTest.java`
  （11 条行为级测试）
- `plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonManager.java`
  （副本完成时补发 `CommunityEventCompletedEvent`，属 rpg-dungeons 不是 rpg-api）

人类只需要二选一：
1. 把 `plugins/rpg-core/**`、`plugins/rpg-dungeons/**` 加进本任务的 `allowed_paths`
   （与 W2-A1 / W2-B1 / W2-B2 有重叠，须串行），然后按上述清单覆盖回去；
2. 或者明确「审批只走 `rpg-api` 接口、持久化由后续 rpg-core 任务实现」，
   则本波只需保留 `rpg-api` 的接口与模型（已在本次 PR 中）。
