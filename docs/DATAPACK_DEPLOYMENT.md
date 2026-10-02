# 数据包部署与校验（G-206 收口）

## 背景

`#minecraft:quick_actions` 是 26.2 原生快捷操作入口（默认按 `G` 打开）。
它由数据包提供，而部署校验脚本 `server/bin/verify-installation.sh` 把它当作发布链的硬依赖。

过去这个数据包**不在版本控制里**（`server/` 被 `.gitignore` 排除），导致：
干净克隆里没有产物、CI 里完全静默、生产机上的部署校验必然失败。
G-206 的收口就是把产物挪进仓库，并让门禁把这类「依赖仓外产物」的引用拦下来。

## 仓库内的真相源

| 路径 | 作用 |
|---|---|
| `datapacks/sivita-navigation/pack.mcmeta` | 数据包元数据（`pack.pack_format`） |
| `datapacks/sivita-navigation/data/minecraft/tags/function/quick_actions.json` | `#minecraft:quick_actions` 的 tag 声明（8 个成员） |
| `datapacks/sivita-navigation/data/sivita/function/quick/*.mcfunction` | 8 个按钮函数，每个只 `run` 一条已注册命令 |
| `scripts/install-datapacks.sh` | 校验 + 部署入口（`--check` 只校验） |
| `scripts/check-repo-artifacts.sh` | 门禁：`docs/` 里引用的 `server/` 产物必须入库或显式登记为仓外产物 |

数据包**只做转交**：不拥有玩法状态、数值、货币或账本。按钮文本与图标由资源包翻译键与客户端提供，
命令归属见各插件 `plugin.yml`（`/menu`、`/tasks`、`/skills`、`/hub`、`/wild`、`/home`、`/rpghelp`）。

## CI 里怎么被拦下

要求④（把「依赖仓外产物」的问题在 CI 拦下来）**不需要改 `.cnb.yml`**：
质量门的第二个 stage「门禁自测（负向对照）」本来就执行 `sh scripts/selftest-gates.sh`，
而 `push` 与 `api_trigger_gate` 共用同一套 stages。G-206 的两条判据就接在这个自测里：

| 判据 | 检测内容 |
|---|---|
| `selftest-gates.sh` §5 | `check-repo-artifacts.sh` 在真实文档上必须绿；把 `datapacks/` 藏起来必须红 |
| `selftest-gates.sh` §4b | `install-datapacks.sh --check` 在真实数据包上必须绿；函数改成未注册命令必须红 |
| `selftest-gates.sh` §6 | **接线自证**：上面两条门禁确实被本脚本调用，且 `.cnb.yml` 确实执行本脚本 |

§6 是最关键的一条：它把「谁在跑」变成可 grep 的事实。
没有它，将来有人删掉 §4b/§5，自测仍会全绿 —— 门禁回到「写了但没人跑」的静默状态。

这些判据都在**临时副本**上做负向对照（`$TMP` 里的夹具），
真实工作区始终只读：夹具被改坏也不会污染仓库。

## 部署步骤

维护窗口内停服执行（数据包在启动时加载，热替换不会被已运行的 Paper 采纳）：

```bash
# 1. 只校验仓库内数据包（CI / 本地都能跑，不写任何文件）
sh scripts/install-datapacks.sh --check

# 2. 部署到服务端（<服务端目录> = 含 world/ 的 Paper 根目录，生产机 /home/lamb/minecraft/server）
sh scripts/install-datapacks.sh /home/lamb/minecraft/server
# 等价：SIVITA_SERVER_DIR=/home/lamb/minecraft/server sh scripts/install-datapacks.sh

# 3. 启动 Paper，然后跑服务端侧的安装校验
/home/lamb/minecraft/server/bin/verify-installation.sh
```

`scripts/install-datapacks.sh` 把 `datapacks/<名>/` 复制到 `<服务端目录>/world/datapacks/<名>/`，
目标目录已存在时先删后拷（避免上一次部署的残留文件被客户端当成同一个数据包）。
目标目录不存在时脚本**不会**自动 `mkdir`，直接非零退出 —— 路径写错就该报错，
不能让 Paper 静默没加载。

## 仓外产物登记（`server/` 被 `.gitignore` 排除）

`server/` 下的运维脚本与运行时产物不在本仓。这类引用必须在本文登记，
否则 `scripts/check-repo-artifacts.sh` 会让质量门变红。

被 `docs/` 引用、且属仓外产物的路径（每条给出本仓的生成/替代方式）：

| 仓外路径 | 本仓可复核的替代 |
|---|---|
| `server/bin/verify-installation.sh` | 数据包校验由 `scripts/install-datapacks.sh --check` 覆盖；该脚本在生产机上检查 `world/datapacks/` 下的数据包 |
| `server/bin/bootstrap.sh`、`server/bin/start.sh` | 服务端引导/启动脚本，本仓不含 Paper 分发与 JVM 配置 |
| `server/bin/sync-betterhud-pack.sh` | BetterHud 布局同步；本仓只提供数据包，资源包流程见 `docs/RESOURCE_PACK_QA.md` |
| `server/bin/maintain-world-coverage.sh` | 世界边界维护；对应规则真相源在 `plugins/rpg-world/src/main/resources/config.yml` |
| `server/bin/migrate-rpg-h2-to-postgresql.sh`、`server/bin/production-preflight.sh` | 迁移与预检入口；schema 真相源与迁移工具在 `plugins/rpg-core/.../persistence/Database.java` 与 `tools/db-migration/`，步骤见 `docs/DATABASE_MIGRATION.md` |
| `server/bin/` | 上述脚本所在目录（`server/` 被 `.gitignore` 排除，`.gitignore` 已登记 `server/runtime/`、`server/secrets/` 等子路径） |
| `server/config/` | 运行期配置由 `server/config-templates/`（已入库）渲染 |
| `server/community-plugins.lock.json` | 第三方插件锁；编译期清单真相源是 `scripts/fetch-deps.sh` |

其余被引用的 `server/` 路径（`server/runtime/`、`server/cache/`、`server/backups/`、
`server/secrets/`、`server/.env`、`server/deploy-candidates/`、`server/vendor/`）
已在 `.gitignore` 里逐条登记，门禁据此放行。

## 相关文档

- `docs/PLUGIN_INTEGRATION.md` —— 四层菜单入口的分工
- `docs/RESOURCE_PACK_QA.md` —— 资源包发布链路与 `verify-installation.sh` 的使用时机
- `docs/GAME_DESIGN.md` —— 八个快捷动作的玩家可见语义
