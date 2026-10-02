#!/usr/bin/env python3
"""Generate deterministic low-resolution BetterHud and skill-menu assets."""

import json
from pathlib import Path
from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "server" / "config-templates" / "BetterHud" / "assets"
REVIEW = ROOT / "resource-pack" / "ai" / "output" / "review"
HUD_ATLAS = ROOT / "resource-pack" / "assets" / "originalrpg" / "textures" / "font" / "hud_symbols.png"
CLASS_SKILL_ATLAS = ROOT / "resource-pack" / "assets" / "originalrpg" / "textures" / "font" / "class_skill_symbols.png"
ITEM_TEXTURES = ROOT / "resource-pack" / "assets" / "originalrpg" / "textures" / "item"
ITEM_MODELS = ROOT / "resource-pack" / "assets" / "originalrpg" / "models" / "item"
ITEM_DEFINITIONS = ROOT / "resource-pack" / "assets" / "originalrpg" / "items"
CLASSES = ("healer", "warrior", "assassin", "mage", "ranger", "freeform")
CLASS_SKILLS = {
    "healer": ("heal", "renewal", "sanctuary", "fate-seal", "life-thread", "omen-reversal", "soul-resonance"),
    "warrior": ("war-cry", "shield-wall", "fortress", "blood-rage", "rallying-standard", "ruin-charge", "unbroken-line"),
    "assassin": ("shadow-step", "venom-blade", "smoke-bomb", "toxic-burst", "void-cut", "plague-bloom", "vanished-horizon"),
    "mage": ("fireball", "frost-nova", "meteor", "mana-surge", "ash-domain", "time-lock", "axiomatic-break"),
    "ranger": ("power-shot", "volley", "piercing-shot", "beast-call", "gale-step", "pack-hunt", "horizon-arrow"),
    "freeform": ("improvisation", "second-wind", "battle-trance", "arcane-edge", "adaptive-arsenal", "rune-reversal", "open-form"),
}
PALETTES = {
    "healer": ((54, 199, 155, 255), (177, 255, 221, 255), (18, 79, 70, 255)),
    "warrior": ((205, 68, 55, 255), (255, 184, 91, 255), (91, 35, 37, 255)),
    # Muted assassin so 4 purple slots don't dominate HUD (was 142,67,190 too saturated)
    "assassin": ((112, 62, 148, 255), (205, 175, 220, 255), (38, 32, 62, 255)),
    "mage": ((55, 148, 225, 255), (139, 235, 255, 255), (35, 47, 111, 255)),
    "ranger": ((72, 176, 91, 255), (194, 239, 118, 255), (37, 81, 49, 255)),
    "freeform": ((222, 150, 52, 255), (255, 231, 125, 255), (88, 59, 40, 255)),
}
# Class colour remains the outer visual language, while a small set of
# strongly semantic skills gets its own material colour.  In the previous
# pass a blue fireball and a purple poison explosion were technically unique
# silhouettes but read poorly during combat.  These overrides keep adjacent
# skills distinguishable without turning the whole strip into a rainbow.
SKILL_PALETTES = {
    "venom-blade": ((87, 193, 83, 255), (202, 255, 126, 255), (36, 78, 43, 255)),
    "toxic-burst": ((115, 202, 69, 255), (226, 255, 132, 255), (57, 80, 32, 255)),
    "plague-bloom": ((128, 193, 73, 255), (225, 255, 142, 255), (63, 71, 36, 255)),
    "fireball": ((223, 75, 37, 255), (255, 205, 72, 255), (105, 35, 28, 255)),
    "meteor": ((213, 63, 35, 255), (255, 173, 57, 255), (82, 31, 31, 255)),
    "ash-domain": ((151, 75, 50, 255), (255, 144, 61, 255), (54, 47, 49, 255)),
    "frost-nova": ((52, 160, 226, 255), (205, 250, 255, 255), (34, 66, 122, 255)),
    "mana-surge": ((69, 116, 235, 255), (155, 240, 255, 255), (42, 42, 125, 255)),
    "time-lock": ((75, 177, 212, 255), (211, 255, 247, 255), (35, 62, 110, 255)),
    "axiomatic-break": ((116, 82, 218, 255), (116, 234, 255, 255), (48, 37, 112, 255)),
    # Movement/void skills get muted, distinctive widows so the assassin strip is not a solid purple block
    "shadow-step": ((92, 72, 140, 255), (175, 160, 210, 255), (38, 32, 66, 255)),
    "void-cut": ((68, 45, 120, 255), (155, 120, 235, 255), (22, 18, 48, 255)),
    "gale-step": ((72, 150, 165, 255), (165, 230, 225, 255), (32, 62, 72, 255)),
    "vanished-horizon": ((88, 62, 160, 255), (165, 140, 225, 255), (28, 24, 58, 255)),
}
SKILLS = (
    "heal", "war-cry", "shadow-step", "fireball", "power-shot", "improvisation",
    "renewal", "sanctuary", "fate-seal", "shield-wall", "fortress", "blood-rage",
    "venom-blade", "smoke-bomb", "toxic-burst", "frost-nova", "meteor", "mana-surge",
    "volley", "piercing-shot", "beast-call", "second-wind", "battle-trance", "arcane-edge",
    "life-thread", "omen-reversal", "soul-resonance", "rallying-standard", "ruin-charge",
    "unbroken-line", "void-cut", "plague-bloom", "vanished-horizon", "ash-domain",
    "time-lock", "axiomatic-break", "gale-step", "pack-hunt", "horizon-arrow",
    "adaptive-arsenal", "rune-reversal", "open-form",
)

