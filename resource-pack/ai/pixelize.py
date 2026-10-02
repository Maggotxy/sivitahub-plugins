#!/usr/bin/env python3
import argparse
from pathlib import Path

from PIL import Image


PALETTE = (
    (24, 21, 32), (42, 38, 52), (73, 75, 88), (105, 117, 128),
    (151, 168, 174), (215, 229, 232), (246, 247, 240),
    (35, 127, 146), (74, 190, 201), (89, 225, 227),
    (94, 50, 135), (139, 75, 191), (182, 120, 239),
    (123, 38, 52), (187, 57, 65), (230, 84, 84),
    (171, 104, 35), (220, 158, 48), (255, 207, 85),
    (62, 111, 74), (81, 160, 98), (118, 219, 131),
    (92, 57, 38), (139, 86, 55),
)


def nearest(rgb):
    return min(PALETTE, key=lambda color: sum((color[index] - rgb[index]) ** 2 for index in range(3)))


def main():
    parser = argparse.ArgumentParser(description="Convert a keyed concept image into a validated RPG pixel sprite")
    parser.add_argument("--input", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--size", type=int, default=32)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    output = Path(args.out)
    if output.exists() and not args.force:
        raise SystemExit(f"Output exists: {output}; pass --force")
    source = Image.open(args.input).convert("RGBA")
    alpha = source.getchannel("A")
    bounds = alpha.point(lambda value: 255 if value >= 48 else 0).getbbox()
    if bounds is None:
        raise SystemExit("No visible subject after background removal")
    subject = source.crop(bounds)
    maximum = max(1, args.size - 4)
    subject.thumbnail((maximum, maximum), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (args.size, args.size), (0, 0, 0, 0))
    canvas.alpha_composite(subject, ((args.size - subject.width) // 2, (args.size - subject.height) // 2))

    pixels = canvas.load()
    visible = 0
    colors = set()
    for y in range(args.size):
        for x in range(args.size):
            red, green, blue, alpha_value = pixels[x, y]
            if alpha_value < 96:
                pixels[x, y] = (0, 0, 0, 0)
                continue
            mapped = nearest((red, green, blue))
            pixels[x, y] = (*mapped, 255)
            colors.add(mapped)
            visible += 1

    coverage = visible / (args.size * args.size)
    if not 0.04 <= coverage <= 0.82:
        raise SystemExit(f"Subject coverage out of range: {coverage:.1%}")
    if any(canvas.getpixel(point)[3] for point in ((0, 0), (args.size - 1, 0), (0, args.size - 1), (args.size - 1, args.size - 1))):
        raise SystemExit("Sprite corners must be transparent")
    output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output, optimize=True)
    print(f"{output.name}: {args.size}x{args.size}, coverage={coverage:.1%}, colors={len(colors)}")


if __name__ == "__main__":
    main()
