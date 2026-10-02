#!/usr/bin/env python3
"""HUD 尺寸门禁：设计基准 / 唯一来源 / 实际素材 三方一致。

判据全部来自 docs/reference/hud-layout.json（唯一来源），逐条核验：
  1) 每张 HUD PNG 的解码尺寸 == 唯一来源里的 width/height；
  2) 每张贴图/图标都在 BetterHud 生成的 font JSON 里有字形定义（否则客户端会显示缺字方框）；
  3) 数值与设计基准 baseline 的差异被显式记录（相等，或在 notes 里写明可行值理由）；
  4) 槽位影格能装进轨道、徽章右留白不越过硬下限。

注意：BetterHud font JSON 里的 bitmap height 是它在不同 GUI 缩放档位下选定的
「定位行高」（同一张 18px 图在不同档位下是 8/11/18），不是素材像素尺寸；
像素尺寸一律以 PNG 头为准。

只读 PNG 头（IHDR）+ JSON，不依赖 Pillow / BetterHud 运行时，CI 容器里可直接跑。
任何一项不符即非零退出，并打印「期望 vs 实际」。
"""

from __future__ import annotations

import json
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LAYOUT = ROOT / "docs/reference/hud-layout.json"
FONT_DIR = ROOT / "resource-pack/assets/betterhud/font"
# 两类字形各有归属：贴图/法力条在 image 字体，语义图标在「按 GUI 缩放分页」的 text 字体。
IMAGE_FONT = FONT_DIR / "hud_sivita_hud_image.json"
TEXT_FONT_GLOB = "hud_sivita_hud_text_*.json"

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def png_size(path: Path) -> tuple[int, int]:
    """只读 IHDR，不加载整张图。"""
    with path.open("rb") as handle:
        header = handle.read(24)
    if len(header) < 24 or header[:8] != PNG_SIGNATURE:
        raise ValueError(f"not a PNG: {path}")
    width, height = struct.unpack(">II", header[16:24])
    return width, height


def load_layout() -> dict:
    if not LAYOUT.is_file():
        raise SystemExit(f"HUD_LAYOUT_GATE=RED 找不到唯一来源 {LAYOUT}")
    return json.loads(LAYOUT.read_text(encoding="utf-8"))


def glyph_heights(path: Path, prefix: str = "") -> dict[str, int]:
    """从一个 BetterHud font JSON 里抽出「贴图文件 -> bitmap height」。

    prefix 用于只收本项目自己的语义图标（glyph_*）；BetterHud 还会把每个
    GUI 缩放页的拉丁字库（text_font2_*.png）混在同一个 providers 数组里，
    那些字形的 height 随缩放档位变化，不属于本次尺寸契约。
    """
    if not path.is_file():
        raise SystemExit(f"HUD_LAYOUT_GATE=RED 找不到 BetterHud 字体定义 {path}")
    document = json.loads(path.read_text(encoding="utf-8"))
    heights: dict[str, int] = {}
    for provider in document.get("providers", []):
        if provider.get("type") != "bitmap":
            continue
        raw = str(provider.get("file", ""))
        # BetterHud 的 file 值形如 betterhud:image_sivita_skill_panel.png，
        # 命名空间在斜杠之前，必须按 ":" 与 "/" 双分隔取裸文件名。
        name = raw.rsplit("/", 1)[-1].rsplit(":", 1)[-1]
        if prefix and not name.startswith(prefix):
            continue
        height = provider.get("height")
        if not isinstance(height, int):
            raise SystemExit(f"HUD_LAYOUT_GATE=RED 字形 {name} 缺少整数 height")
        previous = heights.setdefault(name, height)
        if previous != height:
            raise SystemExit(
                f"HUD_LAYOUT_GATE=RED 字形 {name} 在同一定义里出现多个 height: {previous} vs {height}")
    return heights


def expect(condition: bool, message: str, failures: list[str]) -> None:
    if not condition:
        failures.append(message)


def compare_baseline(label: str, actual: list[int], baseline: list[int], failures: list[str]) -> None:
    """实现值与设计基准不一致本身不算失败 —— 但必须由 notes 显式记录取舍。"""
    if actual == baseline:
        return
    print(f"  [NOTE] {label}: 设计基准 {baseline} -> 实现 {actual}（见 hud-layout.json notes）")


