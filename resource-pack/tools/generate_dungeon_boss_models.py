#!/usr/bin/env python3
"""Build the six low-part-count BetterModel dungeon bosses and a review sheet."""

from __future__ import annotations

import base64
import hashlib
import io
import json
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps


ROOT = Path(__file__).resolve().parents[2]
MODEL_DIR = ROOT / "server" / "config-templates" / "BetterModel" / "models"
REVIEW = ROOT / "resource-pack" / "ai" / "output" / "review" / "mobs" / "dungeon_boss_model_review.png"
CONCEPT_REVIEW = ROOT / "resource-pack" / "ai" / "output" / "review" / "mobs" / "dungeon_boss_concepts.png"
BONES = ("root", "body", "h_head", "right_arm", "left_arm", "right_leg", "left_leg")


@dataclass(frozen=True)
class Cube:
    name: str
    bone: str
    start: tuple[float, float, float]
    end: tuple[float, float, float]
    tile: int
    origin: tuple[float, float, float] | None = None
    rotation: tuple[float, float, float] | None = None


@dataclass(frozen=True)
class ModelSpec:
    model_id: str
    title: str
    palette: tuple[str, str, str, str, str]
    cubes: tuple[Cube, ...]
    floating: bool = False


def uid(model_id: str, value: str) -> str:
    digest = hashlib.md5(f"sivita:{model_id}:{value}".encode(), usedforsecurity=False).hexdigest()
    return f"{digest[:8]}-{digest[8:12]}-{digest[12:16]}-{digest[16:20]}-{digest[20:]}"


def humanoid(
    model_id: str,
    title: str,
    palette: tuple[str, str, str, str, str],
    *,
    tall: float = 1.0,
    heavy: float = 1.0,
    echo: bool = False,
    thunder: bool = False,
) -> ModelSpec:
    top = 12.0 + 11.0 * tall
    head_top = top + 7.0 * tall
    width = 4.0 * heavy
    cubes = [
        Cube("torso", "body", (-width, 11, -3.0 * heavy), (width, top, 3.0 * heavy), 0),
        Cube("chest_core", "body", (-1.35, 15, -3.55 * heavy), (1.35, 20.5, -2.75 * heavy), 12),
        Cube("right_pauldron", "body", (width - 0.5, top - 4.5, -3.5), (width + 2.5, top + 1.0, 3.5), 3),
        Cube("left_pauldron", "body", (-width - 2.5, top - 4.5, -3.5), (-width + 0.5, top + 1.0, 3.5), 3),
        Cube("helm", "h_head", (-3.0 * heavy, top, -3.0), (3.0 * heavy, head_top, 3.0), 5),
        Cube("visor", "h_head", (-2.5, top + 2.0, -3.55), (2.5, top + 4.0, -2.85), 13),
        Cube("right_arm", "right_arm", (width, 7.0, -2.3), (width + 3.3, top, 2.3), 8,
             (width + 1.5, top - 1.0, 0)),
        Cube("left_arm", "left_arm", (-width - 3.3, 7.0, -2.3), (-width, top, 2.3), 8,
             (-width - 1.5, top - 1.0, 0)),
        Cube("right_leg", "right_leg", (0.4, 0.0, -2.6), (3.5, 12.0, 2.6), 9, (1.9, 12.0, 0)),
        Cube("left_leg", "left_leg", (-3.5, 0.0, -2.6), (-0.4, 12.0, 2.6), 9, (-1.9, 12.0, 0)),
    ]
    if echo:
        cubes.extend([
            Cube("echo_halo_a", "root", (-7.0, top + 1.5, 2.8), (7.0, top + 2.2, 3.5), 14,
                 (0, top + 2.0, 3.1), (0, 0, 24)),
            Cube("echo_halo_b", "root", (-7.0, top + 1.5, 2.8), (7.0, top + 2.2, 3.5), 14,
                 (0, top + 2.0, 3.1), (0, 0, -24)),
            Cube("echo_crown_left", "h_head", (-4.7, head_top - 1, -1.0), (-2.2, head_top + 4.5, 1.0), 13,
                 (-2.2, head_top - 1, 0), (0, 0, -28)),
            Cube("echo_crown_right", "h_head", (2.2, head_top - 1, -1.0), (4.7, head_top + 4.5, 1.0), 13,
                 (2.2, head_top - 1, 0), (0, 0, 28)),
        ])
    elif thunder:
        cubes.extend([
            Cube("thunder_blade", "right_arm", (width + 1.0, -5.0, -0.7), (width + 2.4, 13.0, 0.7), 14,
                 (width + 1.7, 12.0, 0), (0, 0, -10)),
            Cube("storm_banner", "body", (-0.7, 14.0, 3.0), (0.7, head_top + 4.0, 4.0), 11),
            Cube("horn_left", "h_head", (-4.5, head_top - 1.0, -1.0), (-2.0, head_top + 3.5, 1.0), 13,
                 (-2.0, head_top - 1.0, 0), (0, 0, -32)),
            Cube("horn_right", "h_head", (2.0, head_top - 1.0, -1.0), (4.5, head_top + 3.5, 1.0), 13,
                 (2.0, head_top - 1.0, 0), (0, 0, 32)),
        ])
    else:
        cubes.extend([
            Cube("broken_crown", "h_head", (-4.2, head_top - 1.0, -1.2), (-2.0, head_top + 4.0, 1.2), 11,
                 (-2.0, head_top - 1.0, 0), (0, 0, -24)),
            Cube("stone_glaive", "right_arm", (width + 0.8, -4.0, -0.8), (width + 2.7, 13.0, 0.8), 10,
                 (width + 1.6, 12.0, 0), (0, 0, -8)),
        ])
    return ModelSpec(model_id, title, palette, tuple(cubes))


