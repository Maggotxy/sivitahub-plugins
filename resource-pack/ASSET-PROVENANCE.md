# 素材来源登记（ASSET-PROVENANCE）

> 生成者：CNB CodeBuddy NPC，任务 `W12-A2-asset-provenance`。
> 分支 `agent/W12-A2-asset-provenance`；基线 `main @ b0a540d`。
> 机器可读版本：`resource-pack/asset-provenance.json`（键 = **相对仓库根的路径**，形如 `assets/...`；
> 值含 category/source/reproduce_cmd/reviewed），
> 由 `resource-pack/tools/generate-provenance.py` 从**生成器源码 + 提示词文件**推导（不写死人工结论）。
> **本任务只做登记与对账，未改动任何 PNG 的像素内容。**

## 0. 覆盖与口径

覆盖 `resource-pack/assets/**` 下**全部 252 张 PNG**，与磁盘一一对应。
键口径：**相对仓库根**，即 `assets/<子路径>/<文件>.png`（在 `resource-pack/` 下跑 `find assets -name '*.png'` 得到的字符串）。
判据：`asset-provenance.json` 的键集合 == `cd resource-pack && find assets -name '*.png'`（见 §7 证据）。

| 分区 | 张数 | generator | derived-from | ai-prompt | reviewed-baseline |
| --- | --- | --- | --- | --- | --- |
| 物品纹理 | 83 | 31 | 42 | 9 | 1 |
| 旅者罗盘帧 | 32 | 0 | 32 | 0 | 0 |
| betterhud | 116 | 77 | 0 | 0 | 39 |
| bettermodel | 16 | 15 | 0 | 0 | 1 |
| 字体图集 | 2 | 2 | 0 | 0 | 0 |
| minecraft 命名空间 | 3 | 0 | 0 | 0 | 3 |
| **合计** | **252** | **125** | **74** | **9** | **44** |

四类口径：

- `generator`：可由仓库内生成器**确定性重跑**得到（Java/Python/Node 脚本）。可复现命令列里给的是单张取证命令。
- `ai-prompt`：由 `ai/` 美术生产线的 Image API 产出，**不可复现**（需要 Key 与模型），提示词文件:行号已登记。
- `derived-from`：由另一张**已登记素材**确定性派生（罗盘帧、skill 图标）。
- `reviewed-baseline`：人工审核过的基线像素，**仓库内既无生成器也无提示词**。这一类的每一项都在 §6 标注了处置结论。

任务书给的「83 = 31 generator + 52 ai」这一拆分**与实测不完全吻合**，逐条纠正见 §2-②（42 张 skill 是
**派生**，不是 AI 直出；另有 2 张既是 AI 早期原稿、又被 generator 覆盖）。


## 1. 生成器清单（可复现来源）

| 生成器 | 产出 | 数量 | 入口 |
| --- | --- | --- | --- |
| `tools/GenerateTextures.java` | `assets/originalrpg/textures/item/*.png`（31 个键） | 31 | `java -cp <build> GenerateTextures <outdir> [--force]` |
| `tools/GenerateCompassFrames.java` | `textures/item/traveler_compass/frame_00..31.png` + `models/item/traveler_compass_*.json` | 32 | 参数：源图 / 帧目录 / 模型目录 |
| `tools/GenerateHudAtlas.java` | `textures/font/hud_symbols.png`（11 枚 U+E000..U+E00A） | 1 | 参数：输出 PNG |
| `tools/GenerateSkillHudAtlas.java` | `textures/font/class_skill_symbols.png`（48 枚 U+E020..U+E04F） | 1 | 参数：输出 PNG |
| `tools/generate_betterhud_panels.py` | `assets/betterhud/textures/{glyph_skill_*,glyph_class_*,glyph_control_*,image_*}` + `assets/originalrpg/textures/item/skill_*` | 77 + 42 | `python3 tools/generate_betterhud_panels.py`（需 Pillow） |
| `tools/generate-mob-model.mjs` | `assets/bettermodel/textures/item/blood_moon_resonant_husk_*.png` | 1 | `node tools/generate-mob-model.mjs`（需 `website/node_modules/sharp`） |
| `tools/generate_blood_moon_models.py` | `assets/bettermodel/textures/item/blood_moon_{grave_knight,rift_stalker}_*.png` | 2 | `python3 tools/generate_blood_moon_models.py` |
| `tools/generate_dungeon_boss_models.py` | `assets/bettermodel/textures/item/boss_*_*.png` | 6 | `python3 tools/generate_dungeon_boss_models.py` |
| `tools/generate_dungeon_wave_models.py` | `assets/bettermodel/textures/item/dungeon_*_*.png` | 6 | `python3 tools/generate_dungeon_wave_models.py` |

> **一个上游依赖要注意**：`generate_betterhud_panels.py` 与 `generate_*_models.py` 的输出目录写的是
> `ROOT/server/config-templates/BetterHud|BetterModel/...`，`assets/betterhud/**` 与
> `assets/bettermodel/**` 是它们的**下游拷贝**（`build.sh` 里 `cp "$HUD_PANEL_SOURCE" "$HUD_PANEL_PACK"`
> 同款做法）。本仓按 629 文件的入库形态登记为 generator，但**逐张复现需要在部署机（有 `server/`）上跑**
> —— 本仓没有 `server/`，这一点在 §6-⑧ 里作为需人工确认项列出。

## 2. skill_* 42 张与提示词的对账（②）

### 2-① 逐条判定：42 张 skill 图标的提示词覆盖

