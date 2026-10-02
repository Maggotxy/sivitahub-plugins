#!/usr/bin/env python3
# scripts/resource-pack-asset-check.py —— 资源包素材门禁的判据实现（W7-A1）
#
# 为什么把判据放在 python 而不是 sh：
#   * PNG 需要 zlib 解压 + per-pixel alpha 判定 —— 不能用 ImageMagick（镜像没装），
#     也不能靠 PNG 头几个字节猜尺寸（那只能验尺寸，验不了透明通道）
#   * 闭包需要真正的 JSON 递归遍历（build.sh 用 jq 递归，本脚本用 stdlib json）
#   * shinal 部分（清单一一对应、注册表、退出码）留在 check-resource-pack.sh 里
#
# 与 resource-pack/build.sh 的关系（任务要求⑤）：
#   build.sh 仍然是**生产机打包**的权威入口（它还要做 SHA-1 / UUID / server.properties
#   一致性 / Paper 运行中拒绝覆盖等本仓无法判定的事）。本脚本是**仓内静态子集**，
#   也是 CI 的唯一素材判据。两者口径有意保持一致但**不共享代码**：
#   build.sh 用 java+jq，本脚本用 python3；重叠判据（闭包 / 32x32 / HUD / 字体 /
#   语言键 / 罗盘分发）若发生分歧，以本脚本为准并在 PR 里同步 build.sh —— 见
#   docs/RESOURCE_PACK_QA.md 的差异说明。
#
# 用法：python3 scripts/resource-pack-asset-check.py --root <仓库根> --check <判据>
# 判据取值：closure | textures | hud | journey | lang | compass
# 退出码 0 = 该判据通过；1 = 不通过（stderr 打印逐条原因）

import argparse
import json
import os
import struct
import sys
import zlib

# ---- HUD 字体契约（与 README / docs/RESOURCE_PACK_QA.md 一致）----
HUD_GLYPH_COUNT = 11
HUD_GLYPH_SIZE = 9
HUD_FIRST_CODEPOINT = 0xE000
# 同一张 hud.json 里还有一份 48 枚职业技能图集 U+E020..U+E04F，本任务①只要求
# U+E000..U+E00A；它作为「顺带断言」守住不会漂移，但失败时也会红。
CLASS_GLYPH_COUNT = 48
CLASS_FIRST_CODEPOINT = 0xE020
# ---------------------------------- 旅程字体契约
JOURNEY_CODEPOINTS = list(range(0xE100, 0xE108))  # U+E100..U+E107 八枚
# ---------------------------------- 旅者罗盘契约（26.2 方向分发）
COMPASS_SEGMENTS = 33
COMPASS_SCALE = 32.0
COMPASS_PROPERTY = "minecraft:compass"
COMPASS_TARGET = "spawn"

ITEM_TEXTURE_SIZE = 32  # 物品 PNG 边长


def fail(msg):
    print(msg, file=sys.stderr)


def load_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


# --------------------------------------------------------------------------- PNG
def read_png_rgba(path):
    """最小 PNG 解码器：只支持 8 bit RGB/RGBA 非隔行（本仓全部 PNG 都是 color type 6）。

    返回 [(width, height, bytes_rgba)]。尺寸以外的信息（位深/颜色类型/隔行）也一并校验，
    因为「能读到尺寸」不等于「客户端能正确渲染」：调色板 PNG、灰度 PNG、
    16 bit PNG 都会让 alpha 判定失去意义。
    """
    with open(path, "rb") as handle:
        data = handle.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("不是 PNG 文件（魔数不匹配）")
    pos = 8
    width = height = None
    bit_depth = color_type = None
    interlace = 0
    idat = []
    while pos + 8 <= len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        chunk_type = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length  # 8 字节头 + 负载 + 4 字节 CRC
        if chunk_type == b"IHDR":
            width, height, bit_depth, color_type, _comp, _filt, interlace = struct.unpack(
                ">IIBBBBB", body)
        elif chunk_type == b"IDAT":
            idat.append(body)
        elif chunk_type == b"IEND":
            break
    if width is None:
        raise ValueError("PNG 缺 IHDR")
    if interlace != 0:
        raise ValueError("不支持隔行 PNG（Adam7）")
    if bit_depth != 8 or color_type != 6:
        raise ValueError(
            "PNG 必须是 8 bit RGBA（color type 6）；实际 bit_depth=%d color_type=%d"
            % (bit_depth, color_type))
    raw = zlib.decompress(b"".join(idat))
    stride = width * 4
    previous = bytearray(stride)
    pixels = bytearray(width * height * 4)
    offset = 0
    for row in range(height):
        filter_type = raw[offset]
        offset += 1
        line = bytearray(raw[offset:offset + stride])
        offset += stride
        # 逐行反滤波（PNG spec 5.2）。像素级门禁必须真的解出 alpha，
        # 因此这里不能省 —— Sub/Up/Average/Paeth 四种都会出现在调色板导出里。
        if filter_type == 1:
            for i in range(4, stride):
                line[i] = (line[i] + line[i - 4]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - 4] if i >= 4 else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                left = line[i - 4] if i >= 4 else 0
                up = previous[i]
                up_left = previous[i - 4] if i >= 4 else 0
                p = left + up - up_left
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - up_left)
                if pa <= pb and pa <= pc:
                    pred = left
                elif pb <= pc:
                    pred = up
                else:
                    pred = up_left
                line[i] = (line[i] + pred) & 0xFF
        elif filter_type != 0:
            raise ValueError("未知 PNG 行滤波类型 %d" % filter_type)
        pixels[row * stride:(row + 1) * stride] = line
        previous = line
    return width, height, bytes(pixels)