def crystal_cube(model_id: str, title: str, palette: tuple[str, str, str, str, str]) -> ModelSpec:
    cubes = (
        Cube("crystal_mass", "body", (-7, 5, -7), (7, 19, 7), 0),
        Cube("front_maw", "h_head", (-4.7, 9, -8.2), (4.7, 16, -6.4), 13),
        Cube("heart", "h_head", (-1.5, 11, -8.8), (1.5, 14, -7.9), 14),
        Cube("crown_center", "body", (-1.5, 18, -1.5), (1.5, 27, 1.5), 12, (0, 18, 0), (0, 0, 12)),
        Cube("crown_left", "left_arm", (-6, 17, -1.4), (-3, 25, 1.4), 11, (-4, 17, 0), (0, 0, -28)),
        Cube("crown_right", "right_arm", (3, 17, -1.4), (6, 25, 1.4), 11, (4, 17, 0), (0, 0, 28)),
        Cube("left_cluster", "left_arm", (-11, 7, -3), (-7, 15, 3), 3, (-7, 11, 0), (0, 0, -18)),
        Cube("right_cluster", "right_arm", (7, 7, -3), (11, 15, 3), 3, (7, 11, 0), (0, 0, 18)),
        Cube("left_foot", "left_leg", (-5.5, 0, -4.5), (-0.5, 6.5, 4.5), 9),
        Cube("right_foot", "right_leg", (0.5, 0, -4.5), (5.5, 6.5, 4.5), 9),
    )
    return ModelSpec(model_id, title, palette, cubes)


