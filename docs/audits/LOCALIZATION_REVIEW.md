# 国际化源码与运行路径复核 / Localization review

审计日期：2026-11-04。修改基于 `codex/international-readiness` 的 `b78ff85` 工作树；完整构建与发行证据见 [交付记录](INTERNATIONAL_READINESS_DELIVERY.md)。

**本轮已扩展到实际运行界面：语言回退、已有玩家语言切换、共享 NPC 与地图标签、旧物品显示、异步 AI 回复和可安装的双语剧情入口。下列源码与行为测试证据不能替代 Paper 服务器、外部插件和真人客户端验收。**

English summary: this change implements runtime localization and a bilingual story package. It preserves custom server text and player item names, uses saved player language for private UI, and uses the server default for shared entities/maps. Static text parity and focused tests do not certify an entire deployed server or every third-party UI.

## 1. 资源门禁 `[S][T]`

`scripts/check-locales.py` 在当前工作树的结果如下。一个字符串或一个字符串列表算一个叶子键；这不是句子数、界面数、调用次数或完成百分比。

| 资源组 | 每种语言的叶子键 | 用途 |
|---|---:|---|
| [Core lang](../../plugins/rpg-core/src/main/resources/lang/en_us.yml) | 2,676 | 共用菜单、职业、装备、任务、经济等 |
| [Hub lang](../../plugins/rpg-hub/src/main/resources/lang/en_us.yml) | 202 | 主城对话、引路、共享标签及地图 |
| Classes `menu-text` | 4 | 隐藏路线入口 |
| Essentials `messages` | 4 | 工具插件桥接提示 |
| Pets `messages` | 110 | 灵契伙伴界面 |
| AI `knowledge.yml` | 19 | 本地关键词、问答及通用降级 |
| **合计** | **3,015** | **六组中英文资源** |

门禁使用 PyYAML 安全解析，拒绝重复键、递归别名、非文本叶子、空内容和点号路径冲突；比较中英键集合、字符串/列表类型及 `{name}` 占位符集合。分行、关键词数量、占位符顺序允许因语言不同而变化。它不验证 Java 调用参数、外部部署配置或视觉排版。

新增 [BetonQuest 剧情包](../../examples/betonquest/QuestPackages/sivita_story/package.yml) 使用上游的 `en-US` / `zh-CN` 格式，单独由 `scripts/check-story-package.py` 检查：1 个对话、14 个选项、15 组双语文本、3 个可达业务动作。它不计入上述 3,015 键。

## 2. 语言选择、旧配置与自定义文本 `[S][T]`

[YamlMessageService](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/i18n/YamlMessageService.java) 使用玩家 PDC 保存的选择，缺省使用 `RpgCore/config.yml` 的 `language.default`。原始默认值仍是 `zh_cn`；英文部署设置 `en_us`。`/language` 不自动读取 Minecraft 客户端语言。

[LocaleBundle](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/i18n/LocaleBundle.java) 的查找顺序是：请求语言的安装覆盖 → 同语言内置包 → 服务器默认语言 → 英文 → 中文。空、类型不匹配或占位符不匹配的覆盖在内存中忽略，并记录诊断。旧安装缺少新键时直接回退至同语言内置包，**不会把整份运营语言文件重新保存或覆盖**。合法自定义文案继续优先。

占位符只替换一遍：玩家名或其他替换值中的 `{token}`、美元符号和反斜杠不会被当作第二层模板或正则替换指令。AI 知识使用严格的同语言查找；英文未知话题不会因中文自定义关键词而返回中文正文。

[PlayerLocaleChangeEvent](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/event/PlayerLocaleChangeEvent.java) 在语言保存成功后同步发送，仅在实际解析语言改变时发送。`MessageService.textForLocale` / `linesForLocale` 提供显式语言读取，兼容旧服务实现。切换后的新界面按新语言生成；已打开的普通菜单仍需重新打开。

## 3. 已实现的实际运行路径 `[S]`