def iter_png(root_dir):
    for dirpath, dirnames, filenames in os.walk(root_dir):
        dirnames.sort()
        for name in sorted(filenames):
            if name.endswith(".png"):
                yield os.path.join(dirpath, name)


# --------------------------------------------------------------------------- 闭包
def iter_model_refs(node, found):
    """递归收集 item 定义里所有 minecraft:model 的 originalrpg:item/* 引用。"""
    if isinstance(node, dict):
        if node.get("type") == "minecraft:model":
            model = node.get("model")
            if isinstance(model, str) and model.startswith("originalrpg:item/"):
                found.add(model)
        for value in node.values():
            iter_model_refs(value, found)
    elif isinstance(node, list):
        for value in node:
            iter_model_refs(value, found)


def short_name(reference, prefix):
    """originalrpg:item/foo -> foo（前缀不符返回 None）。"""
    if not isinstance(reference, str) or not reference.startswith(prefix):
        return None
    return reference[len(prefix):]


def check_closure(assets):
    """判据 2：每个 item 定义闭包到存在的 model → texture；孤儿模型 / 纹理报出来。"""
    models_dir = os.path.join(assets, "models", "item")
    textures_dir = os.path.join(assets, "textures", "item")
    items_dir = os.path.join(assets, "items")
    problems = []

    models = {}
    if os.path.isdir(models_dir):
        for name in sorted(os.listdir(models_dir)):
            if name.endswith(".json"):
                path = os.path.join(models_dir, name)
                try:
                    models[name[:-5]] = load_json(path)
                except Exception as exc:  # noqa: BLE001 - 门禁要报告而不是崩栈
                    problems.append("模型 JSON 解析失败: %s (%s)" % (path, exc))

    textures = set()
    if os.path.isdir(textures_dir):
        for path in iter_png(textures_dir):
            rel = os.path.relpath(path, textures_dir)
            textures.add(rel[:-4].replace(os.sep, "/"))

    entries = set()
    definitions = 0
    for name in sorted(os.listdir(items_dir)) if os.path.isdir(items_dir) else []:
        if not name.endswith(".json"):
            continue
        definitions += 1
        path = os.path.join(items_dir, name)
        try:
            definition = load_json(path)
        except Exception as exc:  # noqa: BLE001
            problems.append("物品定义 JSON 解析失败: %s (%s)" % (path, exc))
            continue
        found = set()
        iter_model_refs(definition, found)
        if not found:
            problems.append("物品定义没有引用任何 originalrpg 模型: %s" % path)
        entries |= found

    if definitions == 0:
        problems.append("items/ 下没有任何物品定义: %s" % items_dir)

    reachable_models = set()
    reachable_textures = set()
    visiting = set()
    stack = sorted(entries)
    while stack:
        reference = stack.pop()
        if reference in reachable_models:
            continue
        reachable_models.add(reference)
        key = short_name(reference, "originalrpg:item/")
        definition = models.get(key)
        if definition is None:
            problems.append("闭包缺模型: %s（找不到 models/item/%s.json）" % (reference, key))
            continue
        if key in visiting:
            problems.append("模型 parent 链存在环: %s" % reference)
            continue
        visiting.add(key)
        texture = definition.get("textures", {}).get("layer0")
        parent = definition.get("parent")
        texture_name = short_name(texture, "originalrpg:item/")
        parent_name = short_name(parent, "originalrpg:item/")
        if texture_name is not None:
            reachable_textures.add(texture_name)
            if texture_name not in textures:
                problems.append("闭包缺纹理: %s -> %s" % (reference, texture))
        elif parent_name is not None:
            stack.append("originalrpg:item/%s" % parent_name)
        elif not texture and not parent:
            problems.append("模型既没有 textures.layer0 也没有 parent: %s" % reference)
        else:
            # 引用的是 minecraft: 父模型（例如 minecraft:item/generated），纹理在别处提供
            reachable_textures.add(key)
        visiting.discard(key)

    for key in sorted(set(models) - {short_name(m, "originalrpg:item/") for m in reachable_models}):
        problems.append("孤儿模型（任何物品定义都到不了）: models/item/%s.json" % key)
    for name in sorted(textures - reachable_textures):
        problems.append("孤儿纹理（任何模型都到不了）: textures/item/%s.png" % name)

    if problems:
        for line in problems[:40]:
            fail("  " + line)
        if len(problems) > 40:
            fail("  ... 另有 %d 条" % (len(problems) - 40))
        fail("闭包判据不通过：%d 个问题" % len(problems))
        return 1
    print("      物品定义 %d / 可达模型 %d / 可达纹理 %d"
          % (definitions, len(reachable_models), len(reachable_textures)))
    return 0


