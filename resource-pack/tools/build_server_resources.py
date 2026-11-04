#!/usr/bin/env python3
"""Export reviewable BetterModel and BetterHud inputs from public source assets.

Python 3.10+, PyYAML==6.0.3 and Node.js are required. No Pillow, Sharp, private
server files, downloads, bitmap generation or running Minecraft server is used.
The output directory must be new. These are compiler inputs, not proof of a
successful BetterModel / BetterHud load or a replacement for a runtime pack.
"""
from __future__ import annotations

import argparse
import base64
from collections import Counter
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import zipfile

try:
    import yaml
except ImportError:
    raise SystemExit("PyYAML is required: python3 -m pip install PyYAML==6.0.3")

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = "resource-pack/tools/build_server_resources.py"
MODEL_SOURCE = "plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/BetterModelMobAppearanceService.java"
PLACEHOLDER_SOURCE = "plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/SivitaPlaceholderExpansion.java"
PLACEHOLDER_HELPERS = (
    "plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/HudPlaceholderValues.java",
    "plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/ProgressionPlaceholders.java",
    "plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/HudReadinessPolicy.java",
)
CLASS_IDS = ("healer", "warrior", "assassin", "mage", "ranger", "freeform", "unchosen")
BONES = {"root", "body", "h_head", "right_arm", "left_arm", "right_leg", "left_leg"}
SCHEMA_RESOURCES = (
    "config.yml", "font.yml", "default/huds/default-hud.yml",
    "default/images/default-image.yml", "default/layouts/default-layout.yml",
    "default/layouts/entity-layout.yml", "default/texts/entity-font.yml",
)


