# 原生 Quick Actions 数据包 / Native Quick Actions

Minecraft 26.2 的默认 `G` 快捷入口读取 **`minecraft:dialog` 注册表的 `#minecraft:quick_actions` 标签**。本仓库提供一个双列 `multi_action` 对话框，包含八个常用操作。旧版放在 `tags/function/` 下的标签、`run /...` 函数和数据格式 15 不能实现这个界面，现已移除。

## 已入库的完整入口

| 路径 | 作用 |
|---|---|
| `datapacks/sivita-navigation/pack.mcmeta` | 精确声明数据包格式 `107.1`（Minecraft 26.2） |
| `datapacks/sivita-navigation/data/minecraft/tags/dialog/quick_actions.json` | 将唯一的 `sivita:navigation` 注册到原生快捷入口；保留其他数据包的注册 |
| `datapacks/sivita-navigation/data/sivita/dialog/navigation.json` | 双列八按钮；点击后以当前玩家身份执行命令并关闭界面 |
| `resource-pack/assets/originalrpg/lang/{en_us,zh_cn}.json` | 客户端对话框翻译；所有可见标签另有英文 fallback |
| `scripts/check-navigation.py` | 验证注册表、格式、按钮、插件命令和翻译；不依赖运行服务器 |
| `scripts/install-datapacks.sh` | `--check` 只校验；给定服务端目录时复制已校验的数据包 |

| English label | Command |
|---|---|
| Continue Current Objective | `/rpghelp current` |
| Navigate Current Objective | `/rpghelp current nopath` |
| Skills and Cooldowns | `/skills` |
| Tasks and Journey Center | `/tasks` |
| Return to Hub | `/hub` |
| Travel to Frontier | `/wild` |
| Return to Bed Home | `/home` |
| All Systems | `/menu` |

对话框只转交玩家命令，不持有角色状态、奖励或账本。关闭按钮不执行命令，Escape 可关闭界面。命令仍遵守对应插件权限、登录和玩法检查。

**Language behavior:** native dialogs use the Minecraft client's language. The client resource pack contains English and Chinese translations; readable English fallback text is embedded in the datapack for clients without those translations. `/language en_us` separately selects server-rendered plugin messages.

## 离线校验与部署

```bash
# 不接触任何服务端目录
sh scripts/install-datapacks.sh --check
python3 -m unittest discover -s scripts/tests -p 'test_navigation.py'

# 仅在已准备好的 Paper 26.2 服务端维护窗口内执行
sh scripts/install-datapacks.sh /path/to/paper-server
```

安装脚本要求目标服务端已存在 `world/`，然后将 `datapacks/<名>/` 复制到 `world/datapacks/<名>/`。部署后重启 Paper，在真实 26.2 客户端按 `G` 检查八个按钮、Escape 和中英文显示。自定义世界目录应在运维流程中明确处理，不能把本脚本的 `world/` 约定当成任意服务端布局。

负向测试会在临时副本中破坏数据包版本、对话框注册位置、命令、英文翻译和关闭行为，要求校验拒绝错误输入。这些可复现的源码检查已替代旧文档中对已移除 CNB 配置和不存在的自测脚本的依赖；不能据此声称已取得真实客户端验收。

## 格式依据

- [Minecraft Java 26.2 release notes](https://feedback.minecraft.net/hc/en-us/articles/46690753273997-Minecraft-Java-Edition-26-2)：数据包 `107.1`，资源包 `88.0`。
- [Minecraft Java 1.21.6 release notes](https://feedback.minecraft.net/hc/en-us/articles/37432144057997-Minecraft-Java-Edition-1-21-6-Chase-the-Skies)：原生 dialogs、Quick Actions、`multi_action` 和玩家命令 action 结构。
- [Minecraft Java 1.21.9 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-9)：现代 `min_format` / `max_format` 与小版本声明。

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
| `server/config/` | 私有部署配置；公开仓库只提供各插件 `src/main/resources/config.yml` 与 `examples/en_us/` 文本覆盖示例 |
| `server/community-plugins.lock.json` | 第三方插件锁；编译期清单真相源是 `scripts/fetch-deps.sh` |

其余被引用的 `server/` 路径（`server/runtime/`、`server/cache/`、`server/backups/`、
`server/secrets/`、`server/.env`、`server/deploy-candidates/`、`server/vendor/`）
已在 `.gitignore` 里逐条登记，门禁据此放行。

## 相关文档

- `docs/PLUGIN_INTEGRATION.md` —— 四层菜单入口的分工
- `docs/RESOURCE_PACK_QA.md` —— 资源包发布链路与 `verify-installation.sh` 的使用时机
- `docs/GAME_DESIGN.md` —— 八个快捷动作的玩家可见语义