# --------------------------------------------------------------------------- 纹理
def check_textures(assets):
    """判据 3：物品 PNG 全部 32x32，alpha 只有 0 / 255，且透明与不透明像素都存在。"""
    textures_dir = os.path.join(assets, "textures", "item")
    problems = []
    total = 0
    for path in iter_png(textures_dir):
        total += 1
        rel = os.path.relpath(path, assets)
        try:
            width, height, pixels = read_png_rgba(path)
        except Exception as exc:  # noqa: BLE001
            problems.append("%s: 无法解码 (%s)" % (rel, exc))
            continue
        if (width, height) != (ITEM_TEXTURE_SIZE, ITEM_TEXTURE_SIZE):
            problems.append("%s: 尺寸必须是 %dx%d，实际 %dx%d"
                            % (rel, ITEM_TEXTURE_SIZE, ITEM_TEXTURE_SIZE, width, height))
            continue
        transparent = 0
        opaque = 0
        partial = 0
        for index in range(3, len(pixels), 4):
            alpha = pixels[index]
            if alpha == 0:
                transparent += 1
            elif alpha == 255:
                opaque += 1
            else:
                partial += 1
        if partial:
            problems.append("%s: alpha 不是二值，有 %d 个半透明像素（拒绝抗锯齿）"
                            % (rel, partial))
        if transparent == 0 or opaque == 0:
            problems.append("%s: 必须同时有全透明和全不透明像素（透明 %d / 不透明 %d）"
                            % (rel, transparent, opaque))
    if total == 0:
        problems.append("textures/item 下没有任何 PNG: %s" % textures_dir)
    if problems:
        for line in problems[:40]:
            fail("  " + line)
        if len(problems) > 40:
            fail("  ... 另有 %d 条" % (len(problems) - 40))
        fail("纹理判据不通过：%d 个问题" % len(problems))
        return 1
    print("      %d 张物品 PNG 全部 %dx%d 二值 alpha"
          % (total, ITEM_TEXTURE_SIZE, ITEM_TEXTURE_SIZE))
    return 0