对 `ai/` 下**全部 44 个提示词来源**（`prompts.jsonl` 27 + `skill-book-prompts.jsonl` 3 +
`boss-concepts.jsonl` 5 + `dungeon-wave-concepts.jsonl` 6 + `prompts/mobs/*.txt` 2 +
`prompts/traveler_compass.txt` 1）逐条做**整词匹配**。判据命令见 §7 证据 ②。

**结论：0 / 42 命中。** 42 张 skill 图标**没有任何一张**在 `ai/` 下有对应提示词。
任务书里「52 张只靠 ai/ 美术生产线」的说法，在**提示词层面不成立**——逐条列出如下。

仅有的两条「字符串看似命中」是**假命中**，已逐条排除：

| skill | 假命中来源 | 命中文本 | 为什么是假命中 |
| --- | --- | --- | --- |
| `fortress` | `ai/prompts.jsonl:23` | `layered steel **fortress** face` | `out` 是 `elite_armored_mask.png`（怪物方块头），与技能图标无关 |
| `heal` | `ai/prompts.jsonl:5` | `Original **healer** wand` | `out` 是 `forged_healer_weapon.png`；匹配靠 `heal` 子串落在 `healer` 上 |

### 2-② 处置结论：42 张全部改为 `derived-from`（不是「补提示词」）

**根因**：42 张 skill 物品纹理由 `tools/generate_betterhud_panels.py#skill_menu_items` 从
**同名的 18px HUD 语义图标**派生（`sivita_icon_skill_<id>.png` → `resize(36) → crop(2,2,34,34)`）。
HUD 图标本身由 `#semantic_icons` 用脚本内的 `CLASS_SKILLS` / `PALETTES` / `SKILL_PALETTES` 处方图
确定性绘制 —— **从 .py 源码到 32px 物品纹理全程可复现，AI 生产线没有参与**。

实测（§7 证据 ③）：42 张里 **37 张与生成器 recipe 逐像素一致**；
另外 **5 张不一致**（`arcane-edge` / `ruin-charge` / `venom-blade` / `void-cut` / `war-cry`，
各差 **4 px**：磁盘版在 2x2 像素块上多了一个强调色（recipe 为 `0a0e13` 面板暗色））
—— 这 5 张登记为 `derived-from` 但标注「与 recipe 差 4 px」，
处置选项：① 重跑生成器覆盖；② 登记为人工覆盖。**属需人工决策项**（§6-⑤）。

不同类图标的判定汇总：

| 图标类 | 张数 | 有对应提示词？ | 登记类别 | 依据 |
| --- | --- | --- | --- | --- |
| `skill_*` | 42 | **0** | `derived-from` | `generate_betterhud_panels.py#skill_menu_items`（37 张逐像素一致；5 张标注不一致） |
| `glyph_skill_*` | 42 | **0** | `generator` | `#semantic_icons`，处方写在脚本内 |
| `glyph_class_*` | 7 | **0** | `generator` | 同上（含 `unchosen` 由 `hud_symbols.png` 第 9 符号裁切） |
| `glyph_control_*` | 4 | **0** | `generator` | `#control_label`，位图写在脚本内 `CONTROL_GLYPHS` |

### 2-③ 提示词覆盖率的真实账（按 `out` 字段逐条核）

`ai/prompts.jsonl` 27 条的 `out` 与当前 83 张物品纹理的对应关系：

| 状态 | 条数 | 说明 |
| --- | --- | --- |
| `out` 命中当前纹理，**且被 `GenerateTextures` 同名覆盖** | 20 | 15 张 `forged_*`/装备 + `mutation_{shard,core}` + `hunt_crystal` + `blood_moon_essence` + `dungeon_core`，其中 `ancient_echo_skill_book` / `common_insight_skill_book` 当前 32x32 由生成器覆盖 |
| `out` 指向已删除的方块头 | 6 | `elite_*_mask` ×4 + `ancient_boss_mask` + `hidden_boss_mask`；`ai/README.md` 已声明移入 `output/rejected/legacy-head-masks`，`build.sh` 用 rg 禁止其回流 |
| `out` 是当前纹理但生成器不覆盖 | 1 | `legacy_blueprint.png`（登记 `ai-prompt`） |

**26 / 27 条 `prompts.jsonl` 的产出不能单独作为当前磁盘纹理的来源证明**（6 条指向已删文件、20 条指向生成器同名覆盖的图）——
这解释了为什么「83 张里只有 9 张能锚到提示词」。

## 3. betterhud 116 张对账（③）

| 子类 | 张数 | 生成器直出 | 判定 |
| --- | --- | --- | --- |
| `glyph_skill_*` | 42 | 42 | `generate_betterhud_panels.py#semantic_icons` 直出（源 `sivita_icon_skill_*`） |
| `glyph_class_*` | 7 | 7 | 同上直出（源 `sivita_icon_class_*`；`unchosen` 由 `hud_symbols.png` 第 9 符号裁切） |
| `glyph_control_*` | 4 | 4 | `#control_label` 直出（位图在 `CONTROL_GLYPHS`） |
| `mana 20 帧` | 20 | 20 | **对不上生成器**：脚本只产出单张 `sivita_mana_fill.png(1x87)`，1..20 帧切分不在仓库内 |
| `面板/徽章/选中/冷却` | 4 | 4 | `#skill_panel` / `#selected_frame` / `#cooldown_mask` / `#class_badge` 直出 |
| `text_font*` | 38 | 0 | **对不上生成器**：BetterHud 编译期字库分页，仓库内无生成器也无提示词 |
| `splitter` | 1 | 0 | **对不上生成器**：BetterHud 通用 256x256 占位贴图 |
| **合计** | **116** | **77** | |

