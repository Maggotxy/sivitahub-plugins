# 资源包

资源包面向 Minecraft Java Edition 26.2，资源格式为 `88.0`。公开源码仓库现在可直接离线打包完整的已审核客户端资源，无需 `server/`、`website/node_modules`、Java 或运行中的 Paper。

```bash
# 在仓库根目录执行；依赖 Bash / POSIX sh 和 Python 3.10+ 标准库
bash resource-pack/build.sh
# 可选的独立输出目录
bash resource-pack/build.sh --output build/resource-pack-candidate
```

默认产物为 `build/resource-pack/sivita-hub-26.2.zip`，同目录提供 `.zip.sha1`、`.zip.sha256`、`.zip.manifest.json`。清单记录每个文件的来源、长度与 SHA-256，并列出原版、`originalrpg`、BetterHud、BetterModel 四个命名空间和四份 shader 覆盖文件。默认构建只读取入库资源，不运行素材生成器，不修改 PNG 或服务端配置。固定 `SOURCE_DATE_EPOCH`、固定文件顺序与 ZIP 属性使相同内容得到相同压缩包；负向测试和两次独立打包校验见 `scripts/tests/test_resource_pack_build.py`。

English: **the default command builds the complete committed bilingual client pack**. Both `en_us` and `zh_cn` are included. The separate `datapacks/sivita-navigation` supplies the native G-key dialog; its text follows the Minecraft client language and includes English fallback text. Server plugin messages follow `/language en_us`. The public exporter below also supplies all 15 BetterModel server registrations and a bilingual BetterHud layout candidate. Those inputs must be compiled with the pinned external plugins; the committed client baseline does not automatically match a new runtime compilation.

私有部署项目的旧生成/发布链保留为显式 `bash resource-pack/build.sh --deployment`。该模式仍依赖私有模型源文件、BetterHud 配置与 Node 依赖，并可能更新 `server.properties`；只应由维护窗口流程调用。公开构建不会静默降级成缺少 HUD 或模型的基础包。

当前审核总览包含 36 张项目内生成的原创 32x32 像素素材：28 张内容素材覆盖职业武器方向、盾牌、护甲、核心材料、强化物、技能书、拉弓帧、独立弩、独立三叉戟、硫灵锻纹、传承蓝图和旅者罗盘；另有 8 张为新手旅程逐步绘制的专属图标，分别表达主城、职业、五维、探索、狩猎、锻造、槽位揭示与副本，不再借用无关掉落物。六张旧精英/Boss 方块头已移出生产目录并保留在拒绝档案，稀有怪改走完整骨骼模型。八张旅程图现在还通过 `originalrpg:journey` 字体复用到 `/rpghelp` 进度条、当前步骤标题与低频侧栏，不增加实体或粒子包；仅在客户端确认资源包成功加载后显示，未加载时回退到 `✓ / ◆ / ·` 与普通文字。旅者罗盘另由 `GenerateCompassFrames.java` 在显式 `--deployment` 流程中派生 32 张转针帧；背包、热栏与手持全部沿用 26.2 的 `minecraft:compass` 角度分发和 `spawn` 目标，审核过的静态图只在角度无效时兜底，因此 `Player#setCompassTarget` 导航不会被自定义材质破坏。`GenerateHudAtlas.java` 在该部署流程中还会构建血月、法力、暴击、处决、锻造、防护、稀有掉落、技能、主城导航、当前目标和服务 NPC 十一枚专用 HUD 符号，避免把复杂物品图硬缩小后失真。

`tools/GenerateTextures.java` 提供可复现的离线素材基线；默认只补齐缺失文件，设置 `REGENERATE_FALLBACK_TEXTURES=1` 才会覆盖现有纹理，补位图也会用最近邻放大到统一 32x32。锻造弓、弩、三叉戟和盾牌均通过 26.2 的 `condition`、`select` 与 `range_dispatch` 接入原创图标和使用姿态；首次客户端验收仍要逐项检查拉弓、蓄力、投掷、格挡和罗盘四向转针的最终手感。