# --------------------------------------------------------------------------- 字体
def bitmap_provider_problems(provider, label, problems):
    """校验一个 bitmap provider 的字段形态；返回 chars 列表或 None。"""
    if provider.get("type") != "bitmap":
        problems.append("%s: provider.type 必须是 bitmap，实际 %r"
                        % (label, provider.get("type")))
        return None
    provider_file = provider.get("file")
    if not isinstance(provider_file, str):
        problems.append("%s: provider.file 缺失或不是字符串" % label)
        return None
    if provider.get("height") != HUD_GLYPH_SIZE:
        problems.append("%s: provider.height 必须是 %d，实际 %r"
                        % (label, HUD_GLYPH_SIZE, provider.get("height")))
    if provider.get("ascent") != 8:
        problems.append("%s: provider.ascent 必须是 8，实际 %r"
                        % (label, provider.get("ascent")))
    chars = provider.get("chars")
    if not isinstance(chars, list) or not chars or not all(isinstance(c, str) for c in chars):
        problems.append("%s: provider.chars 必须是非空字符串数组" % label)
        return None
    return chars


def provider_texture_path(assets, provider_file, problems, label):
    """originalrpg:<dir>/<name>.png -> <assets>/<dir>/<name>.png

    命名空间里的字体纹理路径与 1.21 以前的 assets/<ns>/textures/... 不同：
    `originalrpg:font/hud_symbols.png` 落在 <assets>/textures/font/hud_symbols.png，
    `originalrpg:item/journey_hub.png` 落在 <assets>/textures/item/journey_hub.png。
    1.21+ 把上层 textures/ 目录从 provider.file 里省略了 ——
    （这里踩过一次：直接拼 <assets>/font/... 会恒报「纹理不存在」，
     把门禁变成永远红的假门禁，负向对照才暴露出来）。
    """
    if not provider_file.startswith("originalrpg:"):
        problems.append("%s: provider.file 必须以 originalrpg: 开头，实际 %r"
                        % (label, provider_file))
        return None
    path = os.path.join(assets, "textures", provider_file[len("originalrpg:"):])
    if not os.path.isfile(path):
        problems.append("%s: 字体纹理不存在 %s" % (label, path))
        return None
    return path


def check_hud(assets):
    """判据 4：hud.json 的 U+E000..U+E00A（+ U+E020..U+E04F）与图集尺寸严格一致。"""
    font_json = os.path.join(assets, "font", "hud.json")
    problems = []
    if not os.path.isfile(font_json):
        fail("  font/hud.json 不存在: %s" % font_json)
        return 1
    try:
        definition = load_json(font_json)
    except Exception as exc:  # noqa: BLE001
        fail("  font/hud.json 解析失败: %s" % exc)
        return 1
    providers = definition.get("providers")
    if not isinstance(providers, list) or len(providers) != 2:
        fail("  font/hud.json 必须有恰好 2 个 provider（基础 HUD + 职业技能），实际 %r"
             % (len(providers) if isinstance(providers, list) else providers))
        return 1

    # 期望：provider[0] 覆盖 U+E000..U+E00A 共 11 枚，provider[1] 覆盖 48 枚职业技能
    expectations = [
        (0, "originalrpg:font/hud_symbols.png", HUD_FIRST_CODEPOINT, HUD_GLYPH_COUNT, HUD_GLYPH_SIZE),
        (1, "originalrpg:font/class_skill_symbols.png", CLASS_FIRST_CODEPOINT, CLASS_GLYPH_COUNT,
         HUD_GLYPH_SIZE),
    ]
    atlas_sizes = {}
    for index, expected_file, first, count, glyph_size in expectations:
        label = "hud.providers[%d]" % index
        provider = providers[index]
        chars = bitmap_provider_problems(provider, label, problems)
        if chars is None:
            continue
        if provider.get("file") != expected_file:
            problems.append("%s: provider.file 必须是 %s，实际 %r"
                            % (label, expected_file, provider.get("file")))
        if len(chars) != 1:
            problems.append("%s: provider.chars 必须是一整段（1 个字符串），实际 %d 段"
                            % (label, len(chars)))
            continue
        codepoints = [ord(character) for character in chars[0]]
        expected = list(range(first, first + count))
        if codepoints != expected:
            problems.append(
                "%s: 码位必须严格是 U+%04X..U+%04X 共 %d 枚，实际 %s"
                % (label, expected[0], expected[-1], count,
                   " ".join("U+%04X" % c for c in codepoints[:count + 2]) or "（空）"))
            continue
        path = provider_texture_path(assets, expected_file, problems, label)
        if path is None:
            continue
        try:
            width, height, pixels = read_png_rgba(path)
        except Exception as exc:  # noqa: BLE001
            problems.append("%s: 图集无法解码 %s (%s)" % (label, path, exc))
            continue
        atlas_sizes[expected_file] = (width, height)
        # 图集尺寸 = 字形数 x 字形边长（hud.json 的 height 字段只能描述单枚字形）
        expected_width = count * glyph_size
        if (width, height) != (expected_width, glyph_size):
            problems.append("%s: 图集必须是 %dx%d（%d 枚 %dx%d 字形），实际 %dx%d"
                            % (label, expected_width, glyph_size, count, glyph_size, glyph_size,
                               width, height))
            continue
        # 逐字形：唯一 + 二值 alpha + 覆盖率合理。逐字形而不是整图，
        # 是因为「裁掉一列」在整图层面只表现为宽度少 1，而字形层面的重复/空白更早暴露。
        signatures = set()
        for glyph in range(count):
            visible = 0
            signature = []
            for y in range(glyph_size):
                row_base = y * width * 4
                for x in range(glyph * glyph_size, glyph * glyph_size + glyph_size):
                    offset = row_base + x * 4
                    alpha = pixels[offset + 3]
                    if alpha not in (0, 255):
                        problems.append("%s: 字形 %d 在 (%d,%d) 使用了半透明 alpha=%d"
                                        % (label, glyph, x - glyph * glyph_size, y, alpha))
                    if alpha == 255:
                        visible += 1
                    signature.append(pixels[offset:offset + 4])
            if visible < 8:
                problems.append("%s: 字形 %d 可见像素过少（%d）" % (label, glyph, visible))
            if visible > glyph_size * glyph_size - 9:
                problems.append("%s: 字形 %d 几乎完全实心（%d 个不透明像素）"
                                % (label, glyph, visible))
            key = b"".join(signature)
            if key in signatures:
                problems.append("%s: 字形 %d 与更早的字形完全重复（码位会显示成同一个符号）"
                                % (label, glyph))
            signatures.add(key)
        if len(problems) > 60:
            break

    if problems:
        for line in problems[:40]:
            fail("  " + line)
        if len(problems) > 40:
            fail("  ... 另有 %d 条" % (len(problems) - 40))
        fail("HUD 字体判据不通过：%d 个问题" % len(problems))
        return 1
    print("      U+E000..U+E00A 十一码位 ↔ %dx%d 图集；U+E020..U+E04F 四十八码位 ↔ %dx%d 图集"
          % (atlas_sizes["originalrpg:font/hud_symbols.png"][0], HUD_GLYPH_SIZE,
             atlas_sizes["originalrpg:font/class_skill_symbols.png"][0], HUD_GLYPH_SIZE))
    return 0


