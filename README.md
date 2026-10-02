# 希维塔大陆 SivitaHub Minecraft 原创生存 RPG

`./gradlew`（Windows 为 `gradlew.bat`）默认使用 `server/runtime/java` 中随服安装的 Java 25，
不继承操作系统可能仍指向 Java 8 的默认值。只有明确设置 `SIVITA_JAVA_HOME` 时才覆盖该选择。

面向 Minecraft Java Edition `26.2` 的商业私有原创 RPG 服务端工程。采用 Paper、Java 25、Gradle Kotlin DSL，服务端和每个自研插件均为独立目录。本项目由社区团队独立运营，与 Mojang/Microsoft 无隶属或背书关系。

当前已完成可玩 Alpha 的主要纵切：事务化角色/金币/功勋/星辉/技能/市场账本，带日周额度的三财富单事务兑换，六职业、五维、转生分支和图形界面，原版胚材 0-4 级锻造、签名、绑定/解绑、槽揭示、精炼/突破、分解与传承蓝图，原版活动驱动装备经验和账号绑定生存共鸣精华，26.2 硫磺方块生物喂养/剪取/锻纹定向闭环，玩家放置矿区块级持久溯源，统一职业与装备战斗结算，动态怪物、九种有界普通怪特性、血月连猎，1-4 人共享的三族六副本、持久化体力预留、配置化守卫波次、普通/隐藏 Boss 与多族系地面技能，42 个可学习、装配和施放的技能，原版物资整组托管市场、七类资源委托、双周商人、活动额度、双路径绑定商品、中英文菜单/帮助、新手引导、系统驱动轻主线、断线可恢复的物品邮箱，以及 79 个物品定义、81 张顶层物品纹理（含罗盘帧为 113 张运行时纹理）、8 枚旅程图标、7 枚职业状态图标、42 枚技能模型/图标和三套完整血月怪物骨骼模型。

代码、运行配置、资源包和官网已形成可部署基线；Paper 26.2 已完成真实首启，现有 40 个插件（25 自研 + 15 社区）。BetterModel 3.3.0 只承担 3 种血月稀有怪、6 个副本 Boss 和 6 种副本波次怪的完整骨骼外壳，旧的单独方块头实现已经退出；原实体仍独占 AI、碰撞、伤害和掉落，并在压力状态按 64/20/0 个模型自动降级。独立安全主城已部署 11 名固定功能 NPC、1 名 Citizens 剧情演员和 `/hub`/`wild` 动线；squaremap 展示独立主城与三个持久原版维度的已生成地形，主世界、下界和末地当前边界均已严格核验为 `0 missing / 0 invalid`。原版生存使用 GriefPrevention 玩家领地。固定 NPC 使用原生对话框，BetonQuest 分支对白与自研 `RpgStory` 则只在确实需要护送、走位和章节状态时介入。同行者提供有界、只读、可降级的中英文状态建议。SkinsRestorer 已为离线登录玩家提供按名称设置、更新和清除本人皮肤的能力，URL、代改他人、推荐 GUI 与管理操作默认拒绝。官网、唯一 OWNER 管理后台、地图与游戏端口均可在局域网访问，公开页面不展示后台入口。地图不泄露玩家、资源点、隐藏 Boss 或副本实例。AuthMe/皮肤/护送/完整怪模真人交互、多人副本竞态、长期内容量、PostgreSQL 故障演练和 20/50/70/100 阶梯压力测试仍是正式开放前门槛，不应被单机启动或静态测试替代。

## 目录

```text
plugins/       25 个独立 Gradle 自研插件目录
server/        Paper 下载、第三方插件锁文件、运行配置和基础设施
resource-pack/ 原创资源包工作区
docs/          架构、完整玩法、开源选型、路线图和验收标准
```

## 构建

```bash
server/bin/bootstrap.sh
```

运行 Paper 26.2 需要 Java 25。开发环境默认使用本地 H2；公开测试和生产环境通过 `RPG_DATABASE_*` 环境变量连接 PostgreSQL。

## 已有测试命令

进入开放内测服并完成 AuthMe 注册/登录后使用：

- `/rpg`：角色、体力与金币
- `/class choose <职业>`、`/class attribute <属性>`
- `/forge`：装备胚 + 至少 8 个核心材料，可选催化剂
- `/imprint`：查看 26.2 硫灵锻纹配方、剪取方式与每日额度
- `/salvage`：永久分解主手自制装备，回收绑定锻造精华
- `/legacy [confirm]`：永久献祭已完全培养的稀有特性装备并支付金币，提取一次性传承蓝图
- `/unbind [confirm]`：按品质、槽位和突破等级支付金币，将绑定装备解锁为可托管交易
- `/refine`：精炼主手已揭示属性槽的自制装备
- `/limitbreak`、`/mutate <技能> [preview|confirm|restore]`：装备突破，以及先预览再确认/恢复的技能变异
- `/skills`、`/skills learn <id>`
- `/dungeon enter <id>`、`/dungeon leave`
- `/dungeon party create|invite|accept|leave|kick|disband|info`
- `/mobguide abilities`：查看九种普通怪特性的预警与躲避；OP 可看准敌怪使用 `/mobguide test <特性ID>` 逐项验收
- `/market sell|list|buy|cancel|claim`
- `/store`、`/store claim`
- `/economy status|vanilla|commissions|shop|merchant|exchange [路线 份数]`
- `/menu`、`/tasks`（统一任务与委托中心）、`/rpghelp [主题]`、`/language [zh_cn|en_us]`
- `/aura`：选择或解锁低开销共鸣外观；持续效果受 `/visuals` 与服务器负载自动降级
- `/trail`：组合鞘翅轨迹形状与共鸣配色；轻风免费，双翼和彗尾用可肝取星辉永久解锁
- `/hub`：返回主城；`/wild`：仅从主城发起安全荒野传送
- `/skin set <正版玩家名>`、`/skin update`、`/skin clear`：设置、刷新或清除自己的皮肤
- `/story status|next|start|claim|cancel|help`：查看或继续可选轻主线，并按真实职业、原版探索、锻造、装备成长和副本记录打开下一步
- `/rpgcommerce starlight <玩家> <grant|revoke> <数量> <唯一流水>`（仅管理员；旧 `points` 仅作兼容别名）
- `/rpgadmin economy|xp|energy ...`（仅管理员）

设计入口见 [玩法规格](docs/GAME_DESIGN.md)、[任务与剧情](docs/STORY.md)、[NPC 架构](docs/NPC.md)、[26.2 原版融合](docs/MINECRAFT_26_2.md)、[经济与长期成长](docs/ECONOMY.md)、[赛季终局纵切](docs/SEASON_ENDGAME.md)、[商店闭环审计](docs/SHOP_INTEGRITY.md)、[技术架构](docs/ARCHITECTURE.md)、[共鸣外观与活动场景](docs/COSMETICS.md)、[插件利用与视觉边界](docs/PLUGIN_INTEGRATION.md)、[旧 Particles 项目审计](docs/PARTICLES_AUDIT.md)、[开源选型](docs/OPEN_SOURCE.md)、[并发与压力门槛](docs/PERFORMANCE.md) 和 [实施路线图](docs/ROADMAP.md)。
