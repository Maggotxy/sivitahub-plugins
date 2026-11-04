"""Break real source/asset contracts in isolated fixtures and require rejection."""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("content_assets_checker", ROOT / "scripts/check-content-assets.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ContentAssetContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copytree(ROOT / "resource-pack/assets", self.root / "resource-pack/assets")
        for relative in ("plugins/rpg-skills/src/main/java/cn/originalrpg/skills/SkillCatalog.java",
                         "plugins/rpg-skills/src/main/resources/config.yml",
                         "plugins/rpg-economy/src/main/resources/config.yml",
                         "plugins/rpg-api/src/main/java/cn/originalrpg/api/ui/HudGlyphs.java",
                         "plugins/rpg-dungeons/src/main/resources/config.yml",
                         "plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/BetterModelMobAppearanceService.java"):
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, path)

    def reject(self, expected):
        errors, _ = checker.validate(self.root)
        self.assertTrue(any(expected in error for error in errors), errors)

    def test_current_cross_catalog_coverage(self):
        errors, counts = checker.validate(self.root)
        self.assertEqual([], errors)
        self.assertEqual((81, 42, 15), (counts["item_definitions"], counts["skills"], counts["mob_models"]))
        self.assertGreater(counts["resource_references_checked"], 1500)

    def test_missing_hud_texture_rejected(self):
        (self.root / "resource-pack/assets/betterhud/textures/glyph_skill_heal.png").unlink()
        self.reject("missing textures resource betterhud:glyph_skill_heal.png")

    def test_missing_bettermodel_part_rejected(self):
        (self.root / "resource-pack/assets/bettermodel/models/modern_item/blood_moon_grave_knight_body_1.json").unlink()
        self.reject("missing models resource bettermodel:modern_item/blood_moon_grave_knight_body_1")

    def test_configured_unimplemented_skill_rejected(self):
        path = self.root / "plugins/rpg-skills/src/main/resources/config.yml"
        path.write_text(path.read_text().replace("  heal:\n", "  missing-heal:\n", 1))
        self.reject("configured skill IDs differ")

    def test_missing_dungeon_dispatch_rejected(self):
        path = self.root / "resource-pack/assets/bettermodel/items/bm_models.json"
        data = json.loads(path.read_text())
        data["model"]["entries"] = [entry for entry in data["model"]["entries"]
                                    if "boss_ruins_inscription_warden" not in json.dumps(entry)]
        path.write_text(json.dumps(data))
        self.reject("dispatch is missing boss_ruins_inscription_warden")

    def test_missing_single_model_part_dispatch_rejected(self):
        path = self.root / "resource-pack/assets/bettermodel/items/bm_models.json"
        data = json.loads(path.read_text())
        data["model"]["entries"] = [entry for entry in data["model"]["entries"]
                                    if entry["model"].get("model") != "bettermodel:modern_item/blood_moon_grave_knight_body_1"]
        path.write_text(json.dumps(data))
        self.reject("authored BetterModel part is unreachable")

    def test_unknown_plugin_item_reference_rejected(self):
        path = self.root / "plugins/rpg-skills/src/main/java/cn/originalrpg/skills/FutureDisplay.java"
        path.write_text('class FutureDisplay { Object key = new NamespacedKey("originalrpg", "forgotten_icon"); }')
        self.reject("plugin emits undeclared originalrpg item model: forgotten_icon")

    def test_escaping_resource_reference_rejected(self):
        path = self.root / "resource-pack/assets/originalrpg/models/item/skill_heal.json"
        data = json.loads(path.read_text())
        data["textures"]["layer0"] = "originalrpg:../../external"
        path.write_text(json.dumps(data))
        self.reject("invalid textures reference")


if __name__ == "__main__":
    unittest.main()
