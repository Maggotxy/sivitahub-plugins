"""Verify real geometry exports and break the server/HUD input contracts.

These are source/export tests. They do not start BetterModel, BetterHud, Paper
or a Minecraft client, and do not claim those runtime acceptance gates passed.
"""
import base64
from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("server_candidates", ROOT / "resource-pack/tools/build_server_resources.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class ServerResourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.payloads, cls.receipt = builder.build_payloads(ROOT)

    def model(self):
        record = self.receipt["models"][0]
        model = json.loads(self.payloads[record["path"]])
        png = (ROOT / record["texture_source"]).read_bytes()
        return record, model, png

    def test_all_fifteen_real_models_embed_exact_reviewed_artwork(self):
        self.assertEqual(15, self.receipt["model_count"])
        self.assertEqual(164, sum(record["cuboids"] for record in self.receipt["models"]))
        self.assertEqual(105, sum(record["bones"] for record in self.receipt["models"]))
        categories = [record["category"] for record in self.receipt["models"]]
        self.assertEqual((3, 6, 6), tuple(categories.count(category) for category in ("blood-moon", "dungeon-boss", "dungeon-wave")))
        for record in self.receipt["models"]:
            data = json.loads(self.payloads[record["path"]])
            png = (ROOT / record["texture_source"]).read_bytes()
            self.assertEqual(png, base64.b64decode(data["textures"][0]["source"].split(",", 1)[1]))
            self.assertEqual(record["texture_sha256"], hashlib.sha256(png).hexdigest())
            builder.validate_model(data, record["id"], png, record["category"])

    def test_two_exports_are_identical_and_manifest_covers_all_payloads(self):
        again, receipt = builder.build_payloads(ROOT)
        self.assertEqual(self.payloads, again)
        self.assertEqual(self.receipt, receipt)
        records = {entry["path"]: entry for entry in receipt["files"]}
        self.assertEqual(set(again) - {"manifest.json"}, set(records))
        for name, record in records.items():
            self.assertEqual(record["bytes"], len(again[name]))
            self.assertEqual(record["sha256"], hashlib.sha256(again[name]).hexdigest())
        self.assertTrue(receipt["requires_runtime_recompile"])
        self.assertFalse(receipt["source_artwork_modified"])

    def test_outliner_missing_body_is_rejected(self):
        record, model, png = self.model()
        missing = next(group["uuid"] for group in model["groups"] if group["name"] == "body")

        def remove(nodes):
            result = []
            for node in nodes:
                if isinstance(node, dict):
                    if node["uuid"] == missing:
                        continue
                    node["children"] = remove(node.get("children", []))
                result.append(node)
            return result

        model["outliner"] = remove(model["outliner"])
        with self.assertRaisesRegex(builder.ResourceError, "outliner"):
            builder.validate_model(model, record["id"], png, record["category"])

    def test_replaced_texture_is_rejected(self):
        record, model, png = self.model()
        model["textures"][0]["source"] = "data:image/png;base64," + base64.b64encode(b"wrong artwork").decode()
        with self.assertRaisesRegex(builder.ResourceError, "embedded texture differs"):
            builder.validate_model(model, record["id"], png, record["category"])

    def test_animation_targeting_unknown_bone_is_rejected(self):
        record, model, png = self.model()
        animator = next(iter(model["animations"][0]["animators"].values()))
        model["animations"][0]["animators"] = {"missing-bone": animator}
        with self.assertRaisesRegex(builder.ResourceError, "absent bone"):
            builder.validate_model(model, record["id"], png, record["category"])

    def mutate_yaml(self, name, operation):
        payloads = dict(self.payloads)
        doc = yaml.safe_load(payloads[name])
        operation(doc)
        payloads[name] = builder.yaml_bytes(doc)
        return payloads

    def test_hud_missing_skill_child_is_rejected(self):
        payloads = self.mutate_yaml("plugins/BetterHud/images/sivita.yml",
            lambda doc: doc["sivita_slot_1"]["setting"]["children"].pop("heal"))
        with self.assertRaisesRegex(builder.ResourceError, "all 42 skill icons"):
            builder.validate_hud(ROOT, payloads)

    def test_hud_unknown_placeholder_is_rejected(self):
        payloads = self.mutate_yaml("plugins/BetterHud/images/sivita.yml",
            lambda doc: doc["sivita_slot_1"]["setting"].update({"follow": "papi:sivita_skill_9_id"}))
        with self.assertRaisesRegex(builder.ResourceError, "undeclared placeholder"):
            builder.validate_hud(ROOT, payloads)

    def test_hud_missing_english_control_is_rejected(self):
        payloads = self.mutate_yaml("plugins/BetterHud/images/sivita.yml",
            lambda doc: doc["sivita_cast"]["setting"]["children"].pop("<image:control_cast_en>"))
        with self.assertRaisesRegex(builder.ResourceError, "English and Chinese"):
            builder.validate_hud(ROOT, payloads)

    def test_hud_misaligned_overlay_is_rejected(self):
        payloads = self.mutate_yaml("plugins/BetterHud/layouts/sivita.yml",
            lambda doc: doc["sivita_combat_rail"]["images"]["selected_1"].update({"x": -35}))
        with self.assertRaisesRegex(builder.ResourceError, "does not align"):
            builder.validate_hud(ROOT, payloads)

    def test_hud_without_resource_readiness_gate_is_rejected(self):
        payloads = self.mutate_yaml("plugins/BetterHud/huds/sivita.yml", lambda doc: doc["sivita_hud"].pop("conditions"))
        with self.assertRaisesRegex(builder.ResourceError, "readiness placeholder"):
            builder.validate_hud(ROOT, payloads)

    def test_geometry_helpers_import_without_pillow(self):
        code = ("import sys; sys.modules['PIL'] = None; "
                f"sys.path.insert(0, {str(ROOT / 'resource-pack/tools')!r}); "
                "import generate_dungeon_boss_models, generate_dungeon_wave_models, generate_blood_moon_models")
        result = subprocess.run([sys.executable, "-S", "-c", code], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_existing_output_is_not_modified(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "review"
            output.mkdir()
            sentinel = output / "keep.txt"
            sentinel.write_bytes(b"operator files")
            with self.assertRaisesRegex(builder.ResourceError, "already exists"):
                builder.generate(ROOT, output)
            self.assertEqual(b"operator files", sentinel.read_bytes())
            self.assertEqual([sentinel], list(output.iterdir()))

    def test_live_plugin_output_is_rejected(self):
        with self.assertRaisesRegex(builder.ResourceError, "must not be inside plugins"):
            builder.generate(ROOT, ROOT / "plugins/candidate-must-not-be-written")


if __name__ == "__main__":
    unittest.main()
