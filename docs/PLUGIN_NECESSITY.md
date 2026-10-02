# 插件必要性与系统一致性矩阵

核对日期：2026-08-10。结论先行：不再因为“社区里有插件”就安装插件，也不因为“原创”就重复实现成熟基础设施。每个插件必须至少满足一项独立价值，并且不能拥有第二份角色、装备、货币、任务或交易真相。

## 判定门槛

新增插件必须同时通过以下四项：

1. **独立领域**：有清晰状态、生命周期或可选依赖边界，不能只是几个命令的容器。
2. **无法由现有模块自然承接**：放进既有模块会造成错误依赖方向、敏感权限混用或无法单独降级。
3. **运行收益大于兼容成本**：必须有真实消费者；没有消费者的 API 桥、菜单框架、数据包库一律不预装。
4. **唯一真相不分叉**：第三方只能做基础设施、表现或内容编排，核心结算仍走 `RpgCore` 的事务和幂等流水。

## 社区插件最终结论

| 插件/候选 | 结论 | 必要性与边界 |
|---|---|---|
| LuckPerms | 保留 | 成熟权限基础设施；`RpgIntegrations` 只提供内存 context，不复制职业数据 |
| PlaceholderAPI | 保留 | Citizens、菜单和运维展示的兼容层；回调只读快照，不查数据库 |
| AuthMeReloaded | 保留 | `online-mode=false` 的登录安全边界；不拥有 RPG 档案 |
| SkinsRestorer | 保留 | 离线玩家皮肤缓存和应用；不拥有装备模型或付费外观 |
| WorldEdit / WorldGuard | 保留 | 建筑工具与区域保护成熟，原创它们没有收益；不参与副本 session 和奖励 |
| Chunky | 保留 | 持久原版世界预生成；不处理动态副本 |
| squaremap | 保留 | 官网低频公共地图；隐藏资源、玩家和动态副本不公开 |
| Citizens2 | 保留、限域 | 仅用于确实需要寻路、跟随、护送的少量演员；固定服务 NPC 继续用低开销原生 Mannequin |
| BetonQuest | 保留、限域 | 仅编排对白和章节标签；只能调用 `RpgStory` 白名单动作，不能直接发钱发物 |
| GriefPrevention | 保留、限域 | 成熟的原版生存领地与分级信任；仅 `world` 启用，不拥有 RPG 货币、任务或奖励，领地面积不能用金币买卖 |
| BetterModel | 保留、严格限域 | 成熟的开源 `.bbmodel` 运行时；仅作为稀有怪完整身体表现层。原实体仍是 AI、碰撞、伤害、掉落唯一真相；不允许头盔式局部替换，且受距离、视线和 64/20/0 负载预算约束 |
| EssentialsX Core | 保留、严格裁剪 | 成熟的消息、邮件、AFK、规则、查询、TPA 与管理基础。只装 Core；家园、返回、出生、随机传送、经济、商店、礼包、修理、昵称和世界命令关闭，避免第二套状态 |
| MyPet | 保留、严格限域 | LGPL 开源且持续维护的成熟宠物底座，提供捕获、AI、等级、技能树、存储和 GUI。仅开放四条 30 级无采集路线；`RpgPets` 收口命令并限制 PvP、主城/副本、敌对目标、非致命伤害、经验与统一金币复苏。原生商店、交易、拾取、挖矿、伐木、钓鱼和动物产物关闭。SimplePets 因只能提供外观跟随而移除 |
| QuickShop-Hikari | 不安装 | 项目成熟且 2026 仍维护，但其箱子商店、Vault 余额和本地商店状态会形成第二套玩家市场，绕过签名装备、托管邮箱、手续费和绑定规则 |
| Shopkeepers | 不安装 | 适合原生物品换物与无限库存 NPC；官方说明不处理虚拟货币，无法替代三财富事务、额度和黑心商人反套利 |
| Vault / VaultUnlocked | 暂不安装 | 抽象层本身不提供本项目需要的账本安全；当前没有消费者，开放通用写接口反而允许第三方绕过来源、额度和幂等键。出现经审计的只读消费者后再加只读 provider |
| 通用菜单框架 | 不安装 | 26.2 原生 Quick Actions、旅者罗盘、`/menu`、情境 NPC 与自研 Inventory GUI 已覆盖全部入口；再加 DSL 会产生第二套事件和热重载规则 |
| 通用面对面交易插件 | 不安装 | 普通日用品可原生交换，高价值物品必须走托管；第二套直交易会绕过费用、绑定、唯一物品索引、邮箱和审计 |
| MythicMobs | 当前不安装 | 5.13.0 免费版支持 26.2，但会引入第二套怪物模板、技能、生成与掉落真相；自研动态等级、主动精英词缀、Boss、掉落和性能降级已经共享同一状态。只有未来作为无结算权的表现适配层时再评估 |
| MMOItems / Oraxen | 不安装 | 会产生第二套装备/NBT 或模型真相，与 HMAC 装备和 26.2 原生 `item_model` 冲突 |
| PacketEvents | 不安装 | 2.13.0 已明确支持 26.2，但当前唯一消费者只是未向普通玩家开放的 MyPet 单独音量滑块；登录背包保护、登录前补全、正版绕过和 BetonQuest 数据包对话均未启用。为一个不可达选项增加约 5 MiB 的协议注入层仍不划算 |

