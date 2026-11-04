#!/usr/bin/env python3
"""Build the complete committed SivitaHub client pack without private server files.

No generators, deployment writes, package downloads, or art mutations run here.
The explicit build.sh --deployment path remains available to the private server
project when it needs to regenerate BetterHud / BetterModel runtime assets.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PACK_NAME = "sivita-hub-26.2.zip"
PACK_ID = "6f27f0c8-802e-4b4a-aee9-18084ff12d62"
DEFAULT_EPOCH = 946684800


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def source_files(root: Path) -> dict[str, Path]:
    """Map every shipped file to its source, preserving the existing shader overlay."""
    pack = root / "resource-pack"
    result = {"pack.mcmeta": pack / "pack.mcmeta"}
    for source in sorted((pack / "assets").rglob("*")):
        if source.is_symlink():
            raise ValueError(f"resource source must not be a symlink: {source.relative_to(root)}")
        if source.is_file() and source.name != ".gitkeep":
            result[source.relative_to(pack).as_posix()] = source
    shader_root = pack / "betterhud_26_1/assets/minecraft/shaders/core"
    shaders = sorted(shader_root.glob("*"))
    expected = {"rendertype_text.vsh", "rendertype_text.fsh", "text.vsh", "text.fsh"}
    if {file.name for file in shaders if file.is_file()} != expected:
        raise ValueError("the four reviewed BetterHud shader overrides must all be present")
    for source in shaders:
        if source.is_symlink() or not source.is_file():
            raise ValueError(f"invalid shader source: {source.relative_to(root)}")
        result[f"assets/minecraft/shaders/core/{source.name}"] = source
    if len(result) < 2:
        raise ValueError("resource pack has no assets")
    return dict(sorted(result.items()))


def zip_timestamp(value: str) -> tuple[int, ...]:
    try:
        epoch = int(value)
        stamp = datetime.fromtimestamp(epoch, tz=timezone.utc)
    except (ValueError, OverflowError, OSError) as exc:
        raise ValueError("SOURCE_DATE_EPOCH must be a supported integer Unix timestamp") from exc
    if not 1980 <= stamp.year <= 2107:
        raise ValueError("SOURCE_DATE_EPOCH must fit the ZIP timestamp range (1980–2107)")
    return (stamp.year, stamp.month, stamp.day, stamp.hour, stamp.minute, stamp.second // 2 * 2)


def write_zip(path: Path, payloads: dict[str, bytes], timestamp: tuple[int, ...]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, payload in sorted(payloads.items()):
            info = zipfile.ZipInfo(name, timestamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, payload, compresslevel=9)
    with zipfile.ZipFile(path) as archive:
        damaged = archive.testzip()
        if damaged:
            raise ValueError(f"ZIP integrity check failed: {damaged}")


def check_contract(root: Path, script: str) -> dict:
    spec = importlib.util.spec_from_file_location(script.removesuffix(".py").replace("-", "_"), root / "scripts" / script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    errors, report = module.validate(root)
    if errors:
        raise ValueError("\n".join(errors))
    return report


def build(root: Path, output: Path, timestamp: tuple[int, ...]) -> Path:
    output = output.resolve()
    for protected in ("server", "resource-pack", "datapacks", "plugins", "scripts", "docs"):
        if output == root / protected or (root / protected) in output.parents:
            raise ValueError(f"portable build output must not be inside {protected}/")
    # The checked-in gates contain the canonical pixel, closure and geometry rules.
    for command in (["sh", str(root / "scripts/check-resource-pack.sh"), "--root", str(root)],
                    [sys.executable, str(root / "scripts/check-asset-baseline.py"), "--root", str(root)],
                    [sys.executable, str(root / "resource-pack/tools/verify_hud_layout.py")]):
        subprocess.run(command, cwd=root, check=True)
    content = check_contract(root, "check-content-assets.py")
    navigation = check_contract(root, "check-navigation.py")
    sources = source_files(root)
    payloads = {name: path.read_bytes() for name, path in sources.items()}
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".pack-stage-", dir=output) as staging:
        stage = Path(staging)
        archive = stage / PACK_NAME
        write_zip(archive, payloads, timestamp)
        raw = archive.read_bytes()
        digest1, digest256 = hashlib.sha1(raw).hexdigest(), sha256(raw)
        manifest = {
            "schema_version": 1,
            "artifact": PACK_NAME,
            "edition": "bilingual-client-resources",
            "minecraft_version": "26.2",
            "resource_pack_format": [88, 0],
            "pack_uuid": PACK_ID,
            "archive_bytes": len(raw),
            "sha1": digest1,
            "sha256": digest256,
            "zip_timestamp_utc": list(timestamp),
            "content": content,
            "navigation_datapack_validation": navigation,
            "locales": ["en_us", "zh_cn"],
            "build_mode": "committed-assets",
            "shader_overrides": [name for name in sources if name.startswith("assets/minecraft/shaders/core/")],
            "runtime_requirements": [
                "Install the separate sivita-navigation datapack for the G-key dialog.",
                "English dialog text follows the Minecraft client language; English fallbacks work without the pack.",
                "Server-rendered plugin text follows the player's /language preference.",
                "Generate public BetterHud layout and all 15 BetterModel registration candidates with build_server_resources.py; compile them with the pinned external plugins before using them.",
                "This ZIP preserves the complete committed client baseline. New BetterHud/BetterModel compiler outputs must replace their matching fonts, model indices and shaders together; the candidate inputs do not prove this baseline matches a new runtime compilation.",
                "Minecraft 26.2 client rendering and matching external plugin versions still require a live client acceptance test."
            ],
            "files": [{"path": name, "source": sources[name].relative_to(root).as_posix(),
                       "bytes": len(data), "sha256": sha256(data)} for name, data in payloads.items()]
        }
        (stage / f"{PACK_NAME}.sha1").write_text(f"{digest1}  {PACK_NAME}\n", encoding="utf-8")
        (stage / f"{PACK_NAME}.sha256").write_text(f"{digest256}  {PACK_NAME}\n", encoding="utf-8")
        (stage / f"{PACK_NAME}.manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        # Publish the manifest last: consumers can verify its archive digest before use.
        for name in (PACK_NAME, f"{PACK_NAME}.sha1", f"{PACK_NAME}.sha256", f"{PACK_NAME}.manifest.json"):
            os.replace(stage / name, output / name)
    print(f"RESOURCE_PACK_BUILD=GREEN files={len(sources)} bytes={len(raw)} sha256={digest256}")
    print(output / PACK_NAME)
    return output / PACK_NAME


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(os.environ.get("RESOURCE_PACK_OUTPUT_DIR", ROOT / "build/resource-pack")),
                        help="output directory (default: build/resource-pack or RESOURCE_PACK_OUTPUT_DIR)")
    args = parser.parse_args()
    try:
        build(ROOT, args.output, zip_timestamp(os.environ.get("SOURCE_DATE_EPOCH", str(DEFAULT_EPOCH))))
    except (OSError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile) as exc:
        print(f"RESOURCE_PACK_BUILD=RED {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
