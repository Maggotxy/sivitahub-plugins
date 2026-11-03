"""Exercise the real POSIX downloader CLI using local, checksum-pinned fixtures."""

from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "fetch-deps.sh"
FETCH_ENTRY = re.compile(
    r'(fetch "([^"]+)"\s*\\\n\s*)"[^"]+"(\s*\\\n\s*)"[a-f0-9]{64}"'
    r'(\s*\\\n\s*)"[a-f0-9]{40}"'
)


class FetchDependenciesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.environment = os.environ.copy()
        (self.root / "scripts").mkdir()
        (self.root / "sources").mkdir()
        self.script = self.root / "scripts/fetch-deps.sh"
        self.payloads: dict[str, bytes] = {}
        source = SCRIPT.read_text(encoding="utf-8")

        def fixture(entry: re.Match) -> str:
            name = entry.group(2)
            payload = ("verified fixture for " + name).encode()
            self.payloads[name] = payload
            asset = self.root / "sources" / name
            asset.write_bytes(payload)
            return (entry.group(1) + '"' + asset.as_uri() + '"' + entry.group(3)
                    + '"' + hashlib.sha256(payload).hexdigest() + '"' + entry.group(4)
                    + '"' + hashlib.sha1(payload).hexdigest() + '"')

        self.script.write_text(FETCH_ENTRY.sub(fixture, source), encoding="utf-8")
        self.assertEqual(5, len(self.payloads), "fixture must cover the public dependency manifest")
        self.target = self.root / "libs" / next(iter(self.payloads))
        self.vendor_paths = re.search(r'^PHASE_VENDOR="([^"]+)"', source, re.M).group(1).split()

    def prepare_vendor(self) -> None:
        for path in self.vendor_paths:
            asset = self.root / path
            asset.parent.mkdir(parents=True, exist_ok=True)
            asset.write_bytes(b"vendor presence fixture")

    def prepare_downloaded(self) -> None:
        self.prepare_vendor()
        for name, payload in self.payloads.items():
            (self.root / "libs" / name).write_bytes(payload)

    def run_script(self, *arguments: str) -> subprocess.CompletedProcess:
        # file: sources guarantee this test never contacts a remote server.
        return subprocess.run(["sh", str(self.script), *arguments], cwd=self.root,
                              capture_output=True, text=True, timeout=15, env=self.environment)

    def assert_no_partial_downloads(self) -> None:
        self.assertEqual([], list((self.root / "libs").glob("*.part.*")))

    def test_check_accepts_verified_files_without_changes(self) -> None:
        self.prepare_downloaded()
        before = {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in (self.root / "libs").rglob("*.jar")}
        result = self.run_script("--check")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(before, {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in before})
        self.assert_no_partial_downloads()

    def test_check_preserves_corrupt_files(self) -> None:
        self.prepare_downloaded()
        self.target.write_bytes(b"existing corrupt file retained for diagnosis")
        result = self.run_script("--check")
        self.assertEqual(1, result.returncode)
        self.assertIn("checksum mismatch; file preserved", result.stderr)
        self.assertEqual(b"existing corrupt file retained for diagnosis", self.target.read_bytes())
        self.assert_no_partial_downloads()

    def test_check_does_not_create_missing_library_directory(self) -> None:
        result = self.run_script("--check")
        self.assertEqual(1, result.returncode)
        self.assertFalse((self.root / "libs").exists())

    def test_force_and_check_are_rejected_before_file_changes(self) -> None:
        result = self.run_script("--check", "--force")
        self.assertEqual(2, result.returncode)
        self.assertIn("mutually exclusive", result.stderr)
        self.assertFalse((self.root / "libs").exists())

    def test_bad_forced_download_preserves_previous_dependency(self) -> None:
        self.prepare_downloaded()
        previous = self.target.read_bytes()
        (self.root / "sources" / self.target.name).write_bytes(b"tampered download")
        result = self.run_script("--force")
        self.assertEqual(1, result.returncode)
        self.assertEqual(previous, self.target.read_bytes())
        self.assert_no_partial_downloads()

    def test_successful_download_promotes_only_verified_bytes(self) -> None:
        self.prepare_vendor()
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        for name, payload in self.payloads.items():
            self.assertEqual(payload, (self.root / "libs" / name).read_bytes())
        self.assert_no_partial_downloads()

    def test_failed_promotion_preserves_files_and_cleans_every_temporary(self) -> None:
        self.prepare_downloaded()
        fake_bin = self.root / "bin"
        fake_bin.mkdir()
        failing_move = fake_bin / "mv"
        failing_move.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
        failing_move.chmod(0o755)
        self.environment["PATH"] = str(fake_bin) + os.pathsep + self.environment["PATH"]
        result = self.run_script("--force")
        self.assertEqual(1, result.returncode)
        self.assertIn("cannot replace destination", result.stderr)
        for name, payload in self.payloads.items():
            self.assertEqual(payload, (self.root / "libs" / name).read_bytes())
        self.assert_no_partial_downloads()


if __name__ == "__main__":
    unittest.main()
