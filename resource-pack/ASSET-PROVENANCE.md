# 素材来源登记（ASSET-PROVENANCE）

> 生成者：CNB CodeBuddy NPC，任务 `W14-A1-merge-two-branches`（把 W13-A1 与 W13-A2
> 两个「各做一半」的分支合并成一份：A2 的新类别与登记口径 + A1 的待决项出清）。
> 机器可读版本：`resource-pack/asset-provenance.json`（键 = 相对仓库根的路径，形如 `assets/...`；
> 值含 category/source/reproduce_cmd/reviewed），由 `resource-pack/tools/generate-provenance.py`
> 从**生成器源码 + 提示词文件**推导（不写死人工结论）。
> **本任务只做登记、文档与生成器脚本的改动，未改动任何 PNG 的像素内容**
> （证据：`python3 scripts/check-asset-baseline.py` 仍 GREEN，252 张指纹全一致）。
>
> ⚠️ 本文件是**生成物**：重新生成用
> `python3 resource-pack/tools/generate-provenance.py --write --write-doc`。
> 手改会在下一次生成时被覆盖，且会让 JSON 与文档静默漂移。

## 0. 覆盖与口径

覆盖 `resource-pack/assets/**` 下**全部 252 张 PNG**，与磁盘一一对应。
键口径：**相对仓库根**，即 `assets/<子路径>/<文件>.png`
（在 `resource-pack/` 下跑 `find assets -name '*.png'` 得到的字符串）。
判据：`asset-provenance.json` 的键集合 == `cd resource-pack && find assets -name '*.png'`。

计数表由生成器渲染（分区不匹配会直接报错退出，见 `render_doc()`）：
| 分区 | 张数 | generator | derived-from | ai-prompt | human-override | upstream-betterhud | upstream-vanilla | reviewed-baseline |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 物品纹理 | 83 | 35 | 37 | 10 | 1 | 0 | 0 | 0 |
| 旅者罗盘帧 | 32 | 0 | 32 | 0 | 0 | 0 | 0 | 0 |
| betterhud | 116 | 77 | 0 | 0 | 0 | 38 | 1 | 0 |
| bettermodel | 16 | 15 | 0 | 0 | 0 | 0 | 1 | 0 |
| 字体图集 | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 0 |
| minecraft 命名空间 | 3 | 0 | 0 | 0 | 0 | 0 | 3 | 0 |
| **合计** | **252** | **129** | **69** | **10** | **1** | **38** | **5** | **0** |

六类口径：

- `generator`：可由仓库内生成器**确定性重跑**得到（Java/Python/Node 脚本）。可复现命令列里给的是单张取证命令。
- `derived-from`：由另一张**已登记素材**确定性派生（罗盘帧、37 张 skill 图标）。
- `ai-prompt`：由 `ai/` 美术生产线的 Image API 产出，**不可复现**（需要 Key 与模型），提示词文件:行号已登记。
- `human-override`：人工覆盖，**生成器旧设计已弃用**，因此本张**按定义不可复现**。
  必须带 `override_reason`（为什么弃用生成器旧设计）与 `source_prompt`（采用的是哪条提示词），
  且由 `ASSET_REPRO_GATE` 做**反向断言**：仓库像素必须**不等于**生成器输出。
- `upstream-betterhud`：BetterHud 在服务端**编译期**生成的字体图集分页（38 张）。
  本仓无 `server/`，不能逐张复现；登记可重建步骤而不自己画。
- `upstream-vanilla`：上游发行版（原版客户端 / BetterHud / BetterModel）自带的占位贴图（5 张）。
  本项目**不拥有**其像素，改了会与上游不兼容。
- `reviewed-baseline`：人工审核过的基线像素，**仓库内既无生成器也无提示词**。
  两个分支合并后本类为 0 张。

任务书给的「83 = 31 generator + 52 ai」这一拆分**与实测不完全吻合**，逐条纠正见 §2-②
（42 张 skill 里 37 张是**派生**、5 张已回同步为 generator；另有 1 张既是 AI 早期原稿又被 generator 同名覆盖）。

## 1. 生成器清单（可复现来源）

| 生成器 | 产出 | 数量 | 入口 |
| --- | --- | --- | --- |
| `tools/GenerateTextures.java` | `assets/originalrpg/textures/item/*.png`（36 个键：31 原有 + 5 张 skill_*） | 36 | `java -cp <build> GenerateTextures <outdir> [--force]` |
| `tools/GenerateCompassFrames.java` | `textures/item/traveler_compass/frame_00..31.png` + `models/item/traveler_compass_*.json` | 32 | 参数：源图 / 帧目录 / 模型目录 |
| `tools/GenerateHudAtlas.java` | `textures/font/hud_symbols.png`（11 枚 U+E000..U+E00A） | 1 | 参数：输出 PNG |
| `tools/GenerateSkillHudAtlas.java` | `textures/font/class_skill_symbols.png`（48 枚 U+E020..U+E04F） | 1 | 参数：输出 PNG |
| `tools/generate_betterhud_panels.py` | `assets/betterhud/textures/{glyph_skill_*,glyph_class_*,glyph_control_*,image_*,image_image_sivita_mana_fill_up_20_*}` + `assets/originalrpg/textures/item/skill_*` | 81 + 37 | `python3 tools/generate_betterhud_panels.py`（需 Pillow） |
| `tools/generate-mob-model.mjs` | `assets/bettermodel/textures/item/blood_moon_resonant_husk_*.png` | 1 | `node tools/generate-mob-model.mjs`（需 `website/node_modules/sharp`） |
| `tools/generate_blood_moon_models.py` | `assets/bettermodel/textures/item/blood_moon_{grave_knight,rift_stalker}_*.png` | 2 | `python3 tools/generate_blood_moon_models.py` |
| `tools/generate_dungeon_boss_models.py` | `assets/bettermodel/textures/item/boss_*_*.png` | 6 | `python3 tools/generate_dungeon_boss_models.py` |
| `tools/generate_dungeon_wave_models.py` | `assets/bettermodel/textures/item/dungeon_*_*.png` | 6 | `python3 tools/generate_dungeon_wave_models.py` |

