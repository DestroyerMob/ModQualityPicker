#!/usr/bin/env python3
"""Prism Launcher helper for Mod Quality Picker pending profiles."""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import tomllib
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


MOD_ID = "modqualitypicker"


@dataclass(frozen=True)
class InstancePaths:
    root: Path
    game_dir: Path
    mods_dir: Path
    config_dir: Path
    mod_config_dir: Path

    @classmethod
    def from_root(cls, root: Path) -> "InstancePaths":
        root = root.resolve()
        game_dir = root / "minecraft"
        if not game_dir.exists():
            game_dir = root
        config_dir = game_dir / "config"
        return cls(
            root=root,
            game_dir=game_dir,
            mods_dir=game_dir / "mods",
            config_dir=config_dir,
            mod_config_dir=config_dir / MOD_ID,
        )

    @property
    def pending_profile(self) -> Path:
        return self.mod_config_dir / "pending-profile.json"

    @property
    def applied_profile(self) -> Path:
        return self.mod_config_dir / "applied-profile.json"


def load_pending_profile(paths: InstancePaths) -> dict:
    if not paths.pending_profile.exists():
        raise SystemExit(f"No pending profile found at {paths.pending_profile}")
    return json.loads(paths.pending_profile.read_text(encoding="utf-8"))


def profile_from_pending(pending: dict) -> dict:
    profile = pending.get("profile")
    if not isinstance(profile, dict):
        raise SystemExit("pending-profile.json does not contain a profile object")
    return profile


def discover_mod_jars(mods_dir: Path) -> dict[str, Path]:
    discovered: dict[str, Path] = {}
    for path in sorted(mods_dir.glob("*.jar")) + sorted(mods_dir.glob("*.jar.disabled")):
        mod_ids = read_mod_ids(path)
        for mod_id in mod_ids:
            discovered.setdefault(mod_id, path)
    return discovered


def read_mod_ids(path: Path) -> list[str]:
    try:
        with zipfile.ZipFile(path) as jar:
            with jar.open("META-INF/neoforge.mods.toml") as metadata:
                parsed = tomllib.loads(metadata.read().decode("utf-8"))
    except (KeyError, OSError, tomllib.TOMLDecodeError, zipfile.BadZipFile):
        return [fallback_mod_id(path)]

    mods = parsed.get("mods", [])
    if isinstance(mods, list):
        ids = [item.get("modId", "") for item in mods if isinstance(item, dict)]
        ids = [item for item in ids if item]
        if ids:
            return ids
    return [fallback_mod_id(path)]


def fallback_mod_id(path: Path) -> str:
    name = path.name.removesuffix(".disabled").removesuffix(".jar")
    return re.sub(r"[^a-z0-9_]+", "_", name.lower()).strip("_")


def apply_mod_states(paths: InstancePaths, profile: dict, dry_run: bool) -> list[str]:
    mods = profile.get("mods", {})
    if not isinstance(mods, dict):
        return []

    discovered = discover_mod_jars(paths.mods_dir)
    actions: list[str] = []
    for mod_id, state in sorted(mods.items()):
        if mod_id == MOD_ID:
            continue
        if not isinstance(state, dict):
            continue

        enabled = bool(state.get("enabled", True))
        jar = discovered.get(mod_id)
        if jar is None:
            actions.append(f"missing {mod_id}")
            continue

        if enabled and jar.name.endswith(".jar.disabled"):
            target = jar.with_name(jar.name.removesuffix(".disabled"))
            actions.append(f"enable {jar.name} -> {target.name}")
            if not dry_run:
                jar.rename(target)
        elif not enabled and jar.name.endswith(".jar"):
            target = jar.with_name(jar.name + ".disabled")
            actions.append(f"disable {jar.name} -> {target.name}")
            if not dry_run:
                jar.rename(target)
    return actions


def apply_config_files(paths: InstancePaths, profile: dict, dry_run: bool) -> list[str]:
    actions: list[str] = []
    for item in profile.get("configFiles", []):
        if not isinstance(item, dict):
            continue
        relative_path = item.get("path", "")
        mode = item.get("mode", "REPLACE_FILE")
        preset_file = item.get("presetFile", "") or relative_path
        if not relative_path or mode == "KEEP_PLAYER":
            continue

        target = resolve_inside(paths.game_dir, relative_path)
        source = resolve_inside(paths.mod_config_dir, preset_file)
        if not source.exists():
            actions.append(f"missing config preset {preset_file}")
            continue

        actions.append(f"{mode.lower()} {relative_path}")
        if dry_run:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        if mode == "MERGE_TOML":
            merge_toml_overlay(target, source)
        else:
            shutil.copy2(source, target)
    return actions