def check_journey(assets):
    """判据 5：journey.json 的 U+E100..U+E107 与八张旅程原图严格一致。"""
    font_json = os.path.join(assets, "font", "journey.json")
    problems = []
    if not os.path.isfile(font_json):
        fail("  font/journey.json 不存在: %s" % font_json)
        return 1
    try:
        definition = load_json(font_json)
    except Exception as exc:  # noqa: BLE001
        fail("  font/journey.json 解析失败: %s" % exc)
        return 1
    providers = definition.get("providers")
    if not isinstance(providers, list) or len(providers) != len(JOURNEY_CODEPOINTS):
        fail("  font/journey.json 必须有恰好 %d 个 provider，实际 %r"
             % (len(JOURNEY_CODEPOINTS), len(providers) if isinstance(providers, list) else providers))
        return 1

    # 码位 → 纹理必须一一命中，且纹理文件名与码位顺序严格对应（hub/class/.../dungeon）
    expected_order = ["journey_hub", "journey_class", "journey_attribute", "journey_explore",
                      "journey_hunt", "journey_forge", "journey_reveal", "journey_dungeon"]
    seen_codepoints = {}
    for index, provider in enumerate(providers):
        label = "journey.providers[%d]" % index
        chars = bitmap_provider_problems(provider, label, problems)
        if chars is None:
            continue
        if len(chars) != 1 or len(chars[0]) != 1:
            problems.append("%s: 每个 provider 必须只映射 1 个码位，实际 %r"
                            % (label, chars))
            continue
        codepoint = ord(chars[0])
        if codepoint not in JOURNEY_CODEPOINTS:
            problems.append("%s: 码位 U+%04X 不在 U+E100..U+E107 之内" % (label, codepoint))
            continue
        if codepoint in seen_codepoints:
            problems.append("%s: 码位 U+%04X 被重复映射" % (label, codepoint))
            continue
        seen_codepoints[codepoint] = index
        provider_file = provider.get("file") or ""
        expected_file = "originalrpg:item/%s.png" % expected_order[codepoint - JOURNEY_CODEPOINTS[0]]
        if provider_file != expected_file:
            problems.append("%s: U+%04X 必须指向 %s，实际 %r"
                            % (label, codepoint, expected_file, provider_file))
            continue
        path = provider_texture_path(assets, provider_file, problems, label)
        if path is None:
            continue
        try:
            width, height, _pixels = read_png_rgba(path)
        except Exception as exc:  # noqa: BLE001
            problems.append("%s: 旅程图无法解码 %s (%s)" % (label, path, exc))
            continue
        # 旅程图同时是 32x32 物品纹理（font provider 用 height/ascent 裁出 1x1 逻辑像素），
        # 因此这里断言的就是物品纹理契约本身 —— 尺寸漂了会同时被判据 3 抓到，这里是冗余自证。
        if (width, height) != (ITEM_TEXTURE_SIZE, ITEM_TEXTURE_SIZE):
            problems.append("%s: 旅程图必须是 %dx%d，实际 %dx%d"
                            % (label, ITEM_TEXTURE_SIZE, ITEM_TEXTURE_SIZE, width, height))

    missing = sorted(set(JOURNEY_CODEPOINTS) - set(seen_codepoints))
    if missing:
        problems.append("缺码位: %s" % " ".join("U+%04X" % c for c in missing))
    # 八张旅程图必须真的被字体引用：把 items 定义清空后 font 引用会变成孤儿纹理，
    # 这里的冗余断言保证「八张图 = 八个码位」是双向的。
    if problems:
        for line in problems[:40]:
            fail("  " + line)
        fail("旅程字体判据不通过：%d 个问题" % len(problems))
        return 1
    print("      U+E100..U+E107 八码位 ↔ 八张 %dx%d 旅程图" % (ITEM_TEXTURE_SIZE, ITEM_TEXTURE_SIZE))
    return 0