`runtime-item-models.txt` 是生产插件可能下发的 81 个 `originalrpg:*` 显示键清单。`build.sh` 会在打包前强制验证：清单与 `items/*.json` 一一对应、插件里的直接模型引用属于清单、全部 JSON 可解析、每个物品定义能闭包到模型和纹理、没有孤儿模型/纹理、全部 115 张物品 PNG（83 张基础图与 32 张罗盘帧）为带二值透明通道的 32x32 像素图、HUD 图集尺寸/透明度/唯一性与 U+E000..U+E00A 十一码位严格一致、八个旅程字体提供者与 U+E100..U+E107 严格一致、旅者罗盘保持 26.2 的 33 段方向分发、中英文资源键一致，以及资源格式精确锁定为 88。`scripts/check-content-assets.py` 额外核对 42 个已实现技能与物品/HUD 图标、6 个副本与 15 套怪物模型、BetterModel 的全部 118 个可达部件，以及所有命名空间的模型/纹理/字体引用。任一检查失败都不会发布新 ZIP 或清单；这些检查不替代真实客户端渲染验收。

## 公开服务端资源候选 / Public server resource candidates

```bash
# 服务端候选额外需要 Node.js（只运行标准库）及 PyYAML。
# 不需要 Pillow、Sharp、私有 server/ 或运行中的 Paper。
python3 -m pip install PyYAML==6.0.3
python3 resource-pack/tools/build_server_resources.py --output build/server-resources-candidate
```

输出目录必须是新的，已存在目录、文件或符号链接会被拒绝。`plugins/BetterModel/models/` 包含 15 份完整 `.bbmodel`：3 种血月怪物、6 种副本 Boss、6 种副本波次怪物，共 164 个体块、105 个骨骼。导出器直接复用仓库几何和动画，并逐字节嵌入现存 64×64 正式 PNG；不重新绘制纹理。模型名称与 `BetterModelMobAppearanceService` 的注册映射完全一致，出厂校验覆盖 outliner、骨骼引用、六面 UV 和必要动画。

`plugins/BetterHud/` 包含固定版本 JAR 默认 schema 派生的配置，以及四槽战斗 HUD 候选：42 技能图标、7 职业状态、选中框、冷却遮罩与倒数、法力、转生/本地等级和中英文操作提示。所有占位符必须在 `RpgIntegrations` 的真实接口中存在；`%sivita_hud_ready% == 1` 才显示整个 HUD。操作提示通过已有 `F CAST`／`2x F CYCLE` 及中文位图按玩家 `/language` 选择，不额外创建位图字体。

候选根目录的 `manifest.json` 登记每个输入和产物的 SHA-256，以及 BetterHud schema JAR 的锁定 SHA-512；`README.en.md` 给出英文安装与验收步骤。源码和导出检查不等于外部插件加载成功。必须在隔离服务器中让 BetterHud、BetterModel **分别重新编译**，再把同一次编译的字体码位、模型 dispatch 索引和着色器布局表一起合入最终客户端资源包。不要把新 YAML／注册模型与旧 `build.zip` 或旧 shader switch 表混用。

English: the command exports complete Generic Blockbench model registrations and a practical bilingual four-slot HUD, using existing reviewed PNG bytes. It verifies source references and file integrity. Install these candidates into a fresh staging server with the pinned BetterModel, BetterHud and PlaceholderAPI versions, compile their resources, and test the resulting pack on a real 26.2 client. This command does not claim that external-plugin compilation or client acceptance has happened.

## 英文发行包 / English staging edition

```bash
# 先按主 README 完成依赖和 Java 25 配置，构建真正的全部插件。
./gradlew assemble
bash resource-pack/build.sh
# 必须使用新的发行目录；同时生成同名 .zip 和 .zip.sha256。
python3 scripts/package-edition.py --output build/sivitahub-en-us-26.2
```

打包器要求 25 个真实构建 JAR 全部存在；任何一个缺失、旧资源、错误 Java 版本、缺少构建来源戳或源码指纹不一致都会使整个发行包失败。根 Gradle 将 `META-INF/sivitahub-build.json` 写入每个插件 JAR；所有插件的指纹同时覆盖 `plugins/**/src/main/**`、插件构建脚本、根构建脚本、settings 和 `gradle.properties`。因此，只更新共享 Java API 而遗漏依赖插件重建也不能通过。测试使用的 synthetic JAR 始终只存在于临时夹具，不是可运行的发行产物。

