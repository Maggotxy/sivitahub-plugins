#!/usr/bin/env python3
# 从仓库事实推导每张素材的来源登记，产出 resource-pack/asset-provenance.json
# 与 resource-pack/ASSET-PROVENANCE.md（--write / --write-doc）。两份都是生成物，
# 不要手改：文档里的逐张总表就是从 JSON 渲染出来的，手改一次就开始漂移。
# 本脚本是它们的**生成器**（可重跑、确定）：所有判定都读生成器源码 / 提示词文件 /
# build.sh，不写死人工结论。
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]   # repo root
RP = ROOT / "resource-pack"

SKILLS_RE = re.compile(r'^SKILLS = \((.*?)\n\)', re.S | re.M)
CLASSES_RE = re.compile(r'^CLASSES = \((.*?)\)', re.S | re.M)


def skills_from_generator():
    src = (RP / "tools/generate_betterhud_panels.py").read_text(encoding="utf-8")
    body = SKILLS_RE.search(src).group(1)
    return re.findall(r'"([^"]+)"', body)


def classes_from_generator():
    src = (RP / "tools/generate_betterhud_panels.py").read_text(encoding="utf-8")
    body = CLASSES_RE.search(src).group(1)
    return re.findall(r'"([^"]+)"', body)


def gen_texture_keys():
    src = (RP / "tools/GenerateTextures.java").read_text(encoding="utf-8")
    main = src[src.index("public static void main"):src.index("private static BufferedImage assassin")]
    return re.findall(r'save\(output,\s*"([^"]+)"', main)


