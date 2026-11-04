# 25 插件玩法、可靠性与英文运行证据矩阵

复核日期：2026-11-04。基线为 `b78ff859ed14588d8d04d1faa9d0ddf44730ba6b`，本文件记录 `codex/international-readiness` 的继续完善工作。本文覆盖仓库内实际存在的 25 个 `plugins/rpg-*` 模块；赛季、章节和多服持久化在 `rpg-core` 中，不能误计为另一个 `rpg-seasons` 插件。

**本轮目标是实际运行路径，而不是仅翻译文档。** 已有机制应继续使用原来的权威服务、账本、装备状态和邮箱。C-001…C-015、三次转生、本地等级 1–30、有效等级 1–120、现有税率/奖励数值和默认关闭功能均保持不变。

## 1. 如何阅读证据

- `[S]` 表示已核对的实现或本分支已经写入的修改，不等于实机验证。
- 表中列出的测试类是**可复核的测试入口**，不能仅凭文件存在记为 `[T]` 通过。部分 `*ContractTest` 只检查源码结构或接线，不能代替真实数据库或 Paper 行为测试。
- 根代理统一串行运行 Gradle，避免两个构建争用共享缓存。本报告不把子代理遭遇的 Gradle 缓存锁超时解释为测试失败，也不提前写成通过。
- 语言目录/占位符相等证明静态覆盖；只有真实客户端从登录、切换语言到副本/交易/重连的完整路径，才提供英文运行体验的 `[R]`/`[H]` 证据。
- 当前没有新的真实 Paper + PostgreSQL 双节点、玩家存档崩溃恢复或长期真人验证结果。发布说明应引用实际产生的构建/测试记录，而非历史报告中的完成声明。

### 本轮已取得的自动化结果 `[T]`

根代理在 2026-11-04 串行运行 `:plugins:rpg-core:test :plugins:rpg-mobs:test :tools:db-migration:test -PexcludeTags=env`，构建日志为 `gameplay-tests.log`，33 秒 `BUILD SUCCESSFUL`。本报告独立读取相应 JUnit XML：

| 回归套件 | 执行用例 | 失败/错误/跳过 | 覆盖意义 |
|---|---:|---|---|
| `DatabaseMigrationIntegrationTest` | 3 | 0 / 0 / 0 | 完整生产迁移、重启、真实授点、空/非空旧 schema |
| `JdbcExperienceGrantServiceTest` | 4 | 0 / 0 / 0 | 提交前后故障、重放、冲突与并发 |
| `ExperienceWriteBufferTest` | 2 | 0 / 0 / 0 | 实际 flush/shutdown 保留批次身份 |
| `MechanismRegistryPersistenceTest` | 9 | 0 / 0 / 0 | 开关持久化、删除、回滚与失败快照 |
| `SchemaManifestContractTest` | 1 | 0 / 0 / 0 | 迁移工具的表/唯一约束/索引清单与运行 DDL 一致 |
| `JdbcProgressionAnomalyServiceTest` | 12 | 0 / 0 / 0 | 异常边界与最终满级奖励重载 |
| `JdbcCommunityGovernanceTest` | 20 | 0 / 0 / 0 | 治理权限、审批事实及提交后事件 |

同轮先前执行的 `ProgressionRulesTest` 17 例和 `CommunityGovernanceListenerTest` 6 例亦为零失败/错误/跳过；这里列的是完整套件用例数，**不表示这些用例全部为本次新增**。两个真实 PostgreSQL 条件测试因未配置数据库而 `SKIPPED`，不计为通过。子代理另外执行了 mobs 源码契约 23 项与 DDL/指令双向门禁，均为 `GREEN`（82 个登记表、43 个指令；扫描快照中的生产文件数量不代表测试覆盖率）。

## 2. 模块矩阵

所有路径均相对仓库根目录。英文“共享目录”指 [rpg-core 的 en_us.yml](../../plugins/rpg-core/src/main/resources/lang/en_us.yml)，大多数玩法通过 `MessageService` 获取玩家语言。配置中的业务 ID、权限节点、PDC 键和数据库标识不翻译。