产物包括全部自研插件、规范配置和英文展示覆盖、完整客户端基线包、G 键快捷数据包、双语 BetonQuest 序章会话、`runtime-candidates/` 下的 15 个模型与 HUD 候选，以及每个文件的 SHA-256。候选不会自动写入运行服务器；第三方插件二进制、世界、玩家数据、数据库、密钥和生产配置均不进入发行包。英文 `INSTALL.en.md` 会列出源码声明的外部依赖和具体安装步骤。

相关回归检查：

```bash
python3 -m unittest discover -s scripts/tests -p 'test_navigation.py'
python3 -m unittest discover -s scripts/tests -p 'test_content_assets.py'
python3 -m unittest discover -s scripts/tests -p 'test_resource_pack_build.py'
python3 -m unittest discover -s scripts/tests -p 'test_server_resources.py'
python3 -m unittest discover -s scripts/tests -p 'test_package_edition.py'
```

## 素材来源登记（`ASSET-PROVENANCE.md` / `asset-provenance.json`）

`ASSET-PROVENANCE.md` 是**逐张**的来源登记台账，覆盖 `assets/**` 下全部 **252 张 PNG**
（item 83 + 罗盘帧 32 + betterhud 116 + bettermodel 16 + 字体图集 2 + minecraft 3），
与磁盘一一对应。机器可读版本是 `asset-provenance.json`（键 = **相对仓库根的路径**，形如
`assets/originalrpg/textures/item/xxx.png`；值与 `ASSET-PROVENANCE.md` 的逐张表**同源同构**）。

`category` 六类口径：

| 类别 | 含义 | 覆盖量 |
| --- | --- | --- |
| `generator` | 可由仓库内生成器**确定性重跑** | 129 |
| `derived-from` | 由另一张**已登记素材**派生（skill 图标 37 + 罗盘帧 32） | 69 |
| `ai-prompt` | `ai/` 生产线 Image API 产出，**不可复现**（已登记提示词文件:行号） | 10 |
| `human-override` | 人工覆盖，**生成器旧设计已弃用** ⇒ 按定义不可复现；必须带 `override_reason` + `source_prompt`，并由 `ASSET_REPRO_GATE` **反向断言** | 1 |
| `upstream-betterhud` | BetterHud **编译期**生成的字体图集分页（本仓无 `server/`，只给可重建步骤） | 38 |
| `upstream-vanilla` | 上游/原版发行版自带占位贴图，改动会与上游不兼容 | 5 |
| `reviewed-baseline` | 人工审核基线，**仓库内既无生成器也无提示词** | 0 |

重新生成与自检（**JSON 与那份 .md 都是生成物，不要手改**）：

```bash
# 一次产出 asset-provenance.json + ASSET-PROVENANCE.md（含附录的逐张总表）
python3 resource-pack/tools/generate-provenance.py --write --write-doc
```

**交付给 W12-A3 门禁的四条一致性判据**（均已在本任务实测通过）：

1. `asset-provenance.json` 的键集合 == `cd resource-pack && find assets -name '*.png'`
   （键形如 `assets/...`，**不带** `resource-pack/` 前缀；请勿与 `find resource-pack/assets` 的输出直接比较）；
2. 每个值都有 `category` / `source` / `reproduce_cmd` / `reviewed` 四个字段；
3. `category == "generator"` 的项，其 `source` 必须指向仓库内真实存在的 `tools/*`；
4. `category == "ai-prompt"` 的项，其 `source` 必须形如 `<file>:<line>` 且该行存在。

**对账修正了任务书里的三处口径**（详见 `ASSET-PROVENANCE.md` §2）：

- 42 张 `skill_*` **不是 AI 直出**：`ai/` 下 0 / 42 有对应提示词；
  37 张是 `tools/generate_betterhud_panels.py#skill_menu_items` 从 18px HUD 语义图标派生的
  `derived-from`，另外 5 张（2x2 强调色块）的派生 recipe 已回同步进 `GenerateTextures.java`，
  因此提为 `generator`。
- `ai/prompts.jsonl` 的条目里，**绝大多数不能单独作为当前磁盘纹理的来源证明**
  （多条指向已删除的方块头、多条指向被 `GenerateTextures` 同名覆盖的图）。