# Only these reviewed glyphs are embedded. Keeping the tiny source table here
# makes release builds reproducible and avoids depending on BetterHud's cached
# 7.7 MiB unifont.hex or shipping the complete CJK plane to every client.
CONTROL_GLYPHS = {
    "F": "000000007E4040407C40404040400000",
    "2": "000000003C4242020C102040407E0000",
    "x": "00000000000042422418182442420000",
    "C": "000000003C42424040404042423C0000",
    "A": "0000000018242442427E424242420000",
    "S": "000000003C424240300C0242423C0000",
    "T": "000000007F0808080808080808080000",
    "Y": "00000000414122221408080808080000",
    "L": "000000004040404040404040407E0000",
    "E": "000000007E4040407C404040407E0000",
    "施": "2080108010FE0120FE202120212C3D7427A425242534252A2522450254FE8800",
    "放": "204010400040FE8020FE21083E88248824882450245024204450548889040202",
    "双": "00000000FDFC0484448444842888288810501050282028204450448881040202",
    "击": "0100010001003FF8010001000100FFFE0100010021082108210821083FF80008",
    "切": "1000100011FC104410441E44F044104410441244144418841084010402280410",
    "换": "1080108010F81108FA1015FC112419243124D12417FE10501050108851042602",
}


def save(image: Image.Image, name: str) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    image.save(OUT / name, optimize=True)


def _control_glyph(char: str) -> Image.Image:
    encoded = CONTROL_GLYPHS[char]
    width = 8 if len(encoded) == 32 else 16
    image = Image.new("RGBA", (width, 16), (0, 0, 0, 0))
    pixels = image.load()
    row_chars = width // 4
    for y in range(16):
        bits = int(encoded[y * row_chars:(y + 1) * row_chars], 16)
        for x in range(width):
            if bits & (1 << (width - x - 1)):
                pixels[x, y] = (225, 234, 231, 255)
    return image


def control_label(text: str, name: str) -> None:
    widths = [4 if char == " " else _control_glyph(char).width for char in text]
    width = sum(widths) + max(0, len(text) - 1) + 4
    image = Image.new("RGBA", (width, 12), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 1, width - 1, 10), fill=(8, 13, 17, 214), outline=(61, 78, 80, 235))
    draw.point((0, 1), fill=(178, 104, 58, 255))
    x = 2
    for char, char_width in zip(text, widths):
        if char != " ":
            glyph = _control_glyph(char).crop((0, 2, char_width, 14))
            image.alpha_composite(glyph, (x, 0))
        x += char_width + 1
    save(image, name)


def control_labels() -> None:
    control_label("F 施放", "sivita_control_cast_zh.png")
    control_label("双击 F 切换", "sivita_control_cycle_zh.png")
    control_label("F CAST", "sivita_control_cast_en.png")
    control_label("2x F CYCLE", "sivita_control_cycle_en.png")