### 2.1 权威状态、运维与集成（5 个）

| 模块 | 当前玩法/机制与本次核对 `[S]` | 资源与英文覆盖 `[S]` | 测试入口与剩余依赖 |
|---|---|---|---|
| `rpg-api` | 共享服务、装备 12 态、货币结果、角色/章节/技能契约；`ProgressionRules` 是成长唯一规则入口。本轮修复最终满级经验误报与经验合计溢出，扩展治理通知受众读取。 | 无独立玩家内容包；职业、技能、旅程与 HUD ID 提供跨模块契约。本分支新增公共语言支持供各玩法复用。 | `ProgressionRulesTest`、`EquipmentLifecycleTest`、`CommunityRolePowerTest`。接口通过不代表各提供者已满足持久化语义。 |
| `rpg-core` | 账本、成长、装备、市场、宠物恢复、章节/赛季的权威 SQL 服务。本轮修复治理提交后事件、开关持久化、新库迁移顺序/技能点 schema，并新增自然怪 XP 幂等收据。 | 中英文共享消息目录与 `/language`；旧安装覆盖文件和语言切换刷新由统一语言实现处理。数据库、签名密钥、节点 ID 是部署输入。 | `JdbcServicesTest`、`JdbcDungeonRewardServiceTest`、`JdbcCommunityGovernanceTest`、新增 `DatabaseMigrationIntegrationTest`、`JdbcExperienceGrantServiceTest`、`MechanismRegistryPersistenceTest` 等。H2 不代替 PostgreSQL；Session Lease/Outbox 保持默认关闭，见 §4。 |
| `rpg-admin` | 经济/章节快照、异常复核、事故补偿及赛季预览/批准/推进。`AdminConfirmation` 和状态服务负责危险操作确认；补偿仍引用原凭据。 | 管理输出使用共享目录；快照为运营证据，不是玩家奖励源。真实快照保存位置和备份策略需配置。 | `AdminSnapshotIoTest`、`AnomalyReviewCommandTest`、`SeasonSettlementCommandTest`。需实测权限、原始事故凭据、存储故障、恢复后审计。 |
| `rpg-performance` | 负载采样、滞回、持久化健康与降载状态；高负载时限制视觉/非关键工作，不能据此自动改价或掉率。 | 状态 JSON 和 `/rpgperf` 为运维界面；无独立模型。玩家提示来自共享目录。 | `LoadStateHysteresisTest`、`PersistenceHealthTest`、`PerformanceStatusStoreTest`。吞吐/容量需要真实目标硬件、数据库和玩家行为测量。 |
| `rpg-integrations` | PlaceholderAPI、LuckPerms、BetterHud 适配。上一阶段已修正技能槽键解析；本轮继续完善 HUD 语言呈现。 | 共享 HUD 字形/字体与玩家语言；本轮提供 BetterHud 服务端布局候选，供固定版本插件重新编译。 | `SkillPlaceholderKeyTest`、`HudControlGlyphPolicyTest`、`ProgressionPlaceholdersTest`。需核对插件版本、字体、技能槽状态与语言切换后的客户端重绘。 |

### 2.2 成长、战斗与物品构筑（7 个）

