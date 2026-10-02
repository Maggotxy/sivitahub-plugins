# RpgCore H2 → PostgreSQL 数据迁移

更新时间：2026-08-12。

本页适用于把单节点 `plugins/RpgCore/data/rpg.mv.db` 搬到 PostgreSQL。工具迁移 RpgCore 当前清单中的全部 75 张 `rpg_*` 玩法表，包括装备操作恢复、技能变异、隐藏路线、体力恢复额度、管理员隔离测试档案、事故补偿审计、宠物成长/图鉴/外观/亲密账本与跨库复苏意图及社群周荣誉回执；AuthMe、LuckPerms、原版世界、玩家 `.dat`、PDC、Citizens 和 BetonQuest 数据不在本工具范围内。清单契约测试会在 RpgCore 新增表、唯一键或运行时性能索引而工具尚未同步时失败关闭，避免静默漏迁或把数据迁入缺少关键索引的目标库。

入口：`server/bin/migrate-rpg-h2-to-postgresql.sh`  
实现与测试：`tools/db-migration/`

## 安全边界

- Paper 必须先正常停服。外层脚本会检查本项目 Paper 进程，Java 工具再次按运行目录检查进程；任一门禁失败都不会连接数据库。
- 源库或目标库若有尚未过期的 `rpg_node_leases`，迁移拒绝执行。多节点环境必须先停掉所有 Paper，再至少等待一个租约周期；迁移不会把仍有效的进程权威误当成普通业务数据搬运。
- 源 H2 自动追加 `IFEXISTS=TRUE` 和 `ACCESS_MODE_DATA=r`，不会因路径写错创建空库，也不会写源库。
- JDBC URL 不允许嵌入密码、token 或 `user:password@host`。密码只从 0600、非符号链接的单行文件或 `SIVITA_MIGRATION_*_PASSWORD` 环境变量读取，日志不输出 URL、账号或密码。
- 目标 schema 必须由完全相同的 RpgCore 构建初始化。工具不复制、猜测或维护第二份 schema。
- 源和目标必须完整包含清单中的 75 张表；表、列、类型族、宽度/精度、可空性、主键、关键唯一索引或运行时性能索引不兼容会立即失败。
- 默认只允许空目标。非空目标只有两种合法结果：数据已经逐表完全一致时幂等成功；否则必须显式使用 `--replace-existing --confirm-replace RPG_TABLES --confirm-target <实际数据库名>`；连接后的 PostgreSQL catalog 必须与确认名完全一致。
- 实际复制在 PostgreSQL 单一 `SERIALIZABLE` 事务中完成，并持有迁移 advisory lock。覆盖按反向依赖顺序删除，复制按正向依赖顺序批量写入；任一复制或提交前校验失败会整体回滚。
- 每张表比较行数、主键摘要、全行 SHA-256 摘要和关键金额/数值列合计，并检查主键与关键唯一键无重复。提交后再从已提交视图完整校验一次。

## 第一次生产迁移

### 1. 在预发布环境演练

先使用停服后取得的 H2 备份副本和一次性 PostgreSQL 数据库完成全流程。不要把第一次运行放在唯一生产副本上。

```bash
JAVA_HOME=server/runtime/java \
PATH="$PWD/server/runtime/java/bin:$PATH" \
./gradlew --no-daemon :tools:db-migration:test
```

默认测试使用两个 H2 PostgreSQL-mode 数据库，覆盖空目标、dry-run、幂等重跑、非空门禁、显式覆盖、schema 不兼容和逐表验证。真正 PostgreSQL 条件测试见本文末尾。

### 2. 准备目标 schema

源 H2 与目标 PostgreSQL 都必须先由要上线的同一 RpgCore JAR 完成加法式 schema 初始化。若当前生产 H2 来自上一构建，先在预发布环境对它的备份副本验证升级；正式窗口内备份旧 H2 后，让新构建在维护隔离状态下只完成一次 schema 初始化，再正常停服并等待租约过期。工具发现源少表时会失败，不会把新表静默留空。

创建独立 PostgreSQL 数据库和最小权限迁移账号。用同一 RpgCore JAR 在隔离、不可被玩家访问的预发布 Paper 上初始化目标 schema，随后正常停服并等待至少 60 秒，或从同一构建初始化的临时库取得 schema-only dump。

初始化可能生成章节种子行和节点租约，因此它不是“空目标”。这种目标必须在 dry-run 和正式迁移时都显式选择覆盖策略。不要提前把正式玩家流量接到目标库。

### 3. 停服并备份

```bash
systemctl --user stop original-rpg-lan.service
pgrep -af '/server/runtime/java/bin/java.*-jar paper.jar'
```

第二条命令必须没有输出，并等待至少 60 秒让源/目标节点租约都自然过期。随后备份：

- 完整复制停服后的 `server/runtime/plugins/RpgCore/data/rpg.mv.db`；
- 对目标 PostgreSQL 执行一次 `pg_dump`；
- 记录当前服务端构建、资源包和迁移工具版本；
- 不删除源 H2，直到 PostgreSQL 上线验证和回滚窗口结束。

### 4. 准备凭据

推荐使用位于内存型运行目录的单行 0600 文件，不把密码放进 shell history 或进程参数。

