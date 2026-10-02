# NPC 架构与选型

核对日期：2026-08-08。最终方案不是把四类 NPC 都交给一个通用插件，而是共享同一种低开销外形、把状态交还给真实玩法所有者。

## 最终职责

| 类型 | 游戏内实例 | 入口 | 唯一状态所有者 |
|---|---|---|---|
| 引导 | 引路人、职业导师、藏书师、锻造师、精炼师、远征官、拓荒向导 | `GuideMenuService` 或已有玩家命令 | `RpgGuide`、职业/技能/装备/副本插件 |
| 交易 | 市集管事、功勋官 | 托管市场、资源委托、商店和财富兑换 | `RpgCore` 事务账本及市场/经济服务 |
| 任务 | 委托官·拾简 | `/menu` 的“任务与委托中心” | 新手旅程、经济、副本、世界事件各自的真实状态 |
| 剧情演员 | 巡路人·栖枝与玩家私有巡路回声 | Citizens 走位 + BetonQuest 分支对白 | `RpgStory` 并发/恢复/奖励桥；角色与金币仍归 `RpgCore` |
| AI | 闻见者·小栖 | 右键后一次聊天输入，或 `/ainpc ask <问题>` | 只读短期会话；没有玩法写状态 |

所有固定服务外形由 `RpgHub` 使用 Paper 26.2 原生 `Mannequin + TextDisplay` 创建。实体关闭 AI、重力、碰撞、拾取和伤害，持久化角色 ID；不寻路、不伪造在线玩家，也不为每个 NPC 启动调度器。一个共享任务每 10 tick 检查 11 个 Mannequin，只在 6 格内有可见玩家时转头；交互时挥动主手并播放一次本地音效。右键先显示 26.2 原生 Dialog，提供“办理事务 / 职责说明 / 当前建议 / 退出”，随后才调用真实菜单或命令。AI NPC 的 Dialog 直接包含 240 字符输入框，也保留聊天输入作为兼容路径。主城现有 11 个 Mannequin 和 11 个标签，squaremap 只展示这些公共地标。

标签与 Mannequin 以角色、实体种类、schema 和成对 UUID 共同标记；对应区块由插件票保持加载，每 100 tick 共享一次完整性检查，自动删除重复/孤儿标签并原子补回缺失的一对。停服和插件关闭时主动移除实体，异常崩溃留下的持久实体会在下次启动迁移清理。状态计数只统计完整配对，不再用“实体总数除以二”掩盖孤儿。Paper 的 `Display#setViewRange` 是相对跟踪距离倍率，不是方块数，因此配置使用 `view-distance-blocks: 18` 并转换为 `0.28125`；这让标签和 NPC 在近距离共同出现，杜绝标签跨主城投影而 NPC 已被客户端裁剪。Mannequin 原生说明层使用 `null` 明确隐藏，仅保留一层紧凑的单行 `TextDisplay` 身份牌，避免“右键交谈”、副标题和自定义说明叠字。

### Paper 26.2 与 WorldGuard 的生成边界

Mannequin 在 Paper 26.2 中属于 `LivingEntity`。WorldGuard 主城区域若设置通用
`mob-spawning: deny`，会在 `CreatureSpawnEvent` 阶段取消插件生成的 Mannequin，
但不会取消随后生成的 `TextDisplay`；外观上就会变成“空悬浮字”，完整性任务还会
每五秒反复重建标签。主城区域因此不再使用该 blanket flag，而由 `RpgHub` 的
`HubProtectionListener` 拦截普通生物生成，并明确放行带自研 PDC 标记的服务 NPC
以及 Citizens 的 `NPC` 实体。启动顺序必须保持为：先更新 WorldGuard 区域，再创建
服务 NPC。Paper 26.2 的 spawn consumer 会早于 `CreatureSpawnEvent` 写入 PDC，因此
监听器能在事件阶段可靠识别自研实体。

每次部署后的最低运行态验收是：日志同时出现 `WorldGuard hub region active=true`、
`Hub NPCs active=11/11` 和 `Done (...)`，随后至少二十秒内不得再出现
`Hub NPC integrity repaired` 或 `Hub NPC health diagnostic`；运行区域文件也不得
重新出现主城 `mob-spawning` flag。

任务中心不是第二个任务框架。它汇总八步新手旅程、系统旅程、七类原版资源委托、普通/隐藏副本、血月与连猎、原版共鸣目标和持久化邮箱。玩家点击后仍进入原来的命令或服务；领取条件、体力、门票、额度、掉落和幂等奖励都只结算一次。固定服务 NPC 仍为 11 名 Mannequin；巡路人是额外的 Citizens 演员，只为确实需要分支对白和移动演出的序章服务。

固定 NPC 的首页采用一致的渐进式结构：角色职责只用一句话，档案使用已翻译职业名，然后突出唯一一个“继续当前目标”按钮，并在正文写清地点与作用。NPC 自己的完整服务是次要按钮，职责规则放在三级说明；若当前目标正好由该 NPC 办理，则合并重复按钮。这样玩家在任何柜台都能找回主线，同时不会把所有系统一次塞进同一页。

