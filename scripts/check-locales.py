#!/usr/bin/env python3
"""Check shipped bilingual YAML without requiring a Paper or Java installation.

Install the parser with: python3 -m pip install PyYAML==6.0.3
This checks message data, not Java call-site arguments or rendered UI layout.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
from typing import Any

try:
    import yaml
except ImportError:
    raise SystemExit("PyYAML is required: python3 -m pip install PyYAML==6.0.3")


LOCALES = ("zh_cn", "en_us")
PLACEHOLDER = re.compile(r"\{([A-Za-z_][A-Za-z0-9_-]*)\}")
# Existing bilingual surfaces must not silently disappear from the check.
REQUIRED_GROUPS = {
    "plugins/rpg-core/src/main/resources/lang",
    "plugins/rpg-hub/src/main/resources/lang",
    "plugins/rpg-classes/src/main/resources/config.yml:menu-text",
    "plugins/rpg-essentials/src/main/resources/config.yml:messages",
    "plugins/rpg-pets/src/main/resources/config.yml:messages",
    "plugins/rpg-ai-npc/src/main/resources/knowledge.yml:<root>",
}


class LocaleError(ValueError):
    """Invalid or incompatible locale resources."""


class UniqueKeyLoader(yaml.SafeLoader):
    """Safe YAML parsing with duplicate keys rejected before values are lost."""

    def construct_mapping(self, node: yaml.MappingNode, deep: bool = False) -> dict:
        self.flatten_mapping(node)
        seen = set()
        for key_node, _ in node.value:
            key = self.construct_object(key_node, deep=deep)
            try:
                if key in seen:
                    raise yaml.constructor.ConstructorError(
                        "while reading a mapping", node.start_mark,
                        f"duplicate key: {key!r}", key_node.start_mark,
                    )
                seen.add(key)
            except TypeError as error:
                raise yaml.constructor.ConstructorError(
                    "while reading a mapping", node.start_mark,
                    "mapping keys must be scalar values", key_node.start_mark,
                ) from error
        return super().construct_mapping(node, deep=deep)


def load_yaml(path: Path) -> dict:
    try:
        data = yaml.load(path.read_text(encoding="utf-8"), Loader=UniqueKeyLoader)
    except (OSError, UnicodeError, yaml.YAMLError) as error:
        raise LocaleError(f"{path}: {error}") from error
    if not isinstance(data, dict):
        raise LocaleError(f"{path}: expected a YAML mapping")
    return data


def flatten_messages(data: Any, label: str) -> dict[str, str | list[str]]:
    """Preserve list leaves; translations may reflow lines or reorder tokens."""
    result: dict[str, str | list[str]] = {}

    def walk(node: Any, segments: tuple[str, ...], ancestors: set[int]) -> None:
        key = ".".join(segments)
        location = f"{label}:{key or '<root>'}"
        if isinstance(node, dict):
            if id(node) in ancestors:
                raise LocaleError(f"{location}: recursive YAML aliases are not allowed")
            if not node:
                raise LocaleError(f"{location}: empty message section")
            for name, value in node.items():
                if not isinstance(name, str) or not name or any(not part for part in name.split(".")):
                    raise LocaleError(
                        f"{location}: message keys must have nonempty string path segments; "
                        "quote YAML boolean-like keys such as 'on' and 'off'"
                    )
                # Bukkit treats dotted YAML keys as configuration paths too.
                walk(value, (*segments, *name.split(".")), ancestors | {id(node)})
        elif isinstance(node, str):
            if not node.strip():
                raise LocaleError(f"{location}: blank message")
            if key in result:
                raise LocaleError(f"{location}: colliding dotted message path")
            result[key] = node
        elif isinstance(node, list):
            if not node or any(not isinstance(line, str) or not line.strip() for line in node):
                raise LocaleError(f"{location}: expected a nonempty list of nonblank strings")
            if key in result:
                raise LocaleError(f"{location}: colliding dotted message path")
            result[key] = node
        else:
            raise LocaleError(f"{location}: expected a string or a list of strings")

    if not isinstance(data, dict):
        raise LocaleError(f"{label}: expected a message mapping")
    walk(data, (), set())
    for key in result:
        parts = key.split(".")
        if any(".".join(parts[:index]) in result for index in range(1, len(parts))):
            raise LocaleError(f"{label}:{key}: message path collides with a scalar parent")
    return result


def placeholders(value: str | list[str]) -> set[str]:
    lines = [value] if isinstance(value, str) else value
    return {token for line in lines for token in PLACEHOLDER.findall(line)}


def compare_group(label: str, bundles: dict[str, Any]) -> int:
    for locale in LOCALES:
        if locale not in bundles:
            raise LocaleError(f"{label}: missing locale {locale}")
    chinese = flatten_messages(bundles["zh_cn"], label + ":zh_cn")
    english = flatten_messages(bundles["en_us"], label + ":en_us")
    problems = []
    for key in sorted(chinese.keys() - english.keys()):
        problems.append(f"{label}:{key}: missing English key")
    for key in sorted(english.keys() - chinese.keys()):
        problems.append(f"{label}:{key}: missing Chinese key")
    for key in sorted(chinese.keys() & english.keys()):
        left, right = chinese[key], english[key]
        if type(left) is not type(right):
            problems.append(f"{label}:{key}: string/list type mismatch")
        elif placeholders(left) != placeholders(right):
            problems.append(
                f"{label}:{key}: placeholder mismatch: "
                f"zh_cn={sorted(placeholders(left))}, en_us={sorted(placeholders(right))}"
            )
    if problems:
        raise LocaleError("\n".join(problems))
    return len(chinese)


def embedded_groups(data: dict, file_label: str) -> dict[str, dict]:
    groups = {}

    def walk(node: Any, segments: tuple[str, ...], ancestors: set[int]) -> None:
        if not isinstance(node, dict):
            return
        if id(node) in ancestors:
            raise LocaleError(f"{file_label}: recursive YAML aliases are not allowed")
        if any(locale in node for locale in LOCALES):
            prefix = ".".join(segments) or "<root>"
            groups[f"{file_label}:{prefix}"] = node
            return
        for key, value in node.items():
            walk(value, (*segments, str(key)), ancestors | {id(node)})

    walk(data, (), set())
    return groups


def discover_groups(root: Path) -> dict[str, dict]:
    """Discover bundled files and locale sections in plugin resource YAML."""
    groups = {}
    for resources in sorted((root / "plugins").glob("*/src/main/resources")):
        lang = resources / "lang"
        if lang.is_dir():
            label = lang.relative_to(root).as_posix()
            groups[label] = {
                locale: load_yaml(lang / f"{locale}.yml")
                for locale in LOCALES if (lang / f"{locale}.yml").is_file()
            }
        for path in sorted(resources.glob("*.yml")):
            label = path.relative_to(root).as_posix()
            groups.update(embedded_groups(load_yaml(path), label))
    missing = REQUIRED_GROUPS - groups.keys()
    if missing:
        raise LocaleError("Missing required language groups:\n" + "\n".join(sorted(missing)))
    return groups


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1],
                        help="repository root (defaults to this script's parent repository)")
    args = parser.parse_args(argv)
    try:
        groups = discover_groups(args.root.resolve())
        counts = {label: compare_group(label, bundles) for label, bundles in sorted(groups.items())}
    except LocaleError as error:
        print(f"LOCALE CHECK FAILED\n{error}", file=sys.stderr)
        return 1
    for label, count in counts.items():
        print(f"PASS {label}: {count} matching message keys")
    print(f"PASS {len(counts)} bilingual groups, {sum(counts.values())} keys per locale; "
          "duplicate keys, types and placeholders checked.")
    print("This verifies shipped YAML only; Java arguments, installed overrides and visual layout need separate checks.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