- 5 张 forged 变体帧是**独立素材**而非主纹理派生：与 `forged_ranger_weapon` 的不透明像素
  Jaccard ≤ 0.29；其中 crossbow 的 5 个状态、trident 的 2 个姿态在模型层**回指同一张 `layer0`**。

**旧口径下的 6 组待决项已全部裁决**（W13-A2 五组 + W13-A1 三条出清，合并后
`asset-provenance.json.$needsHumanDecision` 为**空数组**）：38 张 BetterHud 字库分页 →
`upstream-betterhud`；5 张上游占位贴图 → `upstream-vanilla`；`sulfur_resonance_imprint`
相似度判定最高 Jaccard 0.099 ⇒ 独立素材，补登 `ai/prompts.jsonl:28`；
生成器脚本经 `git ls-files` 确认**已在库**；20 张法力帧切分与 5 张 skill 的 4 px 差异
则由 W13-A1 回同步进生成器（见下一节的 `human-override` 与 §7 可复现断言）。

## 逐像素基线：`assets-manifest.json`

`resource-pack/assets-manifest.json` 为 `assets/**` 下**每一张** PNG 登记一条记录：

```json
"originalrpg/textures/item/hunt_crystal.png": {
  "fingerprint": "32x32:016c08c5b1ab04770b7e9a65a031f8e0594a959fa0a0574d98997f9a42f79c4f",
  "bytes": 1043
}
```

`fingerprint` 是**解码后像素**的 SHA-256：摘要输入 = 大端 `width`(4) + `height`(4) +
逐像素大端 ARGB(4)，逐行 `y`、逐列 `x`。它**不是** PNG 文件字节的哈希 ——
编码器换元数据 / 换滤波方式 / 换压缩率都会让文件字节变而画面不变，
拿文件字节做基线会天天假红。这套算法与 `tools/PngFingerprint.java` 同构，
仓内实现是 `scripts/asset_fingerprint.py`（本仓没有 JDK，CI 只有 python3）。

清单覆盖 252 张：`originalrpg` 117（83 张物品纹理 + 32 张罗盘帧 + 2 张字体图集）
+ `betterhud` 116 + `bettermodel` 16 + `minecraft` 3。

### 为什么需要它

素材门禁（`scripts/check-resource-pack.sh`）判的是「素材**自洽**」：闭包、32x32、
二值 alpha、HUD 码位、语言键。它判不了「这张图**还是不是原来那张**」——
换一张同样 32x32、同样二值 alpha 的图，八条判据全绿。基线门禁补的就是这个洞：
**未登记**（磁盘有清单无）、**丢失**（清单有磁盘无）、**被改动**（指纹不一致）三种形态都会红。

### 有意更新基线（唯一的正路）

改素材 → 重算清单 → 提交清单，三步缺一不可：

```sh
# 1. 改素材（生成器 / ai/ 生产线 / 手工，都行）
# 2. 重算基线清单（键排序、稳定输出，重复跑字节一致）
python3 scripts/gen-asset-manifest.py
#    只想确认清单是否最新、不写盘：
python3 scripts/gen-asset-manifest.py --check
# 3. 与素材改动一起提交（两步必须同一个 PR）
git add resource-pack/assets resource-pack/assets-manifest.json
git commit -m "feat(resource-pack): 更新某张纹理并同步基线清单"

# 本地自检（CI 里由 `素材逐像素基线门禁（W12-A1）` stage 跑同一条命令）
python3 scripts/check-asset-baseline.py     # 0=全一致 1=有差异 2=清单缺失
```

提交后的 diff 就是评审材料：改了哪几张图（清单里那几行）、新旧指纹各是什么。

### 为什么不能靠 `--force` 跳过

**门禁有意不提供 `--skip` / `--force` / `--update` 之类的开关。**
理由很直接：基线门禁唯一会红的时候，就是「有人改了素材没更新清单」——
而这恰好也是「有人偷偷换了素材」的样子。这两件事在机械层面**无法区分**，
只能靠人看 diff。

