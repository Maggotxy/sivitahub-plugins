#!/usr/bin/env python3
"""Build a review sheet from the textures that are actually shipped."""

import argparse
import math
import textwrap
from pathlib import Path

from PIL import Image, ImageDraw


BACKGROUND = (12, 14, 20, 255)
CELL = (24, 27, 36, 255)
TEXT = (215, 229, 232, 255)


def main() -> None:
    parser = argparse.ArgumentParser(description="Create a contact sheet for production RPG item textures")
    parser.add_argument("--textures", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--columns", type=int, default=6)
    args = parser.parse_args()

    files = sorted(args.textures.glob("*.png"))
    if not files:
        raise SystemExit(f"No PNG textures found in {args.textures}")
    if args.columns < 1:
        raise SystemExit("--columns must be positive")

    cell_width = 160
    cell_height = 136
    padding = 8
    sprite_size = 96
    rows = math.ceil(len(files) / args.columns)
    sheet = Image.new("RGBA", (args.columns * cell_width, rows * cell_height), BACKGROUND)
    draw = ImageDraw.Draw(sheet)

    for index, path in enumerate(files):
        texture = Image.open(path).convert("RGBA")
        if texture.size != (32, 32):
            raise SystemExit(f"Production texture must be 32x32: {path}")
        column = index % args.columns
        row = index // args.columns
        left = column * cell_width
        top = row * cell_height
        draw.rounded_rectangle(
            (left + 3, top + 3, left + cell_width - 3, top + cell_height - 3),
            radius=5,
            fill=CELL,
        )
        scaled = texture.resize((sprite_size, sprite_size), Image.Resampling.NEAREST)
        sheet.alpha_composite(scaled, (left + (cell_width - sprite_size) // 2, top + padding))
        label = "\n".join(textwrap.wrap(path.stem, width=24, break_long_words=True)[:2])
        draw.multiline_text((left + padding, top + 108), label, fill=TEXT, spacing=1)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.out.with_suffix(".tmp.png")
    sheet.convert("RGB").save(temporary, optimize=True)
    temporary.replace(args.out)
    print(f"Contact sheet: {len(files)} production textures -> {args.out}")


if __name__ == "__main__":
    main()