```bash
install -m 600 /dev/null /run/user/$(id -u)/sivita-pg-migration.secret
read -rsp 'PostgreSQL migration password: ' SIVITA_SECRET_INPUT
printf '%s\n' "$SIVITA_SECRET_INPUT" > /run/user/$(id -u)/sivita-pg-migration.secret
unset SIVITA_SECRET_INPUT
```

### 5. 只读 dry-run

下面的源路径必须指向刚才备份并验证过的实际 H2 数据库前缀，不能带 `.mv.db` 后缀。

```bash
server/bin/migrate-rpg-h2-to-postgresql.sh \
  --source-url 'jdbc:h2:file:/srv/sivita/server/runtime/plugins/RpgCore/data/rpg;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE' \
  --source-user sa \
  --target-url 'jdbc:postgresql://127.0.0.1:5432/original_rpg' \
  --target-user original_rpg \
  --target-password-file /run/user/$(id -u)/sivita-pg-migration.secret \
  --dry-run
```

若目标只有初始化种子数据，明确演练覆盖计划：

```bash
server/bin/migrate-rpg-h2-to-postgresql.sh \
  --source-url 'jdbc:h2:file:/srv/sivita/server/runtime/plugins/RpgCore/data/rpg;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE' \
  --target-url 'jdbc:postgresql://127.0.0.1:5432/original_rpg' \
  --target-user original_rpg \
  --target-password-file /run/user/$(id -u)/sivita-pg-migration.secret \
  --dry-run --replace-existing --confirm-replace RPG_TABLES --confirm-target original_rpg
```

dry-run 不执行删除或插入。任何 schema、唯一键、活动租约或目标策略错误都会非零退出。

### 6. 执行并复验

使用通过 dry-run 的完全相同参数，移除 `--dry-run`。若 dry-run 使用了显式覆盖，正式执行必须保留 `--replace-existing` 和两个确认参数。

成功输出必须以 `outcome=MIGRATED` 或 `outcome=ALREADY_IDENTICAL` 结束。随后再单独运行一次：

```bash
server/bin/migrate-rpg-h2-to-postgresql.sh \
  --source-url 'jdbc:h2:file:/srv/sivita/server/runtime/plugins/RpgCore/data/rpg;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE' \
  --target-url 'jdbc:postgresql://127.0.0.1:5432/original_rpg' \
  --target-user original_rpg \
  --target-password-file /run/user/$(id -u)/sivita-pg-migration.secret \
  --verify-only
```

`--verify-only` 必须得到 `outcome=VERIFIED`。重复普通迁移也应得到 `ALREADY_IDENTICAL`，不会再次插入。

### 7. 切换与回滚窗口

只有迁移和独立复验都成功后，才设置生产环境：

- `RPG_DATABASE_URL=jdbc:postgresql://...`
- `RPG_DATABASE_USER=...`
- `RPG_DATABASE_PASSWORD=...`
- 每个 Paper 使用唯一且稳定的 `RPG_NODE_ID`
- `RPG_REQUIRE_POSTGRESQL=true`

先运行 `server/bin/production-preflight.sh`，再启动一个无玩家金丝雀节点，检查 RpgCore 启动、章节、余额、市场邮箱、体力预留和经济快照。不要同时让旧 H2 节点与 PostgreSQL 节点接受写入。

若迁移命令在提交前失败，目标事务已回滚；修正原因后可重跑。若命令报告“提交后验证失败”，目标提交已经发生但工具仍非零退出：保持 Paper 停止，从迁移前 PostgreSQL 备份恢复或人工确认后运行 `--verify-only`，不能盲目继续上线。源 H2 备份应保留到整个回滚窗口结束。

## 可选参数与退出行为

```text
--batch-size 1..10000       默认 500
--source-schema NAME        默认 PUBLIC
--target-schema NAME        默认 public
--dry-run                   完全不写目标数据
--verify-only               只接受逐表完全相同
--replace-existing          事务内删除目标 rpg_* 数据后复制
--confirm-replace RPG_TABLES
--confirm-target DATABASE   必须与实际连接的 PostgreSQL 数据库名完全一致
```

所有失败均非零退出：参数/安全门禁为 2，驱动缺失为 3，连接或 SQL 失败为 4。外层 shell 的 Gradle/构建失败也会原样非零退出。

## 真 PostgreSQL 条件测试

测试只会在显式提供 `SIVITA_MIGRATION_TEST_POSTGRES_URL` 时运行，并在随机独立 schema 内操作，结束后删除该 schema。示例一次性容器：

```bash
docker run --rm -d --name sivita-migration-pg \
  -p 127.0.0.1:55432:5432 \
  -e POSTGRES_PASSWORD='local-test-only' postgres:17

SIVITA_MIGRATION_TEST_POSTGRES_URL='jdbc:postgresql://127.0.0.1:55432/postgres' \
SIVITA_MIGRATION_TEST_POSTGRES_USER=postgres \
SIVITA_MIGRATION_TEST_POSTGRES_PASSWORD='local-test-only' \
JAVA_HOME=server/runtime/java PATH="$PWD/server/runtime/java/bin:$PATH" \
./gradlew --no-daemon :tools:db-migration:test

docker stop sivita-migration-pg
```

这项条件测试证明 JDBC 类型转换、事务和 PostgreSQL 校验路径能运行；它不替代使用真实数据副本完成的预发布迁移演练。
