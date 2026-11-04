# 希维塔大陆 · SivitaHub Survival RPG

**简体中文** · [English](README.en.md) · [English developer documentation](docs/en/README.md)

**原版生存 × RPG 构筑 × 玩家经济 × 持续社区。** 希维塔将 Minecraft 的探索、采集与建造，连接到职业成长、玩家锻造装备、合作副本、托管市场和轻主线。本仓库包含 25 个自研 Paper 插件、资源包工作区、导航数据包及工程工具。

目标环境为 **Minecraft Java Edition 26.2、Paper、JDK 25**，依赖版本以 [gradle.properties](gradle.properties) 为准。项目由社区团队独立运营，与 Mojang/Microsoft 无隶属或背书关系。

## 从这里开始

| 目标 | 入口 |
|---|---|
| 了解玩法与设计 | [玩法规格](docs/GAME_DESIGN.md)、[经济与长期成长](docs/ECONOMY.md)、[任务与剧情](docs/STORY.md) |
| 查看实际项目完善结果 | [本轮机制完善与证据](docs/audits/MECHANISM_COMPLETION_REVIEW.md)、[全部 25 插件矩阵](docs/audits/FULL_PLUGIN_GAMEPLAY_REVIEW.md)、[成长语义复核](docs/audits/PROGRESSION_SEMANTICS_AUDIT.md) |
| 供英文开发者接手 | [Developer handbook](docs/en/DEVELOPER_HANDBOOK.md)、[Gameplay specification](docs/en/GAMEPLAY_SPECIFICATION.md)、[Architecture](docs/en/ARCHITECTURE.md)、[术语表](docs/en/GLOSSARY.md) |
| 构建和参与开发 | [开发上手](docs/en/GETTING_STARTED.md)、[架构说明](docs/en/ARCHITECTURE.md)、[贡献规范](docs/en/CONTRIBUTING.md) |
| 检查英文覆盖 | [国际化审计](docs/audits/LOCALIZATION_REVIEW.md)、[英文配置预设](examples/en_us/README.md) |
| 准备授权部署 | [运维指南](docs/en/OPERATIONS.md)、[真实运行验收](docs/RUNTIME_SMOKE.md)、[并发与压力门槛](docs/PERFORMANCE.md) |

## 仓库边界与当前状态

这是**插件源码仓库**。实际运营所需的 `server/` 部署树、官网、生产世界、凭据和机器专用脚本不在本仓库中。历史文档描述过更完整的私有部署环境，其首启或局域网验收记录不能直接作为本次源码的运行证据；克隆本仓库也不会得到一套可直接开服的完整发行包。

当前源码已有以下核心链路：

- 六职业、五维属性、三次晋升与职业分支；每段本地等级 1–30，有效等级 1–120，后续章节由服务器状态控制开放。
- 原版胚材锻造、签名、绑定与解绑、槽揭示、精炼、突破、分解和传承；原版生存活动接入装备成长。
- 动态怪物、血月、连猎，以及 1–4 人共享的三族六副本；技能、法力、体力和统一战斗结算。
- Gold / Merit / Starlight 三类游戏财富、事务账本、原版物资和自制装备托管市场、委托与可恢复邮箱。
- 主城、服务 NPC、可选故事引导、伙伴、外观与只读 AI 建议；大量菜单、帮助和错误消息已有中英文资源。

“源码存在”“功能默认启用”“测试通过”“真实多人环境可用”是不同状态。经济仿真已实现 15 场景的离线模型，结果仍需真实服数据校准；多服会话与 Outbox 的源实现、事务证据和实际背包/PDC 迁移是分别验收的边界。固定 Citizens 依赖已核验取得；当前集成树已构建全部 25 个插件，本地通过 1,419 项插件测试与 47 项迁移工具测试，另有 4 项必须连接真实 PostgreSQL 的条件测试。完整机制分支的 CI 已补跑 PostgreSQL 结算、会话、邮件与迁移，并通过真实 Paper 启动门禁。提交号、完整运行记录和剩余验收见[本轮机制审阅](docs/audits/MECHANISM_COMPLETION_REVIEW.md)及[英文路线图](docs/en/ROADMAP.md)。

英文版面向开发者，涵盖各插件职责、玩法闭环、API 与数据权威、线程约束、迁移、恢复、内容接线和验证。玩家语言选择属于独立的可选功能；开发版打包默认保留现有玩家语言设置。构建命令见[开发上手](docs/en/GETTING_STARTED.md)和[资源说明](resource-pack/README.md)。资源包、15 套模型和四槽 HUD 均有可复现构建与引用检查；实际客户端效果仍需验收。

