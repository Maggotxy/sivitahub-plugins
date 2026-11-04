"""Negative controls for the shipped Minecraft dialog (no live server needed)."""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("navigation_checker", ROOT / "scripts/check-navigation.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class NavigationContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copytree(ROOT / "datapacks", self.root / "datapacks")
        shutil.copytree(ROOT / "resource-pack/assets/originalrpg/lang", self.root / "resource-pack/assets/originalrpg/lang")
        for source in (ROOT / "plugins").glob("*/src/main/resources/plugin.yml"):
            target = self.root / source.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        self.dialog = self.root / "datapacks/sivita-navigation/data/sivita/dialog/navigation.json"

    def rewrite(self, path, mutate):
        value = json.loads(path.read_text())
        mutate(value)
        path.write_text(json.dumps(value))

    def reject(self, expected):
        errors, _ = checker.validate(self.root)
        self.assertTrue(any(expected in error for error in errors), errors)

    def test_valid_native_dialog(self):
        errors, report = checker.validate(self.root)
        self.assertEqual([], errors)
        self.assertEqual(8, len(report["commands"]))

    def test_function_tag_does_not_register_a_dialog(self):
        tag = self.root / checker.PACK / checker.TAG
        target = self.root / checker.PACK / "data/minecraft/tags/function/quick_actions.json"
        target.parent.mkdir(parents=True)
        tag.rename(target)
        self.reject("invalid or missing navigation source")

    def test_outdated_datapack_format_rejected(self):
        self.rewrite(self.root / checker.PACK / "pack.mcmeta", lambda data: data["pack"].update(min_format=[107, 0]))
        self.reject("format 107.1")

    def test_unregistered_command_rejected(self):
        self.rewrite(self.dialog, lambda data: data["actions"][0]["action"].update(command="/missing_plugin_command"))
        self.reject("not registered")

    def test_function_action_does_not_replace_player_command(self):
        self.rewrite(self.dialog, lambda data: data["actions"][0]["action"].update(type="minecraft:run_function"))
        self.reject("clicking player")

    def test_missing_english_text_rejected(self):
        path = self.root / "resource-pack/assets/originalrpg/lang/en_us.json"
        self.rewrite(path, lambda data: data.pop("sivita.quick.quests"))
        self.reject("missing en_us translation")

    def test_stale_fallback_rejected(self):
        self.rewrite(self.dialog, lambda data: data["title"].update(fallback="未翻译的旧标题"))
        self.reject("English fallback")

    def test_close_cannot_execute_gameplay_command(self):
        self.rewrite(self.dialog, lambda data: data["exit_action"].update(action={"type": "minecraft:run_command", "command": "/menu"}))
        self.reject("Close must not dispatch")


if __name__ == "__main__":
    unittest.main()
