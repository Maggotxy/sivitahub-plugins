#!/usr/bin/env python3
"""Check plugin-to-asset contracts and all shipped model/font references.

This complements the originalrpg pixel gate: BetterHud and BetterModel are part
of the shipped client pack too, and a skill icon must correspond to a real
configured skill. Uses only the standard library and never rewrites artwork.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys

ID = re.compile(r"([a-z0-9_.-]+):([a-z0-9_./-]+)\Z")
CLASSES = {"healer", "warrior", "assassin", "mage", "ranger", "freeform"}


def json_file(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def walk(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def yaml_section(text: str, name: str) -> str:
    """Extract a block mapping from this repository's canonical source YAML."""
    match = re.search(rf"(?m)^{re.escape(name)}:\s*\n", text)
    if not match:
        raise ValueError(f"missing block mapping: {name}")
    rest = text[match.end():]
    end = re.search(r"(?m)^[A-Za-z0-9_-]+:", rest)
    return rest[:end.start()] if end else rest


def java_strings(text: str, marker: str) -> list[str]:
    match = re.search(rf"\b{re.escape(marker)}\s*=\s*(?:java\.util\.)?(?:Set|List|Map)\.of\((.*?)\);", text, re.S)
    if not match:
        raise ValueError(f"cannot find canonical Java collection: {marker}")
    return re.findall(r'"([a-z0-9_-]+)"', match.group(1))


