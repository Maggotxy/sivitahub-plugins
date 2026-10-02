# 开源与第三方选型

核对日期：2026-08-10。自动安装项同时锁定下载 URL 和 SHA-512，位于 `server/community-plugins.lock.json`。

| 项目 | 许可 | 26.2 状态 | 决策 |
|---|---|---|---|
| Paper | GPL 系列/上游多许可 | 官方当前稳定构建 | 服务端核心，不魔改核心 |
| LuckPerms | MIT | Modrinth 有 26.2 构建 | 直接使用，负责权限 |
| PlaceholderAPI | GPL-3.0 | 2.12.3 标注 26.2 | 直接使用；`RpgIntegrations` 已注册 11 个只读快照占位符 |
| WorldEdit | GPL-3.0 | 7.4.4 标注 26.2 | 直接使用，副本地图制作工具 |
| WorldGuard | LGPL-3.0 | 7.0.18 标注 26.2 | 直接使用；`RpgHub` 通过公开 API 持久化主城多边形保护区 |
| Chunky | GPL-3.0 | 1.5.3 标注 26.2 | 直接使用，预生成原版世界 |
| AuthMeReloaded | GPL-3.0-only | 6.0.0 Paper 构建 | 离线模式登录保护；首启后实测注册、登录、验证码和会话限制 |
| squaremap | MIT | 1.3.15 有 26.2 Paper 构建 | 渲染主城与三个持久原版世界；`RpgHub` 通过公开 API 登记安全区和 11 个公共设施地标，动态副本默认禁用 |
| SkinsRestorer | GPL-3.0-only | 15.12.5 明确支持 26.2，2026-07-21 更新 | 直接使用。离线玩家可设置/更新/清除本人名称皮肤；24 小时缓存、Paper join listener 和资源包重应用已启用，URL/他人/推荐 GUI/管理权限默认拒绝 |
| Paper Mannequin + TextDisplay + Dialog | Paper 26.2 原生 API | 26.2 稳定 API 已提供（Dialog 仍标注 experimental） | 11 名固定功能 NPC 的最终外壳和交互层；共享低频注视、无寻路 AI、假玩家包、额外数据库或第三方升级面 |
| Citizens2 | OSL-3.0 | 官方 Jenkins 2.0.43-b4231 标注 26.2，2026-07-29 仍活跃 | 已采用并锁定 SHA-512；作为少量巡路、护送、寻路与剧情走位的演员层，不迁移固定服务台，也不拥有任务状态 |
| FancyNpcs | MIT | GitHub 源码仓库已归档，发行链仍有二进制更新 | 不预装。源码与当前发行链分离，原生 Mannequin 已满足外形、装备、交互和标签 |
| CoreProtect | Artistic-2.0 | 26.2 兼容基础已合入但公开稳定免费发行状态仍不统一 | 暂不自动装；不能用旧版冒充兼容。公开测试前重新核验稳定社区构建，否则以世界快照、市场/管理审计和备份恢复覆盖 |
| BetonQuest | GPL-3.0 | 稳定 v3.1.0（2026-07-18）明确支持 26.2，40+ 集成 | 已采用并锁定 SHA-512；`sivita_story` 负责可编辑的中英文分支内容，经 `RpgStory` 白名单桥调用自研服务，不直接发核心奖励或持有职业/货币/副本完成状态 |
| GriefPrevention | GPL-3.0 | 16.18.7（2026-03-05）在真实 Paper 26.2 启动验证通过 | 已采用并锁定 SHA-512；仅保护原版主世界玩家领地，关闭面积买卖、自动箱子圈地、PvP 与攻城，关键玩家反馈默认汉化 |
| BetterModel | MIT | 3.3.0 明确支持 Paper 26.2，实服识别 NMS V26_R2 | 已采用并锁定 SHA-512；只渲染经审核的完整 `.bbmodel` 外壳，不持有 AI、碰撞、伤害或掉落。启用视线/48 格裁剪、24 包合并、严格加载，并由 `RpgMobs` 在 NORMAL/PRESSURE/CRITICAL 下限制为 64/20/0 个 |
| EssentialsX Core | GPL-3.0 | `2.x` 分支提交 `382de4f` 已含 26.2 适配，本机按该提交构建并在 Java 25/Paper 26.2 验证 | 采用未修改上游 Core；不装 Spawn/Chat/Protect/AntiBuild。`RpgEssentials` 禁止冲突命令、命名空间绕过、战斗 TPA 和副本跨界；版本、提交与 SHA-512 固定在锁文件 |
| MyPet | LGPL-3.0-or-later | 4.0.0 标签、提交 `b05bea1` 已在 Paper 26.2/Java 25 与 Citizens、BetterModel、GriefPrevention、WorldGuard、PlaceholderAPI 组合启动验证 | 替换仅有外观跟随的 SimplePets。采用捕获、真实实体 AI、等级、技能树、背包、存储和 GUI；`RpgPets` 掌管中文入口、敌怪限定、非致命伤害、统一金币复苏和世界规则。原生商店/交易、自动拾取、挖矿、伐木、钓鱼及生物产物全部关闭 |
| GrimAC | GPL-3.0 | 开源 2.x 声明支持 26.2，异步预测 | 公开测试前的候选，不直接首启：职业位移、击退、飞行和副本技能需先做豁免矩阵与误报压测 |
| Typewriter | 自定义限制性许可 | 代码活跃，公开 release 为 v0.8.0（2025-05） | 不采用；许可证禁止一般修改/分发且部分功能可收费，不符合开源二改基线 |
| NPCSpeak | GPL-3.0 | v1.0 发布于 2026-07-06，发布资产仅 1 次下载 | 只参考异步回主线程思路；成熟度、限流与奖励授权边界不足，不直接安装 |
| LLM-craft | GPL-3.0 | v0.4.2（2025-05），仓库 4 stars | 不直接安装；依赖 Citizens，限额状态和职责范围不满足本服并发/熔断基线 |
| Oraxen | 自定义 Oraxen License | release 说明支持 Java 25/26.x | 源码可见但禁止分发修改版，不作为“开源二改”基础 |
| MythicMobs/MMOItems | 非首选开源基础 | 版本/高级功能受发行模式限制 | 核心怪物、装备和技能自研，避免玩法被商业插件锁定 |