| 模块 | 当前玩法/机制与本次核对 `[S]` | 资源与英文覆盖 `[S]` | 测试入口与剩余依赖 |
|---|---|---|---|
| `rpg-classes` | 六职业；`ClassBranchGraph` 校验每职业三阶段 2/2/1 分支、父子相邻和同职业约束；隐藏路线使用权威前置与晋升服务。 | `menu-text`、职业/分支共享语言键与职业图标；AuthMe 是硬依赖。 | `ClassBranchGraphTest`、`ClassMenuPolicyTest`、认证/异步接线测试。需玩家验证六条起步路径、晋升边界和隐藏路线是否可达。 |
| `rpg-skills` | 42 个技能、六职业各 7 个；冷却、资源、友军、控制、技能升级/变体与输入序列有分离规则；主插件关闭时清理机制。 | `skills`/`skill-books` 配置、42 个物品图标与 HUD 图标；核心共享英文文本。本轮修复语言刷新与残留实体名由所属实现负责。 | `SkillCatalogContractTest`、`SkillUseRulesTest`、`SkillSequenceRulesTest`、`SkillSessionStateTest`。必须实测所有技能、Boss/PvP 系数、切服/退出冷却和动态实体名称。 |
| `rpg-combat` | 统一伤害桥、属性防御、处决、战斗标记及队伍友伤判定；`CombatBalance` 区分玩家/Boss 技能倍率。 | 反馈使用共享目录与资源包字形；不新增经济资源。 | `CombatBalanceTest`、`DefensePolicyTest`、`CombatTagRulesTest`。真实交互仍需验证护甲/药水/其他战斗插件、宠物和技能同时命中；未调整平衡数值。 |
| `rpg-items` | 锻造、揭示、解绑、分解、销毁、传承；`EquipmentOperationCoordinator` 在扣物前保存可重放信封，交由核心事务和邮箱处理；签名与终态保护保留。本轮根代理把启动验签失败改为真实禁用插件，避免仅 return 留下假 enabled 状态。 | 自有装备/材料模型与英文 lore；默认装备名迁入 `ITEM_NAME`，语言切换、拾取/转交后按接收者刷新，同时保留玩家铁砧 `CUSTOM_NAME`；持久操作处理中跳过展示重写。签名密钥不得因安装英文版而重置。 | `ForgeInputBoundaryContractTest`、`DestroyEscrowEndToEndTest`、`TerminalDropGuardTest`、`EquipmentNamePresentationTest`。验签启动修复待后续统一验证；存档、死亡/容器/重连及操作处理中切换语言需实机。 |
| `rpg-enhancement` | 洗练、突破、变异调用同一装备操作服务；消耗、结果/返还与幂等结算不另建状态机。 | `refinement`/`limit-break`/`mutations` 配置及共享英文材料/属性描述；复用锻造材质。 | `RefinementRulesTest`、`RefinementWeightsTest`、`EquipmentOperationIntegrationContractTest`。需要确认多次点击、支付失败、断线恢复和配置范围，不以概率低代替内容。 |
| `rpg-sulfur` | 喂食硫灵→属性共鸣→空白印记提取→签名锻纹；按所有者、每日次数与单实体冷却限制。 | 配方、签名印记模型、共享 `sulfur.*` 英文文本；依赖目标 Paper 的硫灵事件。 | `ResonanceRulesTest`、`SulfurExtractionRulesTest`。实体 PDC 与玩家额度/背包不在同一 SQL 事务，需 Paper 事件取消和保存时序测试；不能把签名等同于崩溃恢复证明。 |
| `rpg-pets` | MyPet 的单活跃宠物、四条路线、亲密/进化/图鉴/主动技能；复苏扣费由 `JdbcPetRevivalService` 保存 intent、完成/隔离/补偿。 | config.yml 中英 messages 与菜单、宠物皮肤；绑定号角的新旧名称和 lore 按玩家语言刷新，保留 owner/glint/自定义名称。MyPet 原生输出另行验收。 | `PetBehaviorRulesTest`、`PetProgressRulesTest`、`PetRevivalLifecycleContractTest` 和核心复苏行为测试。需 MyPet 写回成功但确认失败、宠物更换/缺失、管理员补偿实机测试。 普通物品共享刷新生命周期与展示保护由 `PlayerItemTextRefreshTest` / `ItemTextPresentationTest` 验证；模块具体物品及转移的客户端呈现仍待实机。 |

### 2.3 奖励、交易、社群与外观（6 个）