**对不上生成器的共 39 张**，逐张结论：

- `text_font1_1..19.png` + `text_font2_1..19.png`（38 张）：BetterHud 编译期生成的字形图集分页
  （每格 `4x22`），由 `betterhud_26_1/` 上游资产与 BetterHud 打包流程产出。**处置：登记 `reviewed-baseline`；
  门禁只判「被 font JSON 引用」，不判像素。** 若要可复现，须把 BetterHud 的 font 编译纳入 `build.sh`（§6-①）。
- `image_image_sivita_mana_fill_up_20_1..20.png`（20 张）：`#mana_fill` 只产出 1 张 1x87。
  **处置：需人工决策（§6-②）** —— ① 把 20 帧切分移进 `generate_betterhud_panels.py`（首选，可复现）；
  ② 或登记为 `reviewed-baseline`。
- `splitter.png`（1 张）：BetterHud 通用占位贴图。**处置：`reviewed-baseline` + 门禁豁免（§6-③）。**

其余 77 张（49 图标 + 4 控制条 + 4 面板/徽章）全部由 `generate_betterhud_panels.py` 直出，
且 `build.sh` 已用 `PngFingerprint` 把 49 张图标与 4 张控制条逐张与 reviewed 源比对（防静默漂移）。

## 4. bettermodel 16 张对账（④）

| 相对路径 | 产出者 | 登记类别 |
| --- | --- | --- |
| `assets/bettermodel/textures/item/blood_moon_grave_knight_blood_moon_grave_knight.png` | `tools/generate_blood_moon_models.py`（`derive("blood_moon_grave_knight", 0.78, 1.0, 0.82)` → `PALETTES` 重着色） | generator |
| `assets/bettermodel/textures/item/blood_moon_resonant_husk_blood_moon_resonant_husk.png` | `tools/generate-mob-model.mjs`（`textureOutput`） | generator |
| `assets/bettermodel/textures/item/blood_moon_rift_stalker_blood_moon_rift_stalker.png` | `tools/generate_blood_moon_models.py`（`derive("blood_moon_rift_stalker", 0.68, 1.32, 0.72)`） | generator |
| `assets/bettermodel/textures/item/boss_cinnabar_reborn_core_boss_cinnabar_reborn_core.png` | `tools/generate_dungeon_boss_models.py`（`SPECS` 6 条 → `texture(spec)`） | generator |
| `assets/bettermodel/textures/item/boss_ruins_echo_adjudicator_boss_ruins_echo_adjudicator.png` | `tools/generate_dungeon_boss_models.py`（`SPECS` 6 条 → `texture(spec)`） | generator |
| `assets/bettermodel/textures/item/boss_ruins_inscription_warden_boss_ruins_inscription_warden.png` | `tools/generate_dungeon_boss_models.py`（`SPECS` 6 条 → `texture(spec)`） | generator |
| `assets/bettermodel/textures/item/boss_silent_thunder_oathguard_boss_silent_thunder_oathguard.png` | `tools/generate_dungeon_boss_models.py`（`SPECS` 6 条 → `texture(spec)`） | generator |
| `assets/bettermodel/textures/item/boss_storm_vault_keykeeper_boss_storm_vault_keykeeper.png` | `tools/generate_dungeon_boss_models.py`（`SPECS` 6 条 → `texture(spec)`） | generator |
| `assets/bettermodel/textures/item/boss_sulfur_crystal_devourer_boss_sulfur_crystal_devourer.png` | `tools/generate_dungeon_boss_models.py`（`SPECS` 6 条 → `texture(spec)`） | generator |
| `assets/bettermodel/textures/item/dungeon_ruins_echo_sentinel_dungeon_ruins_echo_sentinel.png` | `tools/generate_dungeon_wave_models.py`（`SPECS` 6 条，复用 boss 的 `texture(spec)`） | generator |
| `assets/bettermodel/textures/item/dungeon_ruins_runebound_dungeon_ruins_runebound.png` | `tools/generate_dungeon_wave_models.py`（`SPECS` 6 条，复用 boss 的 `texture(spec)`） | generator |
| `assets/bettermodel/textures/item/dungeon_storm_conductor_dungeon_storm_conductor.png` | `tools/generate_dungeon_wave_models.py`（`SPECS` 6 条，复用 boss 的 `texture(spec)`） | generator |
| `assets/bettermodel/textures/item/dungeon_storm_gust_wisp_dungeon_storm_gust_wisp.png` | `tools/generate_dungeon_wave_models.py`（`SPECS` 6 条，复用 boss 的 `texture(spec)`） | generator |
| `assets/bettermodel/textures/item/dungeon_sulfur_fume_crawler_dungeon_sulfur_fume_crawler.png` | `tools/generate_dungeon_wave_models.py`（`SPECS` 6 条，复用 boss 的 `texture(spec)`） | generator |
| `assets/bettermodel/textures/item/dungeon_sulfur_slag_mote_dungeon_sulfur_slag_mote.png` | `tools/generate_dungeon_wave_models.py`（`SPECS` 6 条，复用 boss 的 `texture(spec)`） | generator |
| `assets/bettermodel/textures/item/mesh_pixel.png` | **都不是** —— 64x64 白色 mesh 占位贴图，被 `models/mesh_triangle_{single,duplex}.json` 引用 | reviewed-baseline |

