# 国际化源码复核 / Localization review

> 本分支的实际交付、17 项 Java 与 29 项 Python 验证结果、完整构建阻塞及运行证据边界，见 [国际化交付与验证记录](INTERNATIONAL_READINESS_DELIVERY.md)。
审计日期：2026-11-03。源码基线：`c83226ff87c8ecb868277d9ca5bce575b4684abc`。

**结论：仓库已有规模较完整的中英文消息资源；本轮补齐英文开发与使用文档、修复一处消息调用问题，并新增可独立运行的语言资源门禁。资源一致性通过不等于整个部署已完成英文真人验收。**

English summary: the source already ships substantial bilingual content. This change adds an independent locale gate and fixes governance confirmation rendering. Shared NPC/map labels, installed overrides, third-party plugins, and real client layout still require separate international release checks. See the [English documentation](../en/README.md).

## 1. 已验证的语言边界 `[S][T]`

下表以 YAML 的**叶子键**为计数单位：一个字符串或一个字符串列表算一个键；不是句子数、页面数、调用次数或翻译完成百分比。数值由 `scripts/check-locales.py` 对本次工作树实测。

| 消息来源 | 中文叶子键 | 英文叶子键 | 用途 |
|---|---:|---:|---|
| [`rpg-core/lang`](../../plugins/rpg-core/src/main/resources/lang/en_us.yml) | 2,670 | 2,670 | 共用界面、职业、装备、技能、市场、经济、任务等 |
| [`rpg-hub/lang`](../../plugins/rpg-hub/src/main/resources/lang/en_us.yml) | 188 | 188 | 主城、传送、原生 NPC 对话与引路 |
| [`rpg-classes/config.yml:menu-text`](../../plugins/rpg-classes/src/main/resources/config.yml) | 4 | 4 | 隐藏路线入口 |
| [`rpg-essentials/config.yml:messages`](../../plugins/rpg-essentials/src/main/resources/config.yml) | 4 | 4 | 工具插件桥接提示 |
| [`rpg-pets/config.yml:messages`](../../plugins/rpg-pets/src/main/resources/config.yml) | 110 | 110 | 灵契伙伴界面和提示 |
| [`rpg-ai-npc/knowledge.yml`](../../plugins/rpg-ai-npc/src/main/resources/knowledge.yml) | 19 | 19 | 本地降级问答，含关键词列表 |
| **合计** | **2,995** | **2,995** | **六组中英文资源** |

门禁检查：YAML 安全解析、重复键、嵌套及点号路径冲突、必需语言组、双语键集合、字符串/列表类型、非空内容和 `{name}` 占位符集合。列表允许翻译时调整分行，关键词列表允许语言相关的不同长度；占位符允许调整顺序和重复次数。

门禁会发现后续新增的资源根目录语言组，以及插件资源 YAML 中的 `zh_cn` / `en_us` 分组，同时要求当前六组不能静默消失。它不解析 Java 调用参数，不验证占位符传入值，不覆盖服务器已安装的配置、第三方消息、图片内文字或客户端视觉布局。

## 2. 真实语言选择机制 `[S]`

[`YamlMessageService`](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/i18n/YamlMessageService.java) 从 `RpgCore/config.yml` 读取 `language.default`，默认 `zh_cn`；`language.allow-player-selection` 默认开启。`/language en_us` 经 [`LanguageCommand`](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/command/LanguageCommand.java) 写入玩家 PersistentDataContainer，并保存玩家数据。

- 当前实现根据玩家已保存的选择或服务器默认语言决定文本，**不自动读取 Minecraft 客户端语言**。
- 切换后应重新打开菜单；世界中的共享实体名、已创建物品文本、网页和第三方插件不由该命令统一重写。
- 核心语言包会补入缺失的新键，但保留已有运营自定义文本；已存在的键不会被新 JAR 文案强制覆盖。
- Hub 另有独立语言文件；其缺失键有默认语言及内置包的回退路径。
- Pets、Essentials 桥接、职业隐藏路线菜单和 AI 本地知识分属不同资源入口，不能只比较核心语言包就宣称全部覆盖。

英文为主的受权部署可把现有 `RpgCore/config.yml` 中的相应段修改为：

```yaml
language:
  default: en_us
  allow-player-selection: true
```

这是语言设置示例，不是完整服务器配置。其他数据库、世界、签名密钥和插件配置应沿用已验证的部署方案。

## 3. 本轮实际修复 `[S]`

### 社群双批成功消息使用了错误的文本键

[`CommunityGovernanceHandler.render`](../../plugins/rpg-community/src/main/java/cn/originalrpg/community/CommunityGovernanceHandler.java) 在双批达成时原先调用 `community.governance.effective`，却没有提供占位符。该键是事件公告，中文需要 `{action}` 和 `{player}`，英文原先需要 `{action}`。因此已启用治理功能时，命令成功提示可露出未替换的原始 token。