GPL 项目若直接复制或修改代码并分发，必须按对应许可证提供源码和版权/修改说明。第三方插件保持为独立上游 JAR；自研插件不再嵌入第三方运行时。任何 fork 都单独建目录、保留上游 Git 历史，并在开始修改前做许可证审查。

参考链接：

- Paper 下载与 API：https://papermc.io/downloads/paper
- Paper 26.2 公告：https://papermc.io/news/26-2
- Paper 项目设置：https://docs.papermc.io/paper/dev/project-setup
- Folia 支持说明：https://docs.papermc.io/paper/dev/folia-support
- LuckPerms：https://github.com/LuckPerms/LuckPerms
- PlaceholderAPI：https://github.com/PlaceholderAPI/PlaceholderAPI
- WorldEdit：https://github.com/EngineHub/WorldEdit
- WorldGuard：https://github.com/EngineHub/WorldGuard
- Chunky：https://github.com/pop4959/Chunky
- AuthMeReloaded：https://github.com/AuthMe/AuthMeReloaded
- squaremap：https://github.com/jpenilla/squaremap
- SkinsRestorer：https://github.com/SkinsRestorer/SkinsRestorer
- Paper Dialog API：https://docs.papermc.io/paper/dev/dialogs
- Paper Mannequin API：https://jd.papermc.io/paper/26.2/org/bukkit/entity/Mannequin.html
- Citizens2：https://github.com/CitizensDev/Citizens2
- FancyNpcs：https://github.com/FancyMcPlugins/FancyNpcs
- CoreProtect：https://github.com/PlayPro/CoreProtect
- BetonQuest：https://github.com/BetonQuest/BetonQuest
- GriefPrevention：https://github.com/GriefPrevention/GriefPrevention
- BetterModel：https://github.com/toxicity188/BetterModel
- Typewriter：https://github.com/gabber235/Typewriter
- NPCSpeak：https://github.com/l299l/NPCSpeak
- LLM-craft：https://github.com/yro7/LLM-craft
- Oraxen：https://github.com/oraxen/oraxen
- EssentialsX：https://github.com/EssentialsX/Essentials
- MyPet：https://github.com/MyPetORG/MyPet
- GrimAC：https://github.com/GrimAnticheat/Grim
