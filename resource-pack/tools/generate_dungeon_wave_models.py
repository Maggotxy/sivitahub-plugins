#!/usr/bin/env python3
"""Build six low-part-count dungeon wave shells from reviewed image concepts."""

from __future__ import annotations

import base64
import io
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps

from generate_dungeon_boss_models import Cube, ModelSpec, color, model, review_sheet, texture


ROOT = Path(__file__).resolve().parents[2]
MODEL_DIR = ROOT / "server" / "config-templates" / "BetterModel" / "models"
CONCEPT_DIR = ROOT / "resource-pack" / "ai" / "output" / "source" / "dungeon-waves"
REVIEW_DIR = ROOT / "resource-pack" / "ai" / "output" / "review" / "mobs"


SPECS = (
    ModelSpec(
        "dungeon_ruins_runebound", "断文俑",
        ("#171918", "#343733", "#6d6655", "#aa8645", "#f0bd55"),
        (
            Cube("stone_torso", "body", (-4.2, 10, -2.8), (4.2, 21, 2.8), 0),
            Cube("tablet_chest", "body", (-3.1, 11.5, -3.5), (3.1, 18.5, -2.6), 12),
            Cube("slit_head", "h_head", (-3.3, 20, -2.8), (3.3, 26.5, 2.8), 5),
            Cube("amber_visor", "h_head", (-2.6, 22, -3.35), (2.6, 23.5, -2.7), 15),
            Cube("inscribed_fist", "right_arm", (4.0, 7, -3.2), (8.2, 20.5, 3.2), 9, (4.5, 19, 0)),
            Cube("fist_rune", "right_arm", (7.7, 10, -2.0), (8.7, 17.5, 2.0), 14),
            Cube("left_arm", "left_arm", (-7.0, 8, -2.5), (-4.0, 20, 2.5), 8, (-4.4, 19, 0)),
            Cube("right_foot", "right_leg", (0.4, 0, -3.0), (4.0, 10.5, 3.0), 4, (2.0, 10, 0)),
            Cube("left_foot", "left_leg", (-4.0, 0, -3.0), (-0.4, 10.5, 3.0), 4, (-2.0, 10, 0)),
            Cube("broken_crest", "h_head", (-2.0, 26, -1.4), (2.0, 29.5, 1.4), 11),
        ),
    ),
    ModelSpec(
        "dungeon_ruins_echo_sentinel", "回声碑侍",
        ("#111218", "#302d38", "#62566f", "#a184bd", "#e8cfff"),
        (
            Cube("pillar_body", "body", (-2.8, 9, -2.4), (2.8, 23, 2.4), 0),
            Cube("throat_chamber", "body", (-1.5, 13, -3.0), (1.5, 21, -2.2), 14),
            Cube("split_mask", "h_head", (-3.0, 22, -2.5), (3.0, 29, 2.5), 5),
            Cube("mask_slit", "h_head", (-2.2, 24, -3.0), (2.2, 25.2, -2.4), 15),
            Cube("left_echo_slab", "left_arm", (-8.5, 17, -2.0), (-3.5, 27, 2.0), 12, (-3.5, 20, 0), (0, 0, -12)),
            Cube("right_echo_slab", "right_arm", (3.5, 15, -2.0), (8.5, 25, 2.0), 12, (3.5, 20, 0), (0, 0, 12)),
            Cube("tuning_staff", "right_arm", (7.5, 4, -0.7), (8.9, 20, 0.7), 9, (8.2, 18, 0)),
            Cube("staff_fork", "right_arm", (6.5, 18.5, -0.7), (9.9, 21.5, 0.7), 14),
            Cube("right_base", "right_leg", (0.3, 0, -2.5), (3.0, 10, 2.5), 4, (1.5, 10, 0)),
            Cube("left_base", "left_leg", (-3.0, 0, -2.5), (-0.3, 10, 2.5), 4, (-1.5, 10, 0)),
        ),
    ),
    ModelSpec(
        "dungeon_sulfur_fume_crawler", "硫烟伏兽",
        ("#151311", "#34241c", "#704024", "#d8831e", "#ffe06b"),
        (
            Cube("wedge_body", "body", (-7, 4, -6), (7, 12, 6), 0),
            Cube("furnace_grate", "h_head", (-4.5, 5.5, -7.0), (4.5, 10.5, -5.8), 15),
            Cube("left_vent", "left_arm", (-5.5, 11, -1.8), (-2.0, 20, 1.8), 13, (-3.5, 11, 0), (0, 0, -12)),
            Cube("right_vent", "right_arm", (2.8, 11, -1.6), (5.5, 17, 1.6), 12, (3.5, 11, 0), (0, 0, 14)),
            Cube("front_left_leg", "left_leg", (-8.5, 0, -5.0), (-5.0, 6.5, -1.5), 4),
            Cube("rear_left_leg", "left_leg", (-8.5, 0, 1.5), (-5.0, 6.5, 5.0), 4),
            Cube("front_right_leg", "right_leg", (5.0, 0, -5.0), (8.5, 6.5, -1.5), 4),
            Cube("rear_right_leg", "right_leg", (5.0, 0, 1.5), (8.5, 6.5, 5.0), 4),
            Cube("middle_left_leg", "left_leg", (-9.0, 0.4, -1.7), (-5.5, 5.8, 1.7), 8),
            Cube("middle_right_leg", "right_leg", (5.5, 0.4, -1.7), (9.0, 5.8, 1.7), 8),
        ),
    ),
    ModelSpec(
        "dungeon_sulfur_slag_mote", "熔渣浮核",
        ("#14110f", "#3b261a", "#8c421b", "#ed7c1b", "#ffe28a"),
        (
            Cube("slag_core", "body", (-4.5, 10, -4.5), (4.5, 19, 4.5), 0, (0, 14.5, 0), (0, 35, 0)),
            Cube("molten_window", "h_head", (-2.8, 12, -5.2), (2.8, 17, -4.2), 15),
            Cube("left_vane", "left_arm", (-10, 12, -1.5), (-5, 17, 1.5), 12, (-5, 14.5, 0), (0, 0, -12)),
            Cube("right_vane", "right_arm", (5, 12, -1.5), (10, 17, 1.5), 12, (5, 14.5, 0), (0, 0, 12)),
            Cube("upper_vane", "h_head", (-1.5, 20, -1.5), (1.5, 26, 1.5), 13, (0, 20, 0), (0, 0, 10)),
            Cube("lower_vane", "body", (-1.5, 4, -1.5), (1.5, 10, 1.5), 13, (0, 10, 0), (0, 0, -10)),
            Cube("exhaust_shard", "right_leg", (-1.2, -2, -1.2), (1.2, 4.5, 1.2), 9, (0, 4.5, 0), (0, 0, 18)),
            Cube("rear_flue", "left_leg", (-1.4, 13, 4.0), (1.4, 22, 5.4), 8),
        ),
        floating=True,
    ),
    ModelSpec(
        "dungeon_storm_conductor", "引雷弩侍",
        ("#09121d", "#1d3047", "#315b70", "#4fc9dc", "#d5fbff"),
        (
            Cube("armored_torso", "body", (-3.8, 10, -2.7), (3.8, 22, 2.7), 0),
            Cube("charge_chamber", "body", (-1.3, 13, -3.3), (1.3, 20, -2.5), 15),
            Cube("vault_helm", "h_head", (-3.0, 21, -2.7), (3.0, 28, 2.7), 5),
            Cube("cyan_visor", "h_head", (-2.2, 23, -3.2), (2.2, 24.2, -2.6), 14),
            Cube("conductor_bow", "left_arm", (-9.5, 9, -1.0), (-7.8, 25, 1.0), 13, (-8.5, 17, 0)),
            Cube("bow_rail", "left_arm", (-11.0, 16, -0.8), (-5.0, 18, 0.8), 14),
            Cube("right_arm", "right_arm", (3.5, 8, -2.2), (6.5, 21, 2.2), 8, (4.0, 20, 0)),
            Cube("left_fork", "h_head", (-4.3, 27, -1.0), (-2.4, 32, 1.0), 13, (-2.4, 27, 0), (0, 0, -12)),
            Cube("right_fork", "h_head", (2.4, 27, -1.0), (4.3, 32, 1.0), 13, (2.4, 27, 0), (0, 0, 12)),
            Cube("right_leg", "right_leg", (0.4, 0, -2.2), (3.2, 10.5, 2.2), 4, (1.6, 10, 0)),
            Cube("left_leg", "left_leg", (-3.2, 0, -2.2), (-0.4, 10.5, 2.2), 4, (-1.6, 10, 0)),
        ),
    ),
    ModelSpec(
        "dungeon_storm_gust_wisp", "逐风棱灵",
        ("#07121c", "#1f3548", "#397083", "#54d7e8", "#dbfbff"),
        (
            Cube("prism_core", "body", (-4, 10, -4), (4, 18, 4), 0, (0, 14, 0), (0, 45, 0)),
            Cube("cyan_slit", "h_head", (-1.8, 12, -4.8), (1.8, 16, -3.8), 15),
            Cube("upper_fin", "h_head", (-1.4, 18, -1.4), (1.4, 26, 1.4), 13, (0, 18, 0), (0, 0, 16)),
            Cube("left_fin", "left_arm", (-10, 11, -1.3), (-4.5, 15, 1.3), 12, (-4.5, 13, 0), (0, 0, -18)),
            Cube("right_fin", "right_arm", (4.5, 13, -1.3), (10, 17, 1.3), 12, (4.5, 15, 0), (0, 0, 18)),
            Cube("rear_vane", "right_leg", (-1.2, 12, 4.0), (1.2, 23, 5.5), 9, (0, 15, 4.5), (15, 0, 0)),
            Cube("lower_tail", "left_leg", (-1.3, 1, -1.3), (1.3, 10, 1.3), 8, (0, 10, 0), (0, 0, -14)),
        ),
        floating=True,
    ),
)