| 模块 | 当前玩法/机制与本次核对 `[S]` | 资源与英文覆盖 `[S]` | 测试入口与剩余依赖 |
|---|---|---|---|
| `rpg-economy` | 狩猎/事件/委托配额、劳作证据、商店与交易入口。金币批次冻结原始 `ActivityRewardRequest` 并保留幂等键重试；这与自然怪 XP 的旧路径不同。 | 商店/委托目录与共享英文文本；材料物品模型跨插件引用。 | `EconomyReceiptCodecTest`、`ShopIntegrityValidatorTest`、`MerchantPricingTest`，核心活动账本测试。按场次的部分计数在进程内，日额度是 SQL 权威；重启后的场次语义需验证。经济仿真由单独引擎给证据。 |
| `rpg-commerce` | 星辉购买/商店补给/累计奖励使用核心账本和邮箱；真实付费 `support_credit` 仍独立，不能给战力、金币或材料。 | `products`/`supply-shop`/`rewards` 与共享英文商店；奖励模型已有引用。 | `CommerceCatalogPolicyTest`、`SupplyCatalogPolicyTest`、核心商业/独立积分测试。外部支付回调、验签、退款与第三方商城不因此自动完成。 |
| `rpg-market` | 单一托管市场、买单、固定税底线、邮箱领取；当前玩家会话门、库存/游标收据扫描与存档保存共同恢复交付。 | 英文浏览/确认菜单、物品自身展示与 `/market`；地摊所有权验证依赖 GriefPrevention，缺少验证时拒绝。 | `MailboxClaimReconcilerTest`、`MailboxDeliveryIntegrationContractTest`、核心市场/买单行为测试。领取门是进程内保护，不能称为跨节点租约；需真实存档崩溃窗口验证。 |
| `rpg-community` | 七角色、双批审批、贡献/工程/师徒；本轮通知改按该社群当前治理权限成员投递，剔除全服广播与重复成功提示。 | 共享 `community.*` 英文消息与菜单；治理在核心和玩法配置中仍默认关闭。 | `CommunityGovernanceListenerTest`、`CommunityGovernanceGateTest`、核心双批/权限/提交后事件回归。开启前需多社群、多角色、角色变更、审批竞态的 Paper 验收。 |
| `rpg-cosmetics` | 光环/轨迹与视觉预算，按用户偏好和服务器负载控制；不提供额外战力。本轮修正商业目录对账读取错误文件夹的问题，改从已加载 RpgCommerce 的有效配置读取，并声明加载顺序。 | 共享英文外观目录和原版粒子；无需另建付费战力兑换。 | `CosmeticCatalogTest`、`RenderBudgetTest`、轨迹/光环几何测试；新增 3 项启动目录行为/描述符回归已在统一 Gradle 中通过。需实机检查多人低特效与语言切换。 |
| `rpg-mobs` | 自适应等级、精英能力、掉落和缓冲经验；物品邮箱使用稳定击杀/交付 ID。**本轮 XP 批次改用稳定 grant ID**，见 §3.4；硬退出前内存奖励仍无持久日志。 | 怪物指南/掉落共享英文文本；异变核心、碎片与 common-insight 技能书的新旧名称按持有者语言刷新；延迟等级分配保留血月稀有身份，共享怪名用服务器语言并保留命名牌。15 个 BetterModel 服务端模型候选已导出，实际加载另验。 | `MobScalingRulesTest`、`MobRewardReliabilityRulesTest`、新增 `ExperienceWriteBufferTest` 和核心“提交成功但响应失败”回归。模型实际加载、资源下载及掉落反馈需实机验收。 普通物品共享刷新生命周期与展示保护由 `PlayerItemTextRefreshTest` / `ItemTextPresentationTest` 验证；模块具体物品及转移的客户端呈现仍待实机。 |

### 2.4 世界、主城、剧情与入门（7 个）