归属校验方式（§7 证据 ④）：直接读四个生成器的源码常量 ——
`generate-mob-model.mjs` 的 `textureOutput` 常量、`generate_blood_moon_models.py` 的 `derive()` 调用、
`generate_dungeon_boss_models.py` 的 `SPECS = (humanoid(...), ...)`、`generate_dungeon_wave_models.py` 的 `SPECS`。
生成的 PNG 名是 `<model_id>_<model_id>.png`（BetterModel 的 `<namespace>_<name>` 约定），
与 15 张一一对应：无多余、无缺失。

## 5. forged 变体帧 5 张：「派生自主纹理」还是「独立素材」？（⑤）

**判定：独立素材（不是主纹理的派生）。** 三条证据：

**证据 1 — items 定义的状态机用法**（`assets/originalrpg/items/*.json`）：

| 用例 | 分发类型 | 关键字段 | 指向的模型 |
| --- | --- | --- | --- |
| `forged_ranger_bow.json` | `condition`(`using_item`) → `range_dispatch`(`use_duration`, scale 0.05) | `fallback` + `entries[0].threshold=0.65` + `entries[1].threshold=0.9` | `bow_pulling_0` / `bow_pulling_1` / `bow_pulling_2` |
| `forged_ranger_crossbow.json` | `select`(`charge_type`) → `condition`(`using_item`) → `range_dispatch`(`crossbow/pull`) | `cases:[arrow,rocket]`；阈值 0.58 / 1.0 | **5 个状态全指同一张纹理** |
| `forged_ranger_trident.json` | `select`(`display_context`) → `condition`(`using_item`) | `cases:[gui,ground,fixed,on_shelf]`；`on_false`/`on_true` | `trident_in_hand` / `trident_throwing` |
| `forged_shield.json` | `condition`(`using_item`) | `on_false`/`on_true` | `forged_shield` / `forged_shield_blocking` |

**证据 2 — 模型层的 `layer0` 指向**（`assets/originalrpg/models/item/*.json`）：

```
forged_ranger_bow.json               → parent minecraft:item/bow,               layer0 = forged_ranger_weapon（主纹理）
forged_ranger_bow_pulling_0.json     → parent originalrpg:item/forged_ranger_bow, layer0 = ..._pulling_0
forged_ranger_bow_pulling_1.json     → 同上, layer0 = ..._pulling_1
forged_ranger_bow_pulling_2.json     → 同上, layer0 = ..._pulling_2
forged_ranger_crossbow.json          → parent minecraft:item/crossbow,           layer0 = forged_ranger_crossbow
forged_ranger_trident.json           → parent minecraft:item/generated,          layer0 = forged_ranger_trident
forged_ranger_trident_in_hand.json   → parent originalrpg:item/forged_ranger_trident（只改 display）
forged_ranger_trident_throwing.json  → parent originalrpg:item/forged_ranger_trident（只改 display）
```

⇒ **3 张 bow 帧是真·逐帧独立纹理**（各自一个 `layer0`）；
**crossbow 的 5 个状态全部回指同一张 `forged_ranger_crossbow.png`**；
**trident 的 2 个姿态只改 `display`、不改 `layer0`**。

**证据 3 — 像素层面**（32x32；不透明像素集合的 Jaccard，主纹理 = `forged_ranger_weapon`）：

| 变体帧 | 不透明像素 | 与主纹理交集 | Jaccard |
| --- | --- | --- | --- |
| `forged_ranger_bow_pulling_0` | 125 | 31 | 0.072 |
| `forged_ranger_bow_pulling_1` | 115 | 29 | 0.069 |
| `forged_ranger_bow_pulling_2` | 121 | 31 | 0.073 |
| `forged_ranger_crossbow` | 236 | 126 | 0.283 |
| `forged_ranger_trident` | 110 | 30 | 0.072 |

Jaccard ≤ 0.29（若为「旋转 / 裁切派生」应当 > 0.9）⇒ **5 张都是独立像素，不是 `forged_ranger_weapon` 的变形。**

**处置**：5 张全部登记 `ai-prompt`，提示词锚点 = `ai/prompts.jsonl:4`（弓条目）。
但**这 5 张的 `out` 并不在 `prompts.jsonl` 里** —— `prompts.jsonl` 没有 `bow_pulling_*` /
`crossbow` / `trident` 条目。登记时按「同素材族的最近提示词」锚定，并在 §6-⑥ 列出「补逐帧提示词」为可选处置。
`ai/README.md` 正文写「拉弓帧、独立弩、独立三叉戟」属于 28 张内容语义素材，与本次判定一致。

## 6. 需人工决策 / 未决项（不擅自猜测）