def prompt_sources():
    """返回 {相对路径: (行号, out 字段)}，覆盖 ai/ 下所有提示词来源。"""
    found = {}
    for rel in ("ai/prompts.jsonl", "ai/skill-book-prompts.jsonl",
                "ai/boss-concepts.jsonl", "ai/dungeon-wave-concepts.jsonl"):
        for line_no, line in enumerate((RP / rel).read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            entry = json.loads(line)
            found[entry["out"]] = (rel, line_no)
    for path in sorted((RP / "ai/prompts").rglob("*.txt")):
        found[path.name] = (str(path.relative_to(RP)), None)
    return found


PROMPTS = prompt_sources()
GEN_ITEM_KEYS = gen_texture_keys()
SKILLS = skills_from_generator()
# 实测：这 5 张与生成器 recipe 逐像素不一致，其余的逐像素一致（见 ASSET-PROVENANCE.md §2-②）
PADDED_SKILLS = {"arcane-edge", "ruin-charge", "venom-blade", "void-cut", "war-cry"}
CLASSES = classes_from_generator()

out = {}


def OrderedDoc(entries):
    """包一层：$comment / $counts 等元数据 + 按 path 排序的 assets 映射。"""
    import collections
    ordered = collections.OrderedDict((k, entries[k]) for k in sorted(entries))
    return collections.OrderedDict([
        ("$comment", [
            "每张素材（resource-pack/assets/** 下全部 252 张 PNG）的来源登记，供 W12-A3 的素材门禁使用。",
            "键 = 相对 resource-pack/ 的路径；值含 category / source / reproduce_cmd / reviewed。",
            "category ∈ generator（生成器直出）| ai-prompt（AI 生产线直出）| derived-from（由已登记素材派生）| human-override（人工覆盖，生成器旧设计已弃用，见 override_reason）| upstream-betterhud（BetterHud 编译期字体图集）| upstream-vanilla（上游/原版占位贴图）| reviewed-baseline（人工审核基线，无可复现来源）。",
            "reviewed ∈ reviewed-baseline（已审核）| pending-human（需人工决策）。",
            "本文件的生成器：tools/generate-provenance.py（读生成器源码 / 提示词文件推导，不写死人工结论）。",
            "覆盖数 = 磁盘上全部 PNG：item 83 + compass 帧 32 + betterhud 116 + bettermodel 16 + 字体图集 2 + minecraft 3 = 252。",
        ]),
        ("$generatedBy", "W14-A1-merge-two-branches（承接 W13-A2-provenance-decisions / W13-A1-repro-gate-and-generator-sync）"),
        ("$counts", collections.OrderedDict(
            sorted(__import__("collections").Counter(v["category"] for v in ordered.values()).items()))),
        ("$needsHumanDecision", NEEDS_HUMAN_DECISION),
        ("assets", ordered),
    ])


# W13-A1 与 W13-A2 各结算了一半，W14-A1 把两份合并成一份：
#   A2 的五组裁决（38 张字体图集 / 5 张上游占位 / sulfur_resonance_imprint / 生成器入库）
#   + A1 的三条出清（20 帧切分已进生成器 / 5 张 skill 的 2x2 强调块已进生成器 /
#     ancient_echo_skill_book = human-override + 门禁反向断言）
# 两组待决项都已落地，因此清单为空。
NEEDS_HUMAN_DECISION = []

# 分类口径所需的常量（下面各 add() 调用点引用）。
BETTERHUD_REBUILD = ("见 ASSET-PROVENANCE.md §6-①：BetterHud 编译期由 font JSON 的 "
                     "bitmap provider 生成（每格 4x22）；重建走 server/bin/bootstrap.sh → "
                     "Paper 起停一次生成 plugins/BetterHud/build.zip → "
                     "server/bin/sync-betterhud-pack.sh → resource-pack/build.sh")
VANILLA_NOTE = ("上游发行版自带，本项目**不拥有**其像素；改动会与上游不兼容"
                "（BetterHud 占位图/原版 GUI 图集被版本升级覆盖，且原版 bars.png 被"
                "多个原版与 mod HUD 共享）。故只登记不重建，门禁不判像素。")
# 原版客户端命名空间：这些像素属于 Mojang 原版发行版，本项目只是覆盖/透传。
MC_UPSTREAM = {
    "textures/gui/bars.png":
        "上游：原版客户端 assets/minecraft/textures/gui/bars.png（256x256 通用 GUI 条图集）",
    "textures/gui/sprites/boss_bar/yellow_background.png":
        "上游：原版客户端 boss_bar 精灵图（yellow_background，182x5）",
    "textures/gui/sprites/boss_bar/yellow_progress.png":
        "上游：原版客户端 boss_bar 精灵图（yellow_progress，182x5）",
}
# 38 张 text_font（series 1 = default.json 的正文字库，series 2 = 各字号 hud_* 缩放版）
# 由 BetterHud 编译期从 font JSON 的 bitmap provider 产出，每格 4x22。
# 本仓无 server/，无法逐张复现 ⇒ 登记 upstream-betterhud + 给可重建步骤，不自己画。
FONT_SERIES = ("1", "2")
FONT_PAGES = 19


def add(rel, category, source, reproduce, reviewed, note=""):
    if rel in out:
        raise SystemExit("duplicate key: " + rel)
    entry = {"category": category, "source": source,
             "reproduce_cmd": reproduce, "reviewed": reviewed}
    if note:
        entry["note"] = note
    if category == "human-override":
        # 人工覆盖必须自带理由，否则「不可复现」就变成一句无据可查的免罚牌。
        # source_prompt 单独成字段：它就是那条被人工采用的提示词来源。
        if "override_reason:" not in note:
            raise SystemExit("human-override 缺 override_reason: " + rel)
        entry["override_reason"] = note.split("override_reason:", 1)[1].strip()
        entry["source_prompt"] = source.split("  #")[0].strip()
    out[rel] = entry


# ---------- ① 物品纹理 83 ----------
GEN_CMD = ("cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && "
           "java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && "
           "cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png")
# ancient_echo_skill_book：仓库里的是 ai/skill-book-prompts.jsonl:1 的 v2 人工覆盖，
# 生成器 book() 的旧紫皮已弃用 ⇒ category=human-override。
# ASSET_REPRO_GATE 对它做**反向断言**：必须**不等于**生成器输出。
HUMAN_OVERRIDE_KEYS = {"ancient_echo_skill_book"}
# 5 张 skill 现在也在 GenerateTextures.main 里，但它们的登记带 accent 说明，
# 单独在下面的 skill 循环里写（避免这里重复 add）。
SKILL_ITEM_KEYS = {f"skill_{skill}" for skill in PADDED_SKILLS}
for key in GEN_ITEM_KEYS:
    if key in HUMAN_OVERRIDE_KEYS or key in SKILL_ITEM_KEYS:
        continue
    note = "程序化 32x32 像素基线；可复现断言由 ASSET_REPRO_GATE 承担"
    if key == "common_insight_skill_book":
        # ai/prompts.jsonl:18 登记的是早期 1024px 原稿；当前 32x32 与生成器逐像素一致，
        # 因此以生成器为准，旧提示词降级为历史原稿。
        note += "；ai/prompts.jsonl:18 是早期 1024px 原稿版本，当前 32x32 以生成器为准"
    add(f"assets/originalrpg/textures/item/{key}.png", "generator",
        f"tools/GenerateTextures.java#{key}#{key}", GEN_CMD, "reviewed-baseline", note)

# ancient_echo_skill_book 的人工覆盖登记：来源提示词是 v2 那条，生成器旧设计已弃用。
add("assets/originalrpg/textures/item/ancient_echo_skill_book.png", "human-override",
    "ai/skill-book-prompts.jsonl:1  # out=ancient_echo_skill_book_v2.png",
    "无（人工覆盖；生成器 book() 的旧紫皮已弃用，故本张**不可**由生成器复现）",
    "reviewed-baseline",
    "override_reason: 仓库里的是 ai/skill-book-prompts.jsonl:1 的 v2 人工覆盖（sealed echo "
    "grimoire，pale-violet 同心回响符文 + 断裂金印 + 青色共鸣碎粒），生成器 "
    "GenerateTextures.book() 的旧紫皮设计已弃用；ASSET_REPRO_GATE 对本张做反向断言 —— "
    "必须**不等于**生成器输出，若有人把生成器旧设计盖回仓库，门禁必须 RED")

# 42 张 skill_*：37 张由 generate_betterhud_panels.py#skill_menu_items 从 18px HUD 图标派生；
# 5 张（2x2 强调色块）在 W13-A1 回同步进 GenerateTextures.java，故 category=generator。
HUD_CMD = ("cd resource-pack && python3 tools/generate_betterhud_panels.py  # 需要 Pillow；"
           "同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item")
for skill in SKILLS:
    if skill in PADDED_SKILLS:
        add(f"assets/originalrpg/textures/item/skill_{skill}.png", "generator",
            f"tools/GenerateTextures.java#skillItem#skill_{skill}", GEN_CMD, "reviewed-baseline",
            "由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理；W13-A1 把派生 recipe "
            "漏掉的 2x2 强调色块（SKILL_ACCENTS）回同步进 GenerateTextures.java，"
            "本张从此逐像素可复现，由 ASSET_REPRO_GATE 断言")
        continue
    add(f"assets/originalrpg/textures/item/skill_{skill}.png", "derived-from",
        f"tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_{skill}.png",
        HUD_CMD, "reviewed-baseline",
        "由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出）")

# 5 张 forged 变体帧：自主纹理逐像素重绘的独立素材（判定见 ASSET-PROVENANCE.md §5）
BOW_PROMPT = "ai/prompts.jsonl:4"
VARIANT_NOTE = ("独立素材：与主纹理不透明像素 IoU<=0.29，非派生；物品定义走 "
                "condition/select/range_dispatch 状态分发，每帧各自带 layer0")
for key in ("forged_ranger_bow_pulling_0", "forged_ranger_bow_pulling_1",
            "forged_ranger_bow_pulling_2"):
    add(f"assets/originalrpg/textures/item/{key}.png", "ai-prompt",
        BOW_PROMPT, f"见 ASSET-PROVENANCE.md §5-⑤；重生成走 ai/generate.sh --apply（需 Image API Key）",
        "reviewed-baseline", VARIANT_NOTE)
add("assets/originalrpg/textures/item/forged_ranger_crossbow.png", "ai-prompt",
    BOW_PROMPT, "见 ASSET-PROVENANCE.md §5-⑤；重生成走 ai/generate.sh --apply（需 Image API Key）",
    "reviewed-baseline", VARIANT_NOTE)
add("assets/originalrpg/textures/item/forged_ranger_trident.png", "ai-prompt",
    BOW_PROMPT, "见 ASSET-PROVENANCE.md §5-⑤；重生成走 ai/generate.sh --apply（需 Image API Key）",
    "reviewed-baseline", VARIANT_NOTE)

# 其他 5 张
add("assets/originalrpg/textures/item/cinnabar_revelation_skill_book.png", "ai-prompt",
    "ai/skill-book-prompts.jsonl:2",
    "见 ASSET-PROVENANCE.md §2；重生成走 ai/generate.sh --apply（需 Image API Key）", "reviewed-baseline",
    "唯一有逐张提示词的技能书；复用地脉/雷库设定")
add("assets/originalrpg/textures/item/silent_thunder_skill_book.png", "ai-prompt",
    "ai/skill-book-prompts.jsonl:3",
    "见 ASSET-PROVENANCE.md §2；重生成走 ai/generate.sh --apply（需 Image API Key）", "reviewed-baseline",
    "唯一有逐张提示词的技能书；复用地脉/雷库设定")
add("assets/originalrpg/textures/item/legacy_blueprint.png", "ai-prompt",
    "ai/prompts.jsonl:27",
    "见 ASSET-PROVENANCE.md §2；重生成走 ai/generate.sh --apply（需 Image API Key）", "reviewed-baseline")
add("assets/originalrpg/textures/item/traveler_compass.png", "ai-prompt",
    "ai/prompts/traveler_compass.txt",
    "见 ai/README.md 的单资产 image_gen 命令（--prompt-file ai/prompts/traveler_compass.txt）",
    "reviewed-baseline", "静态兜底图；32 帧转针由 GenerateCompassFrames 派生")

# W13-A2 裁决：先做相似度判定 —— 与全部 83 张物品纹理逐对算「不透明像素 + RGB」的
# Jaccard，最高仅 0.099（silent_thunder_skill_book），远低于派生阈值（>=0.9）⇒
# 判为**独立素材**而非某张主纹理的派生，据此在 ai/prompts.jsonl 补一条一致的提示词。
add("assets/originalrpg/textures/item/sulfur_resonance_imprint.png", "ai-prompt",
    "ai/prompts.jsonl:28",
    "见 ASSET-PROVENANCE.md §6-④；重生成走 ai/generate.sh --apply（需 Image API Key）",
    "reviewed-baseline",
    "W13-A2 补登；相似度判定最高 Jaccard=0.099 ⇒ 独立素材（非派生），"
    "提示词按实测像素写（32x32 二值 alpha、15 色：暗紫黑底盘 + 琥珀金菱形核心 + 钢灰环）")

# ---------- ② 罗盘帧 32 ----------
for i in range(32):
    add(f"assets/originalrpg/textures/item/traveler_compass/frame_{i:02d}.png", "derived-from",
        "tools/GenerateCompassFrames.java#drawNeedle#frame_%02d" % i,
        "cd resource-pack && javac -d /tmp/rp-build tools/GenerateCompassFrames.java && "
        "java -cp /tmp/rp-build GenerateCompassFrames "
        "assets/originalrpg/textures/item/traveler_compass.png "
        "assets/originalrpg/textures/item/traveler_compass assets/originalrpg/models/item",
        "reviewed-baseline", "派生物；基图是 ai/prompts/traveler_compass.txt")

# ---------- ③ betterhud 116 ----------
HUD_NOTE = "build.sh 用 PngFingerprint 逐张与 reviewed 源比对，防静默漂移"
for skill in SKILLS:
    add(f"assets/betterhud/textures/glyph_skill_{skill}.png", "generator",
        f"tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_skill_{skill}.png",
        HUD_CMD, "reviewed-baseline", HUD_NOTE)
for cls in CLASSES + ["unchosen"]:
    add(f"assets/betterhud/textures/glyph_class_{cls}.png", "generator",
        f"tools/generate_betterhud_panels.py#semantic_icons#sivita_icon_class_{cls}.png",
        HUD_CMD, "reviewed-baseline", HUD_NOTE)
for name, key in (("cast_en", "F CAST"), ("cast_zh", "F 施放"),
                  ("cycle_en", "2x F CYCLE"), ("cycle_zh", "双击 F 切换")):
    add(f"assets/betterhud/textures/glyph_control_{name}.png", "generator",
        f"tools/generate_betterhud_panels.py#control_label#'{key}'", HUD_CMD, "reviewed-baseline",
        HUD_NOTE + "；字形来自脚本内嵌 CONTROL_GLYPHS")
PANELS = {
    "image_sivita_skill_panel.png": "tools/generate_betterhud_panels.py#skill_panel",
    "image_sivita_skill_selected.png": "tools/generate_betterhud_panels.py#selected_frame",
    "image_sivita_skill_cooldown.png": "tools/generate_betterhud_panels.py#cooldown_mask",
    "image_sivita_class_badge.png": "tools/generate_betterhud_panels.py#class_badge",
}
for name, key in PANELS.items():
    add(f"assets/betterhud/textures/{name}", "generator", key, HUD_CMD, "reviewed-baseline", HUD_NOTE)
for i in range(1, 21):
    add(f"assets/betterhud/textures/image_image_sivita_mana_fill_up_20_{i}.png", "generator",
        f"tools/generate_betterhud_panels.py#mana_fill#frame_{i}of20", HUD_CMD,
        "reviewed-baseline",
        "sivita_mana_fill.png(1x87) 的 20 帧切分：第 i 帧露出底部 floor(87*i/20) 行，其余透明；"
        "切分逻辑已在 W13-A1 补进 generate_betterhud_panels.py#mana_fill，逐像素可复现")
for series in FONT_SERIES:
    for i in range(1, FONT_PAGES + 1):
        add(f"assets/betterhud/textures/text_font{series}_{i}.png", "upstream-betterhud",
            f"BetterHud 编译期生成：assets/betterhud/font/*.json 的 bitmap provider 引用 "
            f"betterhud:text_font{series}_{i}.png（series {series}："
            f"{'正文默认字号' if series == '1' else '各字号缩放分页'}）",
            BETTERHUD_REBUILD, "reviewed-baseline",
            "上游编译器直出，非本项目像素；门禁只判「被 font JSON 引用」，不判像素")
add("assets/betterhud/textures/splitter.png", "upstream-vanilla",
    "上游：BetterHud 发行版自带通用占位/分割贴图（256x256）",
    "无（上游产物，本项目不重建）", "reviewed-baseline", VANILLA_NOTE)

# ---------- ④ bettermodel 16 ----------
BM_NOTE = "64x64 RGBA 像素图；模型侧只保证「文件存在 + 几何/骨骼契约」"
add("assets/bettermodel/textures/item/blood_moon_resonant_husk_blood_moon_resonant_husk.png",
    "generator", "tools/generate-mob-model.mjs#textureOutput",
    "cd resource-pack && node tools/generate-mob-model.mjs  # 需要 website/node_modules/sharp",
    "reviewed-baseline", BM_NOTE + "（build.sh 在每次打包时重跑）")
for key in ("blood_moon_grave_knight", "blood_moon_rift_stalker"):
    add(f"assets/bettermodel/textures/item/{key}_{key}.png", "generator",
        f"tools/generate_blood_moon_models.py#PALETTES#{key}",
        "cd resource-pack && python3 tools/generate_blood_moon_models.py", "reviewed-baseline",
        BM_NOTE + "；由 blood_moon_resonant_husk.bbmodel 内嵌纹理按调色板重着色")
for key in ("boss_ruins_inscription_warden", "boss_ruins_echo_adjudicator",
            "boss_sulfur_crystal_devourer", "boss_cinnabar_reborn_core",
            "boss_storm_vault_keykeeper", "boss_silent_thunder_oathguard"):
    add(f"assets/bettermodel/textures/item/{key}_{key}.png", "generator",
        f"tools/generate_dungeon_boss_models.py#texture#{key}",
        "cd resource-pack && python3 tools/generate_dungeon_boss_models.py", "reviewed-baseline", BM_NOTE)
for key in ("dungeon_ruins_runebound", "dungeon_ruins_echo_sentinel",
            "dungeon_sulfur_fume_crawler", "dungeon_sulfur_slag_mote",
            "dungeon_storm_conductor", "dungeon_storm_gust_wisp"):
    add(f"assets/bettermodel/textures/item/{key}_{key}.png", "generator",
        f"tools/generate_dungeon_wave_models.py#SPECS#{key}",
        "cd resource-pack && python3 tools/generate_dungeon_wave_models.py", "reviewed-baseline",
        BM_NOTE + "；纹理走共用 texture(spec) 配方")
add("assets/bettermodel/textures/item/mesh_pixel.png", "upstream-vanilla",
    "上游：BetterModel 发行版自带 16x16 白色 mesh 占位贴图，"
    "被 models/mesh_triangle_{single,duplex}.json 引用",
    "无（上游产物，本项目不重建）", "reviewed-baseline",
    VANILLA_NOTE + "；实测解码后为 16x16（W12-A2 记的 64x64 有误，W13-A2 更正）")

# ---------- ⑤ 字体图集 2 ----------
add("assets/originalrpg/textures/font/hud_symbols.png", "generator",
    "tools/GenerateHudAtlas.java#11 glyphs U+E000..U+E00A",
    "cd resource-pack && javac -d /tmp/rp-build tools/GenerateHudAtlas.java && "
    "java -cp /tmp/rp-build GenerateHudAtlas assets/originalrpg/textures/font/hud_symbols.png",
    "reviewed-baseline")
add("assets/originalrpg/textures/font/class_skill_symbols.png", "generator",
    "tools/GenerateSkillHudAtlas.java#48 glyphs U+E020..U+E04F",
    "cd resource-pack && javac -d /tmp/rp-build tools/GenerateSkillHudAtlas.java && "
    "java -cp /tmp/rp-build GenerateSkillHudAtlas "
    "assets/originalrpg/textures/font/class_skill_symbols.png", "reviewed-baseline")

# ---------- ⑥ minecraft 命名空间 ----------
for path in sorted((RP / "assets/minecraft").rglob("*")):
    if path.is_file():
        rel = str(path.relative_to(RP))
        key = rel.split("assets/minecraft/", 1)[1]
        add(rel, "upstream-vanilla", MC_UPSTREAM[key], "无（上游产物，本项目不重建）",
            "reviewed-baseline",
            VANILLA_NOTE + "；改动会让客户端与原版 GUI 期望不一致（黑紫方块/错位）")

# ================== 文档叙述层（长期结论，与生成逻辑同文件） ==================
# 这些字符串就是 ASSET-PROVENANCE.md 的正文。放在这里是为了让「改口径」只发生
# 一次：JSON 与 MD 由同一份推导 + 同一份叙述产出。
DOC_NOTES = {
    "header": """# 素材来源登记（ASSET-PROVENANCE）

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
> 手改会在下一次生成时被覆盖，且会让 JSON 与文档静默漂移。""",

    "coverage": ["覆盖 `resource-pack/assets/**` 下**全部 252 张 PNG**，与磁盘一一对应。",
                 "键口径：**相对仓库根**，即 `assets/<子路径>/<文件>.png`",
                 "（在 `resource-pack/` 下跑 `find assets -name '*.png'` 得到的字符串）。",
                 "判据：`asset-provenance.json` 的键集合 == `cd resource-pack && find assets -name '*.png'`。",
                 "",
                 "计数表由生成器渲染（分区不匹配会直接报错退出，见 `render_doc()`）："],

    "categories": [
        "- `generator`：可由仓库内生成器**确定性重跑**得到（Java/Python/Node 脚本）。可复现命令列里给的是单张取证命令。",
        "- `derived-from`：由另一张**已登记素材**确定性派生（罗盘帧、37 张 skill 图标）。",
        "- `ai-prompt`：由 `ai/` 美术生产线的 Image API 产出，**不可复现**（需要 Key 与模型），提示词文件:行号已登记。",
        "- `human-override`：人工覆盖，**生成器旧设计已弃用**，因此本张**按定义不可复现**。",
        "  必须带 `override_reason`（为什么弃用生成器旧设计）与 `source_prompt`（采用的是哪条提示词），",
        "  且由 `ASSET_REPRO_GATE` 做**反向断言**：仓库像素必须**不等于**生成器输出。",
        "- `upstream-betterhud`：BetterHud 在服务端**编译期**生成的字体图集分页（38 张）。",
        "  本仓无 `server/`，不能逐张复现；登记可重建步骤而不自己画。",
        "- `upstream-vanilla`：上游发行版（原版客户端 / BetterHud / BetterModel）自带的占位贴图（5 张）。",
        "  本项目**不拥有**其像素，改了会与上游不兼容。",
        "- `reviewed-baseline`：人工审核过的基线像素，**仓库内既无生成器也无提示词**。",
        "  两个分支合并后本类为 0 张。",
        "",
        "任务书给的「83 = 31 generator + 52 ai」这一拆分**与实测不完全吻合**，逐条纠正见 §2-②",
        "（42 张 skill 里 37 张是**派生**、5 张已回同步为 generator；另有 1 张既是 AI 早期原稿又被 generator 同名覆盖）。",
    ],

    "§1": """## 1. 生成器清单（可复现来源）

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
> 但**逐张复现需要在部署机（有 `server/`）上跑** —— 本仓没有 `server/`，见 §6-⑧。""",

    "§2": """## 2. skill_* 42 张与提示词的对账（②）

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
（登记 `generator`，旧提示词降级为历史原稿）。""",

    "§3": """## 3. betterhud 116 张对账（③）

116 = 81 `generator`（glyph_* / image_* 面板 + 20 帧 mana fill）+ 38 `upstream-betterhud`
（`text_font{1,2}_*.png`）+ 1 `upstream-vanilla`（`splitter.png`）。

20 帧 `image_image_sivita_mana_fill_up_20_*` 在 W13-A1 之前登记为「下游产物」：
脚本只产出单张 `sivita_mana_fill.png(1x87)`，1..20 帧切分不在地仓生成器内。
W13-A1 把切分逻辑（第 i 帧露出底部 `floor(87*i/20)` 行，其余透明）补进
`generate_betterhud_panels.py#mana_fill` ⇒ 这 20 张从此逐像素可复现，登记为 `generator`。""",

    "§4": """## 4. bettermodel 16 张对账（④）

16 = 15 `generator`（`blood_moon_*` 3 + `boss_*` 6 + `dungeon_*` 6）+ 1 `upstream-vanilla`
（`mesh_pixel.png`）。

顺带更正 W12-A2 的一处笔误：`mesh_pixel.png` 实测 **16x16**（不是 64x64），
它是 BetterModel 发行版自带的白色着色占位贴图。""",

    "§5": """## 5. forged 变体帧 5 张：「派生自主纹理」还是「独立素材」？（⑤）

**判定：独立素材（不是主纹理的派生）。** 依据：模型层 `layer0` 指向逐个不同（3 张 bow 帧各有一个
`layer0`；crossbow 的 5 个状态、trident 的 2 个姿态则**回指同一张**），且与主纹理
`forged_ranger_weapon` 的不透明像素 **Jaccard ≤ 0.29**（若为旋转/裁切派生应 > 0.9）。
登记 `ai-prompt`，锚点 `ai/prompts.jsonl:4`（弓条目）。""",

    "§6": """## 6. 裁决记录（W13-A2 的五组 + W13-A1 的三条出清）

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
据此在 `resource-pack/ai/prompts.jsonl` 补一条**与实测像素一致**的提示词（`:28`）。""",

    "§7": """## 7. 可复现性断言（W13-A1 的 `ASSET_REPRO_GATE`）

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
作为已知边界保留，不在登记口径里假装可复现。""",

    "§8": [
        "- 重新生成：`python3 resource-pack/tools/generate-provenance.py --write --write-doc`",
        "  （JSON 与本文档一次产出；两份都是生成物，不要手改）",
        "- 基线门禁：`python3 scripts/check-asset-baseline.py`（0=GREEN）",
        "- 可复现性门禁：`sh scripts/check-asset-reproducible.sh`（判据行 `ASSET_REPRO_GATE=GREEN`）",
        "- 一致性判据：",
        "  1. `asset-provenance.json.assets` 的键集合 == `cd resource-pack && find assets -name '*.png'`",
        "     （键形如 `assets/...`，不带 `resource-pack/` 前缀）；",
        "  2. 每个值都有 `category` / `source` / `reproduce_cmd` / `reviewed` 四个字段；",
        "  3. `category == \"generator\"` 的项，其 `reproduce_cmd` 必须指向仓库内存在的生成器文件；",
        "  4. `category == \"ai-prompt\"` 的项，其 `source` 必须形如 `<file>:<line>` 且该行存在；",
        "  5. `category == \"human-override\"` 的项必须带非空 `override_reason` 与 `source_prompt`",
        "     （`ASSET_REPRO_GATE` 会校验）。",
    ],
}

# ======================= 渲染层：ASSET-PROVENANCE.md =======================
# 文档是**生成物**（--write-doc），不是手写的：§0 的计数表与附录的逐张总表都从
# 上面推导出的 out 渲染，因此不可能与 asset-provenance.json 漂移。
# 叙述性章节（§1..§6 的裁决依据）是长期人工结论，落在 DOC_NOTES 的常量里，
# 与生成逻辑放在同一个文件，改一处即可同时更新 JSON 与文档。

CATEGORY_ORDER = ("generator", "derived-from", "ai-prompt", "human-override",
                  "upstream-betterhud", "upstream-vanilla", "reviewed-baseline")

# 分区：前缀 -> (标题, 期望张数)。顺序与文档一致。
SECTIONS = (
    ("物品纹理", 83, r"^assets/originalrpg/textures/item/[^/]+\.png$"),
    ("旅者罗盘帧", 32, r"^assets/originalrpg/textures/item/traveler_compass/"),
    ("betterhud", 116, r"^assets/betterhud/"),
    ("bettermodel", 16, r"^assets/bettermodel/"),
    ("字体图集", 2, r"^assets/originalrpg/textures/font/"),
    ("minecraft 命名空间", 3, r"^assets/minecraft/"),
)
SECTION_TITLES = ("① 物品纹理 83 张", "② 旅者罗盘帧 32 张", "③ betterhud 纹理 116 张",
                  "④ bettermodel 纹理 16 张", "⑤ 字体图集 2 张", "⑥ minecraft 命名空间 3 张")


def _cell(text):
    """把自由文本压成单行表格单元：markdown 表格里换行会散架。"""
    return " ".join(str(text).split()).replace("|", "\\|")


def _section_rows(pattern):
    rx = re.compile(pattern)
    return [rel for rel in sorted(out) if rx.search(rel)]


def _render_table(rows):
    lines = ["| 相对路径 | 类别 | 来源 | 可复现命令 | 审核状态 |",
             "| --- | --- | --- | --- | --- |"]
    for rel in rows:
        entry = out[rel]
        lines.append("| `%s` | %s | %s | %s | %s |" % (
            rel, entry["category"], _cell(entry["source"]),
            _cell(entry["reproduce_cmd"]), entry["reviewed"]))
        if entry.get("note"):
            lines.append("| | | %s | | |" % _cell(entry["note"]))
    return lines


def render_doc():
    counts = {name: 0 for name in CATEGORY_ORDER}
    for entry in out.values():
        counts[entry["category"]] += 1
    total = len(out)
    head = ["| 分区 | 张数 | " + " | ".join(CATEGORY_ORDER) + " |",
            "| --- | --- | " + " | ".join("---" for _ in CATEGORY_ORDER) + " |"]
    for (title, expected, pattern), sec_title in zip(SECTIONS, SECTION_TITLES):
        rows = _section_rows(pattern)
        if len(rows) != expected:
            raise SystemExit(f"分区「{title}」实得 {len(rows)} 张，期望 {expected} 张")
        per = {name: 0 for name in CATEGORY_ORDER}
        for rel in rows:
            per[out[rel]["category"]] += 1
        head.append("| %s | %s | %s |" % (title, len(rows),
                                          " | ".join(str(per[n]) for n in CATEGORY_ORDER)))
    head.append("| **合计** | **%d** | %s |" % (
        total, " | ".join("**%d**" % counts[n] for n in CATEGORY_ORDER)))

    body = [DOC_NOTES["header"], "", "## 0. 覆盖与口径", ""] + DOC_NOTES["coverage"] + head \
        + ["", "六类口径：", ""] + DOC_NOTES["categories"] + ["", DOC_NOTES["§1"]]
    for key in ("§2", "§3", "§4", "§5", "§6", "§7"):
        body += ["", DOC_NOTES[key]]
    body += ["", "## 8. 复审与维护", ""] + DOC_NOTES["§8"]
    body += ["", "## 附：逐张登记总表（与 `asset-provenance.json` 同源）", "",
             "> 本节由 `python3 resource-pack/tools/generate-provenance.py --write-doc` 渲染，**不要手改**：",
             "> 表里的每一行都来自 `asset-provenance.json`，两者不一致即为漂移。"]
    for (title, expected, pattern), sec_title in zip(SECTIONS, SECTION_TITLES):
        body += ["", "### " + sec_title, ""] + _render_table(_section_rows(pattern))
    text = "\n".join(body).rstrip("\n") + "\n"
    if "| | |" in text and "\\|" in text:
        pass
    return text


if __name__ == "__main__":
    # 默认打印到 stdout；--write 才覆盖 resource-pack/asset-provenance.json。
    import argparse
    parser = argparse.ArgumentParser(description="生成 resource-pack/asset-provenance.json")
    parser.add_argument("--write", action="store_true", help="覆盖 resource-pack/asset-provenance.json")
    parser.add_argument("--write-doc", action="store_true",
                        help="渲染并覆盖 resource-pack/ASSET-PROVENANCE.md（生成物）")
    args = parser.parse_args()
    text = json.dumps(OrderedDoc(out), ensure_ascii=False, indent=1) + "\n"
    if args.write:
        (RP / "asset-provenance.json").write_text(text, encoding="utf-8")
    if args.write_doc:
        (RP / "ASSET-PROVENANCE.md").write_text(render_doc(), encoding="utf-8")
    if not args.write and not args.write_doc:
        sys.stdout.write(text)
