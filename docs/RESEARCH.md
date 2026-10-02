# 联网调研记录

调研日期为 2026-08-06，结论以项目仓库、接口文档和许可文件优先。

- Paper 下载接口在 2026-08-07 最新返回 26.2 稳定 build 103；运行 JAR 与构建 API 均已更新到 build 103。下载脚本每次会选择最新 `STABLE`、核对接口提供的 SHA-256，因此之后的 bootstrap 仍会跟进新稳定构建。
- Paper 26.2 开发文档给出的依赖格式为 `io.papermc.paper:paper-api:26.2.build.+`，工具链要求 Java 25，`plugin.yml` 的有效 `api-version` 包含 `26.2`。
- Paper 官方将 Folia描述为区域化多线程 fork；插件必须显式声明支持并使用全局、区域、异步或实体调度器。本项目首发不声称兼容。
- Paper 调度文档明确指出，世界状态访问不能放入异步任务，数据库、文件和网络等耗时工作也不应占用主线程。代码审计据此将 Player/Entity/Inventory/PDC 操作固定在同步回调，只把 UUID 和不可变值送入 JDBC 线程。
- PlaceholderAPI 官方 Wiki 推荐把依赖自身插件的 expansion 做成内部扩展，手动 `register()`，并让 `persist()` 返回 `true`。`rpg-integrations` 按该方式注册，而且占位符请求只读并发快照，避免计分板高频刷新放大数据库请求。
- LuckPerms 开发者文档将线程安全、阻塞操作和 `CompletableFuture` 单独列为 API 约束。本项目注册只读 Context Calculator，职业与转数来自定时刷新的内存快照；权限计算不访问 JDBC，也不永久修改用户组。
- HikariCP 的连接池调优文档强调连接数应从小池开始并按实测调整，过多连接会增加数据库争用。默认采用 6 连接、6 个 JDBC 工作线程和 256 个有界排队项；执行器饱和时快速失败，不使用会让 JDBC 落到 Paper 主线程的 caller-runs 策略。
- Paper 26.2 的实际配置类确认全局与世界配置版本都是 31；项目只写入已核对字段，不整包复制社区“优化配置”。`YouHaveTrouble/minecraft-optimization`、Paper Chan 与同版本社区模板只用于选择保守起点，再由本服 RPG 生物、副本和红石压测决定是否保留。
- Paper 26.2 提供专用 `SulfurCubeSwallowItemEvent`，事件直接给出玩家、旧内容和新吞入物品；`rpg-sulfur` 据此记录一次实体调谐，不轮询生物。剪取沿用 `PlayerShearEntityEvent` 的掉落接口，锻纹倾向再通过稳定服务接口交给装备插件。硫磺方块生物、朱砂、强效硫磺和间歇泉的行为同时对照 Minecraft Wiki 26.2 社区条目。
- Mineflayer 4.37.1 在 2026-05 仍持续维护，作为独立预发布压测客户端锁定使用；它不进入 Paper 运行时，也不替代真人菜单、经济和副本验收。
- Velocity modern forwarding 比 legacy forwarding 更安全，但官方明确指出它不能替代防火墙。单服 Alpha 不提前增加代理；多服时同时部署 modern forwarding 和网络隔离。
- 26.2 的 `version.json` 声明 Java 25、协议 776、资源包格式 88.0、数据包格式 107.1。25w31a 后资源包使用 `min_format` / `max_format`；锻造模型采用 `assets/originalrpg/items` 物品模型定义。
- 直接拆解 26.2 客户端原生定义后确认：弓使用 `condition + range_dispatch(use_duration)`，弩使用 `select(charge_type) + range_dispatch(crossbow/pull)`，三叉戟使用 `select(display_context) + condition(using_item)`，盾牌使用 `condition(using_item)`。本服的原创模型已按相同状态机重建，不再用一张静态图覆盖拉弓、蓄力、投掷和格挡姿态。
- 社区配置不只根据 Wiki 猜测：AuthMe 6.0.0、LuckPerms 5.5、WorldGuard 7.0.18 和 squaremap 1.3.15 的精确版本源码已核对，Chunky 与 PlaceholderAPI 对照 JAR 内默认配置。WorldGuard 全局配置会作为每世界配置的父值；7.0.18 已主动删除的 `protection.disable-obsidian-generators` 也已从模板移除，避免产生“写了却不生效”的假安全。
- SkinsRestorer 15.12.5 的 Modrinth 元数据在 2026-07-21 更新，GPL-3.0-only，明确列出 Paper 26.2 且无硬依赖；真实启动确认使用 Paper join listener。默认缓存 15 分钟会放大 Mojang 查询，已提高到 1440 分钟；默认玩家父权限范围过大，`RpgIntegrations` 通过 LuckPerms 只允许本人 set/update/clear，并拒绝 URL、代改他人、GUI、头颅与管理权限。
- BetonQuest 3.1 的 conversation `first` 会按顺序选出首个条件成立的节点，且 1.21.4+ 默认支持原生 menu 样式和逐字段翻译；本服据此让同一剧情 NPC 读取共享旅程状态并给出下一步。其 objectives 虽能监听击杀/方块，但动态怪、签名装备、副本和奖励已经有权威服务，因此不重复建立第二套计数。
- Citizens API 明确建议导航目标只设置一次、目标建立后使用 local navigation parameters，并以独立监听器处理高频事件；`RpgStory` 的私有护送采用有限路点和局部参数，固定服务 NPC 继续使用无寻路的原生 Mannequin。
- Sub2API 同步生图和编辑分别使用 `/v1/images/generations` 与 `/v1/images/edits`；长任务还提供 `/async` 端点和任务轮询，但异步默认关闭且必须配置 S3/R2 对象存储。
- Sub2API 的图片路由有独立分组权限。API Key 所属 OpenAI/Grok 分组没有启用 `allow_image_generation` 时，即使 `/models` 返回 `gpt-image-2`，调用仍会返回 `403 Image generation is not enabled for this group`。权限开启后，本机在 2026-08-06 完成了一次小样、24 项批量请求及盾牌/普通技能书两项补充请求；`/v1/images/generations` 已确认可用。
- 复合分组应把 `gpt-image-*` 以 `endpoint=images` 路由到 OpenAI 具体分组；路由只决定平台和模型，不会自动创建图片权限、价格或可用上游账号。
- 怪物完整模型在 2026-08-09 复核了闭源收费 ModelEngine、开源 FreeMinecraftModels、BetterModel、原生 Display Entity 与 AnimatedJava 导出路线。最终采用 MIT 的 BetterModel 3.3.0：它明确支持 Paper 26.2、直接读取 Blockbench `.bbmodel`、通过 item-display 数据包生成骨骼模型，并提供视线/距离裁剪和资源包生成。自研 `RpgMobs` 仍是怪物 AI、属性、词缀、伤害和掉落唯一真相，BetterModel 仅承担可关闭的视觉外壳。
- 首个“血月共鸣尸”采用生图概念稿、定向编辑、人工审核、可复现 Blockbench 模型生成四段流程。第一稿因标准立方体头和零件过多被拒绝；第二稿统一头颈与躯干后，再在工程模型中改成分段上颅、低矮断冠和前伸下颌。最终限制为 11 个可见体块、7 根骨骼、`idle/walk/spawn` 三个必要动画和 64×64 纹理，不使用漂浮挂件、细链、披风或逐 tick 装饰。

