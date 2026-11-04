#!/usr/bin/env python3
"""Package all 25 built plugins and the English presentation preset for staging.

Requires real Gradle JARs, a verified portable resource pack, PyYAML==6.0.3
and Node.js (geometry-only model export; no Sharp or Pillow required).
The output directory and its sibling .zip must both be NEW. No live server,
world, database, credentials, third-party binaries or secret files are read.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


presets = load_module("edition_presets", ROOT / "scripts/generate-english-configs.py")
portable = load_module("edition_pack", ROOT / "resource-pack/tools/build_portable.py")
server_resources = load_module("edition_server_resources", ROOT / "resource-pack/tools/build_server_resources.py")
story_package = load_module("edition_story_package", ROOT / "scripts/check-story-package.py")
PROVENANCE_PATH = "META-INF/sivitahub-build.json"


class PackageError(ValueError):
    pass


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def properties(root: Path) -> dict[str, str]:
    return dict(re.findall(r"(?m)^([A-Za-z][A-Za-z0-9.]*)=(.+)$", (root / "gradle.properties").read_text()))


def source_provenance(root: Path) -> dict:
    """Independently mirror the Gradle provenance contract, without build outputs."""
    inputs = {name: root / name for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties")}
    modules = re.findall(r'"plugins:(rpg-[a-z0-9-]+)"', (root / "settings.gradle.kts").read_text())
    for module in modules:
        directory = root / "plugins" / module
        paths = list((directory / "src/main").rglob("*"))
        paths.extend(directory / name for name in ("build.gradle", "build.gradle.kts"))
        for path in paths:
            if path.is_file() or path.is_symlink():
                inputs[path.relative_to(root).as_posix()] = path
    fingerprint = hashlib.sha256(b"sivitahub-source-v1\0")
    for relative, source in sorted(inputs.items()):
        if source.is_symlink() or not source.is_file() or not source.resolve().is_relative_to(root.resolve()):
            raise PackageError(f"Missing or unsafe build provenance source: {relative}")
        fingerprint.update(relative.encode("utf-8"))
        fingerprint.update(b"\0")
        fingerprint.update(hashlib.sha256(source.read_bytes()).digest())
    return {"schema_version": 1, "algorithm": "sha256-path-nul-content-sha256-v1",
            "source_fingerprint": fingerprint.hexdigest(), "source_files": len(inputs)}


def source_plugins(root: Path) -> list[dict]:
    modules = re.findall(r'"plugins:(rpg-[a-z0-9-]+)"', (root / "settings.gradle.kts").read_text())
    if len(modules) != 25 or len(set(modules)) != 25:
        raise PackageError("Expected exactly 25 distinct plugin projects in settings.gradle.kts")
    result = []
    names = set()
    for module in sorted(modules):
        resource_root = root / "plugins" / module / "src/main/resources"
        metadata, raw = presets.load_mapping(resource_root / "plugin.yml")
        name = metadata.get("name", "")
        if not re.fullmatch(r"Rpg[A-Za-z]+", name) or name in names:
            raise PackageError(f"Invalid or duplicate plugin name: {name!r}")
        names.add(name)
        result.append({"module": module, "name": name, "metadata": metadata, "metadata_bytes": raw,
                       "resources": resource_root})
    return result


def validate_jar(path: Path, plugin: dict, version: str, minecraft: str, provenance: dict) -> bytes:
    raw = path.read_bytes()
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or archive.testzip():
            raise PackageError(f"Invalid or duplicate ZIP entries in {path.name}")
        expected_descriptor = plugin["metadata_bytes"].replace(b"${version}", version.encode()).replace(b"${paperApi}", minecraft.encode())
        if archive.read("plugin.yml") != expected_descriptor:
            raise PackageError(f"{path.name}: plugin.yml is stale or belongs to another build; run Gradle assemble")
        main = plugin["metadata"].get("main", "").replace(".", "/") + ".class"
        bytecode = archive.read(main)
        if len(bytecode) < 10 or bytecode[:4] != b"\xca\xfe\xba\xbe" or struct.unpack(">H", bytecode[6:8])[0] != 69:
            raise PackageError(f"{path.name}: plugin main class must be compiled for Java 25")
        for source in sorted(plugin["resources"].rglob("*")):
            if source.is_symlink():
                raise PackageError(f"Resource source must not be a symlink: {source}")
            if source.is_file() and source.name != "plugin.yml":
                relative = source.relative_to(plugin["resources"]).as_posix()
                if archive.read(relative) != source.read_bytes():
                    raise PackageError(f"{path.name}: bundled resource is stale: {relative}; run Gradle assemble")
        if PROVENANCE_PATH not in names:
            raise PackageError(f"{path.name}: missing build provenance stamp; rebuild all plugins with Gradle assemble")
        stamp = json.loads(archive.read(PROVENANCE_PATH))
        expected = {**provenance, "module": plugin["module"], "project_version": version}
        if stamp != expected:
            raise PackageError(f"{path.name}: build provenance differs from the current shared Java/configuration sources; rebuild all 25 plugins")
    return raw


def validate_pack(root: Path, pack_path: Path) -> tuple[dict[str, bytes], dict]:
    raw = pack_path.read_bytes()
    manifest_path = Path(str(pack_path) + ".manifest.json")
    manifest_raw = manifest_path.read_bytes()
    manifest = json.loads(manifest_raw)
    if manifest.get("artifact") != portable.PACK_NAME or manifest.get("resource_pack_format") != [88, 0]:
        raise PackageError("Resource pack manifest does not identify the complete Minecraft 26.2 pack")
    if manifest.get("sha256") != digest(raw) or manifest.get("sha1") != hashlib.sha1(raw).hexdigest():
        raise PackageError("Resource pack bytes do not match the build manifest")
    sources = portable.source_files(root)
    entries = manifest.get("files", [])
    inventory = {entry["path"]: entry for entry in entries}
    if len(inventory) != len(entries) or set(inventory) != set(sources):
        raise PackageError("Resource pack inventory differs from the complete committed source assets")
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        if len(archive.namelist()) != len(set(archive.namelist())) or set(archive.namelist()) != set(sources) or archive.testzip():
            raise PackageError("Resource pack ZIP contains missing, extra, duplicate or corrupt entries")
        for name, source in sources.items():
            data = archive.read(name)
            if data != source.read_bytes() or inventory[name].get("sha256") != digest(data):
                raise PackageError(f"Resource pack is stale or damaged: {name}; rebuild it first")
    payloads = {f"resourcepacks/{portable.PACK_NAME}": raw,
                f"resourcepacks/{portable.PACK_NAME}.manifest.json": manifest_raw,
                f"resourcepacks/{portable.PACK_NAME}.sha1": f"{hashlib.sha1(raw).hexdigest()}  {portable.PACK_NAME}\n".encode(),
                f"resourcepacks/{portable.PACK_NAME}.sha256": f"{digest(raw)}  {portable.PACK_NAME}\n".encode()}
    return payloads, manifest


def installation_text(required: list[str], optional: list[str]) -> str:
    return f'''# SivitaHub English edition — staging bundle

This bundle contains all 25 custom plugin JARs, canonical configuration defaults
with the reviewed English presentation overrides, the complete bilingual client
resource pack, the native Minecraft 26.2 Quick Actions datapack, and reviewable
BetterModel/BetterHud server resource candidates. The bilingual BetonQuest
prologue conversation is included under plugins/BetonQuest/QuestPackages/.

## Prepare a fresh staging server

1. Use Java 25 and a compatible Paper 26.2 server. Obtain Paper and third-party
   plugins separately from their maintainers; this bundle does not redistribute them.
2. Required external plugins, derived from each plugin.yml: {", ".join(required) or "none"}.
   Optional integrations declared by plugin.yml: {", ".join(optional) or "none"}.
   Optional means plugin loading permits absence; an integration's features still
   require that integration and its configured runtime assets.
3. Stop the staging server. Copy this bundle's plugins/ contents into its plugins/
   directory. These configs are fresh development defaults. For an existing server,
   merge the configuration-overlays/ text fields into reviewed local configs;
   preserve its database, economy, world and feature settings.
   If using BetonQuest, review configuration-overlays/BetonQuest-README.md and
   keep the reserved story NPC binding aligned with the RpgStory configuration.
4. Copy datapacks/sivita-navigation into the active world's datapacks/ directory.
   The installer convention is world/datapacks/; adapt it if your world is renamed.
5. Review runtime-candidates/README.en.md before enabling BetterHud or BetterModel.
   It contains all 15 source .bbmodel registrations, four-slot bilingual HUD inputs
   and a source/output SHA manifest. Compile them on the pinned external plugins
   in staging, then assemble a client pack from those matching outputs. The
   supplied portable pack is a baseline and is not automatically matched to a new
   HUD compilation; model indices, font codepoints and shaders must stay together.
6. Host the reviewed client pack at an HTTPS URL reachable by your players.
   Set resource-pack, resource-pack-sha1 and resource-pack-id together in the stopped
   server's server.properties. The supplied baseline SHA-1 and pack UUID are in
   resourcepacks/{portable.PACK_NAME}.manifest.json; recompute the SHA-1 when you
   compile/merge a different runtime pack. No public download URL is assumed.
7. Start Paper and check all 25 custom plugins enabled. Join with a 26.2 client;
   select English in Minecraft and run /language en_us. Test /menu, /skills, /tasks,
   G-key Quick Actions, one combat skill, one forge operation and one dungeon entry.

## Presentation and deployment boundaries

RpgCore defaults to en_us for new players. Persisted player choices remain in effect.
The G-key native dialog follows the client's language and has English fallback text.
The resource pack includes all committed originalrpg, BetterHud and BetterModel
client assets. Private BetterHud server layouts and BetterModel .bbmodel registry
inputs have not been copied from a production deployment. Instead,
runtime-candidates/ provides public source exports of all 15 model registrations
and a new compact bilingual HUD. These need external-plugin compilation and actual
client acceptance; do not interpret a successful package command as that evidence.

World saves, player accounts, database contents, AI credentials, account-link secrets,
production configuration and third-party binaries are excluded. Paper may download
its declared plugin libraries during startup. Review configuration defaults before
public launch. The manifest verifies packaged files; it is not evidence of a live
server or human client acceptance test.

## Verify the download

The sibling ZIP has a .sha256 sidecar. Inside this directory, manifest.json lists
SHA-256 and sizes for every payload file, all 25 plugin versions, declared external
dependencies, and the English configuration overlay receipt. Preserve INTERNAL_USE.md;
packaging does not add an open-source license or alter project rights.

Each custom JAR contains META-INF/sivitahub-build.json. The packager independently
checks its module/version and the same fingerprint of ALL custom plugin main
sources/resources and Gradle build inputs. A changed shared Java API requires a
matching rebuild of every plugin; stale or unstamped JARs stop packaging. The
manifest's build_provenance records the common fingerprint for this edition.
'''


def package(root: Path, output: Path, pack_path: Path) -> Path:
    # Check the un-resolved final path to reject dangling symlinks as well.
    zip_output = Path(str(output) + ".zip")
    sha_output = Path(str(zip_output) + ".sha256")
    for target in (output, zip_output, sha_output):
        if target.exists() or target.is_symlink():
            raise PackageError(f"Output already exists; choose a NEW directory: {target}")
    output = output.absolute()
    for protected in ("server", "plugins", "datapacks", "resource-pack", "scripts", "docs", "examples"):
        anchor = (root / protected).resolve()
        if output.resolve() == anchor or anchor in output.resolve().parents:
            raise PackageError(f"Edition output must not be inside {protected}/")
    config = properties(root)
    version, minecraft = config["projectVersion"], config["minecraftVersion"]
    if minecraft != "26.2":
        raise PackageError("This edition packager targets Minecraft 26.2")
    plugins = source_plugins(root)
    provenance = source_provenance(root)
    payloads: dict[str, bytes] = {}
    plugin_records = []
    problems = []
    for plugin in plugins:
        name = f"{plugin['module']}-{version}.jar"
        jar = root / "plugins" / plugin["module"] / "build/libs" / name
        if not jar.is_file() or jar.is_symlink():
            problems.append(f"missing built plugin: {jar.relative_to(root)}")
            continue
        try:
            data = validate_jar(jar, plugin, version, minecraft, provenance)
            payloads[f"plugins/{name}"] = data
            plugin_records.append({"module": plugin["module"], "name": plugin["name"], "version": version,
                                   "jar": f"plugins/{name}", "sha256": digest(data),
                                   "depend": plugin["metadata"].get("depend", []),
                                   "softdepend": plugin["metadata"].get("softdepend", []),
                                   "libraries": plugin["metadata"].get("libraries", [])})
        except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
            problems.append(f"{name}: {exc}")
    if problems:
        raise PackageError("All 25 real built plugins are required; nothing was packaged.\n" + "\n".join(problems))
    pack_payloads, pack_manifest = validate_pack(root, pack_path)
    payloads.update(pack_payloads)
    content = portable.check_contract(root, "check-content-assets.py")
    navigation = portable.check_contract(root, "check-navigation.py")
    story = story_package.check(root)
    candidate_payloads, candidate_receipt = server_resources.build_payloads(root)
    payloads.update({f"runtime-candidates/{name}": data for name, data in candidate_payloads.items()})
    for source in sorted((root / "examples/betonquest/QuestPackages").rglob("*")):
        if source.is_symlink():
            raise PackageError(f"Story package source must not be a symlink: {source}")
        if source.is_file():
            payloads[f"plugins/BetonQuest/QuestPackages/{source.relative_to(root / 'examples/betonquest/QuestPackages').as_posix()}"] = source.read_bytes()
    payloads["configuration-overlays/BetonQuest-README.md"] = (root / "examples/betonquest/README.md").read_bytes()
    # Only canonical public source resources enter the edition. No deployment
    # directories or environment variables containing service credentials are read.
    for plugin in plugins:
        for source in sorted(plugin["resources"].rglob("*.yml")):
            if source.name == "plugin.yml":
                continue
            if source.is_symlink():
                raise PackageError(f"Config source must not be a symlink: {source}")
            payloads[f"plugins/{plugin['name']}/{source.relative_to(plugin['resources']).as_posix()}"] = source.read_bytes()
    with tempfile.TemporaryDirectory(prefix="sivita-english-configs-") as directory:
        generated = Path(directory) / "configs"
        overlay_receipt = presets.generate(root, generated)
        for source in generated.glob("*/config.yml"):
            payloads[f"plugins/{source.relative_to(generated).as_posix()}"] = source.read_bytes()
    for source in sorted((root / "examples/en_us").glob("*.yml")):
        payloads[f"configuration-overlays/{source.name}"] = source.read_bytes()
    for source in sorted((root / "datapacks/sivita-navigation").rglob("*")):
        if source.is_symlink():
            raise PackageError(f"Datapack source must not be a symlink: {source}")
        if source.is_file():
            payloads[source.relative_to(root).as_posix()] = source.read_bytes()
    payloads["INTERNAL_USE.md"] = (root / "INTERNAL_USE.md").read_bytes()
    payloads["configuration-overlays/manifest.json"] = (json.dumps(overlay_receipt, indent=2, ensure_ascii=False) + "\n").encode()
    provided = {plugin["name"] for plugin in plugins}
    required = sorted({dep for plugin in plugins for dep in plugin["metadata"].get("depend", [])} - provided)
    optional = sorted({dep for plugin in plugins for dep in plugin["metadata"].get("softdepend", [])} - provided - set(required))
    payloads["INSTALL.en.md"] = installation_text(required, optional).encode()
    if source_provenance(root) != provenance:
        raise PackageError("Plugin sources changed during packaging; finish the edits, rebuild all 25 plugins, and retry")
    manifest = {"schema_version": 1, "edition": "en_us", "minecraft_version": minecraft, "java_version": 25,
                "project_version": version, "plugin_count": len(plugin_records), "plugins": plugin_records,
                "build_provenance": provenance,
                "required_external_plugins": required, "optional_external_plugins": optional,
                "resource_pack_sha256": pack_manifest["sha256"], "content": content,
                "navigation": navigation, "story_package": story, "config_overlays": overlay_receipt,
                "runtime_candidates": {"manifest": "runtime-candidates/manifest.json",
                                       "models": candidate_receipt["model_count"],
                                       "betterhud": candidate_receipt["betterhud"],
                                       "requires_runtime_recompile": True},
                "validation_scope": "Source, packaged-resource, archive and static-content checks; live server/client acceptance is separate.",
                "files": [{"path": name, "bytes": len(data), "sha256": digest(data)} for name, data in sorted(payloads.items())]}
    payloads["manifest.json"] = (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode()
    timestamp = portable.zip_timestamp(os.environ.get("SOURCE_DATE_EPOCH", str(portable.DEFAULT_EPOCH)))
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".edition-stage-", dir=output.parent) as staging:
        staged_zip = Path(staging) / "edition.zip"
        portable.write_zip(staged_zip, {f"{output.name}/{name}": data for name, data in payloads.items()}, timestamp)
        zip_data = staged_zip.read_bytes()
        created = []
        try:
            output.mkdir(exist_ok=False)
            created.append(output)
            # The receipt is published last so a partially copied tree is recognizable.
            for name, data in sorted(payloads.items(), key=lambda entry: (entry[0] == "manifest.json", entry[0])):
                path = output / name
                path.parent.mkdir(parents=True, exist_ok=True)
                with path.open("xb") as handle:
                    handle.write(data)
            with zip_output.open("xb") as handle:
                created.append(zip_output)
                handle.write(zip_data)
            with sha_output.open("xb") as handle:
                created.append(sha_output)
                handle.write(f"{digest(zip_data)}  {zip_output.name}\n".encode())
        except OSError:
            for path in reversed(created):
                shutil.rmtree(path) if path.is_dir() else path.unlink(missing_ok=True)
            raise
    print(f"ENGLISH_EDITION=GREEN plugins=25 files={len(payloads)} sha256={digest(zip_data)}")
    print(zip_output.absolute())
    return zip_output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="NEW output directory; its sibling .zip must also be new")
    parser.add_argument("--resource-pack", type=Path, default=ROOT / "build/resource-pack" / portable.PACK_NAME,
                        help="verified portable ZIP with its adjacent .manifest.json")
    args = parser.parse_args()
    try:
        package(ROOT, args.output, args.resource_pack)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as exc:
        print(f"ENGLISH_EDITION=RED {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