| 表面 / 模块 | 本轮行为 |
|---|---|
| Hub NPC、原生个人对话、引路 | [HubMessages](../../plugins/rpg-hub/src/main/java/cn/originalrpg/hub/HubMessages.java) 将库存中文默认配置映射至当前语言；个人对话按玩家语言，共享实体名按服务器默认语言。合法自定义名称不被覆盖，可用 `names.<locale>` / `subtitles.<locale>` 配置不同语言。 |
| Squaremap | 图层、主城安全区及 NPC 标记按服务器默认语言渲染；动态标签进行 HTML 转义。网页访客没有在线玩家语言上下文。 |
| Story 演员与护送 | 共享演员名按服务器默认语言，私有护送回声与进度 bossbar 在玩家切换语言时刷新；自定义演员名保留。 |
| Story 与 BetonQuest 语言同步 | [StoryListener](../../plugins/rpg-story/src/main/java/cn/originalrpg/story/StoryListener.java) 在认证就绪及实际语言变更事件后执行上游支持的 `questlang` 命令；不再从尚未成功执行的 `/language` 文本预判。英文首次登录也会同步。上游语言白名单与权限仍适用。 |
| AI NPC | 切换时清理旧语言上下文；未完成的远程回答若返回时语言已变，不发送旧语言正文，而使用当前语言的本地回答和提示。保留并发、冷却和费用约束，不额外发起付费请求。 |
| 世界怪物 | 血月稀有怪共享名称按服务器默认语言；迁移仅识别旧生成默认名，保留玩家自定义名。 |
| 世界猎获材料 | [HuntMaterialPresentation](../../plugins/rpg-world/src/main/java/cn/originalrpg/world/HuntMaterialPresentation.java) 刷新猎获晶核和血月精粹的默认名称与数量说明；使用 `itemName`，保留玩家 `customName`。认证、切换语言和物品转移后刷新玩家背包、末影箱、光标，不改共享容器或锻造/交易输入。PDC、数量和模型不变。 |
| 多节点准入 | 三个租约拒绝原因改为核心双语消息。准入及租约规则不变。 |
| PlaceholderAPI / HUD | 离线文本回退使用服务器默认语言；新增 `%sivita_hud_ready%`，仅在线、资源包 `SUCCESSFULLY_LOADED`、角色缓存新鲜且已认证的技能快照可用时返回 `1`，否则 `0`。所有已定义数字占位符在无数据/空槽时保持可解析：通常为 `0`，`mana_max` 至少为 `1`；空槽 `id/timer/glyph` 保持空串，有效选择使用从 1 开始的槽号。技能快照的时间戳表示已学技能缓存加载时间，不作为空闲玩家的 HUD 过期判断。 |
| 社群治理 | 成功确认使用不需参数的 `effective-ok`；广播的英文补齐 `{player}`。已有真实双批处理测试覆盖消息参数渲染。 |

本轮随后补齐四个普通物品生产者的实际刷新路径：

| 生产者 | 识别和刷新范围 | 保留的事实 |
|---|---|---|
| `rpg-mobs` | `adaptive_material` 对应的异变核心/碎片，`skill_book=common-insight` 对应通用技能书；新创建和已有物品使用同一呈现器。 | 材质、数量、模型、拾取归属、技能书实例 UUID；无自带 lore 的物品不清空其他 lore。 |
| `rpg-dungeons` | 直接从现有验证后的 RewardDefinition 读取六种核心材料、三种隐藏门票、三种技能书的名称键；源码不复制经济金额/数量或另造奖励身份。 | 奖励身份、门票目标、书实例、所有者、模型与数量。来源/用途 lore 按当前语言重绘。 |
| `rpg-guide` | 起步基底、核心及绑定食物/床/箭；无 PDC 的旧领地铲必须原名称和 lore 同时匹配才迁移。 | 职业方向、绑定归属、不可破坏属性和原数量。不把 earned-shop 恢复核心误认成免费起步物资。 |
| `rpg-pets` | `companion_signal` 对应的号角名称和 lore。 | 号角 owner、glint、自定义名称及宠物/冷却状态。 |

[PlayerItemTextRefresh](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/i18n/PlayerItemTextRefresh.java) 在认证、真实语言变更和物品变化后合并为下一主线程 tick 的工作。锁定的 Paper API JAR 中确有 `PlayerInventorySlotChangeEvent`，因此异步市场交付和收据清除也会触发检查；没有轮询数据库或定时全服扫描。只访问个人背包、末影箱和光标，打开容器/锻造等界面时延后，关闭事件重新排队。撤销认证、退出和所属插件关闭会清理连接引用，旧连接的排队任务不能吞掉新连接的刷新。

[ItemTextPresentation](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/i18n/ItemTextPresentation.java) 不写 PDC，仅在显示字段真正改变时写回，避免 slot event 循环。`listing_id`、`delivery_id`、`equipment_operation`、`ticket_entry_escrow` 和签名装备全部跳过；只有原生产者清理临时标记后才允许重绘。最后一个门票标记尤其不能遗漏：入场恢复会比较原始序列化 payload，语言刷新不得改变待确认票券。

锻造装备迁移由 `rpg-items` 的独立实现/测试负责，普通物品呈现器不会修改签名装备。以上是实现与定向测试覆盖，不能推导每种客户端转移和第三方插件组合都已通过验收。

## 4. 可安装剧情入口 `[S][T]`

