"""Verify the English onboarding preset changes only approved text values."""

from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

import yaml


REPOSITORY = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "english_presets", REPOSITORY / "scripts/generate-english-configs.py")
presets = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(presets)


def typed_leaves(data, path=""):
    """Compare actual config values and types, not serialized whitespace."""
    if not isinstance(data, dict):
        return {path: (type(data), data)}
    leaves = {}
    for key, value in data.items():
        leaves.update(typed_leaves(value, path + "." + key if path else key))
    return leaves


class EnglishPresetsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "checkout"
        self.output = Path(self.temporary.name) / "review"
        for plugin, (module, _) in presets.SPECS.items():
            for relative in (
                Path("plugins") / module / "src/main/resources/config.yml",
                Path("examples/en_us") / f"{plugin}.yml",
            ):
                destination = self.root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(REPOSITORY / relative, destination)

    def test_generated_configs_only_change_the_25_approved_text_fields(self):
        before = {path: path.read_bytes() for path in self.root.rglob("*.yml")}
        manifest = presets.generate(self.root, self.output)
        self.assertEqual(4, len(manifest["files"]))
        self.assertEqual(manifest, json.loads((self.output / "manifest.json").read_text()))
        changes = 0
        for item in manifest["files"]:
            source_path = self.root / item["source"]
            original = yaml.safe_load(source_path.read_text())
            generated = yaml.safe_load((self.output / item["output"]).read_text())
            old, new = typed_leaves(original), typed_leaves(generated)
            self.assertEqual(old.keys(), new.keys(), "preset must not introduce or remove config fields")
            changed = {path for path in old if old[path] != new[path]}
            self.assertEqual(set(item["text_overrides"]), changed)
            changes += len(changed)
            for path in changed:
                self.assertIs(str, old[path][0])
                self.assertIs(str, new[path][0])
            self.assertEqual(hashlib.sha256(before[source_path]).hexdigest(), item["source_sha256"])
        self.assertEqual(25, changes)
        self.assertEqual(before, {path: path.read_bytes() for path in before}, "source checkout is read-only")
        core = yaml.safe_load((self.output / "RpgCore/config.yml").read_text())
        self.assertEqual("en_us", core["language"]["default"])
        self.assertTrue(core["language"]["allow-player-selection"])
        self.assertFalse(core["session-lease"]["enabled"])
        self.assertFalse(core["community"]["governance-enabled"])
        self.assertEqual("Asia/Shanghai", core["wealth-exchange"]["timezone"])

    def test_existing_directory_is_never_overwritten_even_if_empty(self):
        self.output.mkdir()
        with self.assertRaisesRegex(presets.PresetError, "already exists"):
            presets.generate(self.root, self.output)
        self.assertEqual([], list(self.output.iterdir()))

    def test_existing_file_is_preserved(self):
        self.output.write_text("existing operator content")
        with self.assertRaisesRegex(presets.PresetError, "already exists"):
            presets.generate(self.root, self.output)
        self.assertEqual("existing operator content", self.output.read_text())

    def test_dangling_symlink_is_refused(self):
        destination = self.output.parent / "do-not-create"
        self.output.symlink_to(destination, target_is_directory=True)
        with self.assertRaisesRegex(presets.PresetError, "already exists"):
            presets.generate(self.root, self.output)
        self.assertTrue(self.output.is_symlink())
        self.assertFalse(destination.exists())

    def test_unapproved_text_field_fails_before_output_creation(self):
        overlay = self.root / "examples/en_us/RpgCore.yml"
        overlay.write_text("language:\n  default: en_us\ndatabase:\n  username: changed\n")
        with self.assertRaisesRegex(presets.PresetError, "unexpected=.*database.username"):
            presets.generate(self.root, self.output)
        self.assertFalse(self.output.exists())

    def test_numeric_or_boolean_overrides_are_rejected(self):
        for value in (1, True, [], {}):
            with self.subTest(value=value):
                with self.assertRaisesRegex(presets.PresetError, "only nonempty text"):
                    presets.overlay_leaves({"language": {"default": value}})

    def test_duplicate_yaml_key_fails_before_output_creation(self):
        overlay = self.root / "examples/en_us/RpgCore.yml"
        overlay.write_text("language:\n  default: en_us\n  default: en_us\n")
        with self.assertRaisesRegex(presets.PresetError, "duplicate key"):
            presets.generate(self.root, self.output)
        self.assertFalse(self.output.exists())

    def test_source_schema_drift_fails_without_guessing_a_new_field(self):
        source = self.root / "plugins/rpg-core/src/main/resources/config.yml"
        data = yaml.safe_load(source.read_text())
        del data["language"]["default"]
        source.write_text(yaml.safe_dump(data))
        with self.assertRaisesRegex(presets.PresetError, "expected string field: language.default"):
            presets.generate(self.root, self.output)
        self.assertFalse(self.output.exists())

    def test_hub_names_fit_the_existing_world_label_limit(self):
        source = self.root / "plugins/rpg-hub/src/main/resources/config.yml"
        original = yaml.safe_load(source.read_text())
        overlay = self.root / "examples/en_us/RpgHub.yml"
        updates = presets.overlay_leaves(yaml.safe_load(overlay.read_text()))
        self.assertEqual(set(presets.HUB_ROLES), set(original["npcs"]))
        for path, name in updates.items():
            if path.endswith(".name"):
                self.assertLessEqual(len(name), 24)
        invalid = deepcopy(updates)
        invalid["npcs.dungeon.name"] = "x" * 25
        with self.assertRaisesRegex(presets.PresetError, "24-codepoint"):
            presets.apply_overlay(original, invalid, presets.SPECS["RpgHub"][1])


if __name__ == "__main__":
    unittest.main()