`/rpghelp`、旅者罗盘和 NPC 目录共用同一份角色路由。所有 NPC 目标的主按钮都只开启步行导航：若玩家不在主城，先按战斗、副本与回城规则安全返回，再设置罗盘目标、距离 BossBar 与受预算控制的低频方向粒子；玩家抵达后自行右键 NPC 交谈。目录左右键不再分裂成“瞬移拜访”和“步行”两套语义，也不会自动打开远处 NPC 的对话。需要实战完成的槽位揭示不伪造 NPC 目的地，帮助页会直接打开等级适配怪物指南。这样既不绕过空间和战斗规则，也让主城地标真正承担认路作用。

悬浮字只承担“他是谁”的辨认作用，不承担职责说明或动态任务状态。每名固定 NPC 严格对应一个 Mannequin 和一个单行 TextDisplay，身份牌会折叠换行、过滤控制字符并限制为 24 个 Unicode 字符，18 格外不渲染；配置中的副标题只供 squaremap 地标提示使用，职责、任务进度与下一步留在个人 Dialog 和旅者罗盘中，从根源上避免多人状态、长句换行和主城标签重叠。

## AI NPC 的安全与性能边界

`rpg-ai-npc` 是独立插件目录，支持 OpenAI-compatible `Responses` 和 `Chat Completions`，但当前 LAN 基线显式关闭远程提供者。远程服务不是启动前提；未配置、关闭、超时、HTTP 错误、队列满或熔断时，`knowledge.yml` 的中英文知识立即回答。

- 默认每玩家 8 秒冷却，同一玩家只允许 1 个在途问题。
- 全服最多 2 个远程请求，等待队列上限 16；拒绝策略不会让 Paper 主线程代跑网络请求。
- 连接超时 4 秒、总请求超时 12 秒；连续 3 次失败后熔断 60 秒。
- HTTP 在专用 daemon 线程执行，玩家消息和 Bukkit 对象只在主线程读取/发送。
- 仅发送语言、职业/转数/等级/体力/未分配点数等只读快照，不发送玩家名、坐标、余额、背包、IP 或管理员数据。
- 最多保留 3 轮、10 分钟的内存上下文；退出即清除，不写数据库。审计日志只记录 UUID、模式、字符数与耗时，不记录问题正文。
- 模型没有工具定义，也拿不到 Bukkit 服务引用；输出始终作为纯文本显示，不能变成命令、富文本点击、奖励、价格或任务结果。
- 系统提示将玩家输入视为不可信文本，禁止编造实时价格、掉率、坐标、隐藏解法和管理员信息。

生产环境把 `SIVITA_AI_API_KEY`、`SIVITA_AI_BASE_URL`、`SIVITA_AI_MODEL`、`SIVITA_AI_PROTOCOL` 放入权限受限的 systemd EnvironmentFile。开发机可以设置 `SIVITA_AI_USE_CODEX_AUTH=true`，启动脚本只在进程内读取现有受保护凭据，不复制或打印令牌。当前 LAN 基线没有把不稳定的远程 Sub2API 当作可用能力，实际回答依赖本地知识库。即使远程接口完全停用，NPC、任务和服务器 TPS 都不受影响。

玩家命令：

```text
/ainpc                         # 开始一次聊天输入
/ainpc ask <问题>              # 直接提问
/ainpc leave                   # 清除当前短期对话
/ainpc status                  # 查看远程/本地与熔断状态
/ainpc reload                  # OP 重载配置
```

## 调研结论

| 方案 | 现场核验 | 结论 |
|---|---|---|
| Citizens2 | OSL-3.0，官方 Jenkins `2.0.43-b4231` 标注 26.2，2026-07-29 仍有提交 | 已锁定采用；只承载巡路人、玩家私有回声和后续少量护送/巡逻，不迁移固定柜台，不拥有玩法状态 |
| FancyNpcs | MIT，但 GitHub 源仓已归档 | 不把源码与发行链分离的项目作为长期核心依赖 |
| BetonQuest | GPL-3.0，稳定 v3.1.0 明确支持 26.2，40+ 集成 | 已锁定采用；`sivita_story` 只编排中英文分支对白与章节标签，脚本仅能调用三项 `RpgStory` 白名单组件，不能直接发核心物品或改金币 |
| Typewriter | Web 编辑和演出能力强，但使用自定义限制性许可 | 不属于可自由二改的开源基线，不采用 |
| NPCSpeak | GPL-3.0，v1.0（2026-07-06），发布资产现场仅 1 次下载 | 仍属早期；其异步回主线程值得参考，不能替代限流、熔断和写权限隔离 |
| LLM-craft | GPL-3.0，v0.4.2、4 stars，依赖 Citizens | 项目体量和限额模型不适合作为主服关键依赖 |

参考：

- https://github.com/CitizensDev/Citizens2
- https://github.com/FancyMcPlugins/FancyNpcs
- https://github.com/BetonQuest/BetonQuest
- https://github.com/gabber235/Typewriter
- https://github.com/l299l/NPCSpeak
- https://github.com/yro7/LLM-craft