> **一个上游依赖要注意**：`generate_betterhud_panels.py` 与 `generate_*_models.py` 的输出目录写的是
> `ROOT/server/config-templates/BetterHud|BetterModel/...`，`assets/betterhud/**` 与
> `assets/bettermodel/**` 是它们的**下游拷贝**。本仓按入库形态登记为 generator，
> 但**逐张复现需要在部署机（有 `server/`）上跑** —— 本仓没有 `server/`，见 §6-⑧。

## 2. skill_* 42 张与提示词的对账（②）

### 2-① 逐条判定：42 张 skill 图标的提示词覆盖

对 `ai/` 下**全部 44 个提示词来源**（`prompts.jsonl` 28 + `skill-book-prompts.jsonl` 3 +
`boss-concepts.jsonl` 5 + `dungeon-wave-concepts.jsonl` 6 + `prompts/mobs/*.txt` 2 +
`prompts/traveler_compass.txt` 1）逐条做**整词匹配**。

### 2-② 处置结论：37 张 `derived-from` + 5 张 `generator`

42 张里 **37 张**是 `tools/generate_betterhud_panels.py#skill_menu_items` 从 18px HUD 语义图标
最近邻放大 2x 得到 ⇒ `derived-from`；另外 **5 张**（`arcane-edge / ruin-charge / venom-blade /
void-cut / war-cry`）与派生 recipe 差 4 px（2x2 强调色块）。

W13-A1 的处置是**回同步 recipe** 而不是改素材：那 2x2 强调色块补进
`GenerateTextures.java#skillItem`（`SKILL_ACCENTS` + `SKILL_WINDOW`），这 5 张因此提为
`generator`，并由 `ASSET_REPRO_GATE` 逐像素断言。仓库像素**一个字节都没改**。

### 2-③ 提示词覆盖率的真实账

`ai/prompts.jsonl` 的条目里，绝大多数**不能单独作为当前磁盘纹理的来源证明**
（多条指向已删除的方块头、多条指向被 `GenerateTextures` 同名覆盖的图）。
`ancient_echo_skill_book` 与 `common_insight_skill_book` 两张尤其如此：前者采用
`skill-book-prompts.jsonl:1` 的 v2 人工覆盖（登记 `human-override`），后者与生成器逐像素一致
（登记 `generator`，旧提示词降级为历史原稿）。

## 3. betterhud 116 张对账（③）

116 = 81 `generator`（glyph_* / image_* 面板 + 20 帧 mana fill）+ 38 `upstream-betterhud`
（`text_font{1,2}_*.png`）+ 1 `upstream-vanilla`（`splitter.png`）。

20 帧 `image_image_sivita_mana_fill_up_20_*` 在 W13-A1 之前登记为「下游产物」：
脚本只产出单张 `sivita_mana_fill.png(1x87)`，1..20 帧切分不在地仓生成器内。
W13-A1 把切分逻辑（第 i 帧露出底部 `floor(87*i/20)` 行，其余透明）补进
`generate_betterhud_panels.py#mana_fill` ⇒ 这 20 张从此逐像素可复现，登记为 `generator`。

## 4. bettermodel 16 张对账（④）

16 = 15 `generator`（`blood_moon_*` 3 + `boss_*` 6 + `dungeon_*` 6）+ 1 `upstream-vanilla`
（`mesh_pixel.png`）。

顺带更正 W12-A2 的一处笔误：`mesh_pixel.png` 实测 **16x16**（不是 64x64），
它是 BetterModel 发行版自带的白色着色占位贴图。

## 5. forged 变体帧 5 张：「派生自主纹理」还是「独立素材」？（⑤）

**判定：独立素材（不是主纹理的派生）。** 依据：模型层 `layer0` 指向逐个不同（3 张 bow 帧各有一个
`layer0`；crossbow 的 5 个状态、trident 的 2 个姿态则**回指同一张**），且与主纹理
`forged_ranger_weapon` 的不透明像素 **Jaccard ≤ 0.29**（若为旋转/裁切派生应 > 0.9）。
登记 `ai-prompt`，锚点 `ai/prompts.jsonl:4`（弓条目）。

## 6. 裁决记录（W13-A2 的五组 + W13-A1 的三条出清）

> 两个分支各自只做了一半，本波把它们合并：下表是**并集**。
> 结论已落进 `asset-provenance.json`，`$needsHumanDecision` 因此为**空数组**。

| # | 项 | 张数 | 裁决 | 来源 |
| --- | --- | --- | --- | --- |
| ① | `betterhud/text_font{1,2}_*.png` | 38 | `upstream-betterhud`（给可重建步骤） | W13-A2 |
| ② | `betterhud/splitter.png` + `bettermodel/mesh_pixel.png` + `minecraft/**`(3) | 5 | `upstream-vanilla` | W13-A2 |
| ③ | `originalrpg/item/sulfur_resonance_imprint.png` | 1 | `ai-prompt`（独立素材） | W13-A2 |
| ④ | `tools/generate-provenance.py` 是否入库 | 1（代码） | **已在库**（无需动作） | W13-A2 |
| ⑤ | `skill_{arcane-edge,ruin-charge,venom-blade,void-cut,war-cry}.png` | 5 | `generator`（recipe 已回同步） | W13-A1 |
| ⑥ | `image_image_sivita_mana_fill_up_20_*.png` | 20 | `generator`（切分已进生成器） | W13-A1 |
| ⑦ | `ancient_echo_skill_book.png` | 1 | `human-override` + 反向断言 | W13-A1 |

### 6-① 38 张 BetterHud 字体图集 → `upstream-betterhud`

**结论**：登记新类别 `upstream-betterhud`，来源写「BetterHud 编译期生成」，给出可重建步骤；
**不试图自己画这 38 张**。