def skill_panel() -> None:
    image = Image.new("RGBA", (26, 91), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # A vertical mana rail keeps the whole combat HUD away from the native
    # hotbar and reserves the centered ActionBar for readable feedback.
    draw.rectangle((0, 1, 2, 89), fill=(11, 18, 22, 238))
    draw.line((1, 2, 1, 88), fill=(53, 72, 77, 255))
    draw.point((1, 0), fill=(164, 91, 47, 255))
    draw.point((1, 90), fill=(164, 91, 47, 255))
    # Four stable 21px tiles form a single right-side rail. At GUI scales 2–4
    # the rail stays readable without covering hearts, hunger, XP, or chat.
    for slot in range(4):
        y = 1 + slot * 22
        draw.rectangle((4, y, 25, y + 20), fill=(12, 16, 20, 242))
        draw.rectangle((4, y, 24, y + 20), outline=(105, 116, 119, 255))
        draw.rectangle((6, y + 2, 22, y + 18), fill=(29, 39, 46, 232))
        draw.line((6, y + 2, 22, y + 2), fill=(55, 76, 82, 255))
        draw.point((4, y), fill=(181, 104, 55, 255))
        draw.point((24, y), fill=(181, 104, 55, 255))
        draw.point((4, y + 20), fill=(111, 61, 38, 255))
        draw.point((24, y + 20), fill=(111, 61, 38, 255))
    save(image, "sivita_skill_panel.png")


def selected_frame() -> None:
    # 与 docs/reference/hud-layout.json 的 slot.iconBox 同格：21x21 比槽位宽 3px，
    # 会压到相邻槽之间的 4px 间隙。
    image = Image.new("RGBA", (18, 18), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # Four corner brackets read as selection without becoming a fifth frame.
    gold = (246, 194, 78, 255)
    light = (255, 232, 139, 255)
    for lines in (
        ((0, 0, 4, 0), (0, 0, 0, 4)),
        ((13, 0, 17, 0), (17, 0, 17, 4)),
        ((0, 17, 4, 17), (0, 13, 0, 17)),
        ((13, 17, 17, 17), (17, 13, 17, 17)),
    ):
        draw.line(lines[0], fill=gold)
        draw.line(lines[1], fill=gold)
    draw.line((5, 1, 12, 1), fill=light)
    save(image, "sivita_skill_selected.png")


def cooldown_mask() -> None:
    image = Image.new("RGBA", (18, 18), (7, 10, 14, 154))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 17, 17), outline=(20, 27, 33, 205))
    # Sparse diagonal cuts preserve the skill silhouette below the mask.
    for offset in range(-12, 24, 6):
        draw.line((offset, 17, offset + 17, 0), fill=(69, 82, 91, 72))
    save(image, "sivita_skill_cooldown.png")


def mana_fill() -> None:
    image = Image.new("RGBA", (1, 87), (72, 223, 226, 255))
    draw = ImageDraw.Draw(image)
    draw.point((0, 0), fill=(32, 146, 167, 255))
    draw.point((0, 86), fill=(181, 255, 247, 255))
    save(image, "sivita_mana_fill.png")