def resolve_inside(root: Path, relative: str) -> Path:
    relative_path = Path(relative)
    if relative_path.is_absolute():
        raise SystemExit(f"Profile path must be relative: {relative}")
    resolved = (root / relative_path).resolve()
    root = root.resolve()
    if not resolved.is_relative_to(root):
        raise SystemExit(f"Profile path escapes its root: {relative}")
    return resolved


SECTION = re.compile(r"^\s*\[([^\[\]]+)]\s*(?:#.*)?$")
KEY_VALUE = re.compile(r"^\s*([A-Za-z0-9_.-]+)\s*=\s*(.+)$")


def merge_toml_overlay(target: Path, overlay: Path) -> None:
    target_lines = target.read_text(encoding="utf-8").splitlines() if target.exists() else []
    overlay_entries = collect_toml_entries(overlay.read_text(encoding="utf-8").splitlines())

    for (section, key), line in overlay_entries.items():
        start = find_section_start(target_lines, section)
        if start < 0:
            if target_lines and target_lines[-1].strip():
                target_lines.append("")
            if section:
                target_lines.append(f"[{section}]")
            target_lines.append(line)
            continue

        end = find_section_end(target_lines, start)
        key_index = find_key(target_lines, key, start, end)
        if key_index >= 0:
            target_lines[key_index] = line
        else:
            target_lines.insert(end, line)

    target.write_text("\n".join(target_lines) + "\n", encoding="utf-8")


def collect_toml_entries(lines: Iterable[str]) -> dict[tuple[str, str], str]:
    entries: dict[tuple[str, str], str] = {}
    section = ""
    for line in lines:
        section_match = SECTION.match(line)
        if section_match:
            section = section_match.group(1).strip()
            continue
        key_match = KEY_VALUE.match(line)
        if key_match:
            entries[(section, key_match.group(1))] = line
    return entries


def find_section_start(lines: list[str], section: str) -> int:
    if not section:
        return 0
    for index, line in enumerate(lines):
        match = SECTION.match(line)
        if match and match.group(1).strip() == section:
            return index + 1
    return -1


def find_section_end(lines: list[str], start: int) -> int:
    for index in range(start, len(lines)):
        if SECTION.match(lines[index]):
            return index
    return len(lines)


def find_key(lines: list[str], key: str, start: int, end: int) -> int:
    for index in range(start, end):
        match = KEY_VALUE.match(lines[index])
        if match and match.group(1) == key:
            return index
    return -1


def set_active_profile(paths: InstancePaths, profile: dict, dry_run: bool) -> list[str]:
    profile_id = profile.get("id", "balanced")
    config_file = paths.config_dir / f"{MOD_ID}-common.toml"
    action = f"set activeProfileId = {profile_id}"
    if dry_run:
        return [action]
    if config_file.exists():
        text = config_file.read_text(encoding="utf-8")
        text = re.sub(r'activeProfileId\s*=\s*"[^"]*"', f'activeProfileId = "{profile_id}"', text)
        config_file.write_text(text, encoding="utf-8")
    return [action]


def apply_pending(args: argparse.Namespace) -> int:
    paths = InstancePaths.from_root(Path(args.instance_root))
    pending = load_pending_profile(paths)
    profile = profile_from_pending(pending)
    actions = []
    actions.extend(apply_mod_states(paths, profile, args.dry_run))
    actions.extend(apply_config_files(paths, profile, args.dry_run))
    actions.extend(set_active_profile(paths, profile, args.dry_run))

    for action in actions:
        print(action)

    if not args.dry_run and not args.keep_pending:
        paths.mod_config_dir.mkdir(parents=True, exist_ok=True)
        paths.applied_profile.write_text(json.dumps(pending, indent=2) + "\n", encoding="utf-8")
        paths.pending_profile.unlink(missing_ok=True)
    return 0


def export_presets(args: argparse.Namespace) -> int:
    paths = InstancePaths.from_root(Path(args.instance_root))
    source = paths.mod_config_dir / "presets"
    destination = Path(args.pack_root).resolve() / "config" / MOD_ID / "presets"
    if not source.exists():
        destination.mkdir(parents=True, exist_ok=True)
        print(f"created {destination}")
        return 0
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(source, destination)
    print(f"exported {source} -> {destination}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Apply Mod Quality Picker profiles to a Prism instance before launch.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    apply = subparsers.add_parser("apply", help="Apply config/mod changes from pending-profile.json.")
    apply.add_argument("--instance-root", default=".", help="Prism instance root or game directory.")
    apply.add_argument("--dry-run", action="store_true")
    apply.add_argument("--keep-pending", action="store_true")
    apply.set_defaults(func=apply_pending)

    export = subparsers.add_parser("export-presets", help="Copy in-instance presets into a pack root.")
    export.add_argument("--instance-root", default=".", help="Prism instance root or game directory.")
    export.add_argument("--pack-root", default="pack", help="Pack root to receive config/modqualitypicker/presets.")
    export.set_defaults(func=export_presets)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