## Minecraft 素材生产项目调研

本项目没有直接采用“输入一句话就覆盖完整资源包”的项目。成熟工具与 AI 小项目的边界差异很大，当前组合方案是：GPT Image 生成独立绿幕原稿，自研脚本做透明化、主体裁切、32×32 像素化、24 色映射和覆盖率校验，Blockbench 用于后续人工制作 3D 模型，资源包构建脚本负责模型引用与 ZIP 校验。

- **Blockbench**：GPL-3.0，持续维护，GitHub 约 5.7k stars，原生支持 Minecraft Java/Bedrock 模型和像素贴图；其 README 明确说明使用它制作的资产归创作者所有。适合作为后续武器、家具、Boss 模型的人工修型入口，建议采用，不需要把它嵌入服务端。
- **PackSquash**：AGPL-3.0，持续维护，GitHub 约 845 stars。提供 PNG 量化、JSON 校验与压缩、ZIP 优化和增量复用。可作为资源包变大后的可选发布优化器，但在确认 26.2 格式 88 和现代物品模型定义完整兼容前，不替换当前可复现的 `zip -X` 构建。
- **Oraxen**：功能成熟且支持到 26.2，但仓库采用自定义 Oraxen License，明确禁止分发原版、部分或修改版，不是 OSI 开源许可。未把它作为二开基础；当前自研物品模型和资源包发布链已经覆盖所需纵切，也避免引入第二套物品身份体系。
- **TextureMaker**：GPL-3.0，基于 Stable Diffusion WebUI + Minecraft LoRA，采用绿幕去除思路；仓库自 2023 年后缺乏活跃维护且明确只支持 2D 物品。仅参考了绿幕工作流，不引入其运行时和旧模型依赖。
- **AIPackGenerator**：Craiyon 批量生成方案，仓库体量小、最后活跃于 2023 年且未提供清晰许可。其“按原版文件名批量重画整个包”会造成风格和质量不可控，不采用。
- **Jhon-crypt/minecraft-ai**：README 声称 GAN/Transformer 同时生成 16×16 贴图和 JSON 模型，但仓库只有少量关注，示例仍保留 `yourusername` 占位符且没有有效许可证。不能作为可验证的生产依赖。

