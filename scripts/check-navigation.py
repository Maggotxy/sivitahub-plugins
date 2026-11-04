#!/usr/bin/env python3
"""Validate the actual native Quick Actions dialog shipped to Minecraft 26.2.

The Quick Actions registry is minecraft:dialog, not minecraft:function. This
checker intentionally rejects the old function-tag shape even if its files exist.
Uses only Python's standard library; never starts or modifies a server.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

PACK = "datapacks/sivita-navigation"
TAG = "data/minecraft/tags/dialog/quick_actions.json"
EXPECTED_COMMANDS = (
    "/rpghelp current", "/rpghelp current nopath", "/skills", "/tasks",
    "/hub", "/wild", "/home", "/menu",
)
RESOURCE_ID = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+\Z")


def read_json(path: Path):
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def registered_commands(root: Path) -> set[str]:
    """Read the simple top-level command mapping, without interpreting YAML tags."""
    result = set()
    for path in (root / "plugins").glob("*/src/main/resources/plugin.yml"):
        lines = path.read_text(encoding="utf-8").splitlines()
        inside = False
        for line in lines:
            if line == "commands:":
                inside = True
                continue
            if inside and line and not line[0].isspace() and not line.startswith("#"):
                break
            match = re.fullmatch(r"  ([A-Za-z0-9_-]+):\s*", line) if inside else None
            if match:
                result.add(match.group(1))
    return result


def text_components(value):
    if isinstance(value, dict):
        if "translate" in value:
            yield value
        for child in value.values():
            yield from text_components(child)
    elif isinstance(value, list):
        for child in value:
            yield from text_components(child)


def validate(root: Path) -> tuple[list[str], dict]:
    problems: list[str] = []
    pack = root / PACK
    report = {"minecraft": "26.2", "data_pack_format": [107, 1], "dialog_ids": [], "commands": []}
    try:
        meta = read_json(pack / "pack.mcmeta")["pack"]
        if meta.get("min_format") != [107, 1] or meta.get("max_format") != [107, 1]:
            problems.append("pack.mcmeta must target Minecraft 26.2 data format 107.1 exactly")
        if "pack_format" in meta and meta["pack_format"] != 107:
            problems.append("pack.mcmeta has an incompatible legacy pack_format")
        tag = read_json(pack / TAG)
        values = tag.get("values")
        if not isinstance(values, list) or values != ["sivita:navigation"]:
            problems.append("native quick_actions dialog tag must contain sivita:navigation once")
            values = values if isinstance(values, list) else []
        if tag.get("replace", False):
            problems.append("quick_actions tag must not replace other packs' dialog registrations")
        if (pack / "data/minecraft/tags/function/quick_actions.json").exists():
            problems.append("obsolete function/quick_actions tag cannot register a native dialog")
        obsolete_functions = list((pack / "data/sivita/function").rglob("*.mcfunction"))
        if obsolete_functions:
            problems.append("navigation must use player dialog actions, not obsolete command functions")
        bundles = {locale: read_json(root / f"resource-pack/assets/originalrpg/lang/{locale}.json")
                   for locale in ("en_us", "zh_cn")}
        registered = registered_commands(root)
        for identifier in values:
            if not isinstance(identifier, str) or not RESOURCE_ID.fullmatch(identifier) or ".." in identifier:
                problems.append(f"invalid dialog identifier: {identifier!r}")
                continue
            namespace, path = identifier.split(":", 1)
            report["dialog_ids"].append(identifier)
            dialog = read_json(pack / "data" / namespace / "dialog" / f"{path}.json")
            if dialog.get("type") != "minecraft:multi_action":
                problems.append(f"{identifier}: must be a multi_action dialog")
            if dialog.get("pause") is not False or dialog.get("after_action") != "close":
                problems.append(f"{identifier}: actions must close the screen without pausing play")
            if dialog.get("can_close_with_escape") is not True:
                problems.append(f"{identifier}: Escape must close the navigation screen")
            if dialog.get("columns") != 2:
                problems.append(f"{identifier}: expected two columns of short action labels")
            actions = dialog.get("actions", [])
            if not isinstance(actions, list) or len(actions) != len(EXPECTED_COMMANDS):
                problems.append(f"{identifier}: expected exactly eight navigation actions")
                actions = actions if isinstance(actions, list) else []
            commands = []
            for index, button in enumerate(actions):
                action = button.get("action", {}) if isinstance(button, dict) else {}
                if action.get("type") != "minecraft:run_command":
                    problems.append(f"{identifier} action {index}: must run as the clicking player")
                command = action.get("command", "")
                commands.append(command)
                if not isinstance(command, str) or not re.fullmatch(r"/[a-z0-9_-]+(?: [a-z0-9_-]+)*", command):
                    problems.append(f"{identifier} action {index}: invalid single player command")
                elif command[1:].split()[0] not in registered:
                    problems.append(f"{identifier}: command is not registered by a plugin: {command}")
                if not isinstance(button.get("width"), int) or not 1 <= button["width"] <= 150:
                    problems.append(f"{identifier} action {index}: button exceeds the two-column width")
            if tuple(commands) != EXPECTED_COMMANDS:
                problems.append(f"{identifier}: navigation commands/order differ from the eight supported actions")
            report["commands"].extend(commands)
            components = list(text_components(dialog))
            if len(components) < 12:
                problems.append(f"{identifier}: every visible label and body needs a translatable component")
            for component in components:
                key = component["translate"]
                if not isinstance(key, str):
                    problems.append(f"{identifier}: invalid translation key")
                    continue
                for locale, bundle in bundles.items():
                    if not isinstance(bundle.get(key), str) or not bundle[key].strip():
                        problems.append(f"{identifier}: missing {locale} translation: {key}")
                fallback = component.get("fallback")
                if not isinstance(fallback, str) or fallback != bundles["en_us"].get(key):
                    problems.append(f"{identifier}: English fallback must match en_us for {key}")
            exit_action = dialog.get("exit_action", {})
            if "action" in exit_action:
                problems.append(f"{identifier}: Close must not dispatch a gameplay command")
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
        problems.append(f"invalid or missing navigation source: {exc}")
    return problems, report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    problems, report = validate(args.root)
    if args.json:
        print(json.dumps({**report, "status": "FAIL" if problems else "PASS", "errors": problems}, indent=2))
    else:
        for problem in problems:
            print(f"  [FAIL] {problem}", file=sys.stderr)
        print(f"DATAPACK_CHECK={'RED' if problems else 'GREEN'} native_dialogs={len(report['dialog_ids'])} actions={len(report['commands'])} format=107.1")
    return int(bool(problems))


if __name__ == "__main__":
    raise SystemExit(main())