**依据**（3 条可核事实）：
- 每张解码后均为 **4x22**，是位图字体图集的**单列分页**；38 = 2 组分页 × 19 列。
- `assets/betterhud/font/default.json` 逐条引用 `betterhud:text_font1_1..19.png`；
  各 `hud_sivita_hud_text_<a>_<b>_1.json` 引用 `text_font2_1..19.png`。
  即**字库定义在仓库内是完整的**，缺的只是「编译期把字形渲染进图集」这一步。
- `docs/RESOURCE_PACK_QA.md` 明确：字体码位、位置和着色器**必须来自同一次 BetterHud 编译**，
  且「不得把缺失图片手工塞进 ZIP」。手工画会直接违反这条。

**可重建步骤**（照 `docs/RESOURCE_PACK_QA.md` 的 `sync-betterhud-pack.sh` 流程）：

```sh
# 前提：部署机上有 server/（本仓没有），且 YAML 布局已更新
CONFIG_UPDATE_MODE=replace DEFER_RESOURCE_PACK=1 server/bin/bootstrap.sh   # 第一阶段
# 启动 Paper 一次，确认 BetterHud 重新生成 plugins/BetterHud/build.zip
server/bin/sync-betterhud-pack.sh        # 从 build.zip 同步字形/着色器回本仓
resource-pack/build.sh                   # 重新打包并更新 SHA-1
server/bin/verify-installation.sh
```

**门禁口径**：只判「被 font JSON 引用」，不判像素。

### 6-② 5 张上游/原版占位贴图 → `upstream-vanilla`

**结论**：登记新类别 `upstream-vanilla`，逐张写明上游来源；**为什么不该改**：这些像素不属于本项目，
改动会与上游不兼容。逐张：

| 相对路径 | 上游来源 | 尺寸 | 为什么不该改 |
| --- | --- | --- | --- |
| `assets/betterhud/textures/splitter.png` | BetterHud 发行版自带通用占位/分割贴图 | 256x256 | BetterHud 升级会覆盖；改它等于把上游资产私有化 |
| `assets/bettermodel/textures/item/mesh_pixel.png` | BetterModel 发行版自带 mesh 占位贴图 | 16x16 | 被 `models/mesh_triangle_{single,duplex}.json` 引用；它只是着色基色，改了没有语义收益 |
| `assets/minecraft/textures/gui/bars.png` | **原版客户端** GUI 条图集 | 256x256 | 全服多处共享；覆盖它会让其它 HUD 一起变形 |
| `assets/minecraft/textures/gui/sprites/boss_bar/yellow_background.png` | **原版客户端** boss_bar 精灵图 | 182x5 | 原版 boss bar 九宫格底图；改了会与客户端期望不一致（错位/黑紫） |
| `assets/minecraft/textures/gui/sprites/boss_bar/yellow_progress.png` | **原版客户端** boss_bar 精灵图 | 182x5 | 同上（进度层） |

> 顺带更正 W12-A2 的一处笔误：`mesh_pixel.png` 实测 **16x16**，不是 64x64。

### 6-③ `sulfur_resonance_imprint.png`（1 张）→ `ai-prompt`（独立素材）

**先做相似度判定**（不透明像素集合 + RGB 的 Jaccard，与同目录全部 32x32 物品纹理逐对比）：

| 排名 | 对照纹理 | Jaccard | 交集像素 |
| --- | --- | --- | --- |
| 1 | `silent_thunder_skill_book.png` | **0.099** | 85 |
| 2 | `cinnabar_revelation_skill_book.png` | 0.089 | 78 |
| 3 | `traveler_compass.png` | 0.084 | 90 |
| 4 | `ancient_echo_skill_book.png` | 0.077 | 74 |
| 5 | `forged_ranger_crossbow.png` | 0.039 | 29 |

最高 Jaccard 仅 **0.099**，远低于派生阈值（≥0.9）⇒ **独立素材**。
据此在 `resource-pack/ai/prompts.jsonl` 补一条**与实测像素一致**的提示词（`:28`）。

## 7. 可复现性断言（W13-A1 的 `ASSET_REPRO_GATE`）

`scripts/check-asset-reproducible.sh` 现场编译并运行 `resource-pack/tools/` 下的 Java 生成器到
临时目录，再逐像素比对仓库里的对应 PNG。判据：

| 判据 | 含义 | 红了说明什么 |
| --- | --- | --- |
| `reproducible` | 生成器输出 == 仓库像素 | 正常 |
| 不一致 | 生成器输出 != 仓库像素 | 生成器与仓库不同步（**静默漂移**）；或生成器被改坏 |
| 漏覆盖 / 未登记 | 生成器吐出的文件不在规格里 | 有人加了生成器输出却没登记进 `asset-reproducible.json` |
| **反向断言** | `human-override` 项必须**不等于**生成器输出 | 生成器旧设计被盖回仓库 |

覆盖规格在 `resource-pack/asset-reproducible.json`（70 条资产 / 4 个生成器）：
`GenerateTextures` 36、`GenerateHudAtlas` 1、`GenerateSkillHudAtlas` 1、
`GenerateCompassFrames` 32 帧（另加 32 个罗盘模型 JSON 逐字节）。

**有意不纳入 `GenerateSivitaHudPanel`**：它是 132x44 的遗留物，`resource-pack/build.sh`
不调用它（现役 26x91 面板由 `generate_betterhud_panels.py` 产出），它的输出在
`assets-manifest.json` 里没有对应条目。把没有对应仓库产物的生成器拖进门禁，
只会得到一条恒真的假判据 —— 这比没有门禁更坏。

### 7-① `ancient_echo_skill_book` 的人工覆盖登记

| 字段 | 值 |
| --- | --- |
| `category` | `human-override` |
| `source_prompt` | `ai/skill-book-prompts.jsonl:1`（`out = ancient_echo_skill_book_v2.png`） |
| `override_reason` | 仓库里的是 v2 人工覆盖（sealed echo grimoire：pale-violet 同心回响符文 + 断裂金印 + 青色共鸣碎粒）；生成器 `book()` 的旧紫皮设计已弃用 |
| 门禁行为 | **反向断言** —— 仓库像素必须**不等于**生成器输出；若有人把生成器旧设计盖回仓库，`ASSET_REPRO_GATE=RED` |