def validate(root: Path) -> tuple[list[str], dict]:
    assets = root / "resource-pack/assets"
    errors: list[str] = []
    counts: dict = {}
    namespace_names = {p.name for p in assets.iterdir() if p.is_dir()}
    checked_refs = 0

    def reference(value, kind: str, source: Path, suffix: str):
        nonlocal checked_refs
        if not isinstance(value, str) or value.startswith("#"):
            return
        match = ID.fullmatch(value)
        if not match or ".." in value:
            errors.append(f"{source.relative_to(root)}: invalid {kind} reference {value!r}")
            return
        namespace, name = match.groups()
        if namespace == "minecraft":
            return  # Vanilla assets are provided by the exact supported client.
        if namespace not in namespace_names:
            errors.append(f"{source.relative_to(root)}: unknown asset namespace {namespace}")
            return
        checked_refs += 1
        path = assets / namespace / kind / (name if name.endswith(suffix) else name + suffix)
        if not path.is_file():
            errors.append(f"{source.relative_to(root)}: missing {kind} resource {value}")

    try:
        for path in sorted(assets.rglob("*.json")):
            value = json_file(path)
            rel = path.relative_to(assets).parts
            if len(rel) < 3:
                continue
            kind = rel[1]
            if kind == "models":
                reference(value.get("parent"), "models", path, ".json")
                for texture in value.get("textures", {}).values():
                    reference(texture, "textures", path, ".png")
            elif kind == "items":
                for node in walk(value):
                    if node.get("type") in {"model", "minecraft:model"}:
                        reference(node.get("model"), "models", path, ".json")
            elif kind == "font":
                for provider in value.get("providers", []):
                    if provider.get("type") in {"bitmap", "minecraft:bitmap"}:
                        reference(provider.get("file"), "textures", path, ".png")
                    elif provider.get("type") in {"reference", "minecraft:reference"}:
                        reference(provider.get("id"), "font", path, ".json")

        item_ids = {path.stem for path in (assets / "originalrpg/items").glob("*.json")}
        direct_keys = set()
        for source in (root / "plugins").glob("*/src/main/java/**/*.java"):
            code = source.read_text(encoding="utf-8")
            direct_keys.update(re.findall(r'NamespacedKey\.fromString\("originalrpg:([a-z0-9_./-]+)"', code))
            for expression in re.findall(r'NamespacedKey\("originalrpg",([^;\n]+)', code):
                direct_keys.update(re.findall(r'"([a-z0-9_./-]+)"', expression))
        economy_config = (root / "plugins/rpg-economy/src/main/resources/config.yml").read_text(encoding="utf-8")
        direct_keys.update(re.findall(r'(?m)^\s+model:\s*"?([a-z0-9_./-]+)"?\s*$', economy_config))
        for key in sorted(direct_keys - item_ids):
            errors.append(f"plugin emits undeclared originalrpg item model: {key}")

        catalog = (root / "plugins/rpg-skills/src/main/java/cn/originalrpg/skills/SkillCatalog.java").read_text()
        implemented = set(java_strings(catalog, "IMPLEMENTED"))
        skill_config = (root / "plugins/rpg-skills/src/main/resources/config.yml").read_text(encoding="utf-8")
        skill_section = yaml_section(skill_config, "skills")
        configured = set(re.findall(r"(?m)^  ([a-z0-9_-]+):\s*$", skill_section))
        if len(implemented) != 42 or implemented != configured:
            errors.append(f"configured skill IDs differ from 42 implemented effects: missing={sorted(implemented-configured)} extra={sorted(configured-implemented)}")
        item_skills = {p.stem.removeprefix("skill_") for p in (assets / "originalrpg/items").glob("skill_*.json")}
        hud_skills = {p.stem.removeprefix("glyph_skill_") for p in (assets / "betterhud/textures").glob("glyph_skill_*.png")}
        for label, entries in (("item models", item_skills), ("HUD icons", hud_skills)):
            if entries != implemented:
                errors.append(f"skill {label} mismatch: missing={sorted(implemented-entries)} extra={sorted(entries-implemented)}")
        glyph_source = (root / "plugins/rpg-api/src/main/java/cn/originalrpg/api/ui/HudGlyphs.java").read_text()
        glyph_skills = java_strings(glyph_source, "SKILLS")
        if len(glyph_skills) != len(set(glyph_skills)) or set(glyph_skills) != implemented:
            errors.append("HUD glyph order does not contain each implemented skill exactly once")
        class_counts = Counter(value.lower() for value in re.findall(r"(?m)^    class:\s+([A-Z]+)\s*$", skill_section))
        if dict(class_counts) != {key: 7 for key in CLASSES}:
            errors.append(f"each of the six classes needs seven configured skills: {dict(class_counts)}")
        hud_classes = {p.stem.removeprefix("glyph_class_") for p in (assets / "betterhud/textures").glob("glyph_class_*.png")}
        if hud_classes != CLASSES | {"unchosen"}:
            errors.append("HUD class icons must cover six classes and the unchosen state")

        dungeons_config = (root / "plugins/rpg-dungeons/src/main/resources/config.yml").read_text(encoding="utf-8")
        dungeon_section = yaml_section(dungeons_config, "dungeons")
        dungeon_ids = set(re.findall(r"(?m)^  ([a-z0-9_-]+):\s*$", dungeon_section))
        appearance = (root / "plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/BetterModelMobAppearanceService.java").read_text()
        pairs = java_strings(appearance, "DUNGEON_MODELS")
        dungeon_models = dict(zip(pairs[::2], pairs[1::2]))
        if len(dungeon_ids) != 6 or set(dungeon_models) != dungeon_ids:
            errors.append("each of the six configured dungeons must have a BetterModel boss mapping")
        wave_pairs = java_strings(appearance, "DUNGEON_WAVE_MODELS")
        wave_models = dict(zip(wave_pairs[::2], wave_pairs[1::2]))
        wave_ids = set(re.findall(r"(?m)^        archetype:\s+([a-z0-9_-]+)\s*$", dungeon_section))
        if set(wave_models) != wave_ids or len(wave_ids) != 6:
            errors.append("dungeon wave archetypes must resolve to the six authored wave models")
        blood_models = set(re.findall(r'"(blood_moon_[a-z_]+)"', appearance))
        model_ids = set(dungeon_models.values()) | set(wave_models.values()) | blood_models
        dispatch = json_file(assets / "bettermodel/items/bm_models.json")
        dispatched = {n["model"] for n in walk(dispatch) if n.get("type") in {"model", "minecraft:model"} and isinstance(n.get("model"), str)}
        authored_parts = {f"bettermodel:modern_item/{path.stem}"
                          for path in (assets / "bettermodel/models/modern_item").glob("*.json")}
        for orphan in sorted(authored_parts - dispatched):
            errors.append(f"authored BetterModel part is unreachable from item dispatch: {orphan}")
        for model in sorted(model_ids):
            prefix = f"bettermodel:modern_item/{model}_"
            if not any(item.startswith(prefix) for item in dispatched):
                errors.append(f"BetterModel item dispatch is missing {model}")
            if not (assets / "bettermodel/textures/item" / f"{model}_{model}.png").is_file():
                errors.append(f"BetterModel texture is missing {model}")
        reward_models = set(re.findall(r"\bmodel:\s*([a-z0-9_-]+)", dungeon_section))
        for model in reward_models:
            if not (assets / "originalrpg/items" / f"{model}.json").is_file():
                errors.append(f"dungeon reward item model is missing: {model}")
        if len(model_ids) != 15:
            errors.append(f"expected 15 authored mob models, found {len(model_ids)}")
        counts.update({"item_definitions": len(list((assets / "originalrpg/items").glob("*.json"))),
                       "direct_item_references": len(direct_keys),
                       "runtime_item_textures": len(list((assets / "originalrpg/textures/item").rglob("*.png"))),
                       "skills": len(implemented), "skills_per_class": dict(sorted(class_counts.items())),
                       "class_icons": len(hud_classes), "dungeons": sorted(dungeon_ids),
                       "dungeon_boss_models": dungeon_models, "dungeon_wave_models": wave_models,
                       "blood_moon_models": sorted(blood_models), "mob_models": len(model_ids),
                       "resource_references_checked": checked_refs,
                       "namespaces": sorted(namespace_names)})
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as exc:
        errors.append(f"invalid or missing asset contract source: {exc}")
    return errors, counts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    errors, counts = validate(args.root)
    if args.json:
        print(json.dumps({"status": "FAIL" if errors else "PASS", "counts": counts, "errors": errors}, indent=2))
    else:
        for error in errors:
            print(f"  [FAIL] {error}", file=sys.stderr)
        print(f"CONTENT_ASSET_GATE={'RED' if errors else 'GREEN'} "
              f"items={counts.get('item_definitions', 0)} skills={counts.get('skills', 0)} "
              f"dungeons={len(counts.get('dungeons', []))} mob_models={counts.get('mob_models', 0)} "
              f"references={counts.get('resource_references_checked', 0)}")
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