一旦留了跳过开关，它就会变成出事时的默认动作：先 `--force` 把 CI 弄绿，
评审时没人再看得见那张图被换了。所以这里没有开关可跳 ——
想让门禁转绿，就只有「重算清单 + 提交」这一条路，那条路会留下可评审的 diff。

配套地，`scripts/selftest-gates.sh` §9 用四组负向对照证明这道门禁**真的会红**：
改一个像素 → RED 并指出是哪张（打印期望/实际指纹）、删一张图 → 报「丢失」、
加一张未登记的图 → 报「未登记」、还原后 → GREEN；另外还断言清单缺失时 exit 2
（不是 0，也不是 1）。该历史自测包含 `.cnb.yml` 接线断言；当前仓库已经移除 CNB 配置，不能据此宣称每次推送会自动执行。公开离线构建会直接调用素材、基线和 HUD 检查；其他检查须按下列命令主动执行。

## 打包与素材门禁的分工

公开默认构建与私有部署构建是两个显式入口。默认构建复用静态检查，不重新绘图，也不修改服务器配置。

| 入口 | 执行位置与依赖 | 检查或产物 |
| --- | --- | --- |
| `bash resource-pack/build.sh` | 公开源码；Bash、POSIX sh、Python 3.10+ 标准库 | 调用素材、像素基线、HUD、内容引用和快捷对话框检查；输出完整 ZIP、SHA-1、SHA-256 和逐文件 manifest |
| `bash resource-pack/build.sh --deployment` | 私有部署仓库；Paper 停机窗口、`server/`、Node/Sharp、Pillow、Java、jq、rg、zip/unzip | 原有素材再生成、BetterHud 编译及同步、安装中的 Paper 版本核查、线上资源包与 `server.properties` 更新；公开仓库单独不能执行此流程 |
| `sh scripts/check-resource-pack.sh` | 公开源码；POSIX sh、Python 标准库 | 检查模型闭包、纹理、字体码位、语言键、罗盘分发和资源格式 |
| `python3 scripts/check-asset-baseline.py` | 公开源码；Python 标准库 | 比对 252 张 PNG 与已登记的像素指纹；默认构建必须通过 |
| `sh scripts/check-asset-reproducible.sh` | 公开源码；JDK 25、Python 标准库 | 在临时目录运行已登记的历史生成器，再逐像素比较；用于主动检查生成器与提交素材的对应关系 |
| `python3 scripts/check-content-assets.py` | 公开源码；Python 标准库 | 对照源码与配置检查 42 技能、6 副本、15 怪物模型、118 模型部件及全部自定义资源引用 |
| `python3 scripts/check-navigation.py` | 公开源码；Python 标准库 | 检查 26.2 原生快捷对话框注册、8 个已注册玩家命令、中英文键和英文回退 |

模型清单、32×32 二值 alpha、字体码位、语言键、罗盘分发及 `pack.mcmeta` 的判据以 `scripts/check-resource-pack.sh` 为准。默认构建直接调用这些检查，不维护第二份判据。当前仓库没有 CNB 配置；这里列出的是可执行的本地检查，不代表已运行任何远端 CI。

基线检查只比对「当前像素与登记像素是否相同」，与资源引用闭包检查互补。默认打包同时要求两者通过；更改素材必须留下可审查的素材与基线差异。

可复现性门禁（`check-asset-reproducible.sh`）与上面三道都不同源：它现场编译并运行
`resource-pack/tools/` 下的 Java 生成器到**临时目录**，再逐像素比对仓库里的对应 PNG。
它补的是基线门禁**结构上管不到**的那个洞：

> 基线门禁问的是「磁盘像素 == 清单里的像素」。有人手改一张纹理、再重跑
> `scripts/gen-asset-manifest.py` 把新像素登记进清单，**两道门禁全绿** ——
> 但生成器再也产不出那张图了（生成器与仓库不同步是**静默**的，没有任何东西在看着它）。

覆盖规格在 `resource-pack/asset-reproducible.json`（70 条资产 / 4 个生成器）：
`GenerateTextures` 36（31 原有 + 5 张 `skill_*`）、`GenerateHudAtlas` 1、
`GenerateSkillHudAtlas` 1、`GenerateCompassFrames` 32 帧（另加 32 个罗盘模型 JSON 逐字节）。