| 模块 | 当前玩法/机制与本次核对 `[S]` | 资源与英文覆盖 `[S]` | 测试入口与剩余依赖 |
|---|---|---|---|
| `rpg-dungeons` | 三普通/三隐藏副本、组队、门票托管、波次/Boss、轮换远征；完整奖励批次先 fsync 再结算，`run_id + player_uuid` 回执与 XP/保底/邮箱同事务。 | 六副本配置、Boss/波次/奖励物品模型、共享英文说明；核心材料、隐藏门票、三种技能书按同一 RewardDefinition 刷新名称/lore，保留实例与 PDC。未确认 ticket_entry_escrow 完全跳过；原处理器清理后再刷新。 | `DungeonRewardJournalTest`、`DungeonRewardIoCoordinatorTest`、恢复/退出/超时门禁及核心真实数据库测试。硬杀进程、文件系统错误、全队退出、门票提交不明仍需 Paper 验收；Outbox 聚合协议见 §4。 普通物品共享刷新生命周期与展示保护由 `PlayerItemTextRefreshTest` / `ItemTextPresentationTest` 验证；模块具体物品及转移的客户端呈现仍待实机。 |
| `rpg-world` | 世界边界、原版资产保护、血月、狩猎链；奖励接核心经济/交付服务，禁止以额外货币代替内容。 | 狩猎结晶/血月精华默认名称与 lore 在登录、语言切换及拾取/背包操作后刷新，保留玩家重命名和原有 PDC；血月稀有怪使用服务器默认语言，保留命名牌改名。世界事件文本与血月模型已有英文入口，实际世界/区块参数仍需配置。 | `WorldEventRulesTest`、`HuntMaterialPresentationTest` 和生命周期门禁。场次/活动状态有进程内部分；需跨午夜、重启与数据库故障验证，不把日 SQL 配额推导为整个事件恰好一次；物品语言刷新待统一回归及客户端验证。 |
| `rpg-hub` | 生成主城、服务 NPC、`/hub /wild /home /back`、认证会话与异步传送返回协调；WorldGuard/squaremap 为可选适配。 | 独立中英语言包、配置显示字段、导航与地图标签；本分支补语言切换和共享世界默认语言呈现。 | `HubReturnCoordinatorTest`、`AuthenticationSessionTest`、`TravelDisableLifecycleContractTest`、`HubLanguageContractTest`。实测传送失败/断线/卸载区块、共享 NPC 语言和地图注册。 |
| `rpg-guide` | 职业到首次锻造/技能揭示/副本的旅程，引导菜单、仓库、TAB/HUD 和绑定起步物资；关键步骤以权威事实查询。 | 八旅程图标、英文帮助和侧栏；起步基底/核心/绑定用品按已有身份刷新，旧领地铲只在原名称及 lore 都匹配时迁移，保留改名。职业模块的 starter 本身只处理属性/技能；实物生产者是 StarterKitService。AuthMe 是硬依赖。 | `StarterKitServiceTest`、`GuideJourneyPolicyTest`、`GuideRewardSessionContractTest`。新账号必须从零走一遍英语流程；仓库/领地引擎与私有部署链接另行验收。 普通物品共享刷新生命周期与展示保护由 `PlayerItemTextRefreshTest` / `ItemTextPresentationTest` 验证；模块具体物品及转移的客户端呈现仍待实机。 |
| `rpg-story` | Citizens 演员、BetonQuest action/condition 工厂、护送与核心奖励桥；没有另建任务奖励货币。 | 本分支修复演员/护送名称语言选择，新增 `examples/betonquest/QuestPackages/sivita_story/package.yml` 双语序章起步包：一个会话、14 个选项、15 组中英文本，绑定保留演员 9101，复用现有护送开始/领取与旅程动作。后续完整主线与目标服注册仍需补齐。 | `EscortRulesTest`、`StoryStartPolicyTest`、`StoryJourneyOrderContractTest`、`StoryLanguageSyncTest` 和 `check-story-package.py`。Citizens 与 BetonQuest 为硬依赖；静态包检查不等于实际加载，仍需验证接取→护送→离线→领取与英文对话。 |
| `rpg-ai-npc` | NPC 问答、请求/会话限额、知识检索与静态回退；AI 文本不拥有资产发放权限。 | `knowledge.yml` 双语知识、提示词语言和本分支的缺键同语言回退；外部 AI 提供商配置为可选部署输入。 | `StaticKnowledgeTest`、`AiSessionRegistryTest`、`AiProviderClientTest`。外部超时、断线回调、语言切换和未配置提供商均需交互验证。 |
| `rpg-essentials` | 把 Essentials 的经济/传送/装备旁路引回自有入口，并施加战斗/副本限制；不是第二套经济服务。 | `config.yml` 内双语桥消息；Essentials 是硬依赖。原生命令的其他语言由该插件自身配置决定。 | `EssentialsCommandPolicyTest`。需实测权限、命名空间和别名，确认所有部署中的旁路根命令都被覆盖。 |