| # | 项 | 张数 | 建议处置（需人拍板） |
| --- | --- | --- | --- |
| ① | `betterhud/textures/text_font{1,2}_*.png` | 38 | 保留 `reviewed-baseline`（门禁只判「被 font JSON 引用」）；若要可复现须把 BetterHud font 编译纳入 `build.sh` |
| ② | `betterhud/textures/image_image_sivita_mana_fill_up_20_*.png` | 20 | **首选**把 20 帧切分移进 `generate_betterhud_panels.py`（与 `#mana_fill` 同源、可复现）；次选登记 `reviewed-baseline` |
| ③ | 上游占位贴图：`betterhud/textures/splitter.png`、`bettermodel/textures/item/mesh_pixel.png`、`assets/minecraft/textures/gui/sprites/boss_bar/yellow_background.png、assets/minecraft/textures/gui/bars.png` | 4 | 登记 `reviewed-baseline` 并在 W12-A3 门禁里豁免像素断言（非游戏内容像素） |
| ④ | `originalrpg/textures/item/sulfur_resonance_imprint.png` | 1 | `ai/` 下无任何提示词提到它。补提示词 or 登记 `reviewed-baseline`（README 称其为「硫灵锻纹」28 张之一，但无 `out` 条目） |
| ⑤ | `skill_{arcane-edge,ruin-charge,venom-blade,void-cut,war-cry}.png` | 5 | 与生成器 recipe 差 4 px（2x2 强调色块）。重跑生成器覆盖 or 登记为人工覆盖 |
| ⑥ | `ai/prompts.jsonl:4` 锚定的 5 张变体帧 | 5 | `prompts.jsonl` 无 `bow_pulling_*` / `crossbow` / `trident` 条目；是否补 5 条逐帧提示词 |
| ⑦ | 生成器脚本 `tools/generate-provenance.py` | 1（代码） | 是否入库（本任务选择入库：`asset-provenance.json` 需要可重跑的推导过程，否则是「又一个手工台账」） |
| ⑧ | `build.sh` 的 `python3 generate_betterhud_panels.py` 分支依赖 `server/config-templates/BetterHud/assets`（本仓无 `server/`） | — | 本仓无法逐张复现 betterhud/bettermodel；是否在 CI 侧用「上游产物指纹」代替 |

## 7. 证据（实际跑过的命令与输出）

见 `.cnb/agent-out/W12-A2-asset-provenance.json` 的 `evidence` 字段（命令原文 + 输出原文 + 退出码）。

## 8. 复审与维护

- 重新生成：`python3 resource-pack/tools/generate-provenance.py --write`
- 一致性判据（供 W12-A3 引用）：
  1. `asset-provenance.json.assets` 的键集合 == `cd resource-pack && find assets -name '*.png'`
     （键形如 `assets/...`，不带 `resource-pack/` 前缀）；
  2. 每个值都有 `category` / `source` / `reproduce_cmd` / `reviewed` 四个字段；
  3. `category == "generator"` 的项，其 `reproduce_cmd` 必须指向仓库内存在的生成器文件；
  4. `category == "ai-prompt"` 的项，其 `source` 必须形如 `<file>:<line>` 且该行存在。

## 附：逐张登记总表（与 `asset-provenance.json` 同源）

### ① 物品纹理 83 张