# --------------------------------------------------------------------------- 语言键
def check_lang(assets):
    """判据 6：en_us.json 与 zh_cn.json 的键集合一致（顺序无关）。"""
    lang_dir = os.path.join(assets, "lang")
    problems = []
    keysets = {}
    for name in ("en_us.json", "zh_cn.json"):
        path = os.path.join(lang_dir, name)
        if not os.path.isfile(path):
            fail("  语言文件不存在: %s" % path)
            return 1
        try:
            data = load_json(path)
        except Exception as exc:  # noqa: BLE001
            fail("  %s 解析失败: %s" % (name, exc))
            return 1
        if not isinstance(data, dict):
            fail("  %s 顶层必须是 JSON 对象" % name)
            return 1
        if not data:
            problems.append("%s 一个键都没有" % name)
        keysets[name] = set(data.keys())

    only_en = sorted(keysets["en_us.json"] - keysets["zh_cn.json"])
    only_zh = sorted(keysets["zh_cn.json"] - keysets["en_us.json"])
    if only_en:
        problems.append("只在 en_us.json 里: %s" % " ".join(only_en[:20]))
    if only_zh:
        problems.append("只在 zh_cn.json 里: %s" % " ".join(only_zh[:20]))
    if problems:
        for line in problems[:40]:
            fail("  " + line)
        fail("语言键判据不通过：%d 个问题" % len(problems))
        return 1
    print("      en_us/zh_cn 键集合一致（各 %d 键）" % len(keysets["en_us.json"]))
    return 0


# --------------------------------------------------------------------------- 罗盘
COMPASS_MODEL_KEYS = ["originalrpg:item/traveler_compass_%02d" % i for i in range(32)]


