# SivitaHub 当前源码审计与完善顺序

> 本分支的实际交付、17 项 Java 与 29 项 Python 验证结果、完整构建阻塞及运行证据边界，见 [国际化交付与验证记录](INTERNATIONAL_READINESS_DELIVERY.md)。
审计日期：2026-11-03。源码基线：[`c83226ff87c8ecb868277d9ca5bce575b4684abc`](https://github.com/Maggotxy/sivitahub-plugins/tree/c83226ff87c8ecb868277d9ca5bce575b4684abc)。本报告区分基线缺陷、分支改动与尚未取得的运行证据；不以旧环境的成功日志代替当前验收。

## 1. 判断

当前工程已经具备完整 RPG 的多个核心业务链，主要工作不宜继续按“缺少某个服务类”来规划。优先事项是让现有功能能够被复现、让对外说明与当前源码一致、修复可定位的界面缺陷，以及补全经济仿真和多服能力的实际闭环。

基线包含 25 个自研插件模块，542 个生产 Java 文件、77,733 行生产 Java，以及 280 个测试 Java 文件、32,323 行测试 Java。行数含注释和空行，测试文件数量不是测试用例数量，也不是通过率。核心账本模块 `rpg-core` 有 52 个生产 Java 文件、18,559 行，已是维护和故障验证的重点。

本次重点检查成长与晋升、核心持久化入口、技能 HUD、经济仿真、多服前置及既有证据文档，未声称逐行审计所有代码或排除全部复制路径。采用仓库的 `[D][S][T][M][R][H]` 证据层级；下面标注“测试存在”只表示读取了测试源码，不表示取得新的 `[T]` 通过结果。

## 2. 已有基础及应保留的设计

| 领域 | 当前可见实现 `[S]` | 意义与证据入口 |
|---|---|---|
| 服务边界 | `rpg-api` 定义服务接口，`rpg-core` 注册权威持久化服务，玩法插件调用服务 | 避免每个玩法另建等级、货币或市场状态；见 [RpgCorePlugin](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/RpgCorePlugin.java) |
| 数据库背压 | `AsyncJdbc` 把线程池拒绝转为失败 future，没有回落到调用线程执行 JDBC | 正常异步业务路径不会因队列饱和而主动把查询转移到 tick 线程；见 [AsyncJdbc](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/AsyncJdbc.java) |
| 成长与晋升 | 本地 1–30、三次晋升、有效 1–120；本地里程碑幂等；晋升事务统一入口 | 历史五个核心问题已有源码修正；见[当前成长复核](PROGRESSION_SEMANTICS_AUDIT.md#8-当前源码复核2026-11-03) |
| 装备与交付 | 装备状态服务、市场交付表、操作会话与终态保护均有实现 | 下一步应验证崩溃窗口与并发，不再平行增加第二套装备/交易系统；见 [EquipmentOperationService](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/service/EquipmentOperationService.java) 与 [JdbcMarketService](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcMarketService.java) |
| 赛季结算 | `JdbcSeasonSettlementService` 已有预览摘要、状态转换、实际结算与校验 | “只有修改 season-id”已经不是准确概括；真实数据库故障恢复仍需另证；见 [JdbcSeasonSettlementService](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcSeasonSettlementService.java) |
| 现实支持积分 | `JdbcSupportCreditService` 使用独立账户与账本，跨货币目标在事务前拒绝 | 物理隔离已有实现；这不自动证明支付验签、退款与交付集成完成；见 [JdbcSupportCreditService](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcSupportCreditService.java) |
| 可关闭机制 | `MechanismRegistry` 为七个来源/关键操作提供开关与通过、拒绝计数 | 未知机制失败关闭；仍需把开关行为与真实运维流程连接；见 [MechanismRegistry](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/MechanismRegistry.java) |

## 3. 当前问题与验收方式

优先级含义：P1 表示会直接影响当前国际用户体验，或阻塞对应能力开放；P2 表示需要在后续独立阶段解决。不同阶段的门槛不等同于现有单服默认配置已发生故障。

### IR-01 · P1 · 技能槽 Placeholder 解析错误

**基线证据：** [SivitaPlaceholderExpansion.java:148](https://github.com/Maggotxy/sivitahub-plugins/blob/c83226ff87c8ecb868277d9ca5bce575b4684abc/plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/SivitaPlaceholderExpansion.java#L148) 拼接出的正则是 `skill_(1-4)_(...)`，括号内的 `1-4` 是字面字符串。合法 `skill_1_name`、`skill_4_glyph` 不匹配；异常的 `skill_1-4_name` 反而能匹配，随后 `Integer.parseInt("1-4")` 会失败。

**影响：** 同时影响中英文的技能槽名称、冷却、状态、图标等 Placeholder。翻译内容即使正确，也可能无法通过该接口显示。

**建议修复：** 使用明确的数字解析与 `1..slotCount` 范围检查，覆盖全部七种后缀，并拒绝零、负数、连字符、过大整数和未知字段。测试应验证真实解析入口，不能仅搜索源码是否含某段字符串。客户端验收还需确认 BetterHud 实际解析和字体/资源包显示。

**本分支已实施 `[S]`：** 新增 [SkillPlaceholderKey](../../plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/SkillPlaceholderKey.java)，由实际 `SivitaPlaceholderExpansion` 调用。解析器使用预编译模式、显式数字边界和溢出拒绝；新增 [行为回归测试](../../plugins/rpg-integrations/src/test/java/cn/originalrpg/integrations/SkillPlaceholderKeyTest.java) 覆盖四槽七字段、非法输入及不同槽位上限。测试源码已独立复核；实际执行结果以 PR 的验证记录为准，本段不提前宣称测试或客户端渲染已通过。

### IR-02 · P1 · 经济仿真尚无引擎

**基线证据：** [tools/economy-sim/run.py](../../tools/economy-sim/run.py) 第 15–31 行加载场景，打印配置 SHA；S01–S14 分支明确输出 `NEEDS_ENGINE`，S15 只检查错误连接是否被声明为 `FAIL_FAST`。

**本次已执行：** `python3 tools/economy-sim/run.py`，看到十四项 `NEEDS_ENGINE` 与一项预期 `FAIL_FAST`。此命令成功解析文件，不能记作十五场景经济仿真通过，也没有取得 `[M]`。

**缺少什么：** 现脚本没有玩家行为演化、账本回放、产出/销毁计算、库存龄、财富分布或 `simulation.json` 指标输出。仅计算文件哈希也不能证明运行时参数全部来自实际配置。

**下一步验收：** 独立 Phase 3 实现配置读取、固定随机种子、各场景输入、至少 Gold/Merit/Starlight 余额守恒与来源/回收口计量；报告明确分开 `PARSED`、`SIMULATED`、`PASSED`。最后对授权的七日真实快照做校准，无法校准时标记 `NEEDS_TUNING`，不伪造玩家分布。

### IR-03 · P1（多服开放前）· Session Lease 与 Outbox 尚不构成完整跨服系统

**当前安全边界：** [核心默认配置](../../plugins/rpg-core/src/main/resources/config.yml) 第 49、54、57 行分别将 `session-lease.enabled`、`outbox.enabled`、`outbox.dispatch.enabled` 设为 `false`。本轮不启用这些开关。

**源码事实：**

1. [JdbcSessionLease](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcSessionLease.java) 第 16–22 行明确只实现读取，不包含 acquire/renew/handoff。数据库建了租约表不代表存在租约写侧。
2. [RpgCorePlugin](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/RpgCorePlugin.java) 第 165 行以空处理器列表创建 `OutboxDispatcher`；[OutboxDispatcher](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/OutboxDispatcher.java) 第 77–82 行会把无处理器事件登记为失败后继续重试，不能完成实际投递。
3. [SessionLeaseListener](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/SessionLeaseListener.java) 第 31 行在 `PlayerJoinEvent` 回调直接执行 `admit()`；后者最终在 `JdbcSessionLease.java:69` 获取连接并查询。启用后会把 JDBC 放到登录事件调用链，没有使用已有有界异步执行器。
4. 查询失败在 `JdbcSessionLease.java:89–90` 转为异常；listener 未捕获该异常，拒绝登录代码位于调用之后。因此这条代码路径本身不能保证数据库错误时执行拒绝动作。这里是静态调用链结论，尚未做 Paper 故障注入。

**下一步验收：** 在 Phase 8 独立完成租约获取/续期/移交、所有受保护写入的 fencing、登录异常拒绝和会话竞争测试；注册真实幂等 Outbox 消费者，完成节点失联/数据库分区/重复消费测试，再启用代理多服。现有节点进程租约和邮箱交付保护应保留，不能被误解为已经实现每个玩家的跨服互斥。

### IR-04 · P1 · 历史证据不能当作当前状态

**基线证据：** [旧成长初稿](PROGRESSION_SEMANTICS_AUDIT.md) 描述已修复的五类缺陷；[Phase 1 证据](PHASE1_COMPLETION_EVIDENCE.md) 仍称按有效等级发技能点并保留 deprecated 晋升入口；[Phase 7/8 证据](PHASE7_8_EVIDENCE.md) 仅凭表与其他基础设施就称多服前置已经齐备。它们与当前源码不一致。

**本轮改进：** 给成长初稿保留历史内容、加入明确的当前复核与调用点表；本报告提供当前事实入口。其他历史文档继续作为历史材料阅读。今后的英文介绍应链接当前审计，不能直接逐句翻译旧的“完成”结论。

**持续维护：** 每条完成声明带源码提交、验证命令、结果产物和明确环境；没有拿到的新证据保持空缺。`[T]` 用例源文件存在、`[R]` 文字写着已启动、`[H]` 写着待真人，都不能升级为实际通过。

### IR-05 · P2 · 满级经验与异常检测边界

**源码推导：** 三转本地 30 级后经验可继续累计，异常检测却使用一个阶段的经验和作为上限。长期合法收益可能进入人工异常队列。详细证据和复现条件见[成长审计第 8.5 节](PROGRESSION_SEMANTICS_AUDIT.md#85-仍需单独回归的当前边界)。

**验证要求：** 正常经验入口、单次副本入口和持续领取分别覆盖最高转满级，再明确剩余经验语义。不能借国际化改动静默改成长规则或清空玩家经验。

### IR-06 · P2（跨服事件消费前）· 组队副本事件聚合语义待确认

[DungeonRewardBatch.java:38](https://github.com/Maggotxy/sivitahub-plugins/blob/c83226ff87c8ecb868277d9ca5bce575b4684abc/plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonRewardBatch.java#L38) 确保同一批次各玩家共享 `runId`；[JdbcDungeonRewardService.java:163–168](https://github.com/Maggotxy/sivitahub-plugins/blob/c83226ff87c8ecb868277d9ca5bce575b4684abc/plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcDungeonRewardService.java#L163) 使用仅由 `runId` 派生的 Outbox 幂等键，却把单个玩家写入 payload。重复键由 `JdbcOutboxRegistry` 的 `ON CONFLICT DO NOTHING` 去重。

如果消费者需要逐玩家事件，队伍后续玩家会被折叠；如果消费者需要一次整队事件，则 payload 和发出时机需要表达全队事实。当前没有注册消费者，默认不写 Outbox，因此本报告将其作为启用前必须决定的协议问题，不宣称已造成玩家资产丢失。应增加同一 run 的多玩家测试及重放测试，明确一种聚合语义后实现。

### IR-07 · P2（治理开放前）· 治理通知的受众范围过宽

[CommunityGovernanceListener.java:84–122](https://github.com/Maggotxy/sivitahub-plugins/blob/c83226ff87c8ecb868277d9ca5bce575b4684abc/plugins/rpg-community/src/main/java/cn/originalrpg/community/CommunityGovernanceListener.java#L84) 的生效、拒绝、提交与待审批通知均遍历所有在线玩家。源码注释称通知社群治理角色，实际没有筛选社群 ID 或审批权限；不属于相关社群的玩家也可能收到操作者/动作信息和审批提示。

该功能在 [rpg-community 默认配置](../../plugins/rpg-community/src/main/resources/config.yml) 中关闭。下一阶段应明确每类通知是公开活动公告、社群内消息还是审批角色私有提示，再按该规则投递。添加多社群/不同角色的行为测试；仅翻译消息不能解决受众范围问题。

## 4. 国际化应完成的用户路径

英文版本需要分别覆盖“了解项目”“能构建”“能玩”“能运维”，而非仅有一个英文 README：

| 读者 | 必须回答的问题 | 验收 |
|---|---|---|
| 首次访问者 | 是插件源码集合还是完整可部署服务端？使用权限是什么？ | 首页与实际文件、`INTERNAL_USE.md` 一致 |
| 开发者 | JDK/Paper 版本、依赖来源、可运行命令、缺失外部资产是什么？ | 新环境可定位前置条件，不会照文档调用不存在的脚本 |
| 玩家 | 六职业、金币/功勋/星辉、转生、本地/有效等级、装备绑定、市场与邮箱怎么理解？ | 用统一英文术语走通创建角色到副本/交易的完整路径 |
| 运营者 | 哪些能力默认关闭？怎样备份、恢复、审计异常和评估容量？ | 文档明确已有工具和待真实环境执行的门槛 |
| 贡献者 | 翻译键、占位符、模型 ID 与权限节点哪些能改？ | 语言校验与行为测试同时通过，不能翻译持久化标识 |

游戏内双语测试应包括菜单与命令、错误/拒绝信息、物品 lore、对话、技能栏、资源包字体以及语言切换后的刷新。语言键齐全只能证明目录覆盖，不能证明所有运行路径已经英文可用。

## 5. 建议后续顺序

| 顺序 | 工作包 | 可交付结果 | 退出条件 |
|---|---|---|---|
| 1 | 本轮国际使用基线 | 英文入口、玩家/开发/运维文档、当前审计、语言覆盖校验、已定位的界面修复 | 可审阅 diff；针对性检查结果；未验证项明确列出 |
| 2 | 可重复构建与发布证据 | 固定依赖来源、构建环境与产物清单、干净环境验证 | 新机器能产生同一套插件；缺失部署资产明确报告 |
| 3 | Phase 3 经济引擎 | 可重复场景和真实配置指标输出 | 十五场景实际执行，错误场景正确拒绝，并有校准偏差 |
| 4 | 单服可靠性验证 | 双击/断线/崩溃/数据库故障与备份恢复证据 | 账本和物品守恒；副本/市场/邮箱恢复符合不变量 |
| 5 | 内容与留存验证 | 可读玩法、构筑差异、非副本目标与新内容产线 | 真实测试者留存、经济和职业贡献数据达到已定门槛 |
| 6 | Phase 8 多服 | 会话写侧、fencing、真实 Outbox 消费、代理和 UUID 迁移 | 双节点竞争与故障注入通过后才开放多服 |

该顺序是在已有阶段实现上的证据补全，不增加货币、转数、交易系统或新的权威状态。

## 6. 本轮证据边界

- `[D]`：保留并遵守 C-001…C-015，不修改经济规则。
- `[S]`：已核对的文件与调用链见本报告；新增文档与修复以本分支 diff 为准。
- `[T]`：经济场景脚本本次已执行，但只证实解析器行为；Java 回归检查以 PR 的实际验证结果为准。本报告不复用旧成功日志。
- `[M]`：未取得经济引擎仿真证据。
- `[R]`：本轮未取得真实 Paper + PostgreSQL 的新运行证据。
- `[H]`：本轮未取得中英文真人完整路径或 20 人连续 7 日验证。

上线人数、经济稳定和长期运营就绪不能从文件数量、自动化测试数量或翻译完成度推导。