class ResourceError(ValueError):
    pass


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def source_bytes(root: Path, relative: str) -> bytes:
    path = root / relative
    if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root.resolve()):
        raise ResourceError(f"Missing or unsafe source file: {relative}")
    return path.read_bytes()


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    # dataclasses resolves postponed type annotations through sys.modules.
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def png_source(root: Path, model_id: str) -> tuple[str, bytes, str]:
    relative = f"resource-pack/assets/bettermodel/textures/item/{model_id}_{model_id}.png"
    raw = source_bytes(root, relative)
    if len(raw) < 24 or raw[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", raw[16:24]) != (64, 64):
        raise ResourceError(f"Expected the reviewed 64x64 model texture: {relative}")
    return "data:image/png;base64," + base64.b64encode(raw).decode("ascii"), raw, relative


def validate_model(model: dict, identifier: str, expected_png: bytes, category: str) -> dict:
    if model.get("name") != identifier or model.get("model_identifier") != identifier:
        raise ResourceError(f"Model identifier differs from the plugin mapping: {identifier}")
    if model.get("meta", {}).get("model_format") != "free" or model.get("resolution") != {"width": 64, "height": 64}:
        raise ResourceError(f"{identifier}: expected a 64x64 Generic Blockbench model")
    elements, groups = model.get("elements", []), model.get("groups", [])
    minimum, maximum = {"blood-moon": (11, 11), "dungeon-boss": (8, 16), "dungeon-wave": (7, 12)}[category]
    if not minimum <= len(elements) <= maximum or len(groups) != 7 or {group["name"] for group in groups} != BONES:
        raise ResourceError(f"{identifier}: missing body geometry or one of the seven reviewed bones")
    element_ids = {element["uuid"] for element in elements}
    bone_ids = {group["uuid"] for group in groups}
    if len(element_ids) != len(elements) or len(bone_ids) != len(groups) or element_ids & bone_ids:
        raise ResourceError(f"{identifier}: duplicate element/bone UUID")
    seen: Counter = Counter()

    def visit(node):
        if isinstance(node, str):
            if node not in element_ids:
                raise ResourceError(f"{identifier}: unknown outliner element: {node}")
            seen[node] += 1
        elif isinstance(node, dict) and node.get("uuid") in bone_ids:
            seen[node["uuid"]] += 1
            for child in node.get("children", []):
                visit(child)
        else:
            raise ResourceError(f"{identifier}: invalid outliner bone")

    for node in model.get("outliner", []):
        visit(node)
    if seen != Counter({key: 1 for key in element_ids | bone_ids}):
        raise ResourceError(f"{identifier}: every body element and bone must occur once in the outliner")
    for element in elements:
        for field in ("from", "to", "origin"):
            vector = element.get(field, [])
            if len(vector) != 3 or any(not isinstance(v, (int, float)) or not math.isfinite(v) for v in vector):
                raise ResourceError(f"{identifier}: invalid geometry vector {field}")
        if any(a >= b for a, b in zip(element["from"], element["to"])):
            raise ResourceError(f"{identifier}: empty or inverted cuboid")
        if set(element.get("faces", {})) != {"north", "east", "south", "west", "up", "down"}:
            raise ResourceError(f"{identifier}: incomplete cuboid faces")
        for face in element["faces"].values():
            if face.get("texture") != 0 or len(face.get("uv", [])) != 4 or any(not 0 <= v <= 64 for v in face["uv"]):
                raise ResourceError(f"{identifier}: face UV must reference the reviewed embedded texture")
    textures = model.get("textures", [])
    if len(textures) != 1 or textures[0].get("source") != "data:image/png;base64," + base64.b64encode(expected_png).decode("ascii"):
        raise ResourceError(f"{identifier}: embedded texture differs from the reviewed PNG")
    expected_animations = {"idle", "walk", "spawn"}
    if category != "blood-moon":
        expected_animations |= {"attack", "cast", "phase", "death"}
    animations = model.get("animations", [])
    if len(animations) != len(expected_animations) or {a["name"] for a in animations} != expected_animations:
        raise ResourceError(f"{identifier}: required animations differ")
    for animation in animations:
        if animation.get("length", 0) <= 0 or animation.get("loop") not in {"loop", "once"}:
            raise ResourceError(f"{identifier}: invalid animation timing")
        for bone, animator in animation.get("animators", {}).items():
            if bone not in bone_ids:
                raise ResourceError(f"{identifier}: animation targets an absent bone")
            for frame in animator.get("keyframes", []):
                if frame.get("channel") not in {"rotation", "position", "scale"} or not 0 <= frame.get("time", -1) <= animation["length"]:
                    raise ResourceError(f"{identifier}: invalid animation keyframe")
    return {"id": identifier, "category": category, "cuboids": len(elements), "bones": len(groups),
            "animations": sorted(expected_animations), "texture_sha256": digest(expected_png)}


def model_payloads(root: Path, content: dict) -> tuple[dict[str, bytes], list[dict], list[str]]:
    tools = root / "resource-pack/tools"
    scripts = ["generate_dungeon_boss_models.py", "generate_dungeon_wave_models.py",
               "generate_blood_moon_models.py", "generate-mob-model.mjs"]
    for script in scripts:
        source_bytes(root, f"resource-pack/tools/{script}")
    bosses = load_module("generate_dungeon_boss_models", tools / scripts[0])
    waves = load_module("generate_dungeon_wave_models", tools / scripts[1])
    blood = load_module("sivita_blood_moon_models", tools / scripts[2])
    node = shutil.which("node")
    if node is None:
        raise ResourceError("Node.js is required for the public blood-moon geometry exporter; Sharp is not required")
    result = subprocess.run([node, str(tools / scripts[3]), "--model-json"], cwd=root,
                            text=True, capture_output=True, check=True, timeout=30)
    husk = json.loads(result.stdout)
    models = {husk["name"]: (husk, "blood-moon")}
    for identifier in blood.VARIANTS:
        embedded, _, _ = png_source(root, identifier)
        models[identifier] = (blood.derive_model(husk, identifier, embedded), "blood-moon")
    for spec in bosses.SPECS + waves.SPECS:
        embedded, _, _ = png_source(root, spec.model_id)
        category = "dungeon-boss" if spec in bosses.SPECS else "dungeon-wave"
        models[spec.model_id] = (bosses.model(spec, embedded), category)
    required = set(content["dungeon_boss_models"].values()) | set(content["dungeon_wave_models"].values()) | set(content["blood_moon_models"])
    if len(models) != 15 or set(models) != required:
        raise ResourceError(f"Registration IDs differ from all 15 plugin model mappings: missing={sorted(required-models.keys())} extra={sorted(models.keys()-required)}")
    payloads, records, sources = {}, [], [f"resource-pack/tools/{s}" for s in scripts]
    for identifier, (model, category) in sorted(models.items()):
        _, raw, relative = png_source(root, identifier)
        record = validate_model(model, identifier, raw, category)
        path = f"plugins/BetterModel/models/{identifier}.bbmodel"
        data = (json.dumps(model, ensure_ascii=False, separators=(",", ":")) + "\n").encode()
        payloads[path] = data
        records.append({**record, "path": path, "sha256": digest(data), "texture_source": relative})
        sources.append(relative)
    return payloads, records, sources


def yaml_bytes(value: dict) -> bytes:
    return yaml.safe_dump(value, allow_unicode=True, sort_keys=False, width=110).encode("utf-8")


def numeric_condition(parameter: str, operation: str, value: int) -> dict:
    return {"1": {"first": f"(number)papi:sivita_{parameter}", "second": value, "operation": operation}}


def validate_hud(root: Path, payloads: dict[str, bytes]) -> list[str]:
    """Validate the generated inputs, without claiming to replace BetterHud's compiler."""
    prefix = "plugins/BetterHud/"
    documents = {name: yaml.safe_load(data) for name, data in payloads.items() if name.endswith(".yml")}
    images = documents[prefix + "images/sivita.yml"]
    fonts = documents[prefix + "texts/sivita.yml"]
    layouts = documents[prefix + "layouts/sivita.yml"]
    huds = documents[prefix + "huds/sivita.yml"]
    for image_id, definition in images.items():
        file = definition.get("file", "")
        if ".." in Path(file).parts or prefix + "assets/" + file not in payloads:
            raise ResourceError(f"HUD image {image_id} references a missing/unsafe source file: {file}")
        children = definition.get("setting", {}).get("children", {})
        if not set(children.values()) <= images.keys():
            raise ResourceError(f"HUD image {image_id} refers to an undefined child image")
    for slot in range(1, 5):
        children = images[f"sivita_slot_{slot}"]["setting"]["children"]
        skill_ids = {key.removeprefix("skill_") for key in images if key.startswith("skill_")}
        if len(children) != 42 or set(children) != skill_ids:
            raise ResourceError(f"HUD slot {slot} does not cover all 42 skill icons")
    for action in ("cast", "cycle"):
        expected = {f"<image:control_{action}_{locale}>": f"control_{action}_{locale}" for locale in ("en", "zh")}
        if images[f"sivita_{action}"]["setting"]["children"] != expected:
            raise ResourceError(f"HUD {action} controls must select both English and Chinese bitmaps")
    for definition in layouts.values():
        for item in definition.get("images", {}).values():
            if item["name"] not in images:
                raise ResourceError(f"Layout references an undefined image: {item['name']}")
        for item in definition.get("texts", {}).values():
            if item["name"] not in fonts:
                raise ResourceError(f"Layout references an undefined text font: {item['name']}")
    for hud in huds.values():
        if any(entry["name"] not in layouts for entry in hud["layouts"].values()):
            raise ResourceError("HUD refers to an undefined layout")
        if hud.get("conditions") != numeric_condition("hud_ready", "==", 1):
            raise ResourceError("HUD must be gated by the actual resource-pack/profile readiness placeholder")
    if not set(documents[prefix + "config.yml"]["default-hud"]) <= huds.keys():
        raise ResourceError("BetterHud default-hud does not resolve to the candidate HUD")
    used = sorted(set(re.findall(r"papi:sivita_([a-z0-9_]+)", "\n".join(data.decode() for name, data in payloads.items() if name.endswith(".yml")))))
    source = "\n".join(source_bytes(root, path).decode() for path in (PLACEHOLDER_SOURCE, *PLACEHOLDER_HELPERS))
    direct = set(re.findall(r'normalized\.equals\("([a-z0-9_]+)"\)', source))
    for cases in re.findall(r'case\s+((?:"[a-z0-9_]+"\s*,?\s*)+)\s*->', source):
        direct.update(re.findall(r'"([a-z0-9_]+)"', cases))
    parser = source_bytes(root, "plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/SkillPlaceholderKey.java").decode()
    pattern_match = re.search(r'Pattern\.compile\(\s*"([^"]+)"', parser)
    if pattern_match is None:
        raise ResourceError("Cannot find the canonical skill-slot placeholder parser")
    slot_pattern = re.compile(pattern_match.group(1))
    for key in used:
        slot = slot_pattern.fullmatch(key)
        if key not in direct and not (slot and 1 <= int(slot.group(1)) <= 4):
            raise ResourceError(f"Candidate HUD refers to an undeclared placeholder: %sivita_{key}%")
    rail = json.loads(source_bytes(root, "docs/reference/hud-layout.json"))
    candidate = layouts["sivita_combat_rail"]
    for slot in range(1, 5):
        item = candidate["images"][f"slot_{slot}"]
        expected_y = candidate["images"]["panel"]["y"] + rail["slot"]["firstTop"] + (slot - 1) * rail["slot"]["pitch"]
        if item["y"] != expected_y:
            raise ResourceError(f"HUD slot {slot} drifted from the canonical pitch")
        mask = candidate["images"][f"cooldown_{slot}"]
        selected = candidate["images"][f"selected_{slot}"]
        if (item["x"], item["y"]) != (mask["x"], mask["y"]) or (item["x"], item["y"]) != (selected["x"], selected["y"]):
            raise ResourceError(f"HUD slot {slot} overlay does not align with the icon")
        if not item["layer"] < mask["layer"] < selected["layer"] < candidate["texts"][f"timer_{slot}"]["layer"]:
            raise ResourceError(f"HUD slot {slot} has an incorrect overlay order")
    return used


def hud_payloads(root: Path) -> tuple[dict[str, bytes], dict, list[str]]:
    lock_path = "scripts/runtime-smoke-plugins.lock.json"
    lock = json.loads(source_bytes(root, lock_path))
    pins = {entry["name"]: entry for entry in lock["plugins"]}
    pinned = pins["BetterHud"]
    jar_path = pinned["local"]
    jar = source_bytes(root, jar_path)
    if hashlib.sha512(jar).hexdigest() != pinned["sha512"]:
        raise ResourceError("BetterHud schema JAR differs from the repository's pinned runtime SHA-512")
    with zipfile.ZipFile(root / jar_path) as archive:
        schema = {name: archive.read(name) for name in SCHEMA_RESOURCES}
    defaults = yaml.safe_load(schema["config.yml"])
    # Start from the exact locked version's defaults. All changes here concern
    # presentation and local resource compilation, never economy or permissions.
    defaults.update({"default-hud": ["sivita_hud"], "default-popup": [], "default-compass": [],
                     "enable-self-host": False, "pack-type": "zip", "namespace": "betterhud",
                     "build-folder-location": "BetterHud/build", "remove-default-hotbar": False})
    payloads = {"plugins/BetterHud/config.yml": yaml_bytes(defaults),
                "plugins/BetterHud/font.yml": schema["font.yml"]}
    sources = [lock_path, jar_path, "docs/reference/hud-layout.json", PLACEHOLDER_SOURCE,
               "plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/SkillPlaceholderKey.java",
               "plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/HudControlGlyphPolicy.java", *PLACEHOLDER_HELPERS]
    rail = json.loads(source_bytes(root, "docs/reference/hud-layout.json"))
    if rail["slot"]["count"] != 4:
        raise ResourceError("The reviewed candidate layout requires four skill slots")
    icons = sorted((root / "resource-pack/assets/betterhud/textures").glob("glyph_*.png"))
    icon_files = {path.stem.removeprefix("glyph_"): path.name for path in icons}
    if len([key for key in icon_files if key.startswith("skill_")]) != 42:
        raise ResourceError("Expected all 42 skill glyphs for the HUD")
    images = {}

    def asset(source_name: str, image_id: str):
        relative = f"resource-pack/assets/betterhud/textures/{source_name}"
        data = source_bytes(root, relative)
        payloads[f"plugins/BetterHud/assets/sivita/{source_name}"] = data
        sources.append(relative)
        images[image_id] = {"type": "single", "file": f"sivita/{source_name}"}

    for key, source_name in icon_files.items():
        asset(source_name, key)
    for source_name, image_id in (
        ("image_sivita_skill_panel.png", "sivita_panel"),
        ("image_sivita_class_badge.png", "sivita_badge"),
        ("image_sivita_skill_selected.png", "sivita_selected"),
        ("image_sivita_skill_cooldown.png", "sivita_cooldown"),
        ("image_image_sivita_mana_fill_up_20_20.png", "sivita_mana"),
    ):
        asset(source_name, image_id)
    images["sivita_mana"].update({"type": "listener", "split": 20, "split-type": "up", "setting": {
        "listener": {"class": "placeholder", "value": "(number)papi:sivita_mana", "max": "(number)papi:sivita_mana_max"}}})
    # The locked ImageElement implements children mappings + follow. No dynamic
    # image.name field is invented, and no new font codepoints are hard-coded.
    for action in ("cast", "cycle"):
        images[f"sivita_{action}"] = {"type": "single", "file": f"sivita/glyph_control_{action}_en.png", "setting": {
            "children": {f"<image:control_{action}_{locale}>": f"control_{action}_{locale}" for locale in ("en", "zh")},
            "follow": f"papi:sivita_hud_{action}_glyph"}}
    images["sivita_class"] = {"type": "single", "file": "sivita/glyph_class_unchosen.png", "setting": {
        "children": {f"<image:class_{key}>": f"class_{key}" for key in CLASS_IDS},
        "follow": "papi:sivita_hud_class_glyph"}}
    for slot in range(1, 5):
        images[f"sivita_slot_{slot}"] = {"type": "single", "file": "sivita/glyph_class_unchosen.png", "setting": {
            "children": {key.removeprefix("skill_"): key for key in icon_files if key.startswith("skill_")},
            "follow": f"papi:sivita_skill_{slot}_id"}}
    payloads["plugins/BetterHud/images/sivita.yml"] = yaml_bytes(images)
    # Only digits and A/L separators are rendered as text. The four reviewed
    # Chinese/English control bitmaps avoid depending on a newly generated CJK atlas.
    font = yaml.safe_load(schema["default/texts/entity-font.yml"])["entity_font"]
    payloads["plugins/BetterHud/texts/sivita.yml"] = yaml_bytes({"sivita_numbers": font})
    panel_x, panel_y = -36, -45
    image_layout = {
        "panel": {"name": "sivita_panel", "x": panel_x, "y": panel_y, "layer": 0},
        "badge": {"name": "sivita_badge", "x": -70, "y": -12, "layer": 0},
        "class": {"name": "sivita_class", "x": -68, "y": -6, "layer": 1},
        "mana": {"name": "sivita_mana", "x": panel_x + 2, "y": panel_y + 2, "layer": 1},
        "cast": {"name": "sivita_cast", "x": -100, "y": 51, "layer": 1},
        "cycle": {"name": "sivita_cycle", "x": -100, "y": 65, "layer": 1},
    }
    text_layout = {
        "progression": {"name": "sivita_numbers", "pattern": "A[papi:sivita_ascension] L[papi:sivita_local_level]",
                        "x": -55, "y": 17, "align": "center", "scale": 0.7, "layer": 4},
        "mana": {"name": "sivita_numbers", "pattern": "[papi:sivita_mana]/[papi:sivita_mana_max]",
                 "x": -55, "y": 27, "align": "center", "scale": 0.6, "layer": 4},
    }
    for slot in range(1, 5):
        top = panel_y + rail["slot"]["firstTop"] + (slot - 1) * rail["slot"]["pitch"]
        icon_x = panel_x + 5
        image_layout[f"slot_{slot}"] = {"name": f"sivita_slot_{slot}", "x": icon_x, "y": top, "layer": 1}
        image_layout[f"cooldown_{slot}"] = {"name": "sivita_cooldown", "x": icon_x, "y": top, "layer": 2,
            "conditions": numeric_condition(f"skill_{slot}_cooldown", ">", 0)}
        image_layout[f"selected_{slot}"] = {"name": "sivita_selected", "x": icon_x, "y": top, "layer": 3,
            "conditions": numeric_condition("skill_selected", "==", slot)}
        text_layout[f"timer_{slot}"] = {"name": "sivita_numbers", "pattern": f"[papi:sivita_skill_{slot}_timer]",
            "x": icon_x + 9, "y": top + 5, "align": "center", "scale": 0.8, "layer": 4}
    layout = {"images": image_layout, "texts": text_layout}
    payloads["plugins/BetterHud/layouts/sivita.yml"] = yaml_bytes({"sivita_combat_rail": layout})
    hud = {"layouts": {"1": {"name": "sivita_combat_rail", "gui": {"x": 100, "y": 50}, "pixel": {"x": 0, "y": 0}}},
           "conditions": numeric_condition("hud_ready", "==", 1)}
    payloads["plugins/BetterHud/huds/sivita.yml"] = yaml_bytes({"sivita_hud": hud})
    used = validate_hud(root, payloads)
    report = {"plugin_file": pinned["file"], "plugin_sha512": pinned["sha512"],
              "schema_resources": {name: digest(raw) for name, raw in schema.items()},
              "hud": "sivita_hud", "skill_slots": 4, "skill_icons": 42, "class_icons": 7,
              "control_locales": ["en", "zh"], "placeholder_keys": [f"%sivita_{key}%" for key in used],
              "runtime_validation": "Not performed; compile on the pinned plugin and verify with actual clients."}
    return payloads, report, sources


def candidate_readme() -> str:
    return """# SivitaHub server resource candidates

These public compiler inputs restore the missing BetterModel model registrations
and supply a compact, bilingual BetterHud combat rail. They are generated only
from repository source, the locked BetterHud default schema, and reviewed PNG
bytes. No artwork is redrawn, and no production server files are copied.

## Included

- 15 Generic Blockbench `.bbmodel` files: 3 blood-moon creatures, 6 dungeon bosses,
  and 6 dungeon wave creatures. Each includes its exact reviewed 64x64 PNG, full
  body geometry, seven bones, and the animations used by the source integration.
- BetterHud defaults and a four-slot combat layout with 42 skill icons, seven
  class states, selected-slot frame, cooldown masks and counters, mana, local
  level/ascension, and English/Chinese controls selected by `/language`.
- Every input and output is covered by manifest.json SHA-256 records. Model IDs
  are checked against BetterModelMobAppearanceService, not guessed from filenames.

## Install into an isolated staging server

1. Obtain the exact BetterModel, BetterHud and PlaceholderAPI versions in
   scripts/runtime-smoke-plugins.lock.json, plus the freshly built Rpg plugins.
   The manifest records the exact BetterHud binary used to inspect the schema.
2. While that staging server is stopped, copy plugins/BetterModel/models and
   the BetterHud candidate configuration/assets into the corresponding plugin
   directories. On an existing installation, review and merge these inputs;
   preserve its unrelated models, layouts, fonts, settings and player preferences.
3. Start the external plugins and let BOTH of them compile their resource packs.
   Verify that all 15 model names load and every PAPI key in the manifest resolves.
   Built-in default sample HUD files need not be enabled: default-hud selects
   only sivita_hud. A model/icon export alone does not demonstrate successful load.
4. Combine originalrpg resources with the complete BetterModel and BetterHud
   outputs from THIS server compilation, including BetterHud's Minecraft fonts
   and shaders. Preserve the matching model dispatch indices, font codepoints and
   shader layout table together. Do not combine new registrations/layouts with
   stale compiled bettermodel/betterhud namespaces or the old shader switch table.
   The committed portable ZIP is a source baseline, not an automatically matched
   runtime pack for these new candidate layouts. Validate 26.2 shader compatibility
   and resource format 88.0 before publishing a new pack URL/SHA-1/UUID.
5. Join a 26.2 staging client, accept that runtime pack, and test /guidehud on|off,
   /language en_us and zh_cn, all four slots, cooldown/selection, mana, local levels
   and ascensions. Test GUI scales and small windows. Check no HUD leaks onto a
   player who declined the pack: the entire HUD is gated by sivita_hud_ready.
   Exercise each mob model and animation through
   real RpgMobs/RpgDungeons entities; visual spawn commands alone do not test loot.

The numeric A/L caption means ascension/local level, not effective level. Empty
skill slots use the neutral unchosen icon. Other plugin text follows its normal
server locale system; the G dialog follows the Minecraft client's language.

## Evidence boundary

The exporter checks geometry, outliner references, animations, embedded PNG bytes,
model coverage, YAML references and source placeholder names. No Paper process,
external model/HUD compiler or human client test is run by this command. Inspect
and validate the compiled outputs on the target versions before promoting them.
No database, economy, permissions, gameplay rewards or world settings are changed.

Primary implementation references:
- https://github.com/toxicity188/BetterModel (Generic Blockbench and models/ loader)
- https://github.com/toxicity188/BetterHud/wiki/images (children/follow and listeners)
- https://github.com/toxicity188/BetterHud/wiki/layouts (images/texts and positioning)
- The locked BetterHud JAR's default/*.yml and config.yml; hashes are in manifest.json.
"""


def build_payloads(root: Path) -> tuple[dict[str, bytes], dict]:
    root = root.resolve()
    contract = load_module("sivita_asset_contract", root / "scripts/check-content-assets.py")
    errors, content = contract.validate(root)
    if errors:
        raise ResourceError("\n".join(errors))
    models, model_records, model_sources = model_payloads(root, content)
    hud, hud_record, hud_sources = hud_payloads(root)
    payloads = {**models, **hud, "README.en.md": candidate_readme().encode()}
    source_paths = sorted(set([SCRIPT, MODEL_SOURCE, "scripts/check-content-assets.py"] + model_sources + hud_sources))
    receipt = {"schema_version": 1, "purpose": "server-resource-candidate", "minecraft_version": "26.2",
               "model_count": len(model_records), "models": model_records, "betterhud": hud_record,
               "source_artwork_modified": False, "requires_runtime_recompile": True,
               "validation_scope": "Source and export checks only; external plugin load and client acceptance are unproven.",
               "sources": [{"path": name, "sha256": digest(source_bytes(root, name))} for name in source_paths],
               "files": [{"path": name, "bytes": len(data), "sha256": digest(data)} for name, data in sorted(payloads.items())]}
    payloads["manifest.json"] = (json.dumps(receipt, ensure_ascii=False, indent=2) + "\n").encode()
    return payloads, receipt


def generate(root: Path, output: Path) -> dict:
    if output.exists() or output.is_symlink():
        raise ResourceError(f"Output already exists; choose a NEW directory: {output}")
    resolved = output.resolve()
    for protected in ("server", "plugins", "resource-pack", "scripts", "datapacks", "docs", "examples"):
        anchor = (root / protected).resolve()
        if resolved == anchor or anchor in resolved.parents:
            raise ResourceError(f"Candidate output must not be inside {protected}/")
    payloads, manifest = build_payloads(root)
    output.mkdir(parents=True, exist_ok=False)
    try:
        for name, data in sorted(payloads.items(), key=lambda value: (value[0] == "manifest.json", value[0])):
            path = output / name
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("xb") as handle:
                handle.write(data)
    except OSError:
        shutil.rmtree(output)
        raise
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="NEW candidate directory; never a live plugins/ directory")
    args = parser.parse_args()
    try:
        receipt = generate(ROOT, args.output)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"SERVER_RESOURCE_CANDIDATE=RED {error}", file=sys.stderr)
        return 1
    print(f"SERVER_RESOURCE_CANDIDATE=GREEN models={receipt['model_count']} hud_slots=4 files={len(receipt['files'])}")
    print(args.output.absolute())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