def concept_sheet() -> Image.Image:
    sheet = Image.new("RGB", (1536, 1024), (9, 12, 17))
    draw = ImageDraw.Draw(sheet)
    for index, spec in enumerate(SPECS):
        source = Image.open(CONCEPT_DIR / f"{spec.model_id}_concept.png").convert("RGB")
        left = (index % 3) * 512
        top = (index // 3) * 512
        sheet.paste(ImageOps.fit(source, (496, 456), method=Image.Resampling.LANCZOS), (left + 8, top + 8))
        draw.rectangle((left + 8, top + 464, left + 504, top + 504), fill=(14, 19, 27))
        draw.text((left + 22, top + 476), f"{index + 1}. {spec.title}", fill=color(spec.palette[4]))
    return sheet


def main() -> None:
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    REVIEW_DIR.mkdir(parents=True, exist_ok=True)
    textures: dict[str, Image.Image] = {}
    for spec in SPECS:
        image = texture(spec)
        textures[spec.model_id] = image
        buffer = io.BytesIO()
        image.save(buffer, format="PNG", optimize=False)
        source = "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")
        output = MODEL_DIR / f"{spec.model_id}.bbmodel"
        output.write_text(json.dumps(model(spec, source), ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    review_sheet(SPECS, textures).save(REVIEW_DIR / "dungeon_wave_model_review.png", format="PNG", optimize=False)
    concept_sheet().save(REVIEW_DIR / "dungeon_wave_concepts.png", format="PNG", optimize=False)


if __name__ == "__main__":
    main()
