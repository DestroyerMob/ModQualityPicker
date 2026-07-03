#!/usr/bin/env python3
"""Smoke tests for the Mod Quality Picker Prism helper."""

from __future__ import annotations

import argparse
import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path


HELPER_PATH = Path(__file__).with_name("modqualitypicker_prism.py")
SPEC = importlib.util.spec_from_file_location("modqualitypicker_prism", HELPER_PATH)
assert SPEC is not None
mqp = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = mqp
SPEC.loader.exec_module(mqp)


class PrismHelperConfigTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.game_dir = self.root / "minecraft"
        self.config_dir = self.game_dir / "config"
        self.mod_config_dir = self.config_dir / mqp.MOD_ID
        self.game_dir.mkdir(parents=True)
        self.paths = mqp.InstancePaths.from_root(self.root)
        self.mod_config_dir.mkdir(parents=True)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_game_file(self, relative_path: str, text: str) -> None:
        path = self.game_dir / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def read_game_file(self, relative_path: str) -> str:
        return (self.game_dir / relative_path).read_text(encoding="utf-8")

    def write_profile(self, profile_id: str) -> Path:
        profile = {
            "schemaVersion": 1,
            "id": profile_id,
            "displayName": profile_id,
            "sortOrder": 10,
            "description": "test",
            "mods": {},
            "configFiles": [],
            "options": {},
        }
        path = self.mod_config_dir / "presets" / f"{profile_id}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")
        return path

    def test_capture_defaults_writes_manifest_and_excludes_managed_config(self) -> None:
        self.write_game_file("config/example.toml", "foo = 1\n")
        self.write_game_file(f"config/{mqp.MOD_ID}/presets/ignored.json", "{}\n")

        actions = mqp.capture_default_configs(self.paths, force=False, dry_run=False)

        self.assertEqual(actions, ["capture default config/example.toml"])
        manifest = json.loads(self.paths.default_manifest.read_text(encoding="utf-8"))
        self.assertIn("config/example.toml", manifest["entries"])
        self.assertNotIn(f"config/{mqp.MOD_ID}/presets/ignored.json", manifest["entries"])
        self.assertFalse(mqp.validate_default_manifest(self.paths))

    def test_profile_and_world_diffs_layer_over_defaults(self) -> None:
        self.write_game_file("config/example.toml", "foo = 1\nbar = 2\n")
        self.write_profile("balanced")
        mqp.capture_default_configs(self.paths, force=False, dry_run=False)

        self.write_game_file("config/example.toml", "foo = 3\nbar = 2\nextra = true\n")
        mqp.capture_profile_diffs(
            self.paths,
            profile_id="balanced",
            changed_only=False,
            capture_missing_defaults=False,
            dry_run=False,
        )

        self.write_game_file("config/example.toml", "foo = 3\nbar = 20\nextra = true\nworldOnly = true\n")
        mqp.capture_world_diffs(
            self.paths,
            world_id="New World",
            profile_id="balanced",
            changed_only=True,
            capture_missing_defaults=False,
            dry_run=False,
        )

        self.write_game_file("config/example.toml", "foo = 999\nbar = player_mutation\n")
        profile = json.loads((self.mod_config_dir / "presets" / "balanced.json").read_text(encoding="utf-8"))
        pending = {
            "schemaVersion": 1,
            "reason": "world-profile",
            "sourceWorldId": "New World",
            "queuedAt": "2026-01-01T00:00:00Z",
            "profile": profile,
        }
        (self.mod_config_dir / "pending-profile.json").write_text(json.dumps(pending, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()):
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=True,
                world_id="",
            ))

        self.assertEqual(result, 0)
        self.assertEqual(
            self.read_game_file("config/example.toml"),
            "foo = 3\nbar = 20\nextra = true\nworldOnly = true\n",
        )


if __name__ == "__main__":
    unittest.main()