| 相对路径 | 类别 | 来源 | 可复现命令 | 审核状态 |
| --- | --- | --- | --- | --- |
| `assets/originalrpg/textures/item/ancient_echo_skill_book.png` | generator | tools/GenerateTextures.java#ancient_echo_skill_book#ancient_echo_skill_book | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/blood_moon_essence.png` | generator | tools/GenerateTextures.java#blood_moon_essence#blood_moon_essence | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/cinnabar_revelation_skill_book.png` | ai-prompt | ai/skill-book-prompts.jsonl:2 | 见 ASSET-PROVENANCE.md §2；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| `assets/originalrpg/textures/item/common_insight_skill_book.png` | generator | tools/GenerateTextures.java#common_insight_skill_book#common_insight_skill_book | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/dungeon_core.png` | generator | tools/GenerateTextures.java#dungeon_core#dungeon_core | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/forged_assassin_weapon.png` | generator | tools/GenerateTextures.java#forged_assassin_weapon#forged_assassin_weapon | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/forged_boots.png` | generator | tools/GenerateTextures.java#forged_boots#forged_boots | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/forged_chestplate.png` | generator | tools/GenerateTextures.java#forged_chestplate#forged_chestplate | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/forged_healer_weapon.png` | generator | tools/GenerateTextures.java#forged_healer_weapon#forged_healer_weapon | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/forged_helmet.png` | generator | tools/GenerateTextures.java#forged_helmet#forged_helmet | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/forged_leggings.png` | generator | tools/GenerateTextures.java#forged_leggings#forged_leggings | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/forged_mage_weapon.png` | generator | tools/GenerateTextures.java#forged_mage_weapon#forged_mage_weapon | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/forged_ranger_bow_pulling_0.png` | ai-prompt | ai/prompts.jsonl:4 | 见 ASSET-PROVENANCE.md §5-⑤；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| `assets/originalrpg/textures/item/forged_ranger_bow_pulling_1.png` | ai-prompt | ai/prompts.jsonl:4 | 见 ASSET-PROVENANCE.md §5-⑤；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| `assets/originalrpg/textures/item/forged_ranger_bow_pulling_2.png` | ai-prompt | ai/prompts.jsonl:4 | 见 ASSET-PROVENANCE.md §5-⑤；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| `assets/originalrpg/textures/item/forged_ranger_crossbow.png` | ai-prompt | ai/prompts.jsonl:4 | 见 ASSET-PROVENANCE.md §5-⑤；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| `assets/originalrpg/textures/item/forged_ranger_trident.png` | ai-prompt | ai/prompts.jsonl:4 | 见 ASSET-PROVENANCE.md §5-⑤；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| `assets/originalrpg/textures/item/forged_ranger_weapon.png` | generator | tools/GenerateTextures.java#forged_ranger_weapon#forged_ranger_weapon | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/forged_shield.png` | generator | tools/GenerateTextures.java#forged_shield#forged_shield | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/forged_warrior_weapon.png` | generator | tools/GenerateTextures.java#forged_warrior_weapon#forged_warrior_weapon | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/hidden_ticket.png` | generator | tools/GenerateTextures.java#hidden_ticket#hidden_ticket | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/hunt_crystal.png` | generator | tools/GenerateTextures.java#hunt_crystal#hunt_crystal | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/journey_attribute.png` | generator | tools/GenerateTextures.java#journey_attribute#journey_attribute | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/journey_class.png` | generator | tools/GenerateTextures.java#journey_class#journey_class | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/journey_dungeon.png` | generator | tools/GenerateTextures.java#journey_dungeon#journey_dungeon | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/journey_explore.png` | generator | tools/GenerateTextures.java#journey_explore#journey_explore | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/journey_forge.png` | generator | tools/GenerateTextures.java#journey_forge#journey_forge | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/journey_hub.png` | generator | tools/GenerateTextures.java#journey_hub#journey_hub | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/journey_hunt.png` | generator | tools/GenerateTextures.java#journey_hunt#journey_hunt | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/journey_reveal.png` | generator | tools/GenerateTextures.java#journey_reveal#journey_reveal | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/legacy_blueprint.png` | ai-prompt | ai/prompts.jsonl:27 | 见 ASSET-PROVENANCE.md §2；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| `assets/originalrpg/textures/item/lucky_charm.png` | generator | tools/GenerateTextures.java#lucky_charm#lucky_charm | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/mob_boomerang.png` | generator | tools/GenerateTextures.java#mob_boomerang#mob_boomerang | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/mob_corrosive_orb.png` | generator | tools/GenerateTextures.java#mob_corrosive_orb#mob_corrosive_orb | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/mob_seeker_orb.png` | generator | tools/GenerateTextures.java#mob_seeker_orb#mob_seeker_orb | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/mutation_core.png` | generator | tools/GenerateTextures.java#mutation_core#mutation_core | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/mutation_shard.png` | generator | tools/GenerateTextures.java#mutation_shard#mutation_shard | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/protection_stone.png` | generator | tools/GenerateTextures.java#protection_stone#protection_stone | cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png | reviewed-baseline |
| `assets/originalrpg/textures/item/silent_thunder_skill_book.png` | ai-prompt | ai/skill-book-prompts.jsonl:3 | 见 ASSET-PROVENANCE.md §2；重生成走 ai/generate.sh --apply（需 Image API Key） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_adaptive-arsenal.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_adaptive-arsenal.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_arcane-edge.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_arcane-edge.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_ash-domain.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_ash-domain.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_axiomatic-break.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_axiomatic-break.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_battle-trance.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_battle-trance.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_beast-call.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_beast-call.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_blood-rage.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_blood-rage.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_fate-seal.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_fate-seal.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_fireball.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_fireball.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_fortress.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_fortress.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_frost-nova.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_frost-nova.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_gale-step.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_gale-step.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_heal.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_heal.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_horizon-arrow.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_horizon-arrow.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_improvisation.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_improvisation.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_life-thread.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_life-thread.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_mana-surge.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_mana-surge.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_meteor.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_meteor.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_omen-reversal.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_omen-reversal.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_open-form.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_open-form.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_pack-hunt.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_pack-hunt.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_piercing-shot.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_piercing-shot.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_plague-bloom.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_plague-bloom.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_power-shot.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_power-shot.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_rallying-standard.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_rallying-standard.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_renewal.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_renewal.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_ruin-charge.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_ruin-charge.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_rune-reversal.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_rune-reversal.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_sanctuary.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_sanctuary.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_second-wind.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_second-wind.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_shadow-step.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_shadow-step.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_shield-wall.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_shield-wall.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_smoke-bomb.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_smoke-bomb.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_soul-resonance.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_soul-resonance.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_time-lock.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_time-lock.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_toxic-burst.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_toxic-burst.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_unbroken-line.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_unbroken-line.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_vanished-horizon.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_vanished-horizon.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_venom-blade.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_venom-blade.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_void-cut.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_void-cut.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_volley.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_volley.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/skill_war-cry.png` | derived-from | tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_war-cry.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/originalrpg/textures/item/sulfur_resonance_imprint.png` | reviewed-baseline | （缺）ai/ 下无任何提示词提到 sulfur imprint | 无（不可复现） | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass.png` | ai-prompt | ai/prompts/traveler_compass.txt | 见 ai/README.md 的单资产 image_gen 命令（--prompt-file ai/prompts/traveler_compass.txt） | reviewed-baseline |

### ② 旅者罗盘帧 32 张

| 相对路径 | 类别 | 来源 | 可复现命令 | 审核状态 |
| --- | --- | --- | --- | --- |
| `assets/originalrpg/textures/item/traveler_compass/frame_00.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_00 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_01.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_01 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_02.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_02 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_03.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_03 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_04.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_04 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_05.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_05 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_06.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_06 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_07.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_07 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_08.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_08 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_09.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_09 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_10.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_10 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_11.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_11 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_12.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_12 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_13.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_13 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_14.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_14 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_15.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_15 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_16.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_16 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_17.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_17 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_18.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_18 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_19.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_19 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_20.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_20 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_21.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_21 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_22.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_22 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_23.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_23 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_24.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_24 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_25.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_25 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_26.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_26 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_27.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_27 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_28.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_28 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_29.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_29 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_30.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_30 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |
| `assets/originalrpg/textures/item/traveler_compass/frame_31.png` | derived-from | tools/GenerateCompassFrames.java#drawNeedle#frame_31 | cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && java -cp /tmp/rp-build GenerateCompassFrames assets/originalrpg/textures/item/traveler_compass.png assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item | reviewed-baseline |

### ③ betterhud 纹理 116 张

