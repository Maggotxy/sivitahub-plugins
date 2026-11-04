"""Exercise the entire packager with isolated, explicitly synthetic JAR fixtures.

The fixtures contain class-file headers and real source resources, not executable
plugins. Passing these tests is never evidence that 25 real plugins compiled or
started. The production entry point still requires the actual Gradle outputs.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
from unittest import mock
import zipfile

import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("edition_packager", ROOT / "scripts/package-edition.py")
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)


def create_fixture(root: Path):
    # Copy only public compiler inputs. No real build/libs JAR is ever copied.
    for directory in ("resource-pack/assets", "resource-pack/betterhud_26_1", "resource-pack/tools",
                      "datapacks/sivita-navigation", "examples/en_us", "examples/betonquest", "docs/reference"):
        shutil.copytree(ROOT / directory, root / directory, ignore=shutil.ignore_patterns("__pycache__"))
    for module in (ROOT / "plugins").glob("rpg-*"):
        if (module / "src/main").is_dir():
            shutil.copytree(module / "src/main", root / "plugins" / module.name / "src/main")
            shutil.copy2(module / "build.gradle.kts", root / "plugins" / module.name / "build.gradle.kts")
    paths = ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "INTERNAL_USE.md", "resource-pack/pack.mcmeta",
             "scripts/check-content-assets.py", "scripts/check-navigation.py", "scripts/runtime-smoke-plugins.lock.json",
             "scripts/check-story-package.py", "scripts/check-locales.py",
             "libs/vendor/BetterHud-bukkit-2.1.0-SNAPSHOT-447.jar")
    for name in paths:
        destination = root / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / name, destination)
    version = packager.properties(root)["projectVersion"]
    provenance = packager.source_provenance(root)
    for plugin in packager.source_plugins(root):
        jar = root / "plugins" / plugin["module"] / "build/libs" / f"{plugin['module']}-{version}.jar"
        jar.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in plugin["resources"].rglob("*"):
                if path.is_file():
                    data = path.read_bytes()
                    if path.name == "plugin.yml":
                        data = data.replace(b"${version}", version.encode()).replace(b"${paperApi}", b"26.2")
                    archive.writestr(path.relative_to(plugin["resources"]).as_posix(), data)
            # Deliberately minimal header: it is sufficient to exercise this
            # packaging check, and cannot be run as a Minecraft plugin.
            archive.writestr(plugin["metadata"]["main"].replace(".", "/") + ".class",
                             b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 69, 1))
            archive.writestr("TEST_FIXTURE_ONLY.txt", "Synthetic packaging-test JAR; never deploy.")
            archive.writestr(packager.PROVENANCE_PATH, json.dumps({**provenance, "module": plugin["module"], "project_version": version}))
    sources = packager.portable.source_files(root)
    payloads = {name: path.read_bytes() for name, path in sources.items()}
    output = root / "build/resource-pack"
    output.mkdir(parents=True)
    pack = output / packager.portable.PACK_NAME
    packager.portable.write_zip(pack, payloads, (2000, 1, 1, 0, 0, 0))
    raw = pack.read_bytes()
    manifest = {"artifact": pack.name, "resource_pack_format": [88, 0],
                "sha256": hashlib.sha256(raw).hexdigest(), "sha1": hashlib.sha1(raw).hexdigest(),
                "files": [{"path": name, "sha256": hashlib.sha256(data).hexdigest()} for name, data in payloads.items()]}
    Path(str(pack) + ".manifest.json").write_text(json.dumps(manifest))
    return pack


class EditionPackageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="sivita-fixture-only-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.pack = create_fixture(self.root)
        self.output = self.root / "build/fixture-edition"

    def jar(self, module):
        version = packager.properties(self.root)["projectVersion"]
        return self.root / "plugins" / module / "build/libs" / f"{module}-{version}.jar"

    def assert_no_release(self):
        for path in (self.output, Path(str(self.output) + ".zip"), Path(str(self.output) + ".zip.sha256")):
            self.assertFalse(path.exists(), path)

    def test_complete_success_path_hashes_and_candidate_inclusion(self):
        private = self.root / "server/private/credentials.txt"
        private.parent.mkdir(parents=True)
        private.write_text("DO-NOT-PACK-THIS-CANARY")
        archive_path = packager.package(self.root, self.output, self.pack)
        manifest = json.loads((self.output / "manifest.json").read_text())
        self.assertEqual(25, manifest["plugin_count"])
        self.assertEqual(25, len(list((self.output / "plugins").glob("*.jar"))))
        self.assertEqual(15, len(list((self.output / "runtime-candidates/plugins/BetterModel/models").glob("*.bbmodel"))))
        self.assertTrue(manifest["runtime_candidates"]["requires_runtime_recompile"])
        self.assertEqual(packager.source_provenance(self.root), manifest["build_provenance"])
        self.assertTrue((self.output / "plugins/BetonQuest/QuestPackages/sivita_story/package.yml").is_file())
        self.assertEqual("en_us", yaml.safe_load((self.output / "plugins/RpgCore/config.yml").read_text())["language"]["default"])
        with zipfile.ZipFile(archive_path) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(len(archive.namelist()), len(set(archive.namelist())))
            for record in manifest["files"]:
                data = archive.read(f"{self.output.name}/{record['path']}")
                self.assertEqual((self.output / record["path"]).read_bytes(), data)
                self.assertEqual(record["bytes"], len(data))
                self.assertEqual(record["sha256"], hashlib.sha256(data).hexdigest())
                self.assertNotIn(b"DO-NOT-PACK-THIS-CANARY", data)
            self.assertFalse(any("server/private" in name for name in archive.namelist()))
        checksum = Path(str(archive_path) + ".sha256").read_text().split()[0]
        self.assertEqual(checksum, hashlib.sha256(archive_path.read_bytes()).hexdigest())

    def test_missing_one_jar_blocks_entire_release(self):
        self.jar("rpg-story").unlink()
        with self.assertRaisesRegex(packager.PackageError, "missing built plugin: .*rpg-story"):
            packager.package(self.root, self.output, self.pack)
        self.assert_no_release()

    def test_stale_bundled_language_blocks_entire_release(self):
        language = self.root / "plugins/rpg-core/src/main/resources/lang/en_us.yml"
        language.write_bytes(language.read_bytes() + b"\n# source changed after this fixture was packaged\n")
        with self.assertRaisesRegex(packager.PackageError, "bundled resource is stale: lang/en_us.yml"):
            packager.package(self.root, self.output, self.pack)
        self.assert_no_release()

    def test_changed_shared_java_source_with_unchanged_resources_rejected(self):
        source = self.root / "plugins/rpg-api/src/main/java/cn/originalrpg/api/service/MessageService.java"
        before = packager.source_provenance(self.root)
        source.write_bytes(source.read_bytes() + b"\n// new shared API source revision\n")
        self.assertNotEqual(before["source_fingerprint"], packager.source_provenance(self.root)["source_fingerprint"])
        with self.assertRaisesRegex(packager.PackageError, "build provenance differs"):
            packager.package(self.root, self.output, self.pack)
        self.assert_no_release()

    def test_missing_build_stamp_rejected(self):
        jar = self.jar("rpg-story")
        with zipfile.ZipFile(jar) as archive:
            entries = {name: archive.read(name) for name in archive.namelist() if name != packager.PROVENANCE_PATH}
        with zipfile.ZipFile(jar, "w") as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        with self.assertRaisesRegex(packager.PackageError, "missing build provenance stamp"):
            packager.package(self.root, self.output, self.pack)
        self.assert_no_release()

    def test_source_change_during_packaging_is_rejected(self):
        generate = packager.server_resources.build_payloads

        def change_after_candidate(root):
            result = generate(root)
            source = root / "plugins/rpg-api/src/main/java/cn/originalrpg/api/service/MessageService.java"
            source.write_bytes(source.read_bytes() + b"\n// concurrent source edit\n")
            return result

        with mock.patch.object(packager.server_resources, "build_payloads", side_effect=change_after_candidate):
            with self.assertRaisesRegex(packager.PackageError, "sources changed during packaging"):
                packager.package(self.root, self.output, self.pack)
        self.assert_no_release()

    def test_existing_output_directory_is_preserved(self):
        self.output.mkdir(parents=True)
        sentinel = self.output / "operator-file.txt"
        sentinel.write_bytes(b"preserve these bytes")
        with self.assertRaisesRegex(packager.PackageError, "already exists"):
            packager.package(self.root, self.output, self.pack)
        self.assertEqual(b"preserve these bytes", sentinel.read_bytes())
        self.assertEqual([sentinel], list(self.output.iterdir()))

    def test_existing_sibling_archive_is_preserved(self):
        archive = Path(str(self.output) + ".zip")
        archive.write_bytes(b"existing release")
        with self.assertRaisesRegex(packager.PackageError, "already exists"):
            packager.package(self.root, self.output, self.pack)
        self.assertEqual(b"existing release", archive.read_bytes())
        self.assertFalse(self.output.exists())

    def test_dangling_output_symlink_is_not_followed(self):
        self.output.symlink_to(self.root / "operator-future-directory", target_is_directory=True)
        with self.assertRaisesRegex(packager.PackageError, "already exists"):
            packager.package(self.root, self.output, self.pack)
        self.assertTrue(self.output.is_symlink())
        self.assertFalse((self.root / "operator-future-directory").exists())

    def test_damaged_pack_blocks_release(self):
        self.pack.write_bytes(self.pack.read_bytes() + b"unmanifested bytes")
        with self.assertRaisesRegex(packager.PackageError, "do not match the build manifest"):
            packager.package(self.root, self.output, self.pack)
        self.assert_no_release()

    def test_changed_asset_source_requires_pack_rebuild(self):
        asset = self.root / "resource-pack/assets/originalrpg/items/skill_heal.json"
        asset.write_bytes(asset.read_bytes() + b"\n")
        with self.assertRaisesRegex(packager.PackageError, "Resource pack is stale"):
            packager.package(self.root, self.output, self.pack)
        self.assert_no_release()

    def test_wrong_java_major_is_rejected(self):
        plugin = next(plugin for plugin in packager.source_plugins(self.root) if plugin["module"] == "rpg-story")
        path = self.jar("rpg-story")
        with zipfile.ZipFile(path) as archive:
            contents = {name: archive.read(name) for name in archive.namelist()}
        main = plugin["metadata"]["main"].replace(".", "/") + ".class"
        contents[main] = b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 65, 1)
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in contents.items():
                archive.writestr(name, data)
        with self.assertRaisesRegex(packager.PackageError, "Java 25"):
            packager.package(self.root, self.output, self.pack)
        self.assert_no_release()


if __name__ == "__main__":
    unittest.main()