为什么用反向断言而不是「豁免」：豁免只能证明「这张图没被检查」，
反向断言能证明「这张图**是人工覆盖的那个版本**」。前者在有人覆盖回旧设计时静默放行，
后者会立刻变红。

### 7-②（遗留）本仓无 `server/`，betterhud/bettermodel 无法逐张复现

`generate_betterhud_panels.py` 与 `generate_*_models.py` 的输出目录指向
`server/config-templates/...`，本仓是下游拷贝。逐张复现需在部署机上跑。
作为已知边界保留，不在登记口径里假装可复现。

## 8. 复审与维护

- 重新生成：`python3 resource-pack/tools/generate-provenance.py --write --write-doc`
  （JSON 与本文档一次产出；两份都是生成物，不要手改）
- 基线门禁：`python3 scripts/check-asset-baseline.py`（0=GREEN）
- 可复现性门禁：`sh scripts/check-asset-reproducible.sh`（判据行 `ASSET_REPRO_GATE=GREEN`）
- 一致性判据：
  1. `asset-provenance.json.assets` 的键集合 == `cd resource-pack && find assets -name '*.png'`
     （键形如 `assets/...`，不带 `resource-pack/` 前缀）；
  2. 每个值都有 `category` / `source` / `reproduce_cmd` / `reviewed` 四个字段；
  3. `category == "generator"` 的项，其 `reproduce_cmd` 必须指向仓库内存在的生成器文件；
  4. `category == "ai-prompt"` 的项，其 `source` 必须形如 `<file>:<line>` 且该行存在；
  5. `category == "human-override"` 的项必须带非空 `override_reason` 与 `source_prompt`
     （`ASSET_REPRO_GATE` 会校验）。

## 附：逐张登记总表（与 `asset-provenance.json` 同源）

> 本节由 `python3 resource-pack/tools/generate-provenance.py --write-doc` 渲染，**不要手改**：
> 表里的每一行都来自 `asset-provenance.json`，两者不一致即为漂移。

### ① 物品纹理 83 张

