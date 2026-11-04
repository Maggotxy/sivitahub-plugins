#!/usr/bin/env python3
"""Validate the shipped BetonQuest 3.1 dialogue subset, not an upstream runtime."""
from __future__ import annotations

import argparse
import importlib.util
from pathlib import Path
import sys

_SPEC = importlib.util.spec_from_file_location("sivita_locale_yaml", Path(__file__).with_name("check-locales.py"))
_LOCALES = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_LOCALES)
load_yaml = _LOCALES.load_yaml

PACKAGE_PATH = Path("examples/betonquest/QuestPackages/sivita_story/package.yml")
LANGUAGES = {"en-US", "zh-CN"}
ACTION_INSTRUCTIONS = {
    "sivita_story_begin prologue_escort",
    "sivita_story_claim prologue_escort",
    "sivita_story_open_journey",
}
CONDITION_INSTRUCTIONS = {"sivita_story_eligible prologue_escort"}


class StoryPackageError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise StoryPackageError(message)


def mapping(value: object, label: str) -> dict:
    require(isinstance(value, dict) and bool(value), f"{label}: expected a nonempty mapping")
    require(all(isinstance(key, str) and key for key in value), f"{label}: expected string keys")
    return value


def csv(value: object, label: str) -> list[str]:
    require(isinstance(value, str) and bool(value.strip()), f"{label}: expected comma-separated identifiers")
    parts = [part.strip() for part in value.split(",")]
    require(all(parts), f"{label}: empty identifier")
    return parts


def translations(value: object, label: str) -> int:
    value = mapping(value, label)
    require(LANGUAGES <= value.keys(), f"{label}: missing English or Chinese translation")
    require(all(isinstance(text, str) and text.strip() for text in value.values()), f"{label}: blank/nontext translation")
    return 1


def validate(data: object, actor_id: int) -> dict[str, int]:
    data = mapping(data, "package")
    allowed_roots = {"package", "npcs", "npc_conversations", "actions", "conditions", "conversations"}
    require(data.keys() <= allowed_roots, "package: unsupported root; state/reward features must stay in RPG handlers")
    metadata = mapping(data.get("package"), "package metadata")
    require(metadata.get("version") == "3.0.0-QUEST-1", "package: use the quest format documented for BetonQuest 3.1")
    require(metadata.get("enabled", True) is not False, "package: shipped dialogue must be enabled")
    npcs = mapping(data.get("npcs"), "npcs")
    require(npcs == {"qizhi": f"citizens {actor_id}"}, "npcs: binding does not match the reserved RpgStory actor")
    actions = mapping(data.get("actions"), "actions")
    require(all(value in ACTION_INSTRUCTIONS for value in actions.values()),
            "actions: only existing authoritative RPG actions are permitted; no direct rewards or state writes")
    require(set(actions.values()) == ACTION_INSTRUCTIONS, "actions: start, claim and journey entry points are required")
    conditions = mapping(data.get("conditions"), "conditions")
    require(all(value in CONDITION_INSTRUCTIONS for value in conditions.values()), "conditions: unknown story condition")
    conversations = mapping(data.get("conversations"), "conversations")
    bindings = mapping(data.get("npc_conversations"), "npc_conversations")
    require(bindings.keys() == npcs.keys(), "npc_conversations: unresolved NPC binding")
    require(all(target in conversations for target in bindings.values()), "npc_conversations: unresolved conversation")
    translated = 0
    total_options = 0
    reached_actions: set[str] = set()
    for name, conversation in conversations.items():
        conversation = mapping(conversation, f"conversation {name}")
        require(conversation.keys() <= {"quester", "first", "stop", "conversationIO", "interceptor", "NPC_options", "player_options"},
                f"{name}: unsupported or legacy conversation fields")
        translated += translations(conversation.get("quester"), f"{name}.quester")
        require(conversation.get("conversationIO") == "simple", f"{name}: use the pack-independent simple interface")
        npc_options = mapping(conversation.get("NPC_options"), f"{name}.NPC_options")
        player_options = mapping(conversation.get("player_options"), f"{name}.player_options")
        options = {("npc", key): value for key, value in npc_options.items()}
        options.update({("player", key): value for key, value in player_options.items()})
        edges = {}
        for node, option in options.items():
            label = f"{name}.{node[0]}.{node[1]}"
            option = mapping(option, label)
            require(option.keys() <= {"text", "pointers", "actions", "conditions"}, f"{label}: unsupported or legacy option fields")
            translated += translations(option.get("text"), label)
            opposite = "player" if node[0] == "npc" else "npc"
            edges[node] = [(opposite, target) for target in csv(option["pointers"], label) ] if "pointers" in option else []
            require(all(target in options for target in edges[node]), f"{label}: unresolved conversation pointer")
            for condition in csv(option["conditions"], label) if "conditions" in option else []:
                require(condition.removeprefix("!") in conditions, f"{label}: unresolved condition")
            for action in csv(option["actions"], label) if "actions" in option else []:
                require(action in actions, f"{label}: unresolved action")
        pending = [("npc", first) for first in csv(conversation.get("first"), f"{name}.first")]
        require(all(node in options for node in pending), f"{name}: unresolved starting option")
        visited = set()
        while pending:
            node = pending.pop()
            if node in visited:
                continue
            visited.add(node)
            pending.extend(edges[node])
            if "actions" in options[node]:
                reached_actions.update(csv(options[node]["actions"], f"{name}.{node[1]}"))
        require(visited == options.keys(), f"{name}: unreachable conversation options")
        require(any(not edges[node] for node in visited), f"{name}: conversation has no exit")
        total_options += len(options)
    require(reached_actions == actions.keys(), "actions: an entry point is unreachable")
    return {"conversations": len(conversations), "options": total_options, "bilingual_texts": translated, "actions": len(reached_actions)}


def check(root: Path) -> dict[str, int]:
    config = load_yaml(root / "plugins/rpg-story/src/main/resources/config.yml")
    return validate(load_yaml(root / PACKAGE_PATH), config["actor"]["citizens-id"])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        result = check(args.root)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Story package check failed: {error}", file=sys.stderr)
        return 1
    print("BetonQuest 3.1 dialogue structure: " + ", ".join(f"{value} {key}" for key, value in result.items()))
    print("Static validation only; load with the pinned plugins for runtime verification.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