| 相对路径 | 类别 | 来源 | 可复现命令 | 审核状态 |
| --- | --- | --- | --- | --- |
| `assets/betterhud/textures/glyph_class_assassin.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_class_assassin.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_class_freeform.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_class_freeform.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_class_healer.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_class_healer.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_class_mage.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_class_mage.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_class_ranger.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_class_ranger.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_class_unchosen.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_class_unchosen.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_class_warrior.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_class_warrior.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_control_cast_en.png` | generator | tools/generate_betterhud_panels.py#control_label#'F CAST' | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_control_cast_zh.png` | generator | tools/generate_betterhud_panels.py#control_label#'F 施放' | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_control_cycle_en.png` | generator | tools/generate_betterhud_panels.py#control_label#'2x F CYCLE' | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_control_cycle_zh.png` | generator | tools/generate_betterhud_panels.py#control_label#'双击 F 切换' | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_adaptive-arsenal.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_adaptive-arsenal.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_arcane-edge.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_arcane-edge.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_ash-domain.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_ash-domain.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_axiomatic-break.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_axiomatic-break.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_battle-trance.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_battle-trance.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_beast-call.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_beast-call.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_blood-rage.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_blood-rage.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_fate-seal.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_fate-seal.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_fireball.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_fireball.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_fortress.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_fortress.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_frost-nova.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_frost-nova.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_gale-step.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_gale-step.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_heal.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_heal.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_horizon-arrow.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_horizon-arrow.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_improvisation.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_improvisation.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_life-thread.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_life-thread.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_mana-surge.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_mana-surge.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_meteor.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_meteor.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_omen-reversal.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_omen-reversal.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_open-form.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_open-form.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_pack-hunt.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_pack-hunt.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_piercing-shot.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_piercing-shot.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_plague-bloom.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_plague-bloom.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_power-shot.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_power-shot.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_rallying-standard.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_rallying-standard.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_renewal.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_renewal.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_ruin-charge.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_ruin-charge.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_rune-reversal.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_rune-reversal.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_sanctuary.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_sanctuary.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_second-wind.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_second-wind.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_shadow-step.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_shadow-step.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_shield-wall.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_shield-wall.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_smoke-bomb.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_smoke-bomb.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_soul-resonance.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_soul-resonance.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_time-lock.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_time-lock.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_toxic-burst.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_toxic-burst.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_unbroken-line.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_unbroken-line.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_vanished-horizon.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_vanished-horizon.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_venom-blade.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_venom-blade.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_void-cut.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_void-cut.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_volley.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_volley.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/glyph_skill_war-cry.png` | generator | tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_war-cry.png | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_1.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_10.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_11.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_12.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_13.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_14.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_15.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_16.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_17.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_18.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_19.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_2.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_20.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_3.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_4.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_5.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_6.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_7.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_8.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_image_sivita_mana_fill_up_20_9.png` | generator | tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分 | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_sivita_class_badge.png` | generator | tools/generate_betterhud_panels.py#class_badge | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_sivita_skill_cooldown.png` | generator | tools/generate_betterhud_panels.py#cooldown_mask | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_sivita_skill_panel.png` | generator | tools/generate_betterhud_panels.py#skill_panel | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/image_sivita_skill_selected.png` | generator | tools/generate_betterhud_panels.py#selected_frame | 见 §1 表（需 Pillow；写 server/config-templates + 本仓 assets） | reviewed-baseline |
| `assets/betterhud/textures/splitter.png` | reviewed-baseline | （缺）BetterHud 通用 256x256 空白/分割贴图 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_1.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_10.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_11.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_12.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_13.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_14.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_15.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_16.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_17.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_18.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_19.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_2.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_3.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_4.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_5.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_6.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_7.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_8.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font1_9.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_1.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_10.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_11.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_12.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_13.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_14.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_15.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_16.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_17.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_18.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_19.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_2.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_3.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_4.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_5.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_6.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_7.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_8.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |
| `assets/betterhud/textures/text_font2_9.png` | reviewed-baseline | （缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词 | 无（不可复现） | reviewed-baseline |

### ④ bettermodel 纹理 16 张