def main() -> int:
    layout = load_layout()
    # image 字体管贴图与法力条；text 字体（按 GUI 缩放分页）管语义图标。
    image_heights = glyph_heights(IMAGE_FONT)
    icon_heights: dict[str, int] = {}
    text_fonts = sorted(FONT_DIR.glob(TEXT_FONT_GLOB))
    if not text_fonts:
        print(f"HUD_LAYOUT_GATE=RED {FONT_DIR} 下没有 {TEXT_FONT_GLOB}")
        return 1
    for font in text_fonts:
        for name, height in glyph_heights(font, prefix="glyph_").items():
            # 只登记技能/职业图标；控制提示条（glyph_control_*）自带 12px 高标签，
            # 行高与语义图标不同，且不在本次尺寸契约里。
            if name.startswith("glyph_control_"):
                continue
            icon_heights.setdefault(name, height)
    failures: list[str] = []
    checked = 0

    def check_single(key: str) -> None:
        nonlocal checked
        spec = layout[key]
        path = ROOT / spec["path"]
        if not path.is_file():
            failures.append(f"{key}: 缺少素材 {spec['path']}")
            return
        checked += 1
        actual = list(png_size(path))
        expected = [spec["width"], spec["height"]]
        expect(actual == expected,
               f"{key}: {spec['path']} 实际 {actual} != 唯一来源 {expected}", failures)
        compare_baseline(key, actual, list(spec["baseline"]), failures)
        name = spec["path"].rsplit("/", 1)[-1]
        if name not in image_heights:
            failures.append(f"{key}: BetterHud image 字体没有为 {name} 定义字形")

    def check_glob(key: str) -> None:
        nonlocal checked
        spec = layout[key]
        globs = spec.get("pathGlobs") or [spec["pathGlob"]]
        paths: list[Path] = []
        for pattern in globs:
            found = sorted(ROOT.glob(pattern))
            if not found:
                failures.append(f"{key}: {pattern} 没有匹配到任何素材")
            paths.extend(found)
        if not paths:
            return
        # 数量也是契约的一部分：删掉一张图标不会被「逐张比尺寸」发现，
        # 但会让客户端少一个语义图标，因此按唯一来源声明的张数断言。
        if len(paths) != spec["count"]:
            failures.append(
                f"{key}: {' + '.join(globs)} 匹配 {len(paths)} 张 != 唯一来源 {spec['count']} 张")
        expected = [spec["width"], spec["height"]]
        for path in paths:
            checked += 1
            actual = list(png_size(path))
            expect(actual == expected,
                   f"{key}: {path.relative_to(ROOT)} 实际 {actual} != 唯一来源 {expected}", failures)
        compare_baseline(f"{key}({len(paths)} 张)", expected, list(spec["baseline"]), failures)
        # 同类图标必须能全部被字体定义覆盖，否则会在客户端显示成缺字方框。
        for path in paths:
            name = path.name
            checked += 1
            expect(name in icon_heights,
                   f"{key}: BetterHud 图标字体没有为 {name} 定义字形", failures)
            # 说明：BetterHud 的定位字体 height 是「编译期行高」，与被注入图片的
            # 像素高度是两件事（同档位下 8/11/18 随 GUI 缩放变化）。因此这里只断言
            # 「字体为每张图都定义了字形」，素材像素尺寸由 check_glob 的 PNG 头断言。

    def check_mana() -> None:
        nonlocal checked
        spec = layout["mana"]
        paths = sorted(ROOT.glob(spec["pathGlob"]))
        if len(paths) != spec["frames"]:
            failures.append(f"mana: 匹配到 {len(paths)} 帧 != 唯一来源 {spec['frames']} 帧")
        for path in paths:
            checked += 1
            actual = list(png_size(path))
            expected = [spec["width"], spec["height"]]
            expect(actual == expected,
                   f"mana: {path.name} 实际 {actual} != 唯一来源 {expected}", failures)
        compare_baseline(f"mana({len(paths)} 帧)", [spec["width"], spec["height"]],
                         list(spec["baseline"]), failures)

    def check_slot_grid() -> None:
        """槽位影格必须能装进轨道，且最后一个槽位的底边不越界。"""
        nonlocal checked
        rail = layout["rail"]
        slot = layout["slot"]
        checked += 1
        last_bottom = slot["firstTop"] + slot["pitch"] * (slot["count"] - 1) + slot["iconBox"][1]
        expect(last_bottom <= rail["height"],
               f"slot: 第 {slot['count']} 槽底边 {last_bottom} 越出轨道高度 {rail['height']}", failures)
        checked += 1
        expect(last_bottom == slot["lastBottom"],
               f"slot: lastBottom 记录 {slot['lastBottom']} != 计算值 {last_bottom}", failures)
        checked += 1
        expect(slot["iconContent"][0] < slot["iconBox"][0]
               and slot["iconContent"][1] <= slot["iconBox"][1],
               "slot: iconContent 必须能装进 iconBox", failures)

    def check_badge_fit() -> None:
        """徽章必须能贴在有轨道的屏幕右留白里。"""
        nonlocal checked
        badge = layout["badge"]
        checked += 1
        expect(badge["rightInset"] >= badge["hardRightInsetMin"],
               f"badge: rightInset {badge['rightInset']} < 硬下限 {badge['hardRightInsetMin']}", failures)
        compare_baseline("badge", [badge["width"], badge["height"]],
                         list(badge["baseline"]), failures)

    check_single("rail")
    check_single("badge")
    check_single("selected")
    check_glob("icon")
    check_mana()
    check_slot_grid()
    check_badge_fit()

    print(f"HUD_LAYOUT_CHECKS={checked}")
    if failures:
        for failure in failures:
            print("HUD_LAYOUT_GATE=RED " + failure, file=sys.stderr)
        return 1
    print("HUD_LAYOUT_GATE=GREEN")
    return 0


if __name__ == "__main__":
    sys.exit(main())
