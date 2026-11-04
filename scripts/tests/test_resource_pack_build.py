"""Check portable build determinism and complete, deployment-free ZIP contents."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("portable_pack", ROOT / "resource-pack/tools/build_portable.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class PortablePackTest(unittest.TestCase):
    def test_repeated_builds_are_identical_and_manifest_covers_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            output1, output2 = Path(directory) / "a", Path(directory) / "b"
            for output in (output1, output2):
                result = subprocess.run([sys.executable, str(ROOT / "resource-pack/tools/build_portable.py"), "--output", str(output)],
                                        cwd=ROOT, text=True, capture_output=True)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            name = builder.PACK_NAME
            self.assertEqual((output1 / name).read_bytes(), (output2 / name).read_bytes())
            self.assertEqual((output1 / f"{name}.manifest.json").read_bytes(), (output2 / f"{name}.manifest.json").read_bytes())
            raw = (output1 / name).read_bytes()
            manifest = json.loads((output1 / f"{name}.manifest.json").read_text())
            self.assertEqual(hashlib.sha256(raw).hexdigest(), manifest["sha256"])
            with zipfile.ZipFile(output1 / name) as archive:
                files = {entry["path"]: entry for entry in manifest["files"]}
                self.assertEqual(set(archive.namelist()), set(files))
                self.assertEqual(set(builder.source_files(ROOT)), set(files))
                for name, entry in files.items():
                    self.assertEqual(entry["sha256"], hashlib.sha256(archive.read(name)).hexdigest())
                self.assertIn("assets/bettermodel/items/bm_models.json", files)
                self.assertIn("assets/betterhud/font/default.json", files)
                self.assertIn("assets/minecraft/shaders/core/text.vsh", files)
                self.assertFalse(any(name.startswith(("server/", "plugins/", "website/")) for name in files))

    def test_zip_timestamp_outside_supported_range_rejected(self):
        for invalid in ("0", "-1", "not-an-epoch", "999999999999"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                builder.zip_timestamp(invalid)

    def test_live_server_output_is_rejected_before_build(self):
        with self.assertRaisesRegex(ValueError, "must not be inside server"):
            builder.build(ROOT, ROOT / "server/web/resourcepacks", builder.zip_timestamp(str(builder.DEFAULT_EPOCH)))


if __name__ == "__main__":
    unittest.main()