| 相对路径 | 类别 | 来源 | 可复现命令 | 审核状态 |
| --- | --- | --- | --- | --- |
| `assets/bettermodel/textures/item/blood_moon_grave_knight_blood_moon_grave_knight.png` | generator | tools/generate_blood_moon_models.py#PALETTES#blood_moon_grave_knight | cd resource-pack && python3 tools/generate_blood_moon_models.py | reviewed-baseline |
| `assets/bettermodel/textures/item/blood_moon_resonant_husk_blood_moon_resonant_husk.png` | generator | tools/generate-mob-model.mjs#textureOutput | cd resource-pack && node tools/generate-mob-model.mjs  # 需要 website/node_modules/sharp | reviewed-baseline |
| `assets/bettermodel/textures/item/blood_moon_rift_stalker_blood_moon_rift_stalker.png` | generator | tools/generate_blood_moon_models.py#PALETTES#blood_moon_rift_stalker | cd resource-pack && python3 tools/generate_blood_moon_models.py | reviewed-baseline |
| `assets/bettermodel/textures/item/boss_cinnabar_reborn_core_boss_cinnabar_reborn_core.png` | generator | tools/generate_dungeon_boss_models.py#texture#boss_cinnabar_reborn_core | cd resource-pack && python3 tools/generate_dungeon_boss_models.py | reviewed-baseline |
| `assets/bettermodel/textures/item/boss_ruins_echo_adjudicator_boss_ruins_echo_adjudicator.png` | generator | tools/generate_dungeon_boss_models.py#texture#boss_ruins_echo_adjudicator | cd resource-pack && python3 tools/generate_dungeon_boss_models.py | reviewed-baseline |
| `assets/bettermodel/textures/item/boss_ruins_inscription_warden_boss_ruins_inscription_warden.png` | generator | tools/generate_dungeon_boss_models.py#texture#boss_ruins_inscription_warden | cd resource-pack && python3 tools/generate_dungeon_boss_models.py | reviewed-baseline |
| `assets/bettermodel/textures/item/boss_silent_thunder_oathguard_boss_silent_thunder_oathguard.png` | generator | tools/generate_dungeon_boss_models.py#texture#boss_silent_thunder_oathguard | cd resource-pack && python3 tools/generate_dungeon_boss_models.py | reviewed-baseline |
| `assets/bettermodel/textures/item/boss_storm_vault_keykeeper_boss_storm_vault_keykeeper.png` | generator | tools/generate_dungeon_boss_models.py#texture#boss_storm_vault_keykeeper | cd resource-pack && python3 tools/generate_dungeon_boss_models.py | reviewed-baseline |
| `assets/bettermodel/textures/item/boss_sulfur_crystal_devourer_boss_sulfur_crystal_devourer.png` | generator | tools/generate_dungeon_boss_models.py#texture#boss_sulfur_crystal_devourer | cd resource-pack && python3 tools/generate_dungeon_boss_models.py | reviewed-baseline |
| `assets/bettermodel/textures/item/dungeon_ruins_echo_sentinel_dungeon_ruins_echo_sentinel.png` | generator | tools/generate_dungeon_wave_models.py#SPECS#dungeon_ruins_echo_sentinel | cd resource-pack && python3 tools/generate_dungeon_wave_models.py | reviewed-baseline |
| `assets/bettermodel/textures/item/dungeon_ruins_runebound_dungeon_ruins_runebound.png` | generator | tools/generate_dungeon_wave_models.py#SPECS#dungeon_ruins_runebound | cd resource-pack && python3 tools/generate_dungeon_wave_models.py | reviewed-baseline |
| `assets/bettermodel/textures/item/dungeon_storm_conductor_dungeon_storm_conductor.png` | generator | tools/generate_dungeon_wave_models.py#SPECS#dungeon_storm_conductor | cd resource-pack && python3 tools/generate_dungeon_wave_models.py | reviewed-baseline |
| `assets/bettermodel/textures/item/dungeon_storm_gust_wisp_dungeon_storm_gust_wisp.png` | generator | tools/generate_dungeon_wave_models.py#SPECS#dungeon_storm_gust_wisp | cd resource-pack && python3 tools/generate_dungeon_wave_models.py | reviewed-baseline |
| `assets/bettermodel/textures/item/dungeon_sulfur_fume_crawler_dungeon_sulfur_fume_crawler.png` | generator | tools/generate_dungeon_wave_models.py#SPECS#dungeon_sulfur_fume_crawler | cd resource-pack && python3 tools/generate_dungeon_wave_models.py | reviewed-baseline |
| `assets/bettermodel/textures/item/dungeon_sulfur_slag_mote_dungeon_sulfur_slag_mote.png` | generator | tools/generate_dungeon_wave_models.py#SPECS#dungeon_sulfur_slag_mote | cd resource-pack && python3 tools/generate_dungeon_wave_models.py | reviewed-baseline |
| `assets/bettermodel/textures/item/mesh_pixel.png` | reviewed-baseline | （缺）64x64 白色 mesh 贴图，被 models/mesh_triangle_{single,duplex}.json 引用 | 无（不可复现） | reviewed-baseline |

### ⑤ 字体图集 2 张

| 相对路径 | 类别 | 来源 | 可复现命令 | 审核状态 |
| --- | --- | --- | --- | --- |
| `assets/originalrpg/textures/font/class_skill_symbols.png` | generator | tools/GenerateSkillHudAtlas.java#48 glyphs U+E020..U+E04F | cd resource-pack && javac -d /tmp/rp-build tools/GenerateSkillHudAtlas.java && java -cp /tmp/rp-build GenerateSkillHudAtlas assets/originalrpg/textures/font/class_skill_symbols.png | reviewed-baseline |
| `assets/originalrpg/textures/font/hud_symbols.png` | generator | tools/GenerateHudAtlas.java#11 glyphs U+E000..U+E00A | cd resource-pack && javac -d /tmp/rp-build tools/GenerateHudAtlas.java && java -cp /tmp/rp-build GenerateHudAtlas assets/originalrpg/textures/font/hud_symbols.png | reviewed-baseline |

### ⑥ minecraft 命名空间 3 张

| 相对路径 | 类别 | 来源 | 可复现命令 | 审核状态 |
| --- | --- | --- | --- | --- |
| `assets/minecraft/textures/gui/bars.png` | reviewed-baseline | （缺）原版/上游 UI 贴图（boss bar 进度条） | 无（不可复现） | reviewed-baseline |
| `assets/minecraft/textures/gui/sprites/boss_bar/yellow_background.png` | reviewed-baseline | （缺）原版/上游 UI 贴图（boss bar 进度条） | 无（不可复现） | reviewed-baseline |
| `assets/minecraft/textures/gui/sprites/boss_bar/yellow_progress.png` | reviewed-baseline | （缺）原版/上游 UI 贴图（boss bar 进度条） | 无（不可复现） | reviewed-baseline |

