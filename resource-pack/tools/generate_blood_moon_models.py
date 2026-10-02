#!/usr/bin/env python3
"""Derive reviewed low-part-count blood-moon silhouettes from one rig.

The three models deliberately share a small animation skeleton, but their body
proportions and palettes are different. This keeps BetterModel's display count
bounded while avoiding the old one-model-for-everything look.
"""

from __future__ import annotations

import base64
import copy
import io
import json
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
MODELS = ROOT / "server" / "config-templates" / "BetterModel" / "models"
SOURCE = MODELS / "blood_moon_resonant_husk.bbmodel"

SOURCE_COLORS = {
    (25, 25, 29, 255): "deep",
    (42, 43, 48, 255): "dark",
    (63, 65, 68, 255): "base",
    (106, 105, 97, 255): "mid",
    (145, 140, 126, 255): "light",
    (78, 56, 38, 255): "trim_dark",
    (105, 72, 43, 255): "trim",
    (142, 39, 36, 255): "accent",
    (223, 55, 48, 255): "accent_light",
    (54, 184, 198, 255): "core",
    (118, 232, 230, 255): "core_light",
}

PALETTES = {
    "blood_moon_grave_knight": {
        "deep": (19, 22, 28, 255), "dark": (37, 42, 50, 255),
        "base": (67, 73, 81, 255), "mid": (119, 122, 119, 255),
        "light": (191, 188, 169, 255), "trim_dark": (48, 42, 41, 255),
        "trim": (91, 73, 59, 255), "accent": (135, 31, 42, 255),
        "accent_light": (232, 76, 72, 255), "core": (62, 153, 198, 255),
        "core_light": (148, 226, 241, 255),
    },
    "blood_moon_rift_stalker": {
        "deep": (13, 10, 23, 255), "dark": (28, 20, 43, 255),
        "base": (50, 35, 69, 255), "mid": (78, 61, 99, 255),
        "light": (126, 105, 145, 255), "trim_dark": (40, 22, 55, 255),
        "trim": (83, 38, 105, 255), "accent": (135, 35, 151, 255),
        "accent_light": (226, 79, 220, 255), "core": (48, 184, 196, 255),
        "core_light": (137, 245, 235, 255),
    },
}


def recolor(source: str, palette: dict[str, tuple[int, int, int, int]]) -> str:
    raw = base64.b64decode(source.split(",", 1)[1])
    image = Image.open(io.BytesIO(raw)).convert("RGBA")
    pixels = [palette[SOURCE_COLORS.get(pixel, "base")] for pixel in image.getdata()]
    image.putdata(pixels)
    output = io.BytesIO()
    image.save(output, format="PNG", optimize=True)
    return "data:image/png;base64," + base64.b64encode(output.getvalue()).decode("ascii")


def transform_vector(vector: list[float], x_scale: float, y_scale: float, z_scale: float) -> None:
    vector[0] = round(vector[0] * x_scale, 3)
    vector[1] = round(vector[1] * y_scale, 3)
    vector[2] = round(vector[2] * z_scale, 3)


def derive(identifier: str, x_scale: float, y_scale: float, z_scale: float) -> None:
    model = copy.deepcopy(json.loads(SOURCE.read_text(encoding="utf-8")))
    model["name"] = identifier
    model["model_identifier"] = identifier
    model["visible_box"] = [2.2, round(2.2 * y_scale, 2), 0]
    texture = model["textures"][0]
    texture["name"] = identifier + ".png"
    texture["source"] = recolor(texture["source"], PALETTES[identifier])
    for element in model["elements"]:
        transform_vector(element["from"], x_scale, y_scale, z_scale)
        transform_vector(element["to"], x_scale, y_scale, z_scale)
        transform_vector(element["origin"], x_scale, y_scale, z_scale)
    for group in model["groups"]:
        transform_vector(group["origin"], x_scale, y_scale, z_scale)
    target = MODELS / f"{identifier}.bbmodel"
    target.write_text(json.dumps(model, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


if __name__ == "__main__":
    derive("blood_moon_grave_knight", 0.78, 1.0, 0.82)
    derive("blood_moon_rift_stalker", 0.68, 1.32, 0.72)
