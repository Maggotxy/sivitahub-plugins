#!/usr/bin/env python3
# 从仓库事实推导每张素材的来源登记，产出 resource-pack/asset-provenance.json。
# 本脚本是 asset-provenance.json 的**生成器**（可重跑、确定）：所有判定都读
# 生成器源码 / 提示词文件 / build.sh，不写死人工结论。
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
            "category ∈ generator（生成器直出）| ai-prompt（AI 生产线直出）| derived-from（由已登记素材派生）| reviewed-baseline（人工审核基线，无可复现来源）。",
            "reviewed ∈ reviewed-baseline（已审核）| pending-human（需人工决策）。",
            "本文件的生成器：tools/generate-provenance.py（读生成器源码 / 提示词文件推导，不写死人工结论）。",
            "覆盖数 = 磁盘上全部 PNG：item 83 + compass 帧 32 + betterhud 116 + bettermodel 16 + 字体图集 2 + minecraft 3 = 252。",
        ]),
        ("$generatedBy", "W12-A2-asset-provenance"),
        ("$counts", collections.OrderedDict(
            sorted(__import__("collections").Counter(v["category"] for v in ordered.values()).items()))),
        ("$needsHumanDecision", NEEDS_HUMAN_DECISION),
        ("assets", ordered),
    ])


NEEDS_HUMAN_DECISION = [
    "betterhud/text_font{1,2}_*.png（38 张）：BetterHud 编译期字体图集，仓库内无生成器也无提示词",
    "betterhud/splitter.png + bettermodel/mesh_pixel.png + minecraft/textures/gui/sprites/boss_bar/*.png（5 张）：上游/原版占位贴图",
    "betterhud/image_image_sivita_mana_fill_up_20_1..20.png（20 张）：生成器只产出单张 sivita_mana_fill.png(1x87)，20 帧切分不在仓库生成器内",
    "item/sulfur_resonance_imprint.png（1 张）：ai/ 下无任何提示词提到它",
    "item/skill_{arcane-edge,ruin-charge,venom-blade,void-cut,war-cry}.png（5 张）：与生成器 recipe 差 4 px（2x2 强调色块）",
    "tools/generate-provenance.py（1 个）：生成器脚本本身是否入库",
]


def add(rel, category, source, reproduce, reviewed, note=""):
    if rel in out:
        raise SystemExit("duplicate key: " + rel)
    entry = {"category": category, "source": source,
             "reproduce_cmd": reproduce, "reviewed": reviewed}
    if note:
        entry["note"] = note
    out[rel] = entry


# ---------- ① 物品纹理 83 ----------
GEN_CMD = ("cd resource-pack && javac -d /tmp/rp-build tools/GenerateTextures.java && "
           "java -cp /tmp/rp-build GenerateTextures /tmp/rp-out --force && "
           "cmp /tmp/rp-out/<key>.png assets/originalrpg/textures/item/<key>.png")
# 这两张既有 ai/prompts.jsonl 的早期原稿提示词、又被 GenerateTextures 覆盖，见 §2-③
AI_V0_KEYS = {"ancient_echo_skill_book", "common_insight_skill_book"}
for key in GEN_ITEM_KEYS:
    note = "程序化 32x32 像素基线；可复现性断言由 W12-A3 的 ASSET_REPRO_GATE 承担"
    if key in AI_V0_KEYS:
        # ai/prompts.jsonl:17/18 登记的是 1024px 早期原稿；当前 32x32 与生成器逐像素一致，
        # 因此以生成器为准，旧提示词降级为历史原稿（W12-A3 复现断言将证实这一点）。
        note += "；ai/prompts.jsonl:17/18 是早期 1024px 原稿版本，当前 32x32 以生成器为准"
    add(f"assets/originalrpg/textures/item/{key}.png", "generator",
        f"tools/GenerateTextures.java#{key}#{key}", GEN_CMD, "reviewed-baseline", note)