| 相对路径 | 类别 | 来源 | 可复现命令 | 审核状态 |
| --- | --- | --- | --- | --- |
| `assets/originalrpg/textures/item/ancient_echo_skill_book.png` | human-override | ai/skill-book-prompts.jsonl:1 # out=ancient_echo_skill_book_v2.png | 无（人工覆盖；生成器 book() 的旧紫皮已弃用，故本张**不可**由生成器复现） | reviewed-baseline |
| | | override_reason: 仓库里的是 ai/skill-book-prompts.jsonl:1 的 v2 人工覆盖（sealed echo grimoire，pale-violet 同心回响符文 + 断裂金印 + 青色共鸣碎粒），生成器 GenerateTextures.book() 的旧紫皮设计已弃用；ASSET_REPRO_GATE 对本张做反向断言 —— 必须**不等于**生成器输出，若有人把生成器旧设计盖回仓库，门禁必须 RED | | |
| `assets/originalrpg/textures/item/blood_moon_essence.png` | generator | tools/GenerateTextures.java#blood_moon_essence#blood_moon_essence | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/cinnabar_revelation_skill_book.png` | ai-prompt | ai/skill-book-prompts.jsonl:2 | 见 ASSET-PROVENANCE.md §2；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| | | 唯一有逐张提示词的技能书；复用地脉/雷库设定 | | |
| `assets/originalrpg/textures/item/common_insight_skill_book.png` | generator | tools/GenerateTextures.java#common_insight_skill_book#common_insight_skill_book | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担；ai/prompts.jsonl:18 是早期 1024px 原稿版本，当前 32x32 以生成器为准 | | |
| `assets/originalrpg/textures/item/dungeon_core.png` | generator | tools/GenerateTextures.java#dungeon_core#dungeon_core | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/forged_assassin_weapon.png` | generator | tools/GenerateTextures.java#forged_assassin_weapon#forged_assassin_weapon | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/forged_boots.png` | generator | tools/GenerateTextures.java#forged_boots#forged_boots | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/forged_chestplate.png` | generator | tools/GenerateTextures.java#forged_chestplate#forged_chestplate | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/forged_healer_weapon.png` | generator | tools/GenerateTextures.java#forged_healer_weapon#forged_healer_weapon | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/forged_helmet.png` | generator | tools/GenerateTextures.java#forged_helmet#forged_helmet | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/forged_leggings.png` | generator | tools/GenerateTextures.java#forged_leggings#forged_leggings | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/forged_mage_weapon.png` | generator | tools/GenerateTextures.java#forged_mage_weapon#forged_mage_weapon | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/forged_ranger_bow_pulling_0.png` | ai-prompt | ai/prompts.jsonl:4 | 见 ASSET-PROVENANCE.md §5-⑤；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| | | 独立素材：与主纹理不透明像素 IoU<=0.29，非派生；物品定义走 condition/select/range_dispatch 状态分发，每帧各自带 layer0 | | |
| `assets/originalrpg/textures/item/forged_ranger_bow_pulling_1.png` | ai-prompt | ai/prompts.jsonl:4 | 见 ASSET-PROVENANCE.md §5-⑤；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| | | 独立素材：与主纹理不透明像素 IoU<=0.29，非派生；物品定义走 condition/select/range_dispatch 状态分发，每帧各自带 layer0 | | |
| `assets/originalrpg/textures/item/forged_ranger_bow_pulling_2.png` | ai-prompt | ai/prompts.jsonl:4 | 见 ASSET-PROVENANCE.md §5-⑤；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| | | 独立素材：与主纹理不透明像素 IoU<=0.29，非派生；物品定义走 condition/select/range_dispatch 状态分发，每帧各自带 layer0 | | |
| `assets/originalrpg/textures/item/forged_ranger_crossbow.png` | ai-prompt | ai/prompts.jsonl:4 | 见 ASSET-PROVENANCE.md §5-⑤；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| | | 独立素材：与主纹理不透明像素 IoU<=0.29，非派生；物品定义走 condition/select/range_dispatch 状态分发，每帧各自带 layer0 | | |
| `assets/originalrpg/textures/item/forged_ranger_trident.png` | ai-prompt | ai/prompts.jsonl:4 | 见 ASSET-PROVENANCE.md §5-⑤；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| | | 独立素材：与主纹理不透明像素 IoU<=0.29，非派生；物品定义走 condition/select/range_dispatch 状态分发，每帧各自带 layer0 | | |
| `assets/originalrpg/textures/item/forged_ranger_weapon.png` | generator | tools/GenerateTextures.java#forged_ranger_weapon#forged_ranger_weapon | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/forged_shield.png` | generator | tools/GenerateTextures.java#forged_shield#forged_shield | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/forged_warrior_weapon.png` | generator | tools/GenerateTextures.java#forged_warrior_weapon#forged_warrior_weapon | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/hidden_ticket.png` | generator | tools/GenerateTextures.java#hidden_ticket#hidden_ticket | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/hunt_crystal.png` | generator | tools/GenerateTextures.java#hunt_crystal#hunt_crystal | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/journey_attribute.png` | generator | tools/GenerateTextures.java#journey_attribute#journey_attribute | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/journey_class.png` | generator | tools/GenerateTextures.java#journey_class#journey_class | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/journey_dungeon.png` | generator | tools/GenerateTextures.java#journey_dungeon#journey_dungeon | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/journey_explore.png` | generator | tools/GenerateTextures.java#journey_explore#journey_explore | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/journey_forge.png` | generator | tools/GenerateTextures.java#journey_forge#journey_forge | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/journey_hub.png` | generator | tools/GenerateTextures.java#journey_hub#journey_hub | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/journey_hunt.png` | generator | tools/GenerateTextures.java#journey_hunt#journey_hunt | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/journey_reveal.png` | generator | tools/GenerateTextures.java#journey_reveal#journey_reveal | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/legacy_blueprint.png` | ai-prompt | ai/prompts.jsonl:27 | 见 ASSET-PROVENANCE.md §2；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| `assets/originalrpg/textures/item/lucky_charm.png` | generator | tools/GenerateTextures.java#lucky_charm#lucky_charm | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/mob_boomerang.png` | generator | tools/GenerateTextures.java#mob_boomerang#mob_boomerang | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/mob_corrosive_orb.png` | generator | tools/GenerateTextures.java#mob_corrosive_orb#mob_corrosive_orb | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/mob_seeker_orb.png` | generator | tools/GenerateTextures.java#mob_seeker_orb#mob_seeker_orb | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/mutation_core.png` | generator | tools/GenerateTextures.java#mutation_core#mutation_core | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/mutation_shard.png` | generator | tools/GenerateTextures.java#mutation_shard#mutation_shard | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/protection_stone.png` | generator | tools/GenerateTextures.java#protection_stone#protection_stone | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担 | | |
| `assets/originalrpg/textures/item/silent_thunder_skill_book.png` | ai-prompt | ai/skill-book-prompts.jsonl:3 | 见 ASSET-PROVENANCE.md §2；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| | | 唯一有逐张提示词的技能书；复用地脉/雷库设定 | | |
| `assets/originalrpg/textures/item/skill_adaptive-arsenal.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_adaptive-arsenal.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_arcane-edge.png` | generator | tools/GenerateTextures.java#skillItem#skill_arcane-edge | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理；W13-A1 把派生 recipe 漏掉的 2x2 强调色块（SKILL_ACCENTS）回同步进 GenerateTextures.java，本张从此逐像素可复现，由 ASSET_REPRO_GATE 断言 | | |
| `assets/originalrpg/textures/item/skill_ash-domain.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_ash-domain.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_axiomatic-break.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_axiomatic-break.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_battle-trance.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_battle-trance.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_beast-call.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_beast-call.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_blood-rage.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_blood-rage.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_fate-seal.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_fate-seal.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_fireball.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_fireball.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_fortress.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_fortress.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_frost-nova.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_frost-nova.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_gale-step.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_gale-step.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_heal.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_heal.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_horizon-arrow.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_horizon-arrow.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_improvisation.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_improvisation.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_life-thread.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_life-thread.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_mana-surge.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_mana-surge.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_meteor.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_meteor.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_omen-reversal.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_omen-reversal.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_open-form.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_open-form.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_pack-hunt.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_pack-hunt.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_piercing-shot.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_piercing-shot.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_plague-bloom.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_plague-bloom.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_power-shot.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_power-shot.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_rallying-standard.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_rallying-standard.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_renewal.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_renewal.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_ruin-charge.png` | generator | tools/GenerateTextures.java#skillItem#skill_ruin-charge | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理；W13-A1 把派生 recipe 漏掉的 2x2 强调色块（SKILL_ACCENTS）回同步进 GenerateTextures.java，本张从此逐像素可复现，由 ASSET_REPRO_GATE 断言 | | |
| `assets/originalrpg/textures/item/skill_rune-reversal.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_rune-reversal.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_sanctuary.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_sanctuary.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_second-wind.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_second-wind.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_shadow-step.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_shadow-step.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_shield-wall.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_shield-wall.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_smoke-bomb.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_smoke-bomb.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_soul-resonance.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_soul-resonance.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_time-lock.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_time-lock.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_toxic-burst.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_toxic-burst.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_unbroken-line.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_unbroken-line.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_vanished-horizon.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_vanished-horizon.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_venom-blade.png` | generator | tools/GenerateTextures.java#skillItem#skill_venom-blade | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理；W13-A1 把派生 recipe 漏掉的 2x2 强调色块（SKILL_ACCENTS）回同步进 GenerateTextures.java，本张从此逐像素可复现，由 ASSET_REPRO_GATE 断言 | | |
| `assets/originalrpg/textures/item/skill_void-cut.png` | generator | tools/GenerateTextures.java#skillItem#skill_void-cut | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理；W13-A1 把派生 recipe 漏掉的 2x2 强调色块（SKILL_ACCENTS）回同步进 GenerateTextures.java，本张从此逐像素可复现，由 ASSET_REPRO_GATE 断言 | | |
| `assets/originalrpg/textures/item/skill_volley.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_volley.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出） | | |
| `assets/originalrpg/textures/item/skill_war-cry.png` | generator | tools/GenerateTextures.java#skillItem#skill_war-cry | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| | | 由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理；W13-A1 把派生 recipe 漏掉的 2x2 强调色块（SKILL_ACCENTS）回同步进 GenerateTextures.java，本张从此逐像素可复现，由 ASSET_REPRO_GATE 断言 | | |
| `assets/originalrpg/textures/item/sulfur_resonance_imprint.png` | ai-prompt | ai/prompts.jsonl:28 | 见 ASSET-PROVENANCE.md §6-④；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| | | W13-A2 补登；相似度判定最高 Jaccard=0.099 ⇒ 独立素材（非派生），提示词按实测像素写（32x32 二值 alpha、15 色：暗紫黑底盘 + 琥珀金菱形核心 + 钢灰环） | | |
| `assets/originalrpg/textures/item/traveler_compass.png` | ai-prompt | ai/prompts/traveler_compass.txt | 见 ai/README.md 的单资产 image_gen 命令（--prompt-file ai/prompts/traveler_compass.txt） | reviewed-baseline |
| | | 静态兜底图；32 帧转针由 GenerateCompassFrames 派生 | | |

### ② 旅者罗盘帧 32 张

| 相对路径 | 类别 | 来源 | 可复现命令 | 审核状态 |
| --- | --- | --- | --- | --- |
| `assets/originalrpg/textures/item/traveler_compass/frame_00.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_00 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_01.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_01 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_02.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_02 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_03.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_03 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_04.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_04 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_05.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_05 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_06.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_06 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_07.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_07 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_08.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_08 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_09.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_09 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_10.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_10 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_11.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_11 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_12.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_12 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_13.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_13 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_14.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_14 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_15.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_15 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_16.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_16 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_17.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_17 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_18.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_18 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_19.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_19 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_20.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_20 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_21.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_21 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_22.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_22 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_23.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_23 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_24.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_24 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_25.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_25 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_26.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_26 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_27.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_27 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_28.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_28 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_29.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_29 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_30.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_30 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |
| `assets/originalrpg/textures/item/traveler_compass/frame_31.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_31 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| | | 派生物；基图是 ai/prompts/traveler_compass.txt | | |

### ③ betterhud 纹理 116 张

| 相对路径 | 类别 | 来源 | 可复现命令 | 审核状态 |
| --- | --- | --- | --- | --- |
| `assets/betterhud/textures/glyph_class_assassin.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_class_assassin.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_class_freeform.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_class_freeform.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_class_healer.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_class_healer.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_class_mage.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_class_mage.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_class_ranger.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_class_ranger.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_class_unchosen.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_class_unchosen.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_class_warrior.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_class_warrior.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_control_cast_en.png` | generator | tools/generate_betterhud_panels.py#control_label#'F CAST' | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移；字形来自脚本内嵌 CONTROL_GLYPHS | | |
| `assets/betterhud/textures/glyph_control_cast_zh.png` | generator | tools/generate_betterhud_panels.py#control_label#'F 施放' | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移；字形来自脚本内嵌 CONTROL_GLYPHS | | |
| `assets/betterhud/textures/glyph_control_cycle_en.png` | generator | tools/generate_betterhud_panels.py#control_label#'2x F CYCLE' | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移；字形来自脚本内嵌 CONTROL_GLYPHS | | |
| `assets/betterhud/textures/glyph_control_cycle_zh.png` | generator | tools/generate_betterhud_panels.py#control_label#'双击 F 切换' | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移；字形来自脚本内嵌 CONTROL_GLYPHS | | |
| `assets/betterhud/textures/glyph_skill_adaptive-arsenal.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_adaptive-arsenal.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_arcane-edge.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_arcane-edge.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_ash-domain.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_ash-domain.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_axiomatic-break.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_axiomatic-break.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_battle-trance.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_battle-trance.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_beast-call.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_beast-call.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_blood-rage.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_blood-rage.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_fate-seal.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_fate-seal.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_fireball.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_fireball.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_fortress.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_fortress.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_frost-nova.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_frost-nova.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_gale-step.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_gale-step.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_heal.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_heal.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_horizon-arrow.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_horizon-arrow.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_improvisation.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_improvisation.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_life-thread.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_life-thread.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_mana-surge.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_mana-surge.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_meteor.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_meteor.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_omen-reversal.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_omen-reversal.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_open-form.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_open-form.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_pack-hunt.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_pack-hunt.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_piercing-shot.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_piercing-shot.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_plague-bloom.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_plague-bloom.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_power-shot.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_power-shot.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_rallying-standard.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_rallying-standard.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_renewal.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_renewal.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_ruin-charge.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_ruin-charge.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_rune-reversal.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_rune-reversal.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_sanctuary.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_sanctuary.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_second-wind.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_second-wind.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_shadow-step.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_shadow-step.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_shield-wall.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_shield-wall.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_smoke-bomb.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_smoke-bomb.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_soul-resonance.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_soul-resonance.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_time-lock.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_time-lock.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_toxic-burst.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_toxic-burst.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_unbroken-line.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_unbroken-line.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_vanished-horizon.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_vanished-horizon.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_venom-blade.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_venom-blade.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_void-cut.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_void-cut.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_volley.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_volley.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/glyph_skill_war-cry.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_war-cry.png | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_1.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_1of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_10.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_10of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_11.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_11of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_12.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_12of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_13.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_13of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_14.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_14of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_15.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_15of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_16.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_16of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_17.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_17of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_18.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_18of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_19.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_19of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_2.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_2of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_20.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_20of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_3.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_3of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_4.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_4of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_5.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_5of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_6.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_6of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_7.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_7of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_8.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_8of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_9.png` | generator | tools/generate_betterhud_panels.py#mana_fill#frame_9of20 | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现 | | |
| `assets/betterhud/textures/image_sivita_class_badge.png` | generator | tools/generate_betterhud_panels.py#class_badge | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/image_sivita_skill_cooldown.png` | generator | tools/generate_betterhud_panels.py#cooldown_mask | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/image_sivita_skill_panel.png` | generator | tools/generate_betterhud_panels.py#skill_panel | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/image_sivita_skill_selected.png` | generator | tools/generate_betterhud_panels.py#selected_frame | cd resource-pack && python3 tools/generate_betterhud_panels.py # 需要 Pillow；同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item | reviewed-baseline |
| | | build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移 | | |
| `assets/betterhud/textures/splitter.png` | upstream-vanilla | 上游：BetterHud 发行版自带通用占位/分割贴图（256x256） | 无（上游产物，本项目不重建） | reviewed-baseline |
| | | 上游发行版自带，本项目**不拥有**其像素；改动会与上游不兼容（BetterHud 占位图/原版 GUI 图集被版本升级覆盖，且原版 bars.png 被多个原版与 mod HUD 共享）。故只登记不重建，门禁不判像素。 | | |
| `assets/betterhud/textures/text_font1_1.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_1.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_10.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_10.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_11.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_11.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_12.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_12.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_13.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_13.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_14.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_14.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_15.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_15.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_16.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_16.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_17.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_17.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_18.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_18.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_19.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_19.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_2.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_2.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_3.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_3.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_4.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_4.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_5.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_5.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_6.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_6.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_7.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_7.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_8.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_8.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font1_9.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font1_9.png（series 1：正文默认字号） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_1.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_1.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_10.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_10.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_11.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_11.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_12.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_12.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_13.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_13.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_14.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_14.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_15.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_15.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_16.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_16.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_17.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_17.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_18.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_18.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_19.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_19.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_2.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_2.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_3.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_3.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_4.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_4.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_5.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_5.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_6.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_6.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_7.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_7.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_8.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_8.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |
| `assets/betterhud/textures/text_font2_9.png` | upstream-betterhud | BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 betterhud:text_font2_9.png（series 2：各字号缩放分页） | 见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → Paper 起停一次生成 plugins/BetterHud/build.zip → server/bin/sync-betterhud-pack.sh → resource-pack/build.sh | reviewed-baseline |
| | | 上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素 | | |

### ④ bettermodel 纹理 16 张

| 相对路径 | 类别 | 来源 | 可复现命令 | 审核状态 |
| --- | --- | --- | --- | --- |
| `assets/bettermodel/textures/item/blood_moon_grave_knight_blood_moon_grave_knight.png` | generator | tools/generate_blood_moon_models.py#PALETTES#blood_moon_grave_knight | cd resource-pack && python3 tools/generate_blood_moon_models.py | reviewed-baseline |
| | | 64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」；由 blood_moon_resonant_husk.bbmodel 内嵌纹理按调色板重着色 | | |
| `assets/bettermodel/textures/item/blood_moon_resonant_husk_blood_moon_resonant_husk.png` | generator | tools/generate-mob-model.mjs#textureOutput | cd resource-pack && node tools/generate-mob-model.mjs # 需要 website/node_modules/sharp | reviewed-baseline |
| | | 64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」（build.sh 在每次打包时重跑） | | |
| `assets/bettermodel/textures/item/blood_moon_rift_stalker_blood_moon_rift_stalker.png` | generator | tools/generate_blood_moon_models.py#PALETTES#blood_moon_rift_stalker | cd resource-pack && python3 tools/generate_blood_moon_models.py | reviewed-baseline |
| | | 64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」；由 blood_moon_resonant_husk.bbmodel 内嵌纹理按调色板重着色 | | |
| `assets/bettermodel/textures/item/boss_cinnabar_reborn_core_boss_cinnabar_reborn_core.png` | generator | tools/generate_dungeon_boss_models.py#texture#boss_cinnabar_reborn_core | cd resource-pack && python3 tools/generate_dungeon_boss_models.py | reviewed-baseline |
| | | 64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」 | | |
| `assets/bettermodel/textures/item/boss_ruins_echo_adjudicator_boss_ruins_echo_adjudicator.png` | generator | tools/generate_dungeon_boss_models.py#texture#boss_ruins_echo_adjudicator | cd resource-pack && python3 tools/generate_dungeon_boss_models.py | reviewed-baseline |
| | | 64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」 | | |
| `assets/bettermodel/textures/item/boss_ruins_inscription_warden_boss_ruins_inscription_warden.png` | generator | tools/generate_dungeon_boss_models.py#texture#boss_ruins_inscription_warden | cd resource-pack && python3 tools/generate_dungeon_boss_models.py | reviewed-baseline |
| | | 64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」 | | |
| `assets/bettermodel/textures/item/boss_silent_thunder_oathguard_boss_silent_thunder_oathguard.png` | generator | tools/generate_dungeon_boss_models.py#texture#boss_silent_thunder_oathguard | cd resource-pack && python3 tools/generate_dungeon_boss_models.py | reviewed-baseline |
| | | 64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」 | | |
| `assets/bettermodel/textures/item/boss_storm_vault_keykeeper_boss_storm_vault_keykeeper.png` | generator | tools/generate_dungeon_boss_models.py#texture#boss_storm_vault_keykeeper | cd resource-pack && python3 tools/generate_dungeon_boss_models.py | reviewed-baseline |
| | | 64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」 | | |
| `assets/bettermodel/textures/item/boss_sulfur_crystal_devourer_boss_sulfur_crystal_devourer.png` | generator | tools/generate_dungeon_boss_models.py#texture#boss_sulfur_crystal_devourer | cd resource-pack && python3 tools/generate_dungeon_boss_models.py | reviewed-baseline |
| | | 64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」 | | |
| `assets/bettermodel/textures/item/dungeon_ruins_echo_sentinel_dungeon_ruins_echo_sentinel.png` | generator | tools/generate_dungeon_wave_models.py#SPECS#dungeon_ruins_echo_sentinel | cd resource-pack && python3 tools/generate_dungeon_wave_models.py | reviewed-baseline |
| | | 64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」；纹理走共用 texture(spec) 配方 | | |
| `assets/bettermodel/textures/item/dungeon_ruins_runebound_dungeon_ruins_runebound.png` | generator | tools/generate_dungeon_wave_models.py#SPECS#dungeon_ruins_runebound | cd resource-pack && python3 tools/generate_dungeon_wave_models.py | reviewed-baseline |
| | | 64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」；纹理走共用 texture(spec) 配方 | | |
| `assets/bettermodel/textures/item/dungeon_storm_conductor_dungeon_storm_conductor.png` | generator | tools/generate_dungeon_wave_models.py#SPECS#dungeon_storm_conductor | cd resource-pack && python3 tools/generate_dungeon_wave_models.py | reviewed-baseline |
| | | 64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」；纹理走共用 texture(spec) 配方 | | |
| `assets/bettermodel/textures/item/dungeon_storm_gust_wisp_dungeon_storm_gust_wisp.png` | generator | tools/generate_dungeon_wave_models.py#SPECS#dungeon_storm_gust_wisp | cd resource-pack && python3 tools/generate_dungeon_wave_models.py | reviewed-baseline |
| | | 64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」；纹理走共用 texture(spec) 配方 | | |
| `assets/bettermodel/textures/item/dungeon_sulfur_fume_crawler_dungeon_sulfur_fume_crawler.png` | generator | tools/generate_dungeon_wave_models.py#SPECS#dungeon_sulfur_fume_crawler | cd resource-pack && python3 tools/generate_dungeon_wave_models.py | reviewed-baseline |
| | | 64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」；纹理走共用 texture(spec) 配方 | | |
| `assets/bettermodel/textures/item/dungeon_sulfur_slag_mote_dungeon_sulfur_slag_mote.png` | generator | tools/generate_dungeon_wave_models.py#SPECS#dungeon_sulfur_slag_mote | cd resource-pack && python3 tools/generate_dungeon_wave_models.py | reviewed-baseline |
| | | 64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」；纹理走共用 texture(spec) 配方 | | |
| `assets/bettermodel/textures/item/mesh_pixel.png` | upstream-vanilla | 上游：BetterModel 发行版自带 16x16 白色 mesh 占位贴图，被 models/mesh_triangle_{single,duplex}.json 引用 | 无（上游产物，本项目不重建） | reviewed-baseline |
| | | 上游发行版自带，本项目**不拥有**其像素；改动会与上游不兼容（BetterHud 占位图/原版 GUI 图集被版本升级覆盖，且原版 bars.png 被多个原版与 mod HUD 共享）。故只登记不重建，门禁不判像素。；实测解码后为 16x16（W12-A2 记的 64x64 有误，W13-A2 更正） | | |

### ⑤ 字体图集 2 张

| 相对路径 | 类别 | 来源 | 可复现命令 | 审核状态 |
| --- | --- | --- | --- | --- |
| `assets/originalrpg/textures/font/class_skill_symbols.png` | generator | tools/GenerateSkillHudAtlas.java#48 glyphs U+E020..U+E04F | cd resource-pack && javac -d /tmp/rp-build tools/GenerateSkillHudAtlas.java && java -cp /tmp/rp-build GenerateSkillHudAtlas assets/originalrpg/textures/font/class_skill_symbols.png | reviewed-baseline |
| `assets/originalrpg/textures/font/hud_symbols.png` | generator | tools/GenerateHudAtlas.java#11 glyphs U+E000..U+E00A | cd resource-pack && javac -d /tmp/rp-build tools/GenerateHudAtlas.java && java -cp /tmp/rp-build GenerateHudAtlas assets/originalrpg/textures/font/hud_symbols.png | reviewed-baseline |

### ⑥ minecraft 命名空间 3 张

| 相对路径 | 类别 | 来源 | 可复现命令 | 审核状态 |
| --- | --- | --- | --- | --- |
| `assets/minecraft/textures/gui/bars.png` | upstream-vanilla | 上游：原版客户端 assets/minecraft/textures/gui/bars.png（256x256 通用 GUI 条图集） | 无（上游产物，本项目不重建） | reviewed-baseline |
| | | 上游发行版自带，本项目**不拥有**其像素；改动会与上游不兼容（BetterHud 占位图/原版 GUI 图集被版本升级覆盖，且原版 bars.png 被多个原版与 mod HUD 共享）。故只登记不重建，门禁不判像素。；改动会让客户端与原版 GUI 期望不一致（黑紫方块/错位） | | |
| `assets/minecraft/textures/gui/sprites/boss_bar/yellow_background.png` | upstream-vanilla | 上游：原版客户端 boss_bar 精灵图（yellow_background，182x5） | 无（上游产物，本项目不重建） | reviewed-baseline |
| | | 上游发行版自带，本项目**不拥有**其像素；改动会与上游不兼容（BetterHud 占位图/原版 GUI 图集被版本升级覆盖，且原版 bars.png 被多个原版与 mod HUD 共享）。故只登记不重建，门禁不判像素。；改动会让客户端与原版 GUI 期望不一致（黑紫方块/错位） | | |
| `assets/minecraft/textures/gui/sprites/boss_bar/yellow_progress.png` | upstream-vanilla | 上游：原版客户端 boss_bar 精灵图（yellow_progress，182x5） | 无（上游产物，本项目不重建） | reviewed-baseline |
| | | 上游发行版自带，本项目**不拥有**其像素；改动会与上游不兼容（BetterHud 占位图/原版 GUI 图集被版本升级覆盖，且原版 bars.png 被多个原版与 mod HUD 共享）。故只登记不重建，门禁不判像素。；改动会让客户端与原版 GUI 期望不一致（黑紫方块/错位） | | |