调研依据：

- QuickShop-Hikari 仍以箱子玩家商店为核心并持续维护：https://github.com/QuickShop-Community/QuickShop-Hikari
- Shopkeepers 的管理/玩家商店边界及“不支持虚拟货币”说明：https://github.com/Shopkeepers/Shopkeepers
- VaultUnlocked 是兼容抽象而不是经济真相源：https://github.com/TheNewEconomy/VaultUnlocked
- Citizens2：https://github.com/CitizensDev/Citizens2
- BetonQuest：https://github.com/BetonQuest/BetonQuest
- Paper 26.2 Quick Actions 与 Dialog 注册表：https://docs.papermc.io/paper/dev/dialogs
- 原版 Quick Actions 标签行为：https://minecraft.wiki/w/Dialog_tag_(Java_Edition)
- MythicMobs 5.13.0 免费发行：https://hangar.papermc.io/Lumine/MythicMobs/versions/v5.13.0
- BetterModel：https://github.com/toxicity188/BetterModel
- PacketEvents 2.13.0（26.2 支持说明）：https://github.com/retrooper/packetevents/releases/tag/v2.13.0

## 自研模块是否真的需要独立

这些是同一 Gradle 多模块工程，不是 25 个独立微服务。模块边界用于阻止内部类互相穿透、控制可选依赖和明确故障范围；数据库仍只有一个真相源。

| 模块组 | 结论 | 不合并的原因 |
|---|---|---|
| `rpg-api` / `rpg-core` | 必须独立 | API 避免玩法模块访问 JDBC；Core 独占事务和迁移 |
| 职业、装备、战斗、怪物、技能、强化 | 保持纵切 | 各自有独立事件热路径和规则测试，但只经公开服务交换状态 |
| `rpg-dungeons` / `rpg-world` | 保持独立 | 动态实例生命周期与持久原版事件的清理、负载和奖励语义不同 |
| `rpg-market` / `rpg-economy` | 保持独立 | 前者负责物品托管和玩家交易，后者负责系统产出、委托与商店；余额仍由 Core 原子结算 |
| `rpg-commerce` | 必须独立 | OP 入账、累计支持、会员和星辉审计属于敏感运维边界，不与普通玩法命令混放 |
| `rpg-community` | 保持独立 | 社群成员、邀请、师徒契约、周建设和跨分片排行是独立持久领域；不另装公会插件 |
| `rpg-story` / `rpg-ai-npc` | 必须独立 | 前者是可审计的剧情写桥，后者是可完全关闭的只读网络能力；AI 绝不能获得写服务 |
| `rpg-hub` / `rpg-guide` | 保持独立 | 世界建筑/NPC 实体与玩家菜单/旅程状态生命周期不同 |
| `rpg-integrations` | 必须独立 | 隔离 LuckPerms、PlaceholderAPI 等可选类加载边界 |
| `rpg-performance` / `rpg-admin` | 必须独立 | 过载降级与运维诊断必须能在玩法模块异常时继续工作 |
| `rpg-cosmetics` | 保持独立 | 有独立全局粒子预算，可在压力时整模块降级且不影响战斗 |
| `rpg-sulfur` | 暂时保留 | 它封装 26.2 专用事件、独立签名密钥、每日提取和 `ForgeImprintService`；若未来不再扩展硫灵内容，应在一次迁移中并入 `rpg-items`，不会继续拆出同类小插件 |
| `rpg-essentials` | 必须独立 | 隔离 EssentialsX 可选 API，并在不修改上游的前提下统一阻断命令绕过、战斗 TPA 与副本跨界；它不拥有任何玩家玩法状态 |
| `rpg-pets` | 必须独立 | 隔离 MyPet API；捕获规则、四路线入口、幂等金币复苏、伤害/经验上限、菜单、世界清理和经济防刷属于独立可关闭领域，不让实体引擎接触 RPG 账本 |

新的玩法只有在拥有独立持久状态、独立降级边界或可选外部依赖时才能新建模块。单个新 Boss、商店页、NPC、技能或活动全部进入现有所有者，不新建插件。