def check_compass(assets):
    """判据 7：traveler_compass 的 26.2 33 段方向分发与 32 帧模型/纹理严格一致。

    判据来源（先读现有模型再定判据）：26.2 的 `minecraft:compass` 属性把
    「目标相对角度（0..1 归一化）」按 scale=32 展开成 33 个阈值槽，
    槽 i 的阈值 = i-0.5（i=0 时为 0.0，与 vanilla `compass_16` 兜底同构），
    槽 i 的模型 = 32 帧里 (i+16) mod 32 那一片 —— README 明确要求
    「审核过的静态图只在角度无效时兜底」，所以 fallback 必须是静态 `traveler_compass`。
    """
    items_dir = os.path.join(assets, "items")
    models_dir = os.path.join(assets, "models", "item")
    textures_dir = os.path.join(assets, "textures", "item", "traveler_compass")
    problems = []

    definition_path = os.path.join(items_dir, "traveler_compass.json")
    if not os.path.isfile(definition_path):
        fail("  物品定义不存在: %s" % definition_path)
        return 1
    try:
        model = load_json(definition_path).get("model", {})
    except Exception as exc:  # noqa: BLE001
        fail("  %s 解析失败: %s" % (definition_path, exc))
        return 1

    if model.get("type") != "minecraft:range_dispatch":
        problems.append("model.type 必须是 minecraft:range_dispatch，实际 %r" % model.get("type"))
    if model.get("property") != COMPASS_PROPERTY:
        problems.append("model.property 必须是 %s，实际 %r" % (COMPASS_PROPERTY, model.get("property")))
    if model.get("target") != COMPASS_TARGET:
        problems.append("model.target 必须是 %s，实际 %r" % (COMPASS_TARGET, model.get("target")))
    if model.get("scale") != COMPASS_SCALE:
        problems.append("model.scale 必须是 %s，实际 %r" % (COMPASS_SCALE, model.get("scale")))
    fallback = model.get("fallback", {})
    if not (isinstance(fallback, dict) and fallback.get("type") == "minecraft:model"
            and fallback.get("model") == "originalrpg:item/traveler_compass"):
        problems.append("model.fallback 必须是静态兜底 originalrpg:item/traveler_compass，实际 %r"
                        % (fallback,))

    entries = model.get("entries")
    if not isinstance(entries, list):
        fail("  model.entries 必须是数组")
        return 1
    if len(entries) != COMPASS_SEGMENTS:
        problems.append("model.entries 必须是 %d 段（26.2 罗盘 33 槽方向分发），实际 %d"
                        % (COMPASS_SEGMENTS, len(entries)))
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            problems.append("entries[%d] 不是对象" % index)
            continue
        expected_threshold = 0.0 if index == 0 else index - 0.5
        if entry.get("threshold") != expected_threshold:
            problems.append("entries[%d].threshold 必须是 %s，实际 %r"
                            % (index, expected_threshold, entry.get("threshold")))
        expected_model = COMPASS_MODEL_KEYS[(index + 16) % 32]
        actual = (entry.get("model") or {}).get("model") if isinstance(entry.get("model"), dict) else None
        if (entry.get("model") or {}).get("type") != "minecraft:model":
            problems.append("entries[%d].model.type 必须是 minecraft:model" % index)
        if actual != expected_model:
            problems.append("entries[%d] 必须指向 %s，实际 %r" % (index, expected_model, actual))

    # 32 帧模型：必须存在、必须是 generated 父模型、必须指向对应 frame_NN 纹理
    for index in range(32):
        key = "traveler_compass_%02d" % index
        model_path = os.path.join(models_dir, key + ".json")
        if not os.path.isfile(model_path):
            problems.append("缺帧模型: models/item/%s.json" % key)
            continue
        try:
            frame = load_json(model_path)
        except Exception as exc:  # noqa: BLE001
            problems.append("models/item/%s.json 解析失败: %s" % (key, exc))
            continue
        expected_texture = "originalrpg:item/traveler_compass/frame_%02d" % index
        if frame.get("parent") != "minecraft:item/generated":
            problems.append("models/item/%s.json 的 parent 必须是 minecraft:item/generated，实际 %r"
                            % (key, frame.get("parent")))
        if (frame.get("textures") or {}).get("layer0") != expected_texture:
            problems.append("models/item/%s.json 的 layer0 必须是 %s，实际 %r"
                            % (key, expected_texture, (frame.get("textures") or {}).get("layer0")))
    texture_frames = sorted(os.path.basename(p) for p in iter_png(textures_dir)) \
        if os.path.isdir(textures_dir) else []
    expected_frames = ["frame_%02d.png" % i for i in range(32)]
    if texture_frames != expected_frames:
        problems.append("textures/item/traveler_compass 必须恰好含 %d 帧 %s，实际 %d 个: %s"
                        % (len(expected_frames), expected_frames[0] + ".." + expected_frames[-1],
                           len(texture_frames), " ".join(texture_frames[:8])))

    if problems:
        for line in problems[:40]:
            fail("  " + line)
        if len(problems) > 40:
            fail("  ... 另有 %d 条" % (len(problems) - 40))
        fail("罗盘方向分发判据不通过：%d 个问题" % len(problems))
        return 1
    print("      33 段方向分发 ↔ 32 帧模型 + 32 帧纹理（scale=%s target=%s）"
          % (COMPASS_SCALE, COMPASS_TARGET))
    return 0