def class_badge() -> None:
    image = Image.new("RGBA", (29, 26), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.polygon(((0, 4), (4, 0), (24, 0), (28, 4), (28, 25), (0, 25)),
                 fill=(13, 19, 23, 238))
    # This is a class frame highlight, not a second mana meter. Keeping it
    # bronze makes the cyan line over the four skills unambiguous.
    draw.line((4, 1, 24, 1), fill=(177, 105, 61, 255))
    draw.line((1, 5, 1, 24), fill=(121, 70, 43, 255))
    draw.line((27, 5, 27, 24), fill=(121, 70, 43, 255))
    draw.line((20, 4, 20, 24), fill=(55, 76, 82, 255))
    draw.point((4, 0), fill=(226, 143, 76, 255))
    draw.point((24, 0), fill=(226, 143, 76, 255))
    save(image, "sivita_class_badge.png")


def padded(image: Image.Image, width: int = 18, height: int = 18) -> Image.Image:
    """把窄画布居中放进契约尺寸，透明留白而不是拉伸像素。"""
    if image.size == (width, height):
        return image
    canvas = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    canvas.alpha_composite(image, ((width - image.width) // 2, (height - image.height) // 2))
    return canvas


def icon_canvas(accent: tuple[int, int, int, int]) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    # 语义图标画布 = docs/reference/hud-layout.json 的 icon 宽高；内容仍画在 16px 内圈，
    # 左右各留 1px 透明，使 18px 的图标与 29px 的职业徽章共用同一套留白规则。
    image = Image.new("RGBA", (18, 18), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.polygon(((4, 1), (13, 1), (16, 4), (16, 13), (13, 16), (4, 16), (1, 13), (1, 4)),
                 fill=(10, 14, 19, 245), outline=(79, 88, 94, 255))
    draw.line(((5, 2), (12, 2)), fill=accent)
    draw.point((2, 5), fill=accent)
    draw.point((15, 12), fill=accent)
    return image, draw


def class_icon(class_id: str) -> Image.Image:
    primary, light, shadow = PALETTES[class_id]
    image, draw = icon_canvas(primary)
    if class_id == "healer":
        draw.polygon(((5, 5), (12, 5), (11, 10), (9, 12), (7, 10)), fill=shadow, outline=primary)
        draw.line(((8, 4), (8, 11)), fill=light, width=2)
        draw.line(((5, 7), (11, 7)), fill=light, width=2)
    elif class_id == "warrior":
        draw.polygon(((4, 4), (13, 4), (12, 11), (8, 14), (4, 11)), fill=shadow, outline=primary)
        draw.line(((8, 4), (8, 12)), fill=light)
        draw.line(((5, 6), (11, 10)), fill=primary, width=2)
    elif class_id == "assassin":
        draw.polygon(((8, 3), (13, 6), (11, 13), (8, 15), (5, 12), (4, 6)), fill=shadow, outline=primary)
        draw.line(((5, 12), (12, 5)), fill=light, width=2)
        draw.point((10, 6), fill=(255, 255, 255, 255))
    elif class_id == "mage":
        draw.polygon(((8, 3), (10, 7), (14, 8), (10, 10), (8, 14), (6, 10), (3, 8), (6, 7)),
                     fill=primary, outline=light)
        draw.rectangle((7, 7, 9, 9), fill=(235, 252, 255, 255))
    elif class_id == "ranger":
        draw.arc((3, 3, 12, 14), 275, 85, fill=light, width=2)
        draw.line(((8, 4), (8, 13)), fill=primary)
        draw.line(((5, 8), (13, 8)), fill=light, width=2)
        draw.polygon(((13, 8), (10, 6), (10, 10)), fill=primary)
    else:
        draw.ellipse((4, 4, 13, 13), outline=primary, width=2)
        draw.polygon(((8, 3), (10, 8), (8, 14), (6, 8)), fill=light, outline=shadow)
        draw.point((8, 8), fill=(255, 255, 255, 255))
    return image


def skill_class(skill_id: str) -> str:
    return next(class_id for class_id, ids in CLASS_SKILLS.items() if skill_id in ids)


def skill_icon(skill_id: str) -> Image.Image:
    class_id = skill_class(skill_id)
    primary, light, shadow = SKILL_PALETTES.get(skill_id, PALETTES[class_id])
    image, draw = icon_canvas(primary)
    # Every skill uses a semantic silhouette; these are deliberately drawn at
    # the final 18px resolution instead of enlarging the legacy 9px atlas.
    if skill_id in {"heal", "life-thread"}:
        draw.line(((8, 4), (8, 13)), fill=light, width=2)
        draw.line(((4, 8), (12, 8)), fill=light, width=2)
        if skill_id == "life-thread": draw.line(((3, 13), (6, 10), (10, 12), (14, 5)), fill=primary)
    elif skill_id == "renewal":
        draw.line(((5, 12), (11, 5)), fill=primary, width=2); draw.ellipse((8, 4, 13, 8), fill=light)
    elif skill_id == "sanctuary":
        draw.arc((3, 4, 14, 14), 180, 360, fill=light, width=2); draw.line(((4, 10), (13, 10)), fill=primary)
    elif skill_id in {"fate-seal", "rune-reversal"}:
        draw.polygon(((8, 3), (13, 8), (8, 13), (3, 8)), outline=light, fill=shadow)
        draw.line(((5, 8), (11, 8)), fill=primary); draw.point((8, 6), fill=light)
    elif skill_id == "omen-reversal":
        draw.arc((3, 3, 13, 13), 25, 205, fill=light, width=2); draw.polygon(((3, 8), (3, 12), (6, 10)), fill=primary)
    elif skill_id == "soul-resonance":
        draw.polygon(((8, 3), (12, 8), (9, 14), (5, 11), (4, 7)), fill=primary, outline=light); draw.point((8, 9), fill=light)
    elif skill_id == "war-cry":
        draw.polygon(((3, 7), (8, 5), (13, 3), (11, 9), (8, 10), (5, 9)), fill=primary, outline=light)
        draw.line(((12, 11), (15, 13)), fill=light)
    elif skill_id in {"shield-wall", "fortress", "unbroken-line"}:
        if skill_id == "fortress":
            draw.rectangle((4, 6, 13, 13), fill=shadow, outline=light); draw.rectangle((6, 4, 8, 7), fill=primary); draw.rectangle((10, 4, 12, 7), fill=primary)
        else:
            draw.polygon(((4, 4), (13, 4), (12, 11), (8, 14), (4, 11)), fill=shadow, outline=light)
            draw.line(((8, 4), (8, 13)), fill=primary, width=2)
            if skill_id == "unbroken-line": draw.line(((2, 13), (15, 13)), fill=light, width=2)
    elif skill_id == "blood-rage":
        draw.polygon(((9, 3), (13, 8), (10, 14), (5, 12), (4, 8)), fill=primary, outline=light); draw.line(((8, 5), (6, 11)), fill=shadow, width=2)
    elif skill_id == "rallying-standard":
        draw.line(((5, 3), (5, 14)), fill=light, width=2); draw.polygon(((6, 4), (13, 5), (10, 9), (6, 8)), fill=primary)
    elif skill_id == "ruin-charge":
        draw.line(((3, 12), (12, 4)), fill=light, width=2); draw.polygon(((12, 4), (9, 4), (13, 8)), fill=primary); draw.line(((3, 14), (7, 14)), fill=primary)
    elif skill_id == "shadow-step":
        # Afterimage dash: two offset silhouettes + smoke puff + dagger tip
        draw.ellipse((3, 9, 9, 13), fill=(shadow[0], shadow[1], shadow[2], 110), outline=None)
        draw.ellipse((5, 7, 11, 11), fill=(shadow[0], shadow[1], shadow[2], 160), outline=None)
        draw.line(((3, 12), (11, 4)), fill=light, width=2)
        draw.line(((5, 14), (14, 5)), fill=primary, width=2)
        draw.polygon(((12, 3), (14, 3), (13, 6)), fill=light, outline=primary)
        draw.point((3, 8), fill=(255, 255, 255, 220))
    elif skill_id == "gale-step":
        # Wind swirl with feather
        draw.arc((3, 5, 13, 13), 20, 160, fill=primary, width=2)
        draw.arc((5, 7, 11, 11), 30, 140, fill=light, width=1)
        draw.line(((3, 12), (11, 4)), fill=light, width=1)
        draw.polygon(((11, 3), (14, 5), (12, 7), (9, 5)), fill=light, outline=primary)
        draw.point((6, 12), fill=primary)
    elif skill_id in {"venom-blade", "arcane-edge"}:
        draw.polygon(((4, 13), (7, 7), (13, 3), (10, 10)), fill=light, outline=primary); draw.rectangle((3, 12, 7, 14), fill=shadow, outline=primary)
    elif skill_id == "void-cut":
        # Void rift: dark portal with purple rim + sharp slash tearing it
        draw.ellipse((5, 5, 12, 12), fill=(18, 16, 32, 255), outline=primary)
        draw.ellipse((6, 6, 11, 11), fill=(28, 22, 48, 255), outline=None)
        draw.ellipse((7, 7, 10, 10), fill=(12, 10, 22, 255))
        draw.polygon(((4, 13), (7, 7), (13, 3), (10, 10)), fill=light, outline=primary)
        draw.rectangle((3, 12, 7, 14), fill=shadow, outline=primary)
        draw.line(((3, 4), (14, 14)), fill=(255, 220, 255, 255), width=1)
        draw.line(((4, 5), (13, 13)), fill=primary, width=1)
        draw.point((8, 8), fill=(255, 255, 255, 200))
    elif skill_id == "smoke-bomb":
        draw.ellipse((4, 7, 13, 13), fill=shadow, outline=primary); draw.ellipse((5, 4, 9, 8), fill=primary); draw.ellipse((9, 3, 13, 7), fill=light)
    elif skill_id == "toxic-burst":
        draw.ellipse((5, 5, 12, 12), fill=shadow, outline=light)
        draw.rectangle((7, 7, 10, 10), fill=primary)
        for line in (((8, 4), (8, 2)), ((8, 13), (8, 15)), ((4, 8), (2, 8)),
                     ((13, 8), (15, 8)), ((5, 5), (3, 3)), ((11, 11), (14, 14))):
            draw.line(line, fill=primary, width=2)
    elif skill_id == "plague-bloom":
        draw.ellipse((6, 6, 11, 11), fill=light, outline=shadow)
        for box in ((6, 2, 10, 6), (10, 5, 14, 9), (8, 10, 12, 14),
                    (3, 9, 7, 13), (2, 4, 6, 8)):
            draw.ellipse(box, fill=primary, outline=light)
        draw.point((8, 8), fill=(235, 255, 190, 255))
    elif skill_id == "vanished-horizon":
        draw.line(((2, 10), (15, 10)), fill=primary, width=2); draw.polygon(((8, 3), (11, 8), (8, 13), (5, 8)), fill=light)
    elif skill_id in {"fireball", "meteor", "ash-domain"}:
        draw.polygon(((9, 3), (13, 8), (10, 14), (5, 12), (4, 8)), fill=primary, outline=light)
        if skill_id == "meteor": draw.line(((3, 3), (7, 7)), fill=light, width=2)
        if skill_id == "ash-domain": draw.arc((2, 9, 15, 15), 180, 360, fill=light, width=2)
    elif skill_id == "frost-nova":
        draw.line(((8, 3), (8, 14)), fill=light); draw.line(((3, 8), (14, 8)), fill=light); draw.line(((4, 4), (12, 12)), fill=primary); draw.line(((12, 4), (4, 12)), fill=primary); draw.rectangle((7, 7, 9, 9), fill=(235, 255, 255, 255))
    elif skill_id == "mana-surge":
        draw.line(((9, 3), (5, 9), (9, 9), (7, 14), (13, 7), (9, 7)), fill=light, width=2)
    elif skill_id == "time-lock":
        draw.ellipse((4, 4, 13, 13), outline=light, width=2); draw.line(((8, 5), (8, 9), (11, 11)), fill=primary, width=2)
    elif skill_id == "axiomatic-break":
        draw.polygon(((8, 3), (13, 8), (8, 13), (3, 8)), outline=light); draw.line(((4, 13), (12, 4)), fill=primary, width=2)
    elif skill_id in {"power-shot", "piercing-shot", "horizon-arrow"}:
        draw.line(((3, 12), (13, 4)), fill=light, width=2); draw.polygon(((13, 4), (9, 4), (13, 8)), fill=primary)
        if skill_id == "piercing-shot": draw.line(((4, 14), (14, 14)), fill=primary)
        if skill_id == "horizon-arrow": draw.line(((2, 10), (15, 10)), fill=primary)
    elif skill_id == "volley":
        for offset in (0, 3, 6): draw.line(((3 + offset, 13), (8 + offset, 4)), fill=light)
    elif skill_id == "beast-call":
        draw.polygon(((3, 7), (5, 4), (8, 6), (11, 4), (14, 7), (12, 13), (8, 14), (4, 12)), fill=shadow, outline=primary); draw.point((6, 9), fill=light); draw.point((10, 9), fill=light)
    elif skill_id == "pack-hunt":
        draw.arc((2, 2, 10, 10), 190, 40, fill=light, width=2)
        for offset in (0, 4, 8):
            draw.line(((3 + offset, 14), (7 + offset, 6)), fill=primary, width=2)
        draw.point((13, 4), fill=light)
    elif skill_id == "improvisation":
        draw.polygon(((8, 3), (10, 7), (14, 8), (10, 10), (8, 14), (6, 10), (3, 8), (6, 7)), fill=primary, outline=light)
    elif skill_id == "second-wind":
        draw.arc((3, 4, 14, 14), 40, 300, fill=light, width=2); draw.polygon(((13, 4), (10, 4), (13, 7)), fill=primary)
    elif skill_id == "battle-trance":
        draw.line(((3, 12), (8, 4), (13, 12)), fill=light, width=2); draw.line(((4, 8), (12, 8)), fill=primary)
    elif skill_id == "adaptive-arsenal":
        draw.line(((4, 13), (12, 4)), fill=light, width=2); draw.line(((4, 4), (12, 13)), fill=primary, width=2); draw.rectangle((7, 7, 9, 9), fill=light)
    elif skill_id == "open-form":
        draw.arc((3, 3, 14, 14), 35, 325, fill=light, width=2); draw.line(((8, 4), (8, 13)), fill=primary); draw.line(((4, 8), (13, 8)), fill=primary)
    else:
        draw.polygon(((8, 3), (13, 8), (8, 13), (3, 8)), fill=primary, outline=light)
    return image


def semantic_icons() -> None:
    """Build readable final-resolution BetterHud icons.

    The 9px atlas remains as a chat/action-bar fallback, but BetterHud consumes
    these independent 18px images so each class and skill keeps a crisp shape.
    """
    for class_id in CLASSES:
        save(padded(class_icon(class_id)), f"sivita_icon_class_{class_id}.png")
    for skill_id in SKILLS:
        save(padded(skill_icon(skill_id)), f"sivita_icon_skill_{skill_id}.png")

    base = Image.open(HUD_ATLAS).convert("RGBA")
    if base.size[1] != 9 or base.size[0] < 9 * 9:
        raise ValueError(f"unexpected base HUD atlas size: {base.size}")
    # HUB is U+E008, the ninth reviewed base symbol.
    unchosen = base.crop((8 * 9, 0, 9 * 9, 9)).resize((18, 18), Image.Resampling.NEAREST)
    save(unchosen, "sivita_icon_class_unchosen.png")


def skill_menu_items() -> None:
    """Project the reviewed HUD icons into native 26.2 item models.

    The 18px HUD source is doubled and cropped on whole source pixels, giving
    a full 32px item texture without interpolation. One semantic source now
    identifies a skill in both the combat rail and the inventory skill tree.
    """
    for directory in (ITEM_TEXTURES, ITEM_MODELS, ITEM_DEFINITIONS):
        directory.mkdir(parents=True, exist_ok=True)
    for skill_id in SKILLS:
        source = Image.open(OUT / f"sivita_icon_skill_{skill_id}.png").convert("RGBA")
        texture = source.resize((36, 36), Image.Resampling.NEAREST).crop((2, 2, 34, 34))
        texture.putalpha(texture.getchannel("A").point(lambda alpha: 255 if alpha >= 128 else 0))
        asset_id = f"skill_{skill_id}"
        texture.save(ITEM_TEXTURES / f"{asset_id}.png", optimize=True)
        (ITEM_MODELS / f"{asset_id}.json").write_text(json.dumps({
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"originalrpg:item/{asset_id}"},
        }, separators=(",", ":")) + "\n", encoding="utf-8")
        (ITEM_DEFINITIONS / f"{asset_id}.json").write_text(json.dumps({
            "model": {"type": "minecraft:model", "model": f"originalrpg:item/{asset_id}"},
        }, separators=(",", ":")) + "\n", encoding="utf-8")


def layout_review() -> None:
    """Render a nearest-neighbour production geometry check.

    This is intentionally deterministic rather than concept art: every pixel
    shown here is the exact asset BetterHud receives at runtime.
    """
    rail = Image.new("RGBA", (60, 91), (0, 0, 0, 0))
    rail.alpha_composite(Image.open(OUT / "sivita_class_badge.png").convert("RGBA"), (0, 33))
    rail.alpha_composite(Image.open(OUT / "sivita_skill_panel.png").convert("RGBA"), (34, 0))
    rail.alpha_composite(Image.open(OUT / "sivita_icon_class_mage.png").convert("RGBA"), (2, 39))
    skills = ("fireball", "frost-nova", "meteor", "mana-surge")
    for index, skill_id in enumerate(skills):
        rail.alpha_composite(
            Image.open(OUT / f"sivita_icon_skill_{skill_id}.png").convert("RGBA"),
            (40, 3 + index * 22),
        )
    rail.alpha_composite(Image.open(OUT / "sivita_skill_selected.png").convert("RGBA"), (38, 1))
    rail.alpha_composite(Image.open(OUT / "sivita_skill_cooldown.png").convert("RGBA"), (40, 47))
    mana = Image.open(OUT / "sivita_mana_fill.png").convert("RGBA").crop((0, 15, 1, 87))
    rail.alpha_composite(mana, (35, 16))

    scale = 4
    scaled = rail.resize((rail.width * scale, rail.height * scale), Image.Resampling.NEAREST)
    preview = Image.new("RGBA", (scaled.width + 32, scaled.height + 32), (21, 27, 32, 255))
    preview.alpha_composite(scaled, (16, 16))
    REVIEW.mkdir(parents=True, exist_ok=True)
    preview.save(REVIEW / "hud-combat-rail-v4.png", optimize=True)


def safe_area_review() -> None:
    """Render the exact production rail against vanilla-safe 1080p geometry.

    BetterHud works in logical GUI pixels.  Rendering each supported scale at
    its real 1920x1080 pixel size catches edge clipping and overlap that an
    isolated asset preview cannot reveal.
    """
    rail = Image.new("RGBA", (60, 91), (0, 0, 0, 0))
    rail.alpha_composite(Image.open(OUT / "sivita_class_badge.png").convert("RGBA"), (0, 33))
    rail.alpha_composite(Image.open(OUT / "sivita_skill_panel.png").convert("RGBA"), (34, 0))
    rail.alpha_composite(Image.open(OUT / "sivita_icon_class_mage.png").convert("RGBA"), (2, 39))
    for index, skill_id in enumerate(("fireball", "frost-nova", "meteor", "mana-surge")):
        rail.alpha_composite(
            Image.open(OUT / f"sivita_icon_skill_{skill_id}.png").convert("RGBA"),
            (40, 3 + index * 22),
        )
    rail.alpha_composite(Image.open(OUT / "sivita_skill_selected.png").convert("RGBA"), (38, 23))
    rail.alpha_composite(Image.open(OUT / "sivita_skill_cooldown.png").convert("RGBA"), (40, 47))
    rail.alpha_composite(Image.open(OUT / "sivita_mana_fill.png").convert("RGBA").crop((0, 15, 1, 87)), (35, 16))

    REVIEW.mkdir(parents=True, exist_ok=True)
    contact = Image.new("RGB", (1920, 1080), (12, 17, 21))
    for column, gui_scale in enumerate((2, 3, 4)):
        logical_width = 1920 // gui_scale
        logical_height = 1080 // gui_scale
        canvas = Image.new("RGBA", (logical_width, logical_height), (84, 126, 133, 255))
        draw = ImageDraw.Draw(canvas)
        horizon = logical_height * 3 // 5
        draw.rectangle((0, horizon, logical_width, logical_height), fill=(63, 91, 54, 255))
        for x in range(0, logical_width, 32):
            height = 16 + (x // 32 % 4) * 7
            draw.rectangle((x, horizon - height, x + 24, horizon), fill=(47, 64, 58, 255))
            draw.rectangle((x + 5, horizon - height - 4, x + 19, horizon - height), fill=(99, 80, 54, 255))

        rail_left = logical_width - rail.width - 10
        rail_top = (logical_height - rail.height) // 2
        assert rail_left >= logical_width // 2
        assert rail_left + rail.width <= logical_width - 8
        assert rail_top >= 8
        assert rail_top + rail.height <= logical_height - 8
        canvas.alpha_composite(rail, (rail_left, rail_top))

        # Minimal vanilla geometry: hotbar, XP, hearts/armor and hunger.  These
        # are placement guards, not resource-pack replacements.
        hotbar_left = (logical_width - 182) // 2
        hotbar_top = logical_height - 22
        draw.rectangle((hotbar_left, hotbar_top, hotbar_left + 181, logical_height - 1),
                       fill=(28, 29, 29, 210), outline=(174, 174, 174, 255))
        for slot in range(9):
            x = hotbar_left + 2 + slot * 20
            draw.rectangle((x, hotbar_top + 2, x + 17, hotbar_top + 19), outline=(75, 75, 75, 255))
        draw.rectangle((hotbar_left, logical_height - 25, hotbar_left + 181, logical_height - 24),
                       fill=(66, 196, 171, 255))
        for index in range(10):
            left_x = hotbar_left + index * 8
            right_x = hotbar_left + 104 + index * 8
            draw.rectangle((left_x, logical_height - 34, left_x + 5, logical_height - 30),
                           fill=(206, 55, 61, 255))
            draw.rectangle((right_x, logical_height - 34, right_x + 5, logical_height - 30),
                           fill=(220, 157, 54, 255))

        actual = canvas.resize((1920, 1080), Image.Resampling.NEAREST)
        actual.save(REVIEW / f"hud-1920x1080-gui{gui_scale}-v4.png", optimize=True)
        thumb = actual.resize((640, 360), Image.Resampling.NEAREST)
        contact.paste(thumb.convert("RGB"), (column * 640, 0))
    contact.crop((0, 0, 1920, 360)).save(REVIEW / "hud-safe-area-scales-v4.png", optimize=True)

    # Regression guards for the actual 1408x792 game viewport seen in the
    # operator screenshot and for common small-window layouts. Minecraft caps
    # GUI scale against its logical minimum, so these are the narrowest useful
    # cases rather than arbitrary desktop resolutions.
    for physical_width, physical_height, gui_scale in (
        (1408, 792, 4),
        (1280, 720, 4),
        (1280, 720, 3),
        (960, 540, 2),
    ):
        logical_width = physical_width // gui_scale
        logical_height = physical_height // gui_scale
        rail_left = logical_width - rail.width - 10
        rail_top = (logical_height - rail.height) // 2
        assert rail_left >= logical_width // 2
        assert rail_left + rail.width <= logical_width - 8
        assert rail_top >= 8
        assert rail_top + rail.height <= logical_height - 8


def skill_icon_review() -> None:
    columns = 7
    cell_width = 136
    cell_height = 96
    rows = (len(SKILLS) + columns - 1) // columns
    sheet = Image.new("RGBA", (columns * cell_width, rows * cell_height), (21, 27, 32, 255))
    draw = ImageDraw.Draw(sheet)
    for index, skill_id in enumerate(SKILLS):
        column = index % columns
        row = index // columns
        x = column * cell_width
        y = row * cell_height
        icon = Image.open(OUT / f"sivita_icon_skill_{skill_id}.png").convert("RGBA")
        icon = icon.resize((72, 72), Image.Resampling.NEAREST)
        sheet.alpha_composite(icon, (x + 32, y + 2))
        draw.text((x + 4, y + 76), skill_id, fill=(220, 226, 229, 255))
    REVIEW.mkdir(parents=True, exist_ok=True)
    sheet.save(REVIEW / "skill-icons-contact-sheet-v3.png", optimize=True)


if __name__ == "__main__":
    skill_panel()
    selected_frame()
    cooldown_mask()
    mana_fill()
    class_badge()
    control_labels()
    semantic_icons()
    skill_menu_items()
    layout_review()
    safe_area_review()
    skill_icon_review()
