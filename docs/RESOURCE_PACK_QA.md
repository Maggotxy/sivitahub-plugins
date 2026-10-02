# 26.2 资源包与菜单图标验收

## 当前结论

Minecraft 26.2 服务端 JAR 内的 `version.json` 声明资源格式为 `88.0`，当前 `pack.mcmeta` 与之完全一致。生产目录共有 79 个 `originalrpg:*` 物品定义、81 张顶层物品纹理和 113 张包含旅者罗盘 32 帧的运行时 32x32 RGBA 物品纹理；其中 42 个职业技能各有独立模型/图标，八步旅程各有专属图标，回旋刃、腐蚀弹和巡弋星弹各有独立降级安全的物品模型。旧的六种精英/Boss 方块头已从正式包移到拒绝档案，不再计入生产素材。构建门禁已经证明所有运行时键都能从 `assets/originalrpg/items/<key>.json` 闭包到存在的模型和纹理，没有孤儿资源。HUD 图集固定为 `99x9`，由 11 枚互不重复、只使用全透明/全不透明像素的 `9x9` 字形组成，字体 JSON 严格绑定 U+E000..U+E00A；`originalrpg:journey` 另外把八张旅程原图按 U+E100..U+E107 映射为帮助页与 Dialog 字体图标。

该 ZIP 是 **26.2 专用包**，不是 1.21.x 兼容包。1.21.0–1.21.8 使用旧的 `pack_format` 34/42/46/55/63/64；1.21.9 以后虽然也采用 `min_format` / `max_format`，资源格式仍低于 26.2 的 88。1.21 小版本间还先后改变了数据驱动物品模型，而当前包使用 `on_shelf` 显示上下文等新语义。旧客户端本身也不能直接连接协议 776 的 26.2 服务器，因此不能通过扩大声明范围来伪装兼容；若未来安装跨版本协议层，必须另产一份降级资源包并做逐版本客户端测试。

菜单出现黑紫方块只有两类原因：客户端没有成功加载服务器资源包，或服务端下发了包中不存在/不兼容的 `item_model`。第一类最常见于 ZIP 已更新但 `resource-pack-sha1`、URL 或 UUID 仍指向旧状态；第二类过去不会被构建脚本反向发现。现在 `build.sh` 同时校验并更新部署 SHA/稳定 UUID，并以 `runtime-item-models.txt` 对插件引用做反向门禁，因此缺模型不能再被发布为生产包。

菜单、TAB、右侧战斗轨、BossBar、ActionBar 和结果标题现在还有一层运行时保护：只有当该玩家的
`Player#getResourcePackStatus()` 为 `SUCCESSFULLY_LOADED` 时才应用原创模型与
HUD 字形；下载中、失败或未知状态均逐玩家回退到原版物品和普通符号。因此不再需要
为了保护一个未加载资源包的客户端而全局关闭原创视觉，也不会在资源包尚在应用时抢先
打开黑紫模型。

资源包只解决图标、HUD 字形和自定义实体的客户端模型，不决定箱子 GUI 的槽位、标题、Lore 换行或 NPC 悬浮文字。菜单“排版乱”需要在相应菜单插件中单独做 6x9 槽位与文案长度验收，不能靠扩大纹理或添加更多自定义模型掩盖。

稀有怪模型采用另一条强制门禁：AI 三视图只提供造型方向，不能直接上线；通过审核后必须重建为 `.bbmodel`，并由生成器根据真实体块与 UV 自动输出正/侧/背工程审核图。当前血魔、蚀月射手和裂隙行者各有一套 11 体块、7 骨骼、64×64 纹理和 `idle/walk/spawn` 三个必要动画的完整模型。资源包必须包含三套模型的必需身体组件与纹理；源码若重新出现 `setHelmet` 式怪物头模，构建直接失败。

## 发布命令

统一部署必须先确认无人在线并停止 Paper，再使用：

```bash
cd /home/lamb/minecraft
./gradlew stageRelease
```

`stageRelease` 会先调用 `resource-pack/build.sh` 完成模型闭包、纹理、语言键、
URL、UUID 与 SHA-1 校验，再替换自研插件 JAR；因此新增物品模型时不会出现
“插件已经上线、资源包仍是旧版”的时间窗。脚本会拒绝在 Paper 运行时替换正式 ZIP，
避免正在运行的服务端仍声明旧 SHA、重连客户端却下载到新内容。在线只验证素材时使用
独立候选目录和 `SKIP_PROPERTY_UPDATE=1`，例如：

```bash
candidate_dir=$(mktemp -d /tmp/sivita-resource-pack.XXXXXX)
RESOURCE_PACK_OUTPUT_DIR="$candidate_dir" SKIP_PROPERTY_UPDATE=1 resource-pack/build.sh
```