# --------------------------------------------------------------------------- pack.mcmeta
def check_packmeta(assets):
    """判据 8：pack.mcmeta 精确声明 26.2 的资源格式 88（纯静态，不需要 Paper）。

    为什么这条也能在无 Paper 环境判定：build.sh 里那条「format 88 与安装中的
    26.2 服务端一致」需要读 server/runtime/cache/mojang_26.2.jar（仓外产物，
    CI 里不存在）。但**包自己声明的**格式是纯静态事实，可以判定：
    26.2 的资源格式是 88，而 supported_formats 只列 [88, 88]、无 overlays
    ⇒ 这个包不会被任何别的版本误吃（README 明确说它是 26.2 专用包）。

    无法在 CI 判定的那一半（本地 mojang jar 的 pack_version 是否也是 88）
    由 build.sh 在生产机上判定，见本文件头部说明与 PR 的「替代方案」段落。
    """
    # assets = <repo>/resource-pack/assets/originalrpg，pack.mcmeta 在 resource-pack/ 下。
    # 不要用 os.path.join(assets, "..", "..", "..")：normpath 会把三段 ".." 里的
    # 前两段吃掉（assets 本身已经吃掉了 originalrpg），结果落到 <repo>/pack.mcmeta。
    # 直接用相对资产的固定两级 + 目录名，语义写在代码里而不是靠数点数。
    path = os.path.join(os.path.dirname(os.path.dirname(assets)), "pack.mcmeta")
    problems = []
    if not os.path.isfile(path):
        fail("  pack.mcmeta 不存在: %s" % path)
        return 1
    try:
        definition = load_json(path)
    except Exception as exc:  # noqa: BLE001
        fail("  pack.mcmeta 解析失败: %s" % exc)
        return 1
    pack = definition.get("pack")
    if not isinstance(pack, dict):
        fail("  pack.mcmeta 缺 pack 对象")
        return 1
    if pack.get("pack_format") != 88:
        problems.append("pack.pack_format 必须是 88，实际 %r" % pack.get("pack_format"))
    if pack.get("supported_formats") != [88, 88]:
        problems.append("pack.supported_formats 必须是 [88, 88]，实际 %r"
                        % pack.get("supported_formats"))
    if pack.get("min_format") != 88:
        problems.append("pack.min_format 必须是 88，实际 %r" % pack.get("min_format"))
    if pack.get("max_format") != 88:
        problems.append("pack.max_format 必须是 88，实际 %r" % pack.get("max_format"))
    if "overlays" in definition:
        problems.append("pack.mcmeta 不得声明 overlays（包必须是自包含的单一版本）")
    if problems:
        for line in problems[:20]:
            fail("  " + line)
        fail("pack.mcmeta 判据不通过：%d 个问题" % len(problems))
        return 1
    print("      pack.mcmeta 精确声明资源格式 88（min=max=88，无 overlays）")
    return 0


CHECKS = {
    "closure": check_closure,
    "textures": check_textures,
    "hud": check_hud,
    "journey": check_journey,
    "lang": check_lang,
    "compass": check_compass,
    "packmeta": check_packmeta,
}


def main():
    parser = argparse.ArgumentParser(description="资源包素材门禁判据")
    parser.add_argument("--root", required=True, help="仓库根目录")
    parser.add_argument("--check", required=True, choices=sorted(CHECKS), help="要运行的判据")
    args = parser.parse_args()
    assets = os.path.join(args.root, "resource-pack", "assets", "originalrpg")
    if not os.path.isdir(assets):
        print("资产目录不存在: %s" % assets, file=sys.stderr)
        return 2
    return CHECKS[args.check](assets)


if __name__ == "__main__":
    sys.exit(main())