[安装说明](../../examples/betonquest/README.md) 与 `examples/betonquest/QuestPackages/sivita_story/` 现在提供真实的 Citizens 9101 点击入口。包内采用 BetonQuest 3.1 文档的 `conversations`、`NPC_options`、`player_options`、`actions`、`npcs` 与 `npc_conversations` 结构；quest 文件格式标识为上游文档的 `3.0.0-QUEST-1`，不是插件版本号。

对话只能调用已有 `sivita_story_begin prologue_escort`、`sivita_story_claim prologue_escort`、`sivita_story_open_journey`。开始条件复用已有 `sivita_story_eligible`。不在包内写进度标签、发货币、写经验或复制奖励金额。真实路线、状态与账本幂等仍由 `EscortManager` 和现有工厂控制；完成时自动领取，手动领取仅供到账检查/恢复。

源码原本已有 `/story start/status/next/claim/cancel` 完整命令路径，但公开树没有将保留 NPC 绑定到该玩法的 QuestPackage。本包补齐该内容入口。静态检查验证双语文本、演员 ID、引用、分支可达性、出口和受控动作；**尚未声称通过实际 BetonQuest reload 或 Citizens 点击实测**。

上游依据：[BetonQuest 3.1 对话](https://betonquest.org/3.1/Documentation/Advanced/Conversations/)、[包格式](https://betonquest.org/3.1/Documentation/Basics/Packages-%26-Templates/)、[NPC 绑定](https://betonquest.org/3.1/Documentation/Reference/Integrations-List/NPCs/)、[语言配置](https://betonquest.org/3.1/Documentation/Reference/Plugin-Config/)。

## 5. 验证记录与剩余边界

可复现的静态检查：

```bash
python3 -m pip install PyYAML==6.0.3
python3 scripts/check-locales.py
python3 scripts/check-story-package.py
python3 -m unittest discover -s scripts/tests -p 'test_locales.py' -v
python3 -m unittest discover -s scripts/tests -p 'test_english_presets.py' -v
python3 -m unittest discover -s scripts/tests -p 'test_story_package.py' -v
```

本地语言门禁 13 个夹具、英文配置生成器 9 个夹具和剧情包 6 个夹具全部通过。测试包含错拼占位符、点号碰撞、重复键、缺翻译、悬空分支、不可达节点、错误演员，以及禁止奖励/标签旁路。英文配置生成器的 9 个既有测试单独验证新输出目录和白名单外值不变。

JDK 25 + 已解析的真实 Paper API + JUnit 的定向行为验证覆盖 LocaleBundle、ConfiguredDisplayText、StaticKnowledge、AiResponsePresentation、HuntMaterialPresentation、HudControlGlyphPolicy、HudReadinessPolicy 和 StoryLanguageSync，29 个测试通过。随后普通物品/HUD 补充运行 16 个行为测试，全部通过：HUD 数字值 5、显示字段与锁标记 3、刷新生命周期 5、延迟血月名称 3。前后两组共 45 个不同定向测试；完整 Gradle 构建与全项目计数以根交付记录为准。本定向运行不声称编译了全部 Story/Citizens 代码。

仍需明确区分：

- 原生 G 快捷对话及 Minecraft 原生翻译组件跟随**客户端语言/资源包**；插件普通菜单跟随 `/language` 保存偏好。英文发行配置可以使默认一致，但两个选择不是自动绑定。
- Paper 官方明确说明 [GlobalTranslator 不自动翻译 ItemStack 名称和 lore](https://docs.papermc.io/paper/dev/component-api/i18n/)；已有物品必须靠显式重绘。未归属本插件、运营自定义文本、第三方物品不得据本报告强制改写。
- 资源包成功状态只表示客户端报告加载成功，不能证明每个自定义字形、模型、纹理及打包版本都正确；HUD 仍需实际客户端视觉验收。
- AuthMe、EssentialsX、Citizens、BetonQuest、MyPet、BetterHud 的独立消息和权限配置需要各自正确安装。英文文档、配置生成器和剧情示例不会自动修改正在运行的服务器。
- 需实测首次认证、切换/重登、长英文菜单、共享 NPC/地图、已有/新物品、跨玩家转移、远程 AI 失效/途中切换、实际剧情完成/重复领取、HUD 资源包拒绝/失败/重载，以及 GUI 缩放 2/3/4。
- 已改名、缺少身份标记的旧领地铲无法可靠推断来源，保留原样；嵌套容器物品在取回个人库存后刷新。运营自定义文案、earned-shop 其他商品、硫磺模块和未识别的外部物品不由本轮四个呈现器接管。市场预览的非装备副本仍可能显示卖家保存的语言；实际交付确认后会按接收者语言刷新。
- 当前未宣称 `[R][H]` 真人验收完成。固定版本 Citizens 的构件访问问题及完整构建进展在根交付记录中独立追踪，不以近似依赖替代通过证明。