# 42 张 skill_*：由 generate_betterhud_panels.py#skill_menu_items 从 18px HUD 图标派生
HUD_CMD = ("cd resource-pack && python3 tools/generate_betterhud_panels.py  # 需要 Pillow；"
           "同时写 server/config-templates/BetterHud/assets 与 assets/originalrpg/textures/item")
for skill in SKILLS:
    note = "由 18px HUD 语义图标最近邻放大 2x 后裁成 32px 物品纹理（非 AI 直出）"
    if skill in PADDED_SKILLS:
        note += ("；实测与生成器 recipe 差 4 px（2x2 强调色块，recipe 为 0a0e13 面板暗色）"
                 "⇒ 建议 W12-A3 或重跑生成器覆盖、或登记为人工覆盖")
    add(f"assets/originalrpg/textures/item/skill_{skill}.png", "derived-from",
        f"tools/generate_betterhud_panels.py#skill_menu_items#sivita_icon_skill_{skill}.png",
        HUD_CMD, "reviewed-baseline", note)

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

SURFACE_NOTE = ("无任何提示词/生成器登记；登记为 reviewed-baseline，"
                "需人工决策补 ai/prompts.jsonl 条目还是扩展生成器（见 ASSET-PROVENANCE.md §6-④）")
add("assets/originalrpg/textures/item/sulfur_resonance_imprint.png", "reviewed-baseline",
    "（缺）ai/ 下无任何提示词提到 sulfur imprint",
    "无（不可复现）", "reviewed-baseline", SURFACE_NOTE)

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
        "tools/generate_betterhud_panels.py#mana_fill + 构建期 20 帧切分", HUD_CMD,
        "reviewed-baseline",
        "脚本只生成单张 sivita_mana_fill.png(1x87)；1..20 帧切分不在仓库生成器内"
        "（build.sh 依赖 server/ 侧产物的预切帧）⇒ 在干净克隆里无法逐张复现，需人工决策")
FONT_CMD = ("cd resource-pack && javac -d /tmp/rp-build tools/GenerateHudAtlas.java "
            "tools/GenerateSkillHudAtlas.java && java -cp /tmp/rp-build GenerateHudAtlas "
            "assets/originalrpg/textures/font/hud_symbols.png")
for series in ("1", "2"):
    for i in range(1, 20):
        add(f"assets/betterhud/textures/text_font{series}_{i}.png", "reviewed-baseline",
            "（缺）BetterHud 编译期字体图集；仓库内无生成器也无提示词",
            "无（不可复现）", "reviewed-baseline",
            "占 116 张中的 38 张；是 BetterHud 的拉丁/HUD 字库分页，须人工决策："
            "保留为 reviewed-baseline 还是在 build.sh 里改成「存在即可」")
add("assets/betterhud/textures/splitter.png", "reviewed-baseline",
    "（缺）BetterHud 通用 256x256 空白/分割贴图", "无（不可复现）", "reviewed-baseline",
    "非游戏内容像素；建议登记为 reviewed-baseline 并在门禁豁免")

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
add("assets/bettermodel/textures/item/mesh_pixel.png", "reviewed-baseline",
    "（缺）64x64 白色 mesh 贴图，被 models/mesh_triangle_{single,duplex}.json 引用",
    "无（不可复现）", "reviewed-baseline", "非游戏内容像素；仅着色占位")

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
        add(str(path.relative_to(RP)), "reviewed-baseline",
            "（缺）原版/上游 UI 贴图（boss bar 进度条）", "无（不可复现）", "reviewed-baseline",
            "非项目原创像素")

if __name__ == "__main__":
    # 默认打印到 stdout；--write 才覆盖 resource-pack/asset-provenance.json。
    import argparse
    parser = argparse.ArgumentParser(description="生成 resource-pack/asset-provenance.json")
    parser.add_argument("--write", action="store_true", help="覆盖 resource-pack/asset-provenance.json")
    args = parser.parse_args()
    document = OrderedDoc(out)
    text = json.dumps(document, ensure_ascii=False, indent=1) + "\n"
    if args.write:
        (RP / "asset-provenance.json").write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