本轮将命令确认改为两份资源中**已经存在且不需要占位符**的 `community.governance.effective-ok`。事件公告继续使用 `effective`，并补齐英文中缺失的 `{player}`，与 [`CommunityGovernanceListener`](../../plugins/rpg-community/src/main/java/cn/originalrpg/community/CommunityGovernanceListener.java) 已提供的参数一致。

改动只影响消息选择和翻译。未改变治理开关、批准人数、权限、状态迁移、资金或数据库。治理功能默认关闭，故此问题不表示默认配置下所有玩家都能触发。

已有安装若保留了旧 `plugins/RpgCore/lang/en_us.yml`，请在备份后单独核对并更新 `community.governance.effective`；不要为更新一个句子覆盖整份运营自定义语言包。命令改用的 `effective-ok` 本来就在基线资源内。

[`CommunityGovernanceHandlerTest`](../../plugins/rpg-community/src/test/java/cn/originalrpg/community/CommunityGovernanceHandlerTest.java) 的真实第一票/第二票处理场景现增加双语资源渲染检查：将 handler 实际输出的参数应用到已打包文本，确认不存在未替换占位符。该测试避免只检查“键名存在”而漏掉调用参数错误。

## 4. 已知剩余国际化工作 `[S]`

| 优先级 | 可复核问题 | 影响与下一步 |
|---|---|---|
| P1 | [`HubNpcManager`](../../plugins/rpg-hub/src/main/java/cn/originalrpg/hub/HubNpcManager.java) 使用配置中的 `npcs.*.name` 创建共享名牌及对话标题；默认配置为中文 | 英文玩家仍可能看到中文 NPC 名称。英文专服可配置统一英文名称；双语同服需明确共享世界标签与个人对话标题的分别处理方案。英文标准称呼已在 Hub `wayfinding.roles.*` 中存在。 |
| P1 | [`SquaremapHubIntegration`](../../plugins/rpg-hub/src/main/java/cn/originalrpg/hub/SquaremapHubIntegration.java) 写死中文地图图层/主城提示，NPC 标记复用默认配置名称 | `/language` 不影响浏览器地图。后续应独立配置地图语言，或提供双语共享标记，不能假设网页访客有在线玩家语言。 |
| P1 | [`StoryActorManager`](../../plugins/rpg-story/src/main/java/cn/originalrpg/story/StoryActorManager.java) 使用 `actor.name`，默认“主城导览 · 栖枝” | 英文部署需配置共享剧情演员名称；角色 ID、UUID 和任务状态无需改变。 |
| P1 | AuthMe、Citizens、BetonQuest、EssentialsX、MyPet、BetterHud 等实际部署配置不在当前公开源码树内 | 需要对受权部署逐一核对欢迎、认证、剧情、聊天、错误提示与 HUD，不能根据本仓语言包推断其英文状态。 |
| P2 | [`SessionLeaseListener`](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/SessionLeaseListener.java) 的三个拒绝准入提示为硬编码中文 | 多节点租约功能默认关闭；启用前应把拒绝原因迁入语言服务并验证加入阶段的可用性。 |
| P2 | [`SkillMechanics.beastSpirits`](../../plugins/rpg-skills/src/main/java/cn/originalrpg/skills/SkillMechanics.java) 的狼实体元数据名称为“兽灵” | 默认隐藏名牌，但实体名称仍为中文；可在后续表现层改动中使用明确的共享或施放者语言策略。 |
| P2 | 装备签名失败、外观对账等若干运营日志仍为中文 | 国际运维人员需要英文故障词典；统一日志时应保留稳定故障代码、环境变量名和审计字段。 |

列表是源码检查发现的具体问题，不是全项目所有文字的穷尽清单。不存在“2,995 键一致，因此游戏英文完成率 100%”的推论。

## 5. 验证与后续验收

无需启动服务器的检查：

```bash
python3 -m pip install PyYAML==6.0.3
python3 scripts/check-locales.py
python3 -m unittest discover -s scripts/tests -p 'test_locales.py' -v
```

本轮执行结果：六组资源通过，2,995 个键/语言；**13 个 Python 行为夹具测试通过**。夹具包含重复键、遗漏语言/嵌套键、遗漏或错拼占位符、含连字符占位符、类型错误、布尔键、递归别名、点号路径碰撞、嵌入式资源和空仓误报绿灯。

Java 回归命令（是否运行及结果以本次 PR 的测试记录为准）：

```bash
./gradlew :plugins:rpg-community:test \
  --tests '*CommunityGovernanceHandlerTest*' \
  --tests '*CommunityGovernanceMessagesTest*'
```

真实客户端仍需 `[R][H]`：英文首次进入与认证 → 切换/重登 → 主城对话与导览 → 职业和技能 → 锻造与概率预览 → 市场购买/取消/邮箱 → 副本组队与失败提示 → 英文/中文玩家同服。分别检查 GUI 缩放 2/3/4、英文长文本换行、资源包字体、键位说明、玩家名及物品文本。外部 AI 不可用时也应验证英文静态知识的降级路径。

本次资源门禁和纯消息回归均不替代真实 Paper、PostgreSQL、第三方插件和真人客户端验证。