若 BetterHud 的 YAML 新增或删除了图片/文字布局，旧 `build.zip` 本来就不可能包含
新字形。此时必须在关闭玩家入口的维护窗口使用
`CONFIG_UPDATE_MODE=replace DEFER_RESOURCE_PACK=1 server/bin/bootstrap.sh` 完成第一阶段，
启动 Paper 一次并确认 BetterHud 重新生成 `plugins/BetterHud/build.zip`，随后停服执行
`server/bin/sync-betterhud-pack.sh`、`resource-pack/build.sh` 和
`server/bin/verify-installation.sh`。不得把缺失图片手工塞进 ZIP；字体码位、位置和着色器
必须来自同一次 BetterHud 编译。

> **待办（G-206）**：上述 `server/bin/*.sh`（含 `verify-installation.sh`）与 `sivita-navigation` 数据包当前都不在版本控制内（`server/` 被 `.gitignore` 排除），本页与 `PLUGIN_INTEGRATION.md` 对它们的引用无法在本仓复核。建议后续任务把这些脚本与数据包纳入版本控制或改为本仓生成脚本，属于代码改动，不在文档订正范围内。

如果第一阶段使用隔离的 BetterHud 候选实例，可显式指定该实例目录：

```bash
BETTERHUD_BUILD_DIR=/绝对路径/候选实例/plugins/BetterHud \
  server/bin/sync-betterhud-pack.sh
```

同步脚本会先确认候选的 HUD、图片、布局、占位符和字体配置与版本化模板逐文件一致，
并按解码后的 RGBA 像素核验中英文 `F 施放 / 双按 F 切换` 四张提示图。候选不是当前模板
生成时会直接拒绝，不能用旧 `build.zip` 混入新资源包。

成功输出应包括模型体块/骨骼/动画检查、`Validated 113 RGBA pixel-art textures at 32x32`、`Generated 11 crisp HUD glyphs`、`Validated 11 distinct binary-alpha HUD glyphs at 9x9`、`Generated 32 traveler-compass angle frames` 和新 SHA-1。生成器只在二进制内容变化时改写文件，因此连续两次无改动构建应得到相同 SHA-1。构建失败时不应启动或重载服务端；先修复报告的模型键、父模型、纹理或部署 URL。

## 客户端逐项验收

1. 完全退出客户端，删除该服务器旧的服务器资源包缓存后，以 Minecraft Java 26.2 重进。
2. 接受强制资源包，确认下载地址为当前环境的 `/resourcepacks/sivita-hub-26.2.zip`；局域网默认由官网 8080 端口提供。
3. 打开主菜单、强化、副本、经济与任务页，确认所有原版图标和启用的原创图标都不是黑紫方块。
4. 分别手持锻造弓、弩、三叉戟与盾牌，检查背包、第一/第三人称、拉弓、蓄力、投掷和格挡状态。
5. 用旅者罗盘选择一个导航点，分别站到目标的东、南、西、北四侧，确认背包、热栏与手持视角中的青色针连续转向目标；再拒绝一次资源包，确认它回退为原版罗盘而不是黑紫方块。
6. 逐项确认 HUD 的十一个码位可见且语义正确：血月、法力、暴击、处决、锻造、防护、稀有掉落、技能、主城导航、当前目标、服务 NPC；再打开 `/rpghelp` 确认 U+E100..U+E107 八枚旅程图标按主城到副本顺序显示。右侧战斗轨只显示职业徽记、等级、法力和四个固定技能槽；TAB 只显示职业/等级与最多两个技能状态；当前目标由旅者罗盘、导航和 Dialog 表达。普通文字不得继承位图字体而变成方块。
7. 将客户端语言切换为简体中文和英语各检查一次；资源包语言键已经静态校验同构，但最终字体宽度仍必须由真实客户端确认。
8. 让管理员生成一只血月共鸣尸，依次检查近距离头部断角/下颌、普通战斗距离、转头、行走、受伤闪红和血月夜间光照；原版僵尸身体必须被完整隐藏，模型仍可正常点击和受伤。拒绝资源包或切到严重负载时应恢复一只完整原版怪，而不是隐形、双身体或只剩方块头。
9. 同屏生成 20、64 和超过 64 只候选怪，确认上限外的怪保留完整原版身体；压力状态最多 20 个外壳，严重状态为 0。记录 MSPT、数据包与客户端帧率，不能仅凭空服 TPS 宣称通过。

管理员可以站在安全测试区执行 `/bettermodel spawn blood_moon_resonant_husk husk 1` 只检查模型外观；用 `/bettermodel test blood_moon_resonant_husk idle`、`walk`、`spawn` 分别检查三个动画。该命令是 BetterModel 自带的表现测试器，不会生成 RPG 等级、奖励或掉落资格；玩法验收仍应等待自然生成或通过受控活动生成真正的 `RpgMobs` 实体。

若客户端下载失败，先直接访问资源包 URL 并对照 `server.properties` 的 `resource-pack-sha1`。若下载成功但仍有单个黑紫物品，记录该物品显示名和所在菜单；这代表运行时模型键与构建时扫描边界存在新分支，应先扩展 `runtime-item-models.txt` 与门禁测试，而不是关闭强制资源包。