## 3. 本轮已修复与具体可靠性发现

### 3.1 最终满级经验误报与溢出：已实施 `[S]`

[JdbcProfileService](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java) 在三转本地 30 级保留后续获得的 XP，累加到 `Long.MAX_VALUE` 时饱和。旧 `ProgressionRules.classifyAnomaly` 却按整个阶段预算上限判异常，导致合法写入经重载被列入人工队列。本分支仅让该最终状态接受非负已赚 XP；转数/等级越界、负 XP 以及尚未最终满级的异常跳跃仍会被标记。`stageExperienceSpent` 同步改为饱和相加。

回归入口：[ProgressionRulesTest](../../plugins/rpg-api/src/test/java/cn/originalrpg/api/rules/ProgressionRulesTest.java) 与 [JdbcProgressionAnomalyServiceTest](../../plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/JdbcProgressionAnomalyServiceTest.java)。旧测试“巨大 XP 异常”的输入移到三转本地 29 级，并新增最终升至 120、持续奖励、清缓存重载、极值奖励与技能点不重复的行为用例。没有清空玩家经验或增加等级上限。

### 3.2 治理通知受众与提交顺序：已实施 `[S]`

[CommunityGovernanceListener](../../plugins/rpg-community/src/main/java/cn/originalrpg/community/CommunityGovernanceListener.java) 旧实现把审批相关通知发给全部在线玩家。本分支通过 [CommunityGovernanceService](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/service/CommunityGovernanceService.java) 异步读取当前社群具有 `GOVERNANCE_ACTION` 的成员，在主线程给已认证在线会话投递；读取失败时不广播。角色变更后的下次读取立即反映新的受众。

[JdbcCommunityService](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcCommunityService.java) 现在先提交事务再发布治理事件；同一次提交不再同时从回调和事件重复通知。成功的非角色活动也不再误发 `CommunityRoleRejectionEvent`。这些事件仍是进程内通知，**没有因此获得持久 Outbox 保证**；事务已提交而进程随即退出时，业务事实保留、提示可能缺失。

回归入口：`CommunityGovernanceListenerTest`、`JdbcCommunityGovernanceTest`。覆盖多社群角色过滤、降职、主线程投递、关闭/失败、跨连接提交可见和非角色成功无错误拒绝事件。治理默认开关保持关闭。

### 3.3 Kill Switch 持久化缺陷：已实施 `[S]`

[MechanismRegistry](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/MechanismRegistry.java) 的 PostgreSQL 更新原为没有冲突目标的 `ON CONFLICT DO UPDATE`；失败后尝试 H2 `MERGE ... KEY(...)`，会把真实 SQL 错误误作数据库方言识别。在不支持 PostgreSQL 语法的 H2 模式下，初始化的 `MERGE` 还会覆盖已经关闭的开关。刷新缓存只追加行，无法撤回已经删除的开关。

本分支按数据库元数据显式选择 PostgreSQL/H2：PostgreSQL 用 `(mechanism_id)` 冲突目标；H2 初始化只插入不存在的行。提交成功后才更新缓存，读取完整快照后整体替换；SQL 错误保留原始原因并回滚，不再更换方言重试。初始化在单连接内完成，避免持有连接又向单连接池请求第二个连接。该变化只修复开关可靠性，不改变默认开关值。