def floating_core(
    model_id: str,
    title: str,
    palette: tuple[str, str, str, str, str],
    *,
    keykeeper: bool,
) -> ModelSpec:
    cubes = [
        Cube("core", "body", (-4.5, 11, -4.5), (4.5, 20, 4.5), 12, (0, 15.5, 0), (0, 45, 0)),
        Cube("inner_light", "h_head", (-2.2, 13.3, -5.2), (2.2, 17.7, -4.2), 14),
        Cube("upper_shard", "h_head", (-1.4, 20, -1.4), (1.4, 29, 1.4), 13, (0, 20, 0), (0, 0, 18)),
        Cube("lower_shard", "body", (-1.4, 2, -1.4), (1.4, 11, 1.4), 10, (0, 11, 0), (0, 0, -18)),
        Cube("orbit_left", "left_arm", (-11, 13.8, -1.1), (-5, 16.2, 1.1), 3, (-5, 15, 0), (0, 0, -12)),
        Cube("orbit_right", "right_arm", (5, 13.8, -1.1), (11, 16.2, 1.1), 3, (5, 15, 0), (0, 0, 12)),
        Cube("orbit_front", "left_leg", (-1.1, 8, -11), (1.1, 10.4, -5), 8, (0, 9, -5), (12, 0, 0)),
        Cube("orbit_back", "right_leg", (-1.1, 20, 5), (1.1, 22.4, 11), 8, (0, 21, 5), (-12, 0, 0)),
        Cube("ring_a", "root", (-8, 14.5, -0.5), (8, 15.5, 0.5), 11, (0, 15, 0), (0, 0, 28)),
        Cube("ring_b", "root", (-8, 14.5, -0.5), (8, 15.5, 0.5), 11, (0, 15, 0), (0, 0, -28)),
    ]
    if keykeeper:
        cubes.extend([
            Cube("key_spine", "body", (-0.8, 7, 3.8), (0.8, 24, 5.2), 10),
            Cube("key_tooth_left", "body", (-4.0, 7, 3.8), (-0.4, 9.0, 5.2), 10),
            Cube("key_tooth_right", "body", (0.4, 7, 3.8), (4.0, 9.0, 5.2), 10),
        ])
    else:
        cubes.extend([
            Cube("cinnabar_petal_left", "left_arm", (-8, 18, -2), (-3, 24, 2), 13, (-3, 18, 0), (0, 0, -28)),
            Cube("cinnabar_petal_right", "right_arm", (3, 18, -2), (8, 24, 2), 13, (3, 18, 0), (0, 0, 28)),
        ])
    return ModelSpec(model_id, title, palette, tuple(cubes), floating=True)


SPECS = (
    humanoid("boss_ruins_inscription_warden", "断铭守卫", ("#171a19", "#474b43", "#8b8064", "#d1a951", "#f6e6a6"), heavy=1.12),
    humanoid("boss_ruins_echo_adjudicator", "归声裁决者", ("#14101d", "#3b2d52", "#7158a6", "#b790ef", "#f0dbff"), tall=1.12, echo=True),
    crystal_cube("boss_sulfur_crystal_devourer", "硫晶吞噬者", ("#21140e", "#62311b", "#b95b22", "#f2ad38", "#fff0a0")),
    floating_core("boss_cinnabar_reborn_core", "丹心蜕生者", ("#1b0b0d", "#5c1823", "#aa3142", "#ef6955", "#ffd199"), keykeeper=False),
    floating_core("boss_storm_vault_keykeeper", "风暴执钥者", ("#071923", "#153f56", "#237e9c", "#55d8e8", "#c8fbff"), keykeeper=True),
    humanoid("boss_silent_thunder_oathguard", "寂雷誓卫", ("#0c1018", "#202d44", "#3d6088", "#67cfe7", "#d7fbff"), tall=1.18, heavy=1.06, thunder=True),
)


def color(hex_value: str) -> tuple[int, int, int, int]:
    value = hex_value.removeprefix("#")
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), 255