```text
plugins/        25 个独立 Gradle 插件模块
resource-pack/  资源包、模型、纹理与检查工具
datapacks/      导航及快捷操作数据包
scripts/        依赖、语言、源码、资源与运行检查
tools/          数据迁移、经济仿真和压力测试工具
libs/vendor/    固定版本的第三方产物及来源说明
docs/           设计、审计、规格与英文使用文档
examples/en_us/ 可选英文服务端文本配置预设
```

## 构建与检查

准备 JDK 25、Git、Python 3 和 POSIX shell。Windows 可在 Git Bash / WSL 中运行 shell 脚本，并在对应环境使用 `gradlew.bat` 或 `./gradlew`。

```bash
git clone https://github.com/Maggotxy/sivitahub-plugins.git
cd sivitahub-plugins

# 替换成你实际安装的 JDK 25 路径。
export SIVITA_JAVA_HOME=/absolute/path/to/jdk-25

sh scripts/check-file-deps.sh
sh scripts/fetch-deps.sh
sh scripts/fetch-deps.sh --check
python3 scripts/check-smoke-lock.py
./gradlew --version
./gradlew test -PexcludeTags=env
./gradlew assemble
```

`fetch-deps.sh` 下载并校验五个公开编译依赖；四个编译用 vendor 文件另由仓库提供，完整运行锁含五个本地产物，哈希由 `check-smoke-lock.py` 校验。依赖下载需要能够访问对应官方源，不能用相近版本替代校验失败的文件。`--check` 只检查，不下载、不删除或改写现有文件。

`-PexcludeTags=env` 排除依赖私有部署环境的测试；它不代表真实 Paper、PostgreSQL 故障恢复或全部环境测试通过。完整测试使用 `./gradlew test`，前提见[开发上手](docs/en/GETTING_STARTED.md)。插件 JAR 位于 `plugins/<模块>/build/libs/`，不要将 `-sources.jar` 当运行插件部署。

Gradle 子项目路径包含 `plugins:`，例如 `./gradlew :plugins:rpg-api:test`。`stagePlugins` / `stageRelease` 需要私有部署脚本，不属于本仓库独立构建流程。Wrapper 优先使用明确设置的 `SIVITA_JAVA_HOME`，其次是私有部署树里的随服 Java，缺少二者时按标准 `JAVA_HOME` / `PATH` 选择。

### 语言资源检查

```bash
python3 -m pip install PyYAML==6.0.3
python3 scripts/check-locales.py
python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v
```

语言检查覆盖六组双语资源的重复键、键集合、值类型与占位符一致性。它不等于真人界面验收，也不证明第三方插件和全部共享实体文字均已翻译。

## 玩家常用入口

在已部署的服务器完成认证后使用：

| 任务 | 命令 |
|---|---|
| 总菜单、帮助、任务中心 | `/menu`、`/rpghelp [topic]`、`/tasks` |
| 切换语言 | `/language en_us`、`/language zh_cn`，切换后重新打开菜单 |
| 角色与职业 | `/rpg`、`/class` |
| 锻造与成长 | `/forge`、`/imprint`、`/refine`、`/limitbreak` |
| 装备退出与交易准备 | `/salvage`、`/legacy`、`/unbind`，操作前核对确认页与损耗 |
| 技能 | `/skills`、`/skills learn <id>` |
| 副本与队伍 | `/dungeon enter <id>`、`/dungeon party`、`/dungeon leave` |
| 市场与经济 | `/market`、`/economy`、`/store` |
| 世界与引导 | `/hub`、`/wild`、`/story` |
| 外观 | `/aura`、`/trail` |

完整解释、英文术语、参数和使用限制见 [Player guide](docs/en/PLAYER_GUIDE.md)。命令、持久化 ID、权限节点和占位符标识不能随翻译更名。

## 开发与开放门槛

修改前阅读 [AGENTS.md](AGENTS.md) 与[玩法经济宪法](docs/GAME_ECONOMY_CONSTITUTION.md)；国际贡献者可使用[英文宪法](docs/en/GAME_ECONOMY_CONSTITUTION.md)。保留单一权威状态、账本幂等、单一市场和现实付费隔离，不随意新增货币、转数或交易体系。

采用 `[D] Design / [S] Source / [T] Test / [M] Simulation / [R] Runtime / [H] Human` 六级证据。正式开放仍需要多人竞态与崩溃恢复、数据库故障演练、备份恢复、经济观察、中英文客户端完整路径，以及按档位执行的压力测试。人数目标不能当作已经验证的承载能力。

## 使用权限

本仓库沿用 [INTERNAL_USE.md](INTERNAL_USE.md)：原始代码、玩法与资产属于服主内部材料，仓库公开可见不授予复制发行、再许可或运营衍生公开服务器的权限。第三方项目保留其各自版权、许可与通知。英文文档不改变这些权限。
