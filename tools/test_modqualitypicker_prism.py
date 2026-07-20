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
import zipfile
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
        self.mods_dir = self.game_dir / "mods"
        self.mods_dir.mkdir(parents=True)

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

    def write_neoforge_mod_jar(self, file_name: str, mod_id: str, required_mod_ids: list[str] | None = None) -> Path:
        path = self.mods_dir / file_name
        required_mod_ids = required_mod_ids or []
        metadata = [
            "[[mods]]",
            f'modId = "{mod_id}"',
            'version = "1.0.0"',
            f'displayName = "{mod_id}"',
            "",
        ]
        for required_mod_id in required_mod_ids:
            metadata.extend([
                f"[[dependencies.{mod_id}]]",
                f'modId = "{required_mod_id}"',
                'type = "required"',
                'versionRange = "*"',
                'ordering = "NONE"',
                'side = "BOTH"',
                "",
            ])

        with zipfile.ZipFile(path, "w") as jar:
            jar.writestr("META-INF/neoforge.mods.toml", "\n".join(metadata))
        return path

    def write_multi_id_neoforge_mod_jar(self, file_name: str, mod_ids: list[str]) -> Path:
        path = self.mods_dir / file_name
        metadata: list[str] = []
        for mod_id in mod_ids:
            metadata.extend([
                "[[mods]]",
                f'modId = "{mod_id}"',
                'version = "1.0.0"',
                f'displayName = "{mod_id}"',
                "",
            ])

        with zipfile.ZipFile(path, "w") as jar:
            jar.writestr("META-INF/neoforge.mods.toml", "\n".join(metadata))
        return path

    def neoforge_metadata(self, mod_id: str, required_mod_ids: list[str] | None = None) -> str:
        required_mod_ids = required_mod_ids or []
        metadata = [
            "[[mods]]",
            f'modId = "{mod_id}"',
            'version = "1.0.0"',
            f'displayName = "{mod_id}"',
            "",
        ]
        for required_mod_id in required_mod_ids:
            metadata.extend([
                f"[[dependencies.{mod_id}]]",
                f'modId = "{required_mod_id}"',
                'type = "required"',
                'versionRange = "*"',
                'ordering = "NONE"',
                'side = "BOTH"',
                "",
            ])
        return "\n".join(metadata)

    def test_capture_defaults_writes_manifest_and_excludes_managed_config(self) -> None:
        self.write_game_file("config/example.toml", "foo = 1\n")
        self.write_game_file(f"config/{mqp.MOD_ID}/presets/ignored.json", "{}\n")

        actions = mqp.capture_default_configs(self.paths, force=False, dry_run=False)

        self.assertEqual(actions, ["capture default config/example.toml"])
        manifest = json.loads(self.paths.default_manifest.read_text(encoding="utf-8"))
        self.assertIn("config/example.toml", manifest["entries"])
        self.assertNotIn(f"config/{mqp.MOD_ID}/presets/ignored.json", manifest["entries"])
        self.assertFalse(mqp.validate_default_manifest(self.paths))

    def test_sync_profile_mods_adds_current_state_and_prunes_stale_entries(self) -> None:
        self.write_neoforge_mod_jar("enabled.jar", "enabled_mod")
        self.write_neoforge_mod_jar("disabled.jar.disabled", "disabled_mod")
        self.write_neoforge_mod_jar("modqualitypicker.jar", mqp.MOD_ID)
        profile_path = self.write_profile("balanced")
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
        profile["mods"] = {
            "enabled_mod": {"enabled": False, "locked": False, "reason": "preserve me"},
            "minecraft": {"enabled": True, "locked": False, "reason": "platform"},
            "stale_mod": {"enabled": True, "locked": False, "reason": "remove me"},
        }
        profile_path.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")

        actions = mqp.sync_profile_mods(
            self.paths,
            profile_id="balanced",
            new_mod_state="current",
            prune_missing=True,
            dry_run=False,
        )

        synced = json.loads(profile_path.read_text(encoding="utf-8"))["mods"]
        self.assertFalse(synced["enabled_mod"]["enabled"])
        self.assertFalse(synced["disabled_mod"]["enabled"])
        self.assertTrue(synced[mqp.MOD_ID]["enabled"])
        self.assertTrue(synced[mqp.MOD_ID]["locked"])
        self.assertIn("minecraft", synced)
        self.assertNotIn("stale_mod", synced)
        self.assertIn("add disabled_mod = disabled", actions)
        self.assertIn("remove missing stale_mod", actions)

    def test_capture_profile_diff_can_attach_config_to_enabled_mod(self) -> None:
        self.write_game_file("config/ecology-common.toml", 'gameplayPreset = "SAFE"\n')
        profile_path = self.write_profile("max")
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
        profile["mods"] = {
            "ecology": {"enabled": True, "locked": False, "reason": "full simulation"},
        }
        profile_path.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")
        mqp.capture_default_configs(self.paths, force=False, dry_run=False)
        self.write_game_file("config/ecology-common.toml", 'gameplayPreset = "FULL_SIMULATION"\n')

        mqp.capture_profile_diffs(
            self.paths,
            profile_id="max",
            changed_only=True,
            capture_missing_defaults=False,
            dry_run=False,
            mod_id="ecology",
            selected_configs=("config/ecology-common.toml",),
        )

        captured = json.loads(profile_path.read_text(encoding="utf-8"))
        self.assertEqual(captured["schemaVersion"], 3)
        self.assertEqual(captured["configFiles"], [])
        self.assertEqual(captured["mods"]["ecology"]["configFiles"][0]["path"], "config/ecology-common.toml")
        self.assertTrue((self.mod_config_dir / "presets/max/config/ecology-common.toml.diff").is_file())

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

    def test_apply_without_pending_profile_is_noop(self) -> None:
        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=False,
                world_id="",
            ))

        self.assertEqual(result, 0)
        self.assertIn("No pending profile found", output.getvalue())

    def test_apply_without_pending_profile_applies_active_saved_preset(self) -> None:
        self.write_neoforge_mod_jar("addon.jar", "addon")
        self.write_game_file(f"config/{mqp.MOD_ID}-common.toml", 'activeProfileId = "balanced"\n')
        profile_path = self.write_profile("balanced")
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
        profile["mods"] = {
            "addon": {"enabled": False, "locked": False, "reason": ""},
        }
        profile_path.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()):
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=False,
                world_id="",
            ))

        self.assertEqual(result, 0)
        self.assertFalse((self.mods_dir / "addon.jar").exists())
        self.assertTrue((self.mods_dir / "addon.jar.disabled").exists())
        applied = json.loads(self.paths.applied_profile.read_text(encoding="utf-8"))
        self.assertEqual(applied["reason"], "active-profile")
        self.assertFalse(applied["profile"]["mods"]["addon"]["enabled"])

    def test_absent_discovered_mod_is_disabled_by_authoritative_profile(self) -> None:
        self.write_neoforge_mod_jar("addon.jar", "addon")
        self.write_game_file(f"config/{mqp.MOD_ID}-common.toml", 'activeProfileId = "balanced"\n')
        self.write_profile("balanced")

        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=False,
                world_id="",
            ))

        self.assertEqual(result, 0)
        self.assertFalse((self.mods_dir / "addon.jar").exists())
        self.assertTrue((self.mods_dir / "addon.jar.disabled").exists())
        self.assertIn("disable addon.jar -> addon.jar.disabled", output.getvalue())

    def test_disabling_mod_replaces_existing_disabled_target_without_duplicate(self) -> None:
        self.write_neoforge_mod_jar("addon.jar", "addon")
        (self.mods_dir / "addon.jar.disabled").write_text("previous disabled copy", encoding="utf-8")
        self.write_game_file(f"config/{mqp.MOD_ID}-common.toml", 'activeProfileId = "balanced"\n')
        profile_path = self.write_profile("balanced")
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
        profile["mods"] = {
            "addon": {"enabled": False, "locked": False, "reason": ""},
        }
        profile_path.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=False,
                world_id="",
            ))

        self.assertEqual(result, 0)
        self.assertFalse((self.mods_dir / "addon.jar").exists())
        self.assertTrue((self.mods_dir / "addon.jar.disabled").exists())
        self.assertFalse((self.mods_dir / "addon-1.jar.disabled").exists())
        with zipfile.ZipFile(self.mods_dir / "addon.jar.disabled") as jar:
            self.assertIn("META-INF/neoforge.mods.toml", jar.namelist())
        self.assertIn("disable addon.jar -> addon.jar.disabled", output.getvalue())

    def test_enabling_mod_removes_existing_disabled_duplicate(self) -> None:
        self.write_neoforge_mod_jar("addon.jar", "addon")
        self.write_neoforge_mod_jar("addon.jar.disabled", "addon")
        self.write_game_file(f"config/{mqp.MOD_ID}-common.toml", 'activeProfileId = "balanced"\n')
        profile_path = self.write_profile("balanced")
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
        profile["mods"] = {
            "addon": {"enabled": True, "locked": False, "reason": ""},
        }
        profile_path.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=False,
                world_id="",
            ))

        self.assertEqual(result, 0)
        self.assertTrue((self.mods_dir / "addon.jar").exists())
        self.assertFalse((self.mods_dir / "addon.jar.disabled").exists())
        self.assertIn("remove duplicate disabled copy", output.getvalue())

    def test_set_active_profile_creates_missing_config(self) -> None:
        profile = {
            "id": "balanced",
        }

        actions = mqp.set_active_profile(self.paths, profile, dry_run=False)

        self.assertEqual(actions, ["set activeProfileId = balanced"])
        self.assertEqual(self.read_game_file(f"config/{mqp.MOD_ID}-common.toml"), 'activeProfileId = "balanced"\n')

    def test_pending_profile_id_uses_latest_saved_preset(self) -> None:
        self.write_neoforge_mod_jar("addon.jar.disabled", "addon")
        profile_path = self.write_profile("balanced")
        latest_profile = json.loads(profile_path.read_text(encoding="utf-8"))
        latest_profile["mods"] = {
            "addon": {"enabled": True, "locked": False, "reason": ""},
        }
        profile_path.write_text(json.dumps(latest_profile, indent=2) + "\n", encoding="utf-8")
        stale_profile = dict(latest_profile)
        stale_profile["mods"] = {
            "addon": {"enabled": False, "locked": False, "reason": "stale queued copy"},
        }
        pending = {
            "schemaVersion": 1,
            "reason": "client-menu",
            "sourceWorldId": "",
            "queuedAt": "2026-01-01T00:00:00Z",
            "profile": stale_profile,
        }
        self.paths.pending_profile.write_text(json.dumps(pending, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()):
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=False,
                world_id="",
            ))

        self.assertEqual(result, 0)
        self.assertTrue((self.mods_dir / "addon.jar").exists())
        self.assertFalse((self.mods_dir / "addon.jar.disabled").exists())
        applied = json.loads(self.paths.applied_profile.read_text(encoding="utf-8"))
        self.assertTrue(applied["profile"]["mods"]["addon"]["enabled"])

    def test_world_profile_uses_queued_snapshot_instead_of_latest_saved_preset(self) -> None:
        self.write_neoforge_mod_jar("addon.jar.disabled", "addon")
        profile_path = self.write_profile("balanced")
        saved_profile = json.loads(profile_path.read_text(encoding="utf-8"))
        saved_profile["mods"] = {
            "addon": {"enabled": False, "locked": False, "reason": "new pack default"},
        }
        profile_path.write_text(json.dumps(saved_profile, indent=2) + "\n", encoding="utf-8")

        world_profile = dict(saved_profile)
        world_profile["mods"] = {
            "addon": {"enabled": True, "locked": False, "reason": "saved with this world"},
        }
        pending = {
            "schemaVersion": 1,
            "reason": "world-profile",
            "sourceWorldId": "New World",
            "queuedAt": "2026-01-01T00:00:00Z",
            "profile": world_profile,
        }
        self.paths.pending_profile.write_text(json.dumps(pending, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()):
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=False,
                world_id="",
            ))

        self.assertEqual(result, 0)
        self.assertTrue((self.mods_dir / "addon.jar").exists())
        self.assertFalse((self.mods_dir / "addon.jar.disabled").exists())
        applied = json.loads(self.paths.applied_profile.read_text(encoding="utf-8"))
        self.assertTrue(applied["profile"]["mods"]["addon"]["enabled"])

    def test_invalid_config_plan_does_not_mutate_mod_jars(self) -> None:
        self.write_neoforge_mod_jar("addon.jar", "addon")
        profile_path = self.write_profile("balanced")
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
        profile["mods"] = {
            "addon": {"enabled": False, "locked": False, "reason": "test"},
        }
        profile["configFiles"] = [{
            "path": "config/missing.toml",
            "mode": "APPLY_DIFF",
            "presetFile": "presets/balanced/config/missing.toml.diff",
            "sha256": "",
        }]
        profile_path.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=False,
                world_id="",
            ))

        self.assertEqual(result, 1)
        self.assertTrue((self.mods_dir / "addon.jar").exists())
        self.assertFalse((self.mods_dir / "addon.jar.disabled").exists())
        self.assertIn("ERROR missing default config baseline config/missing.toml", output.getvalue())

    def test_apply_clears_pending_profile_and_selection(self) -> None:
        self.write_profile("balanced")
        profile = json.loads((self.mod_config_dir / "presets" / "balanced.json").read_text(encoding="utf-8"))
        pending = {
            "schemaVersion": 1,
            "reason": "test",
            "sourceWorldId": "",
            "queuedAt": "2026-01-01T00:00:00Z",
            "profile": profile,
        }
        self.paths.pending_profile.write_text(json.dumps(pending, indent=2) + "\n", encoding="utf-8")
        self.paths.pending_selection.write_text(json.dumps({"activeProfileId": "balanced"}, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()):
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=False,
                world_id="",
            ))

        self.assertEqual(result, 0)
        self.assertFalse(self.paths.pending_profile.exists())
        self.assertFalse(self.paths.pending_selection.exists())
        self.assertTrue(self.paths.applied_profile.exists())

    def test_enabled_mod_keeps_required_dependency_enabled(self) -> None:
        self.write_neoforge_mod_jar("addon.jar", "addon", ["library"])
        self.write_neoforge_mod_jar("library.jar.disabled", "library")
        pending = {
            "schemaVersion": 1,
            "reason": "test",
            "sourceWorldId": "",
            "queuedAt": "2026-01-01T00:00:00Z",
            "profile": {
                "schemaVersion": 1,
                "id": "balanced",
                "displayName": "Balanced",
                "sortOrder": 10,
                "description": "test",
                "mods": {
                    "addon": {"enabled": True, "locked": False, "reason": ""},
                    "library": {"enabled": False, "locked": False, "reason": ""},
                },
                "configFiles": [],
                "options": {},
            },
        }
        self.paths.pending_profile.write_text(json.dumps(pending, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=True,
                world_id="",
            ))

        self.assertEqual(result, 0)
        self.assertTrue((self.mods_dir / "library.jar").exists())
        self.assertFalse((self.mods_dir / "library.jar.disabled").exists())
        self.assertIn("require library because addon requires it", output.getvalue())
        self.assertIn("DEPENDENCY require library because addon requires it", output.getvalue())
        self.assertIn("WARNING profile disables library, but it will stay enabled because addon requires it", output.getvalue())

    def test_applied_profile_records_required_dependency_resolution(self) -> None:
        self.write_neoforge_mod_jar("addon.jar", "addon", ["library"])
        self.write_neoforge_mod_jar("library.jar.disabled", "library")
        pending = {
            "schemaVersion": 1,
            "reason": "test",
            "sourceWorldId": "",
            "queuedAt": "2026-01-01T00:00:00Z",
            "profile": {
                "schemaVersion": 1,
                "id": "balanced",
                "displayName": "Balanced",
                "sortOrder": 10,
                "description": "test",
                "mods": {
                    "addon": {"enabled": True, "locked": False, "reason": ""},
                    "library": {"enabled": False, "locked": False, "reason": ""},
                },
                "configFiles": [],
                "options": {},
            },
        }
        self.paths.pending_profile.write_text(json.dumps(pending, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()):
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=False,
                world_id="",
            ))

        self.assertEqual(result, 0)
        applied = json.loads(self.paths.applied_profile.read_text(encoding="utf-8"))
        self.assertTrue(applied["profile"]["mods"]["library"]["enabled"])
        self.assertTrue(applied["profile"]["mods"]["library"]["locked"])
        self.assertIn("Required because addon requires it.", applied["profile"]["mods"]["library"]["reason"])

    def test_locked_disabled_dependency_blocks_apply_before_mutating(self) -> None:
        self.write_neoforge_mod_jar("addon.jar", "addon", ["ecology"])
        self.write_neoforge_mod_jar("ecology.jar.disabled", "ecology")
        pending = {
            "schemaVersion": 1,
            "reason": "test",
            "sourceWorldId": "",
            "queuedAt": "2026-01-01T00:00:00Z",
            "profile": {
                "schemaVersion": 1,
                "id": "balanced",
                "displayName": "Balanced",
                "sortOrder": 10,
                "description": "test",
                "mods": {
                    "addon": {"enabled": True, "locked": False, "reason": ""},
                    "ecology": {"enabled": False, "locked": True, "reason": "disabled for performance"},
                },
                "configFiles": [],
                "options": {},
            },
        }
        self.paths.pending_profile.write_text(json.dumps(pending, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=True,
                world_id="",
            ))

        self.assertEqual(result, 1)
        self.assertTrue((self.mods_dir / "addon.jar").exists())
        self.assertFalse((self.mods_dir / "addon.jar.disabled").exists())
        self.assertFalse((self.mods_dir / "ecology.jar").exists())
        self.assertTrue((self.mods_dir / "ecology.jar.disabled").exists())
        self.assertTrue(self.paths.pending_profile.exists())
        self.assertFalse(self.paths.applied_profile.exists())
        self.assertIn("ERROR profile locks ecology disabled, but addon requires it", output.getvalue())

    def test_validate_profile_reports_locked_disabled_dependency(self) -> None:
        self.write_neoforge_mod_jar("addon.jar", "addon", ["ecology"])
        self.write_neoforge_mod_jar("ecology.jar.disabled", "ecology")
        profile_path = self.write_profile("balanced")
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
        profile["mods"] = {
            "addon": {"enabled": True, "locked": False, "reason": ""},
            "ecology": {"enabled": False, "locked": True, "reason": "disabled for performance"},
        }
        profile_path.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = mqp.validate_profile(argparse.Namespace(
                instance_root=str(self.root),
                profile_id="balanced",
            ))

        self.assertEqual(result, 1)
        self.assertIn("ERROR profile locks ecology disabled, but addon requires it", output.getvalue())

    def test_validate_profile_warns_when_disabled_mod_id_shares_enabled_jar(self) -> None:
        self.write_multi_id_neoforge_mod_jar("letsdo-api.jar", ["doapi", "terraform"])
        profile_path = self.write_profile("balanced")
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
        profile["mods"] = {
            "doapi": {"enabled": False, "locked": False, "reason": ""},
            "terraform": {"enabled": True, "locked": False, "reason": ""},
        }
        profile_path.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = mqp.validate_profile(argparse.Namespace(
                instance_root=str(self.root),
                profile_id="balanced",
            ))

        self.assertEqual(result, 0)
        self.assertIn("WARNING profile disables doapi, but its jar will stay enabled because the same jar also provides enabled mod id terraform", output.getvalue())

    def test_locked_disabled_mod_id_sharing_enabled_jar_blocks_apply(self) -> None:
        self.write_multi_id_neoforge_mod_jar("letsdo-api.jar.disabled", ["doapi", "terraform"])
        pending = {
            "schemaVersion": 1,
            "reason": "test",
            "sourceWorldId": "",
            "queuedAt": "2026-01-01T00:00:00Z",
            "profile": {
                "schemaVersion": 1,
                "id": "balanced",
                "displayName": "Balanced",
                "sortOrder": 10,
                "description": "test",
                "mods": {
                    "doapi": {"enabled": False, "locked": True, "reason": "disabled for testing"},
                    "terraform": {"enabled": True, "locked": False, "reason": ""},
                },
                "configFiles": [],
                "options": {},
            },
        }
        self.paths.pending_profile.write_text(json.dumps(pending, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=True,
                world_id="",
            ))

        self.assertEqual(result, 1)
        self.assertFalse((self.mods_dir / "letsdo-api.jar").exists())
        self.assertTrue((self.mods_dir / "letsdo-api.jar.disabled").exists())
        self.assertIn("ERROR profile locks doapi disabled, but the same jar also provides enabled mod id terraform", output.getvalue())

    def test_validate_profile_suggests_closest_discovered_mod_for_stale_id(self) -> None:
        self.write_neoforge_mod_jar("farmers-cutting-regions-unexplored-1.21.1-1.1b-neoforge.jar", "mr_farmers_cuttingregionsunexplored")
        profile_path = self.write_profile("balanced")
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
        profile["mods"] = {
            "farmers_cutting_regions_unexplored_1_21_1_1_1b_neoforge": {"enabled": True, "locked": False, "reason": ""},
        }
        profile_path.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = mqp.validate_profile(argparse.Namespace(
                instance_root=str(self.root),
                profile_id="balanced",
            ))

        self.assertEqual(result, 0)
        self.assertIn("closest discovered: mr_farmers_cuttingregionsunexplored (farmers-cutting-regions-unexplored-1.21.1-1.1b-neoforge.jar)", output.getvalue())

    def test_modqualitypicker_stays_enabled_even_when_absent_from_profile(self) -> None:
        self.write_neoforge_mod_jar("modqualitypicker.jar", mqp.MOD_ID)
        self.write_game_file(f"config/{mqp.MOD_ID}-common.toml", 'activeProfileId = "balanced"\n')
        self.write_profile("balanced")

        with contextlib.redirect_stdout(io.StringIO()):
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=False,
                world_id="",
            ))

        self.assertEqual(result, 0)
        self.assertTrue((self.mods_dir / "modqualitypicker.jar").exists())
        self.assertFalse((self.mods_dir / "modqualitypicker.jar.disabled").exists())

    def test_untyped_neoforge_dependencies_are_required(self) -> None:
        addon = self.mods_dir / "addon.jar"
        with zipfile.ZipFile(addon, "w") as jar:
            jar.writestr("META-INF/neoforge.mods.toml", "\n".join([
                "[[mods]]",
                'modId = "addon"',
                'version = "1.0.0"',
                'displayName = "addon"',
                "",
                "[[dependencies.addon]]",
                'modId = "connector"',
                "",
                "[[dependencies.addon]]",
                'modId = "fabric_api"',
                "",
                "[[dependencies.addon]]",
                'modId = "optional_library"',
                'type = "optional"',
                "",
            ]))
        self.write_neoforge_mod_jar("connector.jar.disabled", "connector")
        self.write_neoforge_mod_jar("fabric-api.jar.disabled", "fabric_api")
        self.write_neoforge_mod_jar("optional-library.jar.disabled", "optional_library")
        pending = {
            "schemaVersion": 1,
            "reason": "test",
            "sourceWorldId": "",
            "queuedAt": "2026-01-01T00:00:00Z",
            "profile": {
                "schemaVersion": 1,
                "id": "balanced",
                "displayName": "Balanced",
                "sortOrder": 10,
                "description": "test",
                "mods": {
                    "addon": {"enabled": True, "locked": False, "reason": ""},
                    "connector": {"enabled": False, "locked": False, "reason": ""},
                    "fabric_api": {"enabled": False, "locked": False, "reason": ""},
                    "optional_library": {"enabled": False, "locked": False, "reason": ""},
                },
                "configFiles": [],
                "options": {},
            },
        }
        self.paths.pending_profile.write_text(json.dumps(pending, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=True,
                world_id="",
            ))

        self.assertEqual(result, 0)
        self.assertTrue((self.mods_dir / "connector.jar").exists())
        self.assertTrue((self.mods_dir / "fabric-api.jar").exists())
        self.assertFalse((self.mods_dir / "optional-library.jar").exists())
        self.assertIn("require connector because addon requires it", output.getvalue())
        self.assertIn("require fabric_api because addon requires it", output.getvalue())
        self.assertNotIn("require optional_library", output.getvalue())

    def test_nested_jar_provider_satisfies_required_dependency(self) -> None:
        self.write_neoforge_mod_jar("addon.jar", "addon", ["library"])
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as nested_jar:
            nested_jar.writestr("META-INF/neoforge.mods.toml", self.neoforge_metadata("library"))
        with zipfile.ZipFile(self.mods_dir / "provider.jar.disabled", "w") as provider:
            provider.writestr("META-INF/neoforge.mods.toml", self.neoforge_metadata("provider"))
            provider.writestr("META-INF/jarjar/library.jar", nested.getvalue())

        pending = {
            "schemaVersion": 1,
            "reason": "test",
            "sourceWorldId": "",
            "queuedAt": "2026-01-01T00:00:00Z",
            "profile": {
                "schemaVersion": 1,
                "id": "balanced",
                "displayName": "Balanced",
                "sortOrder": 10,
                "description": "test",
                "mods": {
                    "addon": {"enabled": True, "locked": False, "reason": ""},
                    "library": {"enabled": False, "locked": False, "reason": ""},
                    "provider": {"enabled": False, "locked": False, "reason": ""},
                },
                "configFiles": [],
                "options": {},
            },
        }
        self.paths.pending_profile.write_text(json.dumps(pending, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=False,
                keep_pending=True,
                world_id="",
            ))

        self.assertEqual(result, 0)
        self.assertTrue((self.mods_dir / "provider.jar").exists())
        self.assertIn("require library because addon requires it", output.getvalue())

    def test_fabric_api_provider_enables_captured_module_ids(self) -> None:
        desired = {
            "fabric_api": True,
            "fabric_api_base": False,
            "fabric_renderer_indigo": False,
            "forgified_fabric_api": False,
            "unrelated": False,
        }

        mqp.enable_provided_runtime_modules(desired)

        self.assertTrue(desired["fabric_api_base"])
        self.assertTrue(desired["fabric_renderer_indigo"])
        self.assertTrue(desired["forgified_fabric_api"])
        self.assertFalse(desired["unrelated"])

    def test_neoforge_jar_ignores_fabric_only_dependency_metadata(self) -> None:
        addon = self.write_neoforge_mod_jar("addon.jar", "addon")
        with zipfile.ZipFile(addon, "a") as jar:
            jar.writestr("fabric.mod.json", json.dumps({
                "id": "addon",
                "depends": {
                    "fabric-api": "*",
                },
            }))
        self.write_neoforge_mod_jar("fabric-api.jar.disabled", "fabric_api")
        pending = {
            "schemaVersion": 1,
            "reason": "test",
            "sourceWorldId": "",
            "queuedAt": "2026-01-01T00:00:00Z",
            "profile": {
                "schemaVersion": 1,
                "id": "balanced",
                "displayName": "Balanced",
                "sortOrder": 10,
                "description": "test",
                "mods": {
                    "addon": {"enabled": True, "locked": False, "reason": ""},
                    "fabric_api": {"enabled": False, "locked": False, "reason": ""},
                },
                "configFiles": [],
                "options": {},
            },
        }
        self.paths.pending_profile.write_text(json.dumps(pending, indent=2) + "\n", encoding="utf-8")

        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = mqp.apply_pending(argparse.Namespace(
                instance_root=str(self.root),
                dry_run=True,
                keep_pending=True,
                world_id="",
            ))

        self.assertEqual(result, 0)
        self.assertNotIn("require fabric_api", output.getvalue())


if __name__ == "__main__":
    unittest.main()