本轮的 33 张正式素材全部通过尺寸、透明角、颜色数、主体覆盖率与模型引用检查：26 张基础物品，加上三段原创拉弓帧、独立弩、独立三叉戟、硫灵锻纹和传承蓝图。资源包保留大图原稿与 32×32 最终图分层，后续使用 `/v1/images/edits` 只修改单项候选，只有显式 `--apply` 才进入正式包。审核总览图从正式纹理目录自动生成，不再使用可能过期的手工拼图。

主要来源：

- https://papermc.io/downloads/paper
- https://fill.papermc.io/v3/projects/paper/versions/26.2/builds
- https://papermc.io/news/26-2
- https://minecraft.wiki/w/Java_Edition_26.2
- https://jd.papermc.io/paper/26.2/io/papermc/paper/event/entity/SulfurCubeSwallowItemEvent.html
- https://docs.papermc.io/paper/dev/data-component-api/
- https://docs.papermc.io/paper/dev/project-setup
- https://docs.papermc.io/paper/dev/plugin-yml
- https://docs.papermc.io/paper/dev/folia-support
- https://docs.papermc.io/paper/dev/scheduler
- https://docs.papermc.io/velocity/player-information-forwarding
- https://docs.papermc.io/velocity/security
- https://wiki.placeholderapi.com/developers/creating-a-placeholderexpansion
- https://luckperms.net/wiki/Developer-API
- https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
- https://github.com/YouHaveTrouble/minecraft-optimization
- https://github.com/PrismarineJS/mineflayer
- https://betonquest.org/3.1/Documentation/Advanced/Conversations
- https://betonquest.org/3.1/Documentation/Basics/Scripting-Elements
- https://betonquest.org/3.1/Documentation/Reference/Integrations-List/NPCs/Citizens
- https://wiki.citizensnpcs.co/API
- https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
- https://minecraft.wiki/w/Pack_format
- https://minecraft.wiki/w/Items_model_definition
- https://github.com/Wei-Shaw/sub2api
- https://github.com/Wei-Shaw/sub2api/blob/main/docs/ASYNC_IMAGE_TASKS.md
- https://github.com/Wei-Shaw/sub2api/blob/main/docs/COMPOSITE_GROUPS.md
- https://github.com/Wei-Shaw/sub2api/issues/1987
- https://github.com/JannisX11/blockbench
- https://github.com/ComunidadAylas/PackSquash
- https://github.com/oraxen/oraxen
- https://github.com/LyFl0w/TextureMaker
- https://github.com/DWF967/AIPackGenerator
- https://github.com/Jhon-crypt/minecraft-ai
