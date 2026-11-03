#!/usr/bin/env python3
"""Generate review copies of English onboarding configs; never install them.

Usage: python3 scripts/generate-english-configs.py --output build/en_us-preview
Requires PyYAML==6.0.3. The output directory must not already exist.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import sys

try:
    import yaml
except ImportError:
    raise SystemExit("PyYAML is required: python3 -m pip install PyYAML==6.0.3")


HUB_ROLES = (
    "guide", "quests", "ai", "class", "skills", "forge", "enhancement",
    "dungeon", "market", "economy", "wilderness",
)
# Only these existing string fields can be replaced. No numeric, boolean,
# database, currency, world, UUID, permission or feature-switch fields qualify.
SPECS = {
    "RpgCore": ("rpg-core", {"language.default"}),
    "RpgHub": ("rpg-hub", {f"npcs.{role}.{field}" for role in HUB_ROLES
                           for field in ("name", "subtitle")}),
    "RpgStory": ("rpg-story", {"actor.name"}),
    "RpgPerformance": ("rpg-performance", {"overload-protection.join-message"}),
}


class PresetError(ValueError):
    pass


class StrictConfigLoader(yaml.SafeLoader):
    def compose_node(self, parent, index):
        if self.check_event(yaml.AliasEvent):
            event = self.peek_event()
            raise yaml.constructor.ConstructorError(
                None, None, "YAML aliases are not supported by this review-copy generator", event.start_mark,
            )
        return super().compose_node(parent, index)

    def construct_mapping(self, node, deep=False):
        self.flatten_mapping(node)
        seen = set()
        for key_node, _ in node.value:
            key = self.construct_object(key_node, deep=deep)
            if not isinstance(key, str):
                raise yaml.constructor.ConstructorError(
                    None, None, "configuration keys must be strings", key_node.start_mark,
                )
            if key in seen:
                raise yaml.constructor.ConstructorError(
                    None, None, f"duplicate key: {key!r}", key_node.start_mark,
                )
            seen.add(key)
        return super().construct_mapping(node, deep=deep)


def load_mapping(path: Path) -> tuple[dict, bytes]:
    try:
        raw = path.read_bytes()
        data = yaml.load(raw.decode("utf-8"), Loader=StrictConfigLoader)
    except (OSError, UnicodeError, yaml.YAMLError) as error:
        raise PresetError(f"{path}: {error}") from error
    if not isinstance(data, dict) or not data:
        raise PresetError(f"{path}: expected a nonempty YAML mapping")
    return data, raw


def overlay_leaves(data: dict, prefix: str = "") -> dict[str, str]:
    leaves = {}
    for key, value in data.items():
        if not key or "." in key:
            raise PresetError("Overlay keys must be nonempty nested keys, without dots")
        path = f"{prefix}.{key}" if prefix else key
        if isinstance(value, dict) and value:
            leaves.update(overlay_leaves(value, path))
        elif isinstance(value, str) and value.strip():
            if any(ord(character) < 32 for character in value):
                raise PresetError(f"{path}: presentation text must be a single line")
            leaves[path] = value
        else:
            raise PresetError(f"{path}: overlays may contain only nonempty text fields")
    return leaves


def apply_overlay(source: dict, updates: dict[str, str], allowed: set[str]) -> dict:
    if updates.keys() != allowed:
        raise PresetError(
            f"Overlay differs from the approved text-only fields: "
            f"missing={sorted(allowed - updates.keys())}, unexpected={sorted(updates.keys() - allowed)}"
        )
    generated = deepcopy(source)
    for path, text in updates.items():
        segments = path.split(".")
        node = generated
        for segment in segments[:-1]:
            node = node.get(segment) if isinstance(node, dict) else None
        if not isinstance(node, dict) or not isinstance(node.get(segments[-1]), str):
            raise PresetError(f"Canonical source no longer has the expected string field: {path}")
        if path == "language.default" and text != "en_us":
            raise PresetError("English preset language.default must equal en_us")
        if path.startswith("npcs.") and path.endswith(".name") and len(text) > 24:
            raise PresetError(f"{path}: shared NPC name exceeds the current 24-codepoint limit")
        node[segments[-1]] = text
    return generated


def generate(root: Path, output: Path) -> dict:
    # Do not resolve the final path first: a dangling symlink must also fail.
    if output.exists() or output.is_symlink():
        raise PresetError(f"Output already exists; choose a NEW review directory: {output}")
    planned = {}
    manifest = {"schema_version": 1, "preset": "en_us", "purpose": "review-only", "files": []}
    for plugin, (module, allowed) in SPECS.items():
        source_path = Path("plugins") / module / "src/main/resources/config.yml"
        overlay_path = Path("examples/en_us") / f"{plugin}.yml"
        source, source_bytes = load_mapping(root / source_path)
        overlay, _ = load_mapping(root / overlay_path)
        updates = overlay_leaves(overlay)
        generated = apply_overlay(source, updates, allowed)
        target = Path(plugin) / "config.yml"
        body = yaml.safe_dump(generated, allow_unicode=True, sort_keys=False, width=120)
        # Ensure serialization preserves every semantic value, including quoted
        # amounts and booleans; comments/formatting are intentionally not copied.
        if yaml.load(body, Loader=StrictConfigLoader) != generated:
            raise PresetError(f"Serialization changed configuration values: {source_path}")
        planned[target] = (
            "# GENERATED REVIEW COPY from canonical source defaults.\n"
            "# Review the small examples/en_us overlay before applying to an existing deployment.\n"
            + body
        )
        manifest["files"].append({
            "source": source_path.as_posix(),
            "source_sha256": hashlib.sha256(source_bytes).hexdigest(),
            "overlay": overlay_path.as_posix(),
            "output": target.as_posix(),
            "text_overrides": dict(sorted(updates.items())),
        })
    # Validate every input before creating output. mkdir(exist_ok=False) also
    # rejects a destination created by another process since the first check.
    output.mkdir(parents=True, exist_ok=False)
    for relative, body in planned.items():
        target = output / relative
        target.parent.mkdir()
        with target.open("x", encoding="utf-8") as stream:
            stream.write(body)
    with (output / "manifest.json").open("x", encoding="utf-8") as stream:
        json.dump(manifest, stream, indent=2, ensure_ascii=False)
        stream.write("\n")
    return manifest


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path,
                        help="NEW directory for generated review copies (never a live plugin directory)")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1],
                        help="source checkout root; not a running server directory")
    args = parser.parse_args(argv)
    try:
        manifest = generate(args.root.resolve(), args.output)
    except (PresetError, OSError) as error:
        print(f"ENGLISH PRESET FAILED: {error}", file=sys.stderr)
        return 1
    changed = sum(len(item["text_overrides"]) for item in manifest["files"])
    print(f"Generated {len(manifest['files'])} review config copies with {changed} text overrides: {args.output}")
    print("No server files were read or installed. Apply reviewed text overlays to your deployment configuration.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