[MechanismRegistryPersistenceTest](../../plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/MechanismRegistryPersistenceTest.java) 使用真实 H2 的普通/兼容模式及单连接池，核对重启保留人工关闭、插入/更新、删除后失败关闭、SQL/提交故障、半途刷新和播种回滚。PostgreSQL SQL 语义另核对了 [官方 INSERT 文档](https://www.postgresql.org/docs/18/sql-insert.html)；H2 语法参考[官方 MERGE 文档](https://h2database.com/html/commands.html#merge_using)。H2 通过不能升级为 PostgreSQL 实机通过。

### 3.4 自然怪经验的提交不明重试：已实施 `[S]`

基线 [ExperienceWriteBuffer](../../plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/ExperienceWriteBuffer.java) 对任意失败 future 把整批 XP 并回 `pending`，随后重新调用无业务收据的 `ProfileService.grantExperience`。核心只对技能点里程碑去重，**XP 增量本身不去重**。因此“数据库已提交，随后连接/响应失败”会导致重试再次增加经验；普通断言“失败 future 可重试”不足以发现这一点。

本轮在原 `rpg-core` 档案事务中增加稳定 grant ID 收据；冻结 XP 批次保留原 ID，失败批次与后来新 XP 分开处理；新回归注入“真实提交后抛异常”，再重试并核对经验/技能点/收据。`ProfileService.grantExperienceOnce` 拒绝同一 ID 的不同玩家或金额。收据记录操作身份，不是第二份等级余额或第二个成长账本。迁移、数据保留和回滚见[独立运行说明](../operations/PROGRESSION_RECEIPT_MIGRATION.md)；上述自动化结果 `[T]` 已通过。

### 3.5 内存待发奖励的硬退出窗口：未解决 `[S]`

`ExperienceWriteBuffer` 和 [DelayedMobRewardQueue](../../plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/DelayedMobRewardQueue.java) 的待发 XP/物品首先存在内存，正常关闭会尝试排空。自然怪物品交付已使用稳定 ID，不能据此推导“尚未进入数据库的内存队列可在硬退出后恢复”。新增 XP 幂等收据也只解决重复提交，**不会自动持久化仍未提交的奖励意图**。

与其对照：[DungeonRewardJournal](../../plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonRewardJournal.java) 为整个副本队伍持久化批次并 fsync，随后各玩家由 [JdbcDungeonRewardService](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcDungeonRewardService.java) 在同一事务结算 XP、保底、邮箱与完成回执；重放用 `run_id + player_uuid` 去重。两条奖励链的可靠性不能混用描述。

下一独立恢复工作包应先确定自然怪奖励承诺的持久化点、最高写入量与过载策略，再补稳定事件意图、重放和 SIGKILL/磁盘故障实测。未取得这些证据前，保留明确的丢失窗口。

### 3.6 新库启动与技能点 schema 漂移：已实施 `[S]`

真实迁移清单存在两个旧问题：赛季转换索引先于目标表创建；`rpg_skill_point_grants` 仍用 `source/idempotency_key/from_local_level` 的范围形状，消费者却写 `milestone_key/receipt/milestone_index`。之前的服务测试手工创建正确表，掩盖了运行迁移的漂移，旧升级测试也明确避开完整 `Database.migrate()`。

本轮调整建表/索引顺序、同步正确 DDL 与迁移清单。`SkillPointGrantSchema` 在运行迁移之前检查现有形状：新库或规范表正常处理；空旧表可安全重建；非空旧审计表和未知形状明确停止升级，保留全部原始记录及档案余额，不推断历史幂等身份。新增 `DatabaseMigrationIntegrationTest` 直接运行完整生产迁移，再真实授 XP/技能点、重新启动，并覆盖旧 schema。测试存在不等于已在 PostgreSQL 实机通过。

## 4. 需保留的功能边界

1. **多服仍未开放。** `session-lease.enabled`、`outbox.enabled`、`outbox.dispatch.enabled` 默认均为 `false`。`JdbcSessionLease` 的 acquire/renew/handoff、全部受保护写入的 fencing、真实 Outbox 消费者仍是 Phase 8 工作。单进程 `MailboxClaimGate` 或节点租约不能冒充玩家跨服会话租约。
2. **组队副本 Outbox 事件尚需定义。** 当前键按 `runId` 去重，但 payload 表达单个玩家；全队事件或逐玩家事件必须择一明确，再在同一 run 多玩家和重放测试中验证。默认关闭时不把这一缺口描述为正在发生的资产损失。
3. **短生命周期通知与业务提交不同。** 治理提交后事件修复避免回滚前泄漏，仍可能在提交后进程退出时漏提示；数据库事实和玩家资产才是恢复的权威。
4. **正常关闭不是硬崩溃证据。** 副本 fsync、玩家 `saveData()`、数据库事务和 MyPet 存储有不同持久化边界。现有源码门禁只能检查这些调用顺序，不能证明目标 Paper/文件系统的真实断电行为。
5. **不能把只读取公共仓库等同于完整部署。** 自有插件和客户端资源可构建，AuthMe/Essentials/MyPet/Citizens/BetonQuest 及其服务端配置、签名密钥和真实世界仍必须配齐；外部插件界面需分别验收英文。

## 5. 共享资源与英文验收清单

资源并非每个插件各有一套，应按真实跨模块引用验证：

| 资源链 | 本仓入口 | 已有/本轮检查能力 | 仍需 `[R]`/`[H]` |
|---|---|---|---|
| 装备、材料、42 技能图标 | `resource-pack/assets/originalrpg`、`SkillCatalog`、玩法配置 | 模型 ID、JSON/纹理引用、像素基线和英文资源键检查 | 手持/GUI、弓弩三叉戟/盾牌姿态、物品转交后的名称与 lore |
| BetterHud、职业/旅程字形 | `resource-pack/assets/betterhud`、`HudGlyphs`、`JourneyGlyphs` | 客户端打包、引用/解析回归与服务端布局候选；四槽各 42 图标、职业/操作键 follow 与 Placeholder 输出已只读核对；候选已接入 `hud_ready` 总条件，数字空槽由专门值策略处理 | 固定 BetterHud 编译和装载、字体重载、不同语言长度、资源加载失败回退 |
| 副本/波次/血月怪物 | `BetterModelMobAppearanceService`、`resource-pack/assets/bettermodel`、`build_server_resources.py` | 六副本、六波次和血月映射；15 个服务器模型候选 ID 与源码完全相等，含当前副本动画名 | BetterModel 实际注册/骨骼动画、实体遮挡/碰撞、掉落提示 |
| 原生导航对话框 | `datapacks/sivita-navigation`、`originalrpg/lang` | 本分支改用客户端翻译键与英文 fallback；离线安装检查 | 目标 26.2 客户端原生菜单入口、实际按键、命令权限 |
| 服务端文本和知识 | `MessageService`、共享/Hub 语言目录、内嵌双语配置、`knowledge.yml` | 键/占位符一致性、同语言回退、语言变更刷新 | 旧配置升级、在线切换、NPC/物品/菜单/提示全流程 |
| 剧情/登录/宠物等第三方内容 | 对应 `plugin.yml` 硬依赖、`examples/betonquest` 与部署配置 | 版本/依赖清单、自有桥接层及双语序章最小包的结构/动作检查 | 序章实际加载、后续完整任务内容、登录界面、MyPet 原生菜单与错误信息 |

[check-content-assets.py](../../scripts/check-content-assets.py)、[资源包构建说明](../../resource-pack/README.md)和语言检查结果属于各自可复核产物；不应将“资源存在”写成“所有动画或英文布局已经在客户端通过”。

## 6. 建议验收顺序

先串行完成新增 Java 行为回归与全部可构建插件，再核对资源引用、语言/占位符与可重复产物。随后用全新英文账号在真实 Paper + PostgreSQL 上完成：选职业→起步技能/锻造→野外奖励→副本→邮箱→交易→宠物/剧情→退出重连→语言切换。故障回归至少包含提交响应丢失、存档保存前后终止、同账号快速重连、数据库不可用以及恢复后账本/物品对账。最后再评估职业贡献、经济存量与内容留存，不用单次构建成功替代长期验证。
