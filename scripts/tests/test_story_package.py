"""Mutation tests for the installable dialogue's safety and traversal contracts."""
import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("story_package", ROOT / "scripts/check-story-package.py")
story = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(story)


class StoryPackageTest(unittest.TestCase):
    def setUp(self):
        self.data = story.load_yaml(ROOT / story.PACKAGE_PATH)
        self.actor_id = story.load_yaml(ROOT / "plugins/rpg-story/src/main/resources/config.yml")["actor"]["citizens-id"]
        self.conversation = self.data["conversations"]["hub_orientation"]

    def test_installable_package_has_reachable_authoritative_handlers(self):
        result = story.validate(self.data, self.actor_id)
        self.assertEqual(3, result["actions"])
        self.assertGreater(result["options"], result["actions"])
        self.assertEqual(result["options"] + result["conversations"], result["bilingual_texts"])

    def test_dangling_pointer_is_rejected(self):
        self.conversation["player_options"]["start_tour"]["pointers"] = "missing"
        with self.assertRaisesRegex(story.StoryPackageError, "unresolved conversation pointer"):
            story.validate(self.data, self.actor_id)

    def test_unreachable_or_untranslated_content_cannot_hide_in_the_pack(self):
        for mutation in ("orphan", "missing_language"):
            data = copy.deepcopy(self.data)
            nodes = data["conversations"]["hub_orientation"]["NPC_options"]
            if mutation == "orphan":
                nodes["orphan"] = {"text": {"en-US": "Orphan", "zh-CN": "孤立"}}
            else:
                del nodes["greeting"]["text"]["en-US"]
            with self.subTest(mutation=mutation), self.assertRaises(story.StoryPackageError):
                story.validate(data, self.actor_id)

    def test_reward_or_tag_bypass_cannot_be_added(self):
        for instruction in ("money 300", "tag add sivita_prologue_escort_complete", "command eco give %player% 300"):
            self.data["actions"]["claim_tour"] = instruction
            with self.subTest(instruction=instruction), self.assertRaisesRegex(story.StoryPackageError, "authoritative"):
                story.validate(self.data, self.actor_id)

    def test_wrong_actor_unresolved_condition_and_legacy_events_are_rejected(self):
        mutations = [
            lambda data: data["npcs"].update(qizhi="citizens 1"),
            lambda data: data["conversations"]["hub_orientation"]["player_options"]["start_tour"].update(conditions="missing"),
            lambda data: data["conversations"]["hub_orientation"]["NPC_options"]["greeting"].update(events="old_event"),
        ]
        for mutation in mutations:
            data = copy.deepcopy(self.data)
            mutation(data)
            with self.subTest(mutation=mutation), self.assertRaises(story.StoryPackageError):
                story.validate(data, self.actor_id)

    def test_duplicate_yaml_keys_cannot_silently_override_a_handler(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "package.yml"
            path.write_text("actions:\n  claim: safe\n  claim: unsafe\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate key"):
                story.load_yaml(path)


if __name__ == "__main__":
    unittest.main()