def texture(spec: ModelSpec) -> Image.Image:
    image = Image.new("RGBA", (64, 64), color(spec.palette[0]))
    draw = ImageDraw.Draw(image)
    palette = [color(value) for value in spec.palette]
    for tile in range(16):
        x0 = (tile % 4) * 16
        y0 = (tile // 4) * 16
        base = palette[min(3, tile // 4)]
        dark = palette[max(0, min(3, tile // 4) - 1)]
        draw.rectangle((x0, y0, x0 + 15, y0 + 15), fill=base)
        for pixel_y in range(16):
            for pixel_x in range(16):
                seed = (pixel_x * 17 + pixel_y * 31 + tile * 13 + len(spec.model_id)) % 19
                if seed in (0, 1, 2):
                    image.putpixel((x0 + pixel_x, y0 + pixel_y), dark)
        draw.line((x0, y0, x0 + 15, y0), fill=palette[min(4, tile // 4 + 1)])
        draw.line((x0, y0, x0, y0 + 15), fill=palette[min(4, tile // 4 + 1)])
    accent = palette[4]
    for offset in range(0, 16, 4):
        draw.point((48 + offset, 48 + offset), fill=accent)
        draw.point((63 - offset, 48 + offset), fill=accent)
    draw.rectangle((53, 53, 58, 58), outline=accent)
    draw.line((48, 56, 63, 56), fill=accent)
    return image


def faces(tile: int) -> dict[str, dict[str, object]]:
    x = (tile % 4) * 16
    y = (tile // 4) * 16
    face = {"uv": [x, y, x + 16, y + 16], "texture": 0}
    return {name: dict(face) for name in ("north", "east", "south", "west", "up", "down")}


def keyframe(channel: str, when: float, xyz: tuple[float, float, float], token: str) -> dict[str, object]:
    return {
        "channel": channel,
        "data_points": [{"x": str(xyz[0]), "y": str(xyz[1]), "z": str(xyz[2])}],
        "uuid": uid(token, f"{channel}:{when}:{xyz}"),
        "time": when,
        "color": -1,
        "interpolation": "catmullrom" if when else "linear",
    }


def animator(bone_id: str, bone: str, frames: list[tuple[str, float, tuple[float, float, float]]], token: str) -> tuple[str, dict[str, object]]:
    return bone_id, {
        "name": bone,
        "type": "bone",
        "rotation_global": False,
        "quaternion_interpolation": False,
        "keyframes": [keyframe(channel, when, xyz, f"{token}:{index}") for index, (channel, when, xyz) in enumerate(frames)],
    }


def animation(spec: ModelSpec, bone_ids: dict[str, str], name: str) -> dict[str, object]:
    token = f"{spec.model_id}:{name}"
    configs: dict[str, tuple[float, str, list[tuple[str, float, tuple[float, float, float]]]]] = {
        "idle": (2.0, "loop", [
            ("body", 0, (2, 0, 0)), ("body", 1, (-2, 0, 0)), ("body", 2, (2, 0, 0)),
        ]),
        "walk": (1.0, "loop", [
            ("right_arm", 0, (24, 0, 0)), ("right_arm", .5, (-24, 0, 0)), ("right_arm", 1, (24, 0, 0)),
            ("left_arm", 0, (-24, 0, 0)), ("left_arm", .5, (24, 0, 0)), ("left_arm", 1, (-24, 0, 0)),
            ("right_leg", 0, (-20, 0, 0)), ("right_leg", .5, (20, 0, 0)), ("right_leg", 1, (-20, 0, 0)),
            ("left_leg", 0, (20, 0, 0)), ("left_leg", .5, (-20, 0, 0)), ("left_leg", 1, (20, 0, 0)),
        ]),
        "spawn": (.8, "once", [("root_position", 0, (0, -12, 0)), ("root_position", .8, (0, 0, 0)), ("body", 0, (28, 0, 0)), ("body", .8, (0, 0, 0))]),
        "attack": (.65, "once", [("right_arm", 0, (-12, 0, 0)), ("right_arm", .3, (-105, 0, -18)), ("right_arm", .65, (-12, 0, 0)), ("body", 0, (0, 0, 0)), ("body", .3, (10, 22, 0)), ("body", .65, (0, 0, 0))]),
        "cast": (1.0, "once", [("left_arm", 0, (0, 0, 0)), ("left_arm", .35, (-85, -18, 15)), ("left_arm", .75, (-85, 18, -15)), ("left_arm", 1, (0, 0, 0)), ("h_head", 0, (0, 0, 0)), ("h_head", .5, (-12, 0, 0)), ("h_head", 1, (0, 0, 0))]),
        "phase": (1.2, "once", [("root_position", 0, (0, 0, 0)), ("root_position", .4, (0, 4, 0)), ("root_position", .8, (0, 1, 0)), ("root_position", 1.2, (0, 0, 0)), ("body", 0, (0, 0, 0)), ("body", .6, (0, 180, 0)), ("body", 1.2, (0, 360, 0))]),
        "death": (1.3, "once", [("root", 0, (0, 0, 0)), ("root", .7, (82, 0, 12)), ("root", 1.3, (90, 0, 12)), ("root_position", 0, (0, 0, 0)), ("root_position", 1.3, (0, -7, 0))]),
    }
    length, loop, flat = configs[name]
    grouped: dict[str, list[tuple[str, float, tuple[float, float, float]]]] = {}
    for logical, when, xyz in flat:
        channel = "position" if logical.endswith("_position") else "rotation"
        bone = logical.removesuffix("_position")
        grouped.setdefault(bone, []).append((channel, when, xyz))
    animators = dict(animator(bone_ids[bone], bone, frames, token) for bone, frames in grouped.items())
    return {
        "uuid": uid(spec.model_id, f"animation:{name}"),
        "name": name,
        "loop": loop,
        "override": name not in ("idle", "walk"),
        "length": length,
        "snapping": 20,
        "selected": False,
        "group_name": "",
        "anim_time_update": "",
        "blend_weight": "",
        "start_delay": "",
        "loop_delay": "",
        "animators": animators,
    }


def model(spec: ModelSpec, source: str) -> dict[str, object]:
    bone_ids = {bone: uid(spec.model_id, f"bone:{bone}") for bone in BONES}
    elements = []
    grouped: dict[str, list[str]] = {bone: [] for bone in BONES}
    for cube in spec.cubes:
        cube_id = uid(spec.model_id, f"cube:{cube.name}")
        grouped[cube.bone].append(cube_id)
        origin = cube.origin or tuple((cube.start[index] + cube.end[index]) / 2 for index in range(3))
        value: dict[str, object] = {
            "name": cube.name,
            "box_uv": False,
            "render_order": "default",
            "locked": False,
            "allow_mirror_modeling": True,
            "from": list(cube.start),
            "to": list(cube.end),
            "autouv": 0,
            "color": 0,
            "origin": list(origin),
            "faces": faces(cube.tile),
            "type": "cube",
            "uuid": cube_id,
        }
        if cube.rotation is not None:
            value["rotation"] = list(cube.rotation)
        elements.append(value)
    groups = []
    for bone in BONES:
        groups.append({
            "uuid": bone_ids[bone], "export": True, "locked": False,
            "origin": [0, 0, 0], "rotation": [0, 0, 0], "color": 0, "name": bone,
            "children": [], "reset": False, "shade": True, "mirror_uv": False,
            "selected": False, "visibility": True, "autouv": 0, "isOpen": True,
            "primary_selected": False,
        })
    root_children = []
    for bone in BONES[1:]:
        root_children.append({"uuid": bone_ids[bone], "isOpen": True, "children": grouped[bone]})
    root_children.extend(grouped["root"])
    return {
        "meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
        "name": spec.model_id,
        "model_identifier": spec.model_id,
        "visible_box": [3.5, 3.5, 0],
        "variable_placeholders": "",
        "variable_placeholder_buttons": [],
        "timeline_setups": [],
        "unhandled_root_fields": {},
        "resolution": {"width": 64, "height": 64},
        "elements": elements,
        "groups": groups,
        "outliner": [{"uuid": bone_ids["root"], "isOpen": True, "children": root_children}],
        "textures": [{
            "name": f"{spec.model_id}.png", "path": "", "folder": "", "namespace": "", "id": "0",
            "group": "", "width": 64, "height": 64, "uv_width": 64, "uv_height": 64,
            "particle": False, "use_as_default": True, "layers_enabled": False,
            "sync_to_project": "", "render_mode": "default", "render_sides": "auto",
            "pbr_channel": "color", "frame_time": 1, "frame_order_type": "loop",
            "frame_order": "", "frame_interpolate": False, "visible": True,
            "internal": True, "saved": False, "uuid": uid(spec.model_id, "texture"), "source": source,
        }],
        "animations": [animation(spec, bone_ids, name) for name in ("idle", "walk", "spawn", "attack", "cast", "phase", "death")],
    }


def review_sheet(specs: tuple[ModelSpec, ...], textures: dict[str, Image.Image]) -> Image.Image:
    sheet = Image.new("RGBA", (1152, 768), (9, 12, 17, 255))
    draw = ImageDraw.Draw(sheet)
    for index, spec in enumerate(specs):
        column = index % 3
        row = index // 3
        left = column * 384
        top = row * 384
        draw.rectangle((left + 8, top + 8, left + 376, top + 376), fill=(18, 23, 31, 255), outline=(57, 71, 86, 255), width=2)
        texture_preview = textures[spec.model_id].resize((160, 160), Image.Resampling.NEAREST)
        sheet.alpha_composite(texture_preview, (left + 208, top + 40))
        scale = 7.0
        floor = top + 330
        center = left + 105
        for cube in sorted(spec.cubes, key=lambda item: item.start[2], reverse=True):
            x0 = int(center + cube.start[0] * scale)
            x1 = int(center + cube.end[0] * scale)
            y0 = int(floor - cube.end[1] * scale)
            y1 = int(floor - cube.start[1] * scale)
            tile_color = color(spec.palette[min(4, cube.tile // 4)])
            draw.rectangle((x0, y0, x1, y1), fill=tile_color, outline=color(spec.palette[4]), width=1)
        draw.text((left + 24, top + 22), spec.title, fill=color(spec.palette[4]), stroke_width=1, stroke_fill=(0, 0, 0, 255))
        draw.text((left + 208, top + 214), spec.model_id, fill=(191, 204, 217, 255))
        draw.text((left + 208, top + 242), f"{len(spec.cubes)} parts · 7 animations", fill=(132, 153, 172, 255))
        draw.text((left + 208, top + 270), "front silhouette / 64px atlas", fill=(132, 153, 172, 255))
    return sheet


def concept_sheet(specs: tuple[ModelSpec, ...]) -> Image.Image | None:
    images = []
    source_dir = ROOT / "resource-pack" / "ai" / "output" / "source" / "mobs"
    for spec in specs:
        path = source_dir / f"{spec.model_id}_concept.png"
        if not path.is_file():
            return None
        images.append(Image.open(path).convert("RGB"))
    sheet = Image.new("RGB", (1536, 1024), (9, 12, 17))
    draw = ImageDraw.Draw(sheet)
    for index, (spec, source) in enumerate(zip(specs, images, strict=True)):
        left = (index % 3) * 512
        top = (index // 3) * 512
        fitted = ImageOps.fit(source, (496, 456), method=Image.Resampling.LANCZOS)
        sheet.paste(fitted, (left + 8, top + 8))
        draw.rectangle((left + 8, top + 464, left + 504, top + 504), fill=(14, 19, 27))
        draw.text((left + 22, top + 476), f"{index + 1}. {spec.title}", fill=color(spec.palette[4]))
    return sheet


def main() -> None:
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    REVIEW.parent.mkdir(parents=True, exist_ok=True)
    textures: dict[str, Image.Image] = {}
    for spec in SPECS:
        image = texture(spec)
        textures[spec.model_id] = image
        buffer = io.BytesIO()
        image.save(buffer, format="PNG", optimize=False)
        source = "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")
        output = MODEL_DIR / f"{spec.model_id}.bbmodel"
        output.write_text(json.dumps(model(spec, source), ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    review_sheet(SPECS, textures).save(REVIEW, format="PNG", optimize=False)
    concepts = concept_sheet(SPECS)
    if concepts is not None:
        concepts.save(CONCEPT_REVIEW, format="PNG", optimize=False)


if __name__ == "__main__":
    main()