**有意不纳入 `GenerateSivitaHudPanel`**：它是 132x44 的遗留物，`build.sh --deployment` 不调用
（现役 26x91 面板由 `generate_betterhud_panels.py` 产出），输出在
`assets-manifest.json` 里没有条目。把没有对应仓库产物的生成器拖进门禁，
只会得到一条**恒真的假判据** —— 那比没有门禁更坏。

### `human-override`：人工覆盖要反向断言，不能豁免

`ancient_echo_skill_book.png` 是 `ai/skill-book-prompts.jsonl:1`（v2）的人工覆盖，
生成器 `GenerateTextures.book()` 的旧紫皮设计已弃用。它**按定义不可复现**，
因此登记为 `category: human-override`，并在门禁里做**反向断言**：

> 仓库像素必须**不等于**生成器输出。若有人把生成器的旧设计盖回仓库，必须 RED。

为什么不用「豁免」：豁免只能证明「这张图没被检查」，反向断言能证明
「这张图**是人工覆盖的那个版本**」。前者在有人覆盖回旧设计时静默放行，后者立刻变红。
登记必须带 `override_reason` + `source_prompt`，缺了门禁同样 RED ——
否则 `human-override` 会变成一个可以随便贴的免罚标签。

### 有意更新生成器覆盖

改了生成器（或要新增覆盖）后的正路：

```sh
# 1. 跑生成器，逐张确认真的一致（判据行必须是 GREEN）
sh scripts/check-asset-reproducible.sh

# 2. 若新增/改名了生成器输出，同步覆盖规格
$EDITOR resource-pack/asset-reproducible.json

# 3. 若像素真的变了（例如生成器修好了某张图）：同步基线清单
python3 scripts/gen-asset-manifest.py
git add resource-pack/assets resource-pack/assets-manifest.json
git commit -m "feat(resource-pack): 同步生成器与基线"

# 4. 同步来源登记与登记文档（两份都是生成物）
python3 resource-pack/tools/generate-provenance.py --write --write-doc
```

与基线门禁同样，**不做** `--skip` / `--force` 开关：想转绿只有
「改生成器」或「改素材并同步基线」两条路。
`scripts/selftest-gates.sh` §10 用三组负向对照证明它真的会红
（改生成器常量 / 人工覆盖被盖回去 / `human-override` 登记被改）。

## 服务器注册与实际客户端验收

离线 ZIP 包含所有已提交的客户端资产及四份 26.2 着色器覆盖。它不表示服务器已经加载 BetterHud 布局或 BetterModel 的 `.bbmodel` 注册。以下检查属于显式 `--deployment` 和实际安装验收：

- 与安装中的 Paper 26.2 `version.json` 比对资源格式，检查服务器公布的资源包 URL、SHA-1 和 UUID。
- 用安装中的 BetterHud 编译经过审查的 YAML，再同步该次编译的字体、纹理和着色器。不能把另一次编译的码位与旧客户端包混用。
- 加载 BetterModel 服务端模型，验证技能、6 个副本和血月怪物的模型绑定及动画。
- 在真实客户端以不同语言、GUI 缩放和窗口尺寸检验 HUD、对话框、物品模型及战斗可读性。

只有 `bash resource-pack/build.sh --deployment` 成功后，旧部署流程才会写 `server/web/resourcepacks/sivita-hub-26.2.zip` 并更新服务器属性。默认构建只输出 `build/resource-pack/`；不会自动替换线上地址或哈希。详细的客户端验收见 [`docs/RESOURCE_PACK_QA.md`](../docs/RESOURCE_PACK_QA.md)。

`ai/` 提供 Image API 美术生产线：28 条批量提示词 + 3 条技能书提示词，加上独立的旅者罗盘提示词，全部通过 `/v1/images/generations` 生成绿幕原稿，去背后压缩成统一 32x32 调色板；单资产修改通过 `/v1/images/edits` 版本化输出。只有显式传入 `--apply` 才覆盖当前资源，具体命令见 `ai/README.md`。当前 Sub2API Key 的分组必须开启 `allow_image_generation`，否则网关会返回 403。

物品身份仍以服务端签名 PDC 和数据库 `item_id` 为准，模型键只负责显示，绝不作为交易或属性校验依据。
