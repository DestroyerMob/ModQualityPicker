#!/usr/bin/env python3
"""Prism Launcher helper for Mod Quality Picker pending profiles."""

from __future__ import annotations

import argparse
import copy
import difflib
import hashlib
import io
import json
import re
import shutil
import sys
import tomllib
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


MOD_ID = "modqualitypicker"
DEFAULTS_ROOT = "defaults"
DEFAULTS_MANIFEST = "defaults-manifest.json"
DIFF_EXTENSION = ".diff"
WORLD_DIFFS_ROOT = "config-diffs"
WORLD_DIFFS_MANIFEST = "config-diffs.json"
IGNORED_REQUIRED_MOD_IDS = {
    "fabricloader",
    "forge",
    "java",
    "minecraft",
    "neoforge",
}
SUPPORTED_CONFIG_SUFFIXES = (
    ".toml",
    ".json",
    ".json5",
    ".cfg",
    ".properties",
    ".snbt",
    ".yaml",
    ".yml",
    ".ini",
    ".txt",
)


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
    def pending_selection(self) -> Path:
        return self.mod_config_dir / "pending-selection.json"

    @property
    def applied_profile(self) -> Path:
        return self.mod_config_dir / "applied-profile.json"

    @property
    def default_manifest(self) -> Path:
        return self.mod_config_dir / DEFAULTS_MANIFEST

    def world_dir(self, world_id: str) -> Path:
        return self.game_dir / "saves" / world_id

    def world_mod_dir(self, world_id: str) -> Path:
        return self.world_dir(world_id) / MOD_ID

    def world_diff_manifest(self, world_id: str) -> Path:
        return self.world_mod_dir(world_id) / WORLD_DIFFS_MANIFEST


@dataclass(frozen=True)
class ModMetadata:
    mod_ids: tuple[str, ...]
    required_mod_ids: tuple[str, ...]


@dataclass(frozen=True)
class ModJar:
    path: Path
    mod_ids: tuple[str, ...]
    required_mod_ids: tuple[str, ...]


@dataclass(frozen=True)
class ModPlan:
    discovered: dict[str, ModJar]
    desired_by_mod: dict[str, bool]
    dependency_actions: tuple[str, ...]
    errors: tuple[str, ...]
    warnings: tuple[str, ...]


def load_pending_profile(paths: InstancePaths) -> dict | None:
    if not paths.pending_profile.exists():
        return None
    return json.loads(paths.pending_profile.read_text(encoding="utf-8-sig"))


def profile_from_pending(pending: dict) -> dict:
    profile = pending.get("profile")
    if not isinstance(profile, dict):
        raise SystemExit("pending-profile.json does not contain a profile object")
    return profile


def profile_id_from_pending(pending: dict) -> str:
    profile = pending.get("profile", {})
    if isinstance(profile, dict):
        profile_id = profile.get("id", "")
        if isinstance(profile_id, str) and profile_id:
            return profile_id

    profile_id = pending.get("activeProfileId", "")
    if isinstance(profile_id, str) and profile_id:
        return profile_id
    return "balanced"


def active_profile_id(paths: InstancePaths) -> str:
    config_file = paths.config_dir / f"{MOD_ID}-common.toml"
    if not config_file.exists():
        return "balanced"

    text = config_file.read_text(encoding="utf-8-sig")
    try:
        parsed = tomllib.loads(text)
        profile_id = parsed.get("activeProfileId", "balanced")
        return profile_id if isinstance(profile_id, str) and profile_id else "balanced"
    except tomllib.TOMLDecodeError:
        match = re.search(r'activeProfileId\s*=\s*"([^"]+)"', text)
        return match.group(1) if match else "balanced"


def resolve_profile_for_apply(paths: InstancePaths, pending: dict | None) -> tuple[dict | None, dict | None]:
    if pending is None:
        profile_id = active_profile_id(paths)
        try:
            _, profile = load_profile(paths, profile_id)
        except SystemExit:
            return None, None
        return profile, pending_record(profile_with_required_dependencies(paths, profile), "active-profile", "")

    profile_id = profile_id_from_pending(pending)
    try:
        _, profile = load_profile(paths, profile_id)
    except SystemExit:
        profile = profile_from_pending(pending)

    return profile, pending_record(profile_with_required_dependencies(paths, profile), pending.get("reason", "queued-profile"), pending.get("sourceWorldId", ""))


def pending_record(profile: dict, reason: str, source_world_id: str) -> dict:
    return {
        "schemaVersion": 1,
        "reason": reason if isinstance(reason, str) and reason else "active-profile",
        "sourceWorldId": source_world_id if isinstance(source_world_id, str) else "",
        "queuedAt": utc_now(),
        "profile": profile,
    }


def discover_mod_jars(mods_dir: Path) -> dict[str, ModJar]:
    discovered: dict[str, ModJar] = {}
    for path in sorted(mods_dir.glob("*.jar")) + sorted(mods_dir.glob("*.jar.disabled")):
        metadata = read_mod_metadata(path)
        mod_ids = metadata.mod_ids or (fallback_mod_id(path),)
        jar = ModJar(path, mod_ids, metadata.required_mod_ids)
        for mod_id in mod_ids:
            discovered.setdefault(mod_id, jar)
    return discovered


def read_mod_ids(path: Path) -> list[str]:
    return list(read_mod_metadata(path).mod_ids)


def read_mod_metadata(path: Path) -> ModMetadata:
    try:
        with zipfile.ZipFile(path) as jar:
            metadata = read_archive_mod_metadata(jar)
    except (OSError, tomllib.TOMLDecodeError, zipfile.BadZipFile, json.JSONDecodeError):
        return ModMetadata((fallback_mod_id(path),), ())

    return metadata if metadata.mod_ids else ModMetadata((fallback_mod_id(path),), ())


def read_archive_mod_ids(jar: zipfile.ZipFile, depth: int = 0) -> list[str]:
    return list(read_archive_mod_metadata(jar, depth).mod_ids)


def read_archive_mod_metadata(jar: zipfile.ZipFile, depth: int = 0) -> ModMetadata:
    ids: list[str] = []
    fabric_ids: list[str] = []
    required: list[str] = []
    fabric_required: list[str] = []
    nested_mod_ids: list[str] = []
    nested_required: list[str] = []
    names = set(jar.namelist())
    if "META-INF/neoforge.mods.toml" in names:
        with jar.open("META-INF/neoforge.mods.toml") as metadata:
            parsed = tomllib.loads(metadata.read().decode("utf-8"))
            ids.extend(read_neoforge_mod_ids(parsed))
            required.extend(read_neoforge_required_mod_ids(parsed))

    if not ids and "fabric.mod.json" in names:
        with jar.open("fabric.mod.json") as metadata:
            parsed = json.loads(metadata.read().decode("utf-8"))
            fabric_ids.extend(fabric_id_aliases(parsed))
            fabric_required.extend(read_fabric_required_mod_ids(parsed))

    if depth < 3:
        for name in names:
            if not name.endswith(".jar"):
                continue
            with jar.open(name) as nested:
                try:
                    with zipfile.ZipFile(io.BytesIO(nested.read())) as nested_jar:
                        nested_metadata = read_archive_mod_metadata(nested_jar, depth + 1)
                        nested_mod_ids.extend(nested_metadata.mod_ids)
                        nested_required.extend(nested_metadata.required_mod_ids)
                except (OSError, tomllib.TOMLDecodeError, zipfile.BadZipFile, json.JSONDecodeError):
                    continue

    if not ids:
        ids.extend(fabric_ids)
        required.extend(fabric_required)
    ids.extend(nested_mod_ids)
    required.extend(nested_required)
    return ModMetadata(tuple(unique(ids)), tuple(unique_required(required)))


def read_neoforge_mod_ids(parsed: dict) -> list[str]:
    mods = parsed.get("mods", [])
    if not isinstance(mods, list):
        return []
    return [item.get("modId", "") for item in mods if isinstance(item, dict) and item.get("modId", "")]


def read_neoforge_required_mod_ids(parsed: dict) -> list[str]:
    dependencies = parsed.get("dependencies", {})
    if not isinstance(dependencies, dict):
        return []

    required: list[str] = []
    for dependency_list in dependencies.values():
        if not isinstance(dependency_list, list):
            continue
        for dependency in dependency_list:
            if not isinstance(dependency, dict):
                continue
            mod_id = dependency.get("modId", "")
            if not isinstance(mod_id, str) or not mod_id:
                continue

            dependency_type = str(dependency.get("type", "")).lower()
            mandatory = dependency.get("mandatory")
            if mandatory is False or dependency_type in {"optional", "incompatible", "discouraged"}:
                continue
            if dependency_type in {"", "required", "mandatory"} or mandatory is True:
                required.extend(mod_id_aliases(mod_id))
    return required


def fabric_id_aliases(parsed: dict) -> list[str]:
    mod_id = parsed.get("id", "")
    if not isinstance(mod_id, str) or not mod_id:
        return []
    return mod_id_aliases(mod_id)


def read_fabric_required_mod_ids(parsed: dict) -> list[str]:
    depends = parsed.get("depends", {})
    if not isinstance(depends, dict):
        return []

    required: list[str] = []
    for mod_id in depends:
        if isinstance(mod_id, str):
            required.extend(mod_id_aliases(mod_id))
    return required


def mod_id_aliases(mod_id: str) -> list[str]:
    normalized = mod_id.replace("-", "_")
    if normalized == mod_id:
        return [mod_id]
    return [mod_id, normalized]


def unique(values: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        if value and value not in seen:
            seen.add(value)
            result.append(value)
    return result


def unique_required(values: Iterable[str]) -> list[str]:
    return [value for value in unique(values) if value not in IGNORED_REQUIRED_MOD_IDS]


def fallback_mod_id(path: Path) -> str:
    name = path.name.removesuffix(".disabled").removesuffix(".jar")
    return re.sub(r"[^a-z0-9_]+", "_", name.lower()).strip("_")


def resolve_mod_plan(paths: InstancePaths, profile: dict) -> ModPlan:
    discovered = discover_mod_jars(paths.mods_dir)
    desired_by_mod: dict[str, bool] = {
        mod_id: False
        for mod_id in discovered
    }
    locked_disabled_mod_ids: set[str] = set()
    warnings: list[str] = []

    mods = profile.get("mods", {})
    if not isinstance(mods, dict):
        warnings.append("profile mods is not an object; discovered mods default to disabled")
        mods = {}

    for mod_id, state in sorted(mods.items()):
        if not isinstance(state, dict):
            warnings.append(f"profile entry {mod_id} is not an object")
            continue

        enabled = True if mod_id == MOD_ID else bool(state.get("enabled", True))
        desired_by_mod[mod_id] = enabled
        if not enabled and bool(state.get("locked", False)):
            locked_disabled_mod_ids.add(mod_id)
        if enabled and mod_id not in discovered and mod_id != MOD_ID and mod_id not in IGNORED_REQUIRED_MOD_IDS:
            warnings.append(missing_mod_warning(mod_id, discovered))

    desired_by_mod[MOD_ID] = True

    actions = enable_required_dependencies(discovered, desired_by_mod, locked_disabled_mod_ids)
    enable_provided_runtime_modules(desired_by_mod)
    errors = locked_dependency_errors(discovered, desired_by_mod, locked_disabled_mod_ids)
    errors.extend(locked_bundled_mod_errors(discovered, desired_by_mod, locked_disabled_mod_ids))
    warnings.extend(disabled_dependency_warnings(discovered, desired_by_mod, mods))
    return ModPlan(discovered, desired_by_mod, tuple(actions), tuple(errors), tuple(warnings))


def disabled_dependency_warnings(
    discovered: dict[str, ModJar],
    desired_by_mod: dict[str, bool],
    profile_mods: dict,
) -> list[str]:
    warnings: list[str] = []
    for mod_id, state in sorted(profile_mods.items()):
        if not isinstance(state, dict):
            continue
        if bool(state.get("enabled", True)) or bool(state.get("locked", False)):
            continue
        if mod_id not in discovered:
            continue

        if desired_by_mod.get(mod_id) is True:
            dependents = enabled_dependents(discovered, desired_by_mod, mod_id)
            if dependents:
                warnings.append(disabled_dependency_warning(mod_id, dependents))
        siblings = enabled_sibling_mod_ids(discovered, desired_by_mod, mod_id)
        if siblings:
            warnings.append(disabled_bundled_mod_warning(mod_id, siblings))
    return warnings


def enabled_dependents(discovered: dict[str, ModJar], desired_by_mod: dict[str, bool], required_mod_id: str) -> list[str]:
    dependents: list[str] = []
    for mod_id, enabled in sorted(desired_by_mod.items()):
        if enabled is not True or mod_id == required_mod_id:
            continue
        jar = discovered.get(mod_id)
        if jar is not None and required_mod_id in jar.required_mod_ids:
            dependents.append(mod_id)
    return dependents


def enabled_sibling_mod_ids(discovered: dict[str, ModJar], desired_by_mod: dict[str, bool], mod_id: str) -> list[str]:
    jar = discovered.get(mod_id)
    if jar is None:
        return []
    return sorted(
        sibling_id
        for sibling_id in jar.mod_ids
        if sibling_id != mod_id and desired_by_mod.get(sibling_id) is True
    )


def disabled_dependency_warning(mod_id: str, dependents: list[str]) -> str:
    requiring = ", ".join(dependents)
    verb = "requires" if len(dependents) == 1 else "require"
    return f"profile disables {mod_id}, but it will stay enabled because {requiring} {verb} it"


def disabled_bundled_mod_warning(mod_id: str, siblings: list[str]) -> str:
    label = "enabled mod id " if len(siblings) == 1 else "enabled mod ids "
    return f"profile disables {mod_id}, but its jar will stay enabled because the same jar also provides {label}{', '.join(siblings)}"


def missing_mod_warning(mod_id: str, discovered: dict[str, ModJar]) -> str:
    suggestions = related_mod_suggestions(mod_id, discovered)
    if suggestions:
        return f"profile enables {mod_id}, but no matching jar is installed; closest discovered: {', '.join(suggestions)}"
    return f"profile enables {mod_id}, but no matching jar is installed"


def related_mod_suggestions(mod_id: str, discovered: dict[str, ModJar]) -> list[str]:
    query = compact_identity(mod_id)
    query_tokens = identity_tokens(mod_id)
    scored: list[tuple[int, str]] = []
    seen: set[str] = set()
    for discovered_id, jar in sorted(discovered.items()):
        suggestion = f"{discovered_id} ({jar.path.name})"
        if suggestion in seen:
            continue
        seen.add(suggestion)

        score = relation_score(query, query_tokens, discovered_id, jar.path.name)
        if score > 0:
            scored.append((score, suggestion))

    scored.sort(key=lambda item: (-item[0], item[1]))
    return [suggestion for _, suggestion in scored[:3]]


def relation_score(query: str, query_tokens: set[str], *values: str) -> int:
    score = 0
    for value in values:
        compact = compact_identity(value)
        if compact and (compact in query or query in compact):
            score += 8
        value_tokens = identity_tokens(value)
        score += len(query_tokens & value_tokens) * 2
    return score


def compact_identity(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", value.lower())


def identity_tokens(value: str) -> set[str]:
    ignored = {"jar", "disabled", "forge", "neoforge", "mc", "mod", "mr"}
    tokens = re.split(r"[^a-z0-9]+", value.lower())
    return {token for token in tokens if len(token) > 1 and not token.isdigit() and token not in ignored}


def locked_dependency_errors(
    discovered: dict[str, ModJar],
    desired_by_mod: dict[str, bool],
    locked_disabled_mod_ids: set[str],
) -> list[str]:
    errors: list[str] = []
    seen: set[str] = set()
    for mod_id, enabled in sorted(desired_by_mod.items()):
        if enabled is not True:
            continue

        jar = discovered.get(mod_id)
        if jar is None:
            continue

        for required_mod_id in jar.required_mod_ids:
            if required_mod_id not in locked_disabled_mod_ids or required_mod_id not in discovered:
                continue
            message = f"profile locks {required_mod_id} disabled, but {mod_id} requires it"
            if message not in seen:
                seen.add(message)
                errors.append(message)
    return errors


def locked_bundled_mod_errors(
    discovered: dict[str, ModJar],
    desired_by_mod: dict[str, bool],
    locked_disabled_mod_ids: set[str],
) -> list[str]:
    errors: list[str] = []
    for mod_id in sorted(locked_disabled_mod_ids):
        if mod_id not in discovered:
            continue
        siblings = enabled_sibling_mod_ids(discovered, desired_by_mod, mod_id)
        if siblings:
            label = "enabled mod id " if len(siblings) == 1 else "enabled mod ids "
            errors.append(f"profile locks {mod_id} disabled, but the same jar also provides {label}{', '.join(siblings)}")
    return errors


def print_validation(plan: ModPlan) -> None:
    for action in plan.dependency_actions:
        print(f"DEPENDENCY {action}")
    for warning in plan.warnings:
        print(f"WARNING {warning}")
    for error in plan.errors:
        print(f"ERROR {error}")


def apply_mod_states(paths: InstancePaths, profile: dict, dry_run: bool, plan: ModPlan | None = None) -> list[str]:
    plan = plan or resolve_mod_plan(paths, profile)
    if plan.errors:
        raise SystemExit("; ".join(plan.errors))

    discovered = plan.discovered
    desired_by_mod = plan.desired_by_mod
    actions: list[str] = list(plan.dependency_actions)

    desired_by_jar: dict[Path, bool] = {}
    for mod_id, enabled in sorted(desired_by_mod.items()):
        jar = discovered.get(mod_id)
        if jar is None:
            continue
        desired_by_jar[jar.path] = desired_by_jar.get(jar.path, False) or enabled

    for jar, enabled in desired_by_jar.items():
        if enabled and jar.name.endswith(".jar.disabled"):
            target = jar.with_name(jar.name.removesuffix(".disabled"))
            if target.exists():
                actions.append(f"enable {jar.name} already satisfied by {target.name}; remove duplicate disabled copy")
            else:
                actions.append(f"enable {jar.name} -> {target.name}")
            if not dry_run:
                if target.exists():
                    jar.unlink()
                else:
                    jar.rename(target)
        elif enabled and jar.name.endswith(".jar"):
            duplicate = jar.with_name(jar.name + ".disabled")
            if duplicate.exists():
                actions.append(f"enable {jar.name} already satisfied; remove duplicate disabled copy {duplicate.name}")
                if not dry_run:
                    duplicate.unlink()
        elif not enabled and jar.name.endswith(".jar"):
            target = jar.with_name(jar.name + ".disabled")
            actions.append(f"disable {jar.name} -> {target.name}")
            if not dry_run:
                jar.replace(target)
        elif not enabled and jar.name.endswith(".jar.disabled"):
            duplicate = jar.with_name(jar.name.removesuffix(".disabled"))
            if duplicate.exists():
                actions.append(f"disable {jar.name} already satisfied; remove duplicate enabled copy {duplicate.name}")
                if not dry_run:
                    duplicate.unlink()
    return actions


def enable_provided_runtime_modules(desired_by_mod: dict[str, bool]) -> None:
    if desired_by_mod.get("fabric_api") is not True and desired_by_mod.get("forgified_fabric_api") is not True:
        return

    for mod_id in list(desired_by_mod):
        if mod_id.startswith("fabric_"):
            desired_by_mod[mod_id] = True
    if "forgified_fabric_api" in desired_by_mod:
        desired_by_mod["forgified_fabric_api"] = True


def enable_required_dependencies(
    discovered: dict[str, ModJar],
    desired_by_mod: dict[str, bool],
    locked_disabled_mod_ids: set[str] | None = None,
) -> list[str]:
    actions: list[str] = []
    blocked = locked_disabled_mod_ids if locked_disabled_mod_ids is not None else set()
    changed = True

    while changed:
        changed = False
        for mod_id in list(desired_by_mod):
            if desired_by_mod.get(mod_id) is not True:
                continue

            jar = discovered.get(mod_id)
            if jar is None:
                continue

            for required_mod_id in jar.required_mod_ids:
                required_jar = discovered.get(required_mod_id)
                if required_mod_id in blocked:
                    actions.append(f"skip {required_mod_id} because it is locked disabled")
                    continue
                if required_jar is None:
                    continue

                if desired_by_mod.get(required_mod_id) is not True:
                    desired_by_mod[required_mod_id] = True
                    actions.append(f"require {required_mod_id} because {mod_id} requires it")
                    changed = True

                for bundled_id in required_jar.mod_ids:
                    if desired_by_mod.get(bundled_id) is not True:
                        desired_by_mod[bundled_id] = True
                        changed = True
    return actions


def profile_with_required_dependencies(paths: InstancePaths, profile: dict) -> dict:
    mods = profile.get("mods", {})
    if not isinstance(mods, dict):
        return profile

    plan = resolve_mod_plan(paths, profile)

    resolved = copy.deepcopy(profile)
    resolved_mods = resolved.get("mods")
    if not isinstance(resolved_mods, dict):
        resolved_mods = {}
        resolved["mods"] = resolved_mods

    for mod_id, enabled in sorted(plan.desired_by_mod.items()):
        if not enabled:
            continue

        state = resolved_mods.get(mod_id)
        if not isinstance(state, dict):
            resolved_mods[mod_id] = {
                "enabled": True,
                "locked": mod_id == MOD_ID,
                "reason": required_reason(mod_id, list(plan.dependency_actions)),
            }
            continue

        if not bool(state.get("enabled", True)):
            updated = dict(state)
            updated["enabled"] = True
            updated["locked"] = True
            updated["reason"] = required_reason(mod_id, list(plan.dependency_actions))
            resolved_mods[mod_id] = updated

    return resolved


def required_reason(mod_id: str, actions: list[str]) -> str:
    prefix = f"require {mod_id} because "
    for action in actions:
        if action.startswith(prefix):
            return f"Required because {action[len(prefix):]}."
    if mod_id == MOD_ID:
        return "Required by Mod Quality Picker."
    return "Required by an enabled mod."


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
        actions.append(f"{mode.lower()} {relative_path}")
        if dry_run:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)

        if mode == "APPLY_DIFF":
            baseline = resolve_inside(paths.mod_config_dir, f"{DEFAULTS_ROOT}/{relative_path}")
            diff = resolve_inside(paths.mod_config_dir, preset_file)
            if not baseline.exists():
                actions[-1] = f"missing default config baseline {relative_path}"
                continue
            apply_config_diff(target, baseline, diff)
        else:
            source = resolve_inside(paths.mod_config_dir, preset_file)
            if not source.exists():
                actions[-1] = f"missing config preset {preset_file}"
                continue
            if mode == "MERGE_TOML":
                merge_toml_overlay(target, source)
            else:
                shutil.copy2(source, target)
    return actions


def capture_default_configs(paths: InstancePaths, force: bool, dry_run: bool) -> list[str]:
    actions: list[str] = []
    manifest = load_default_manifest(paths)
    entries = dict(manifest.get("entries", {}))
    manifest_changed = False
    for relative_path in list_config_files(paths):
        source = resolve_inside(paths.game_dir, relative_path)
        destination = resolve_inside(paths.mod_config_dir, f"{DEFAULTS_ROOT}/{relative_path}")
        if destination.exists() and not force:
            manifest_changed = update_default_entry(entries, relative_path, destination) or manifest_changed
            continue
        actions.append(f"capture default {relative_path}")
        if dry_run:
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        manifest_changed = update_default_entry(entries, relative_path, destination) or manifest_changed
    if manifest_changed and not dry_run:
        write_default_manifest(paths, manifest_with_entries(entries))
    return actions


def capture_profile_diffs(paths: InstancePaths, profile_id: str, changed_only: bool, capture_missing_defaults: bool, dry_run: bool) -> list[str]:
    profile_path, profile = load_profile(paths, profile_id)
    configs = profile_config_items(profile)
    manifest = load_default_manifest(paths)
    default_entries = dict(manifest.get("entries", {}))
    default_manifest_changed = False

    actions: list[str] = []
    by_path = {item.get("path", ""): item for item in configs if item.get("path", "")}
    for relative_path in list_config_files(paths):
        source = resolve_inside(paths.game_dir, relative_path)
        baseline = resolve_inside(paths.mod_config_dir, f"{DEFAULTS_ROOT}/{relative_path}")
        if not baseline.exists():
            if not capture_missing_defaults:
                actions.append(f"missing default config baseline {relative_path}")
                continue
            actions.append(f"capture default {relative_path}")
            if not dry_run:
                baseline.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, baseline)
                default_manifest_changed = update_default_entry(default_entries, relative_path, baseline) or default_manifest_changed

        diff_rel = f"presets/{profile_id}/{relative_path}{DIFF_EXTENSION}"
        diff_file = resolve_inside(paths.mod_config_dir, diff_rel)
        baseline_lines = read_lines(baseline) if baseline.exists() else read_lines(source)
        diff_lines = create_unified_diff(relative_path, baseline_lines, read_lines(source))
        if changed_only and not diff_lines:
            by_path.pop(relative_path, None)
            continue

        actions.append(f"capture diff {relative_path}")
        by_path[relative_path] = {
            "path": relative_path,
            "mode": "APPLY_DIFF",
            "presetFile": diff_rel,
            "sha256": sha256(source),
        }
        if not dry_run:
            diff_file.parent.mkdir(parents=True, exist_ok=True)
            write_lines(diff_file, diff_lines)

    if not dry_run:
        profile["configFiles"] = [by_path[path] for path in sorted(by_path)]
        profile_path.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")
        if default_manifest_changed:
            write_default_manifest(paths, manifest_with_entries(default_entries))
    return actions


def capture_world_diffs(
    paths: InstancePaths,
    world_id: str,
    profile_id: str,
    changed_only: bool,
    capture_missing_defaults: bool,
    dry_run: bool,
) -> list[str]:
    _, profile = load_profile(paths, profile_id)
    world_mod_dir = paths.world_mod_dir(world_id)
    existing_manifest = load_world_diff_manifest(paths, world_id)
    entries = dict(existing_manifest.get("entries", {}))
    default_manifest = load_default_manifest(paths)
    default_entries = dict(default_manifest.get("entries", {}))
    default_manifest_changed = False

    actions: list[str] = []
    for relative_path in list_config_files(paths):
        source = resolve_inside(paths.game_dir, relative_path)
        baseline = resolve_inside(paths.mod_config_dir, f"{DEFAULTS_ROOT}/{relative_path}")
        if not baseline.exists():
            if not capture_missing_defaults:
                actions.append(f"missing default config baseline {relative_path}")
                continue
            actions.append(f"capture default {relative_path}")
            if not dry_run:
                baseline.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, baseline)
                default_manifest_changed = update_default_entry(default_entries, relative_path, baseline) or default_manifest_changed

        base_lines = render_profile_config_lines(paths, profile, relative_path, source)
        diff_lines = create_unified_diff(relative_path, base_lines, read_lines(source))
        if changed_only and not diff_lines:
            entries.pop(relative_path, None)
            continue

        diff_rel = f"{WORLD_DIFFS_ROOT}/{relative_path}{DIFF_EXTENSION}"
        diff_file = resolve_inside(world_mod_dir, diff_rel)
        actions.append(f"capture world diff {relative_path}")
        entries[relative_path] = {
            "path": relative_path,
            "diffFile": diff_rel,
            "profileId": profile_id,
            "baseSha256": sha256_lines(base_lines),
            "sha256": sha256(source),
            "updatedAt": utc_now(),
        }
        if not dry_run:
            diff_file.parent.mkdir(parents=True, exist_ok=True)
            write_lines(diff_file, diff_lines)

    if not dry_run:
        write_world_diff_manifest(paths, world_id, profile_id, entries)
        if default_manifest_changed:
            write_default_manifest(paths, manifest_with_entries(default_entries))
    return actions


def apply_world_config_diffs(paths: InstancePaths, pending: dict, profile: dict, world_id: str | None, dry_run: bool) -> list[str]:
    selected_world_id = world_id or pending.get("sourceWorldId", "")
    if not selected_world_id:
        return []

    manifest_path = paths.world_diff_manifest(selected_world_id)
    if not manifest_path.exists():
        return []

    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    entries = manifest.get("entries", {})
    if not isinstance(entries, dict):
        return []

    actions: list[str] = []
    world_mod_dir = paths.world_mod_dir(selected_world_id)
    for relative_path, entry in sorted(entries.items()):
        if not isinstance(entry, dict):
            continue
        diff_file = entry.get("diffFile", "")
        if not relative_path or not diff_file:
            continue

        target = resolve_inside(paths.game_dir, relative_path)
        diff = resolve_inside(world_mod_dir, diff_file)
        if not diff.exists():
            actions.append(f"missing world config diff {selected_world_id}:{relative_path}")
            continue

        actions.append(f"world_diff {selected_world_id}:{relative_path}")
        if dry_run:
            continue

        target.parent.mkdir(parents=True, exist_ok=True)
        base_lines = render_profile_config_lines(paths, profile, relative_path, target)
        write_lines(target, apply_unified_diff(base_lines, read_lines(diff)))
    return actions


def render_profile_config_lines(paths: InstancePaths, profile: dict, relative_path: str, current_target: Path) -> list[str]:
    baseline = resolve_inside(paths.mod_config_dir, f"{DEFAULTS_ROOT}/{relative_path}")
    if baseline.exists():
        base_lines = read_lines(baseline)
    elif current_target.exists():
        base_lines = read_lines(current_target)
    else:
        base_lines = []

    config = next((item for item in profile_config_items(profile) if item.get("path", "") == relative_path), None)
    if not config:
        return base_lines

    mode = config.get("mode", "REPLACE_FILE")
    preset_file = config.get("presetFile", "") or relative_path
    if mode == "APPLY_DIFF":
        diff = resolve_inside(paths.mod_config_dir, preset_file)
        return apply_unified_diff(base_lines, read_lines(diff) if diff.exists() else [])
    if mode == "REPLACE_FILE":
        source = resolve_inside(paths.mod_config_dir, preset_file)
        return read_lines(source) if source.exists() else base_lines
    if current_target.exists():
        return read_lines(current_target)
    return base_lines


def load_profile(paths: InstancePaths, profile_id: str) -> tuple[Path, dict]:
    profile_path = paths.mod_config_dir / "presets" / f"{profile_id}.json"
    if not profile_path.exists():
        raise SystemExit(f"Profile not found: {profile_path}")
    return profile_path, json.loads(profile_path.read_text(encoding="utf-8-sig"))


def profile_config_items(profile: dict) -> list[dict]:
    existing = profile.get("configFiles", [])
    if not isinstance(existing, list):
        return []
    return [item for item in existing if isinstance(item, dict)]


def list_config_files(paths: InstancePaths) -> list[str]:
    if not paths.config_dir.exists():
        return []

    config_files: list[str] = []
    for path in paths.config_dir.rglob("*"):
        if not path.is_file() or not is_supported_config_file(path):
            continue
        try:
            path.relative_to(paths.mod_config_dir)
            continue
        except ValueError:
            pass
        config_files.append(path.relative_to(paths.game_dir).as_posix())
    return sorted(config_files)


def is_supported_config_file(path: Path) -> bool:
    return path.name.lower().endswith(SUPPORTED_CONFIG_SUFFIXES)


def create_unified_diff(relative_path: str, base_lines: list[str], modified_lines: list[str]) -> list[str]:
    return list(difflib.unified_diff(
        base_lines,
        modified_lines,
        fromfile=f"a/{relative_path}",
        tofile=f"b/{relative_path}",
        lineterm="",
    ))


def apply_config_diff(target: Path, baseline: Path, diff: Path) -> None:
    base_lines = read_lines(baseline)
    diff_lines = read_lines(diff) if diff.exists() else []
    write_lines(target, apply_unified_diff(base_lines, diff_lines))


HUNK_HEADER = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$")


def apply_unified_diff(base_lines: list[str], diff_lines: list[str]) -> list[str]:
    if not diff_lines:
        return list(base_lines)

    output: list[str] = []
    base_index = 0
    patch_index = 0
    while patch_index < len(diff_lines) and (
        diff_lines[patch_index].startswith("--- ") or diff_lines[patch_index].startswith("+++ ")
    ):
        patch_index += 1

    while patch_index < len(diff_lines):
        match = HUNK_HEADER.match(diff_lines[patch_index])
        if not match:
            raise SystemExit(f"Invalid config diff hunk: {diff_lines[patch_index]}")

        target_index = max(0, int(match.group(1)) - 1)
        if target_index < base_index or target_index > len(base_lines):
            raise SystemExit(f"Config diff hunk is out of range: {diff_lines[patch_index]}")

        output.extend(base_lines[base_index:target_index])
        base_index = target_index
        patch_index += 1

        while patch_index < len(diff_lines) and not diff_lines[patch_index].startswith("@@ "):
            line = diff_lines[patch_index]
            if not line:
                raise SystemExit("Invalid empty config diff line")
            marker = line[0]
            value = line[1:]
            if marker == " ":
                require_base_line(base_lines, base_index, value)
                output.append(value)
                base_index += 1
            elif marker == "-":
                require_base_line(base_lines, base_index, value)
                base_index += 1
            elif marker == "+":
                output.append(value)
            elif marker != "\\":
                raise SystemExit(f"Invalid config diff line: {line}")
            patch_index += 1

    output.extend(base_lines[base_index:])
    return output


def require_base_line(base_lines: list[str], base_index: int, expected: str) -> None:
    if base_index >= len(base_lines) or base_lines[base_index] != expected:
        raise SystemExit("Config diff does not apply cleanly")


def read_lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8-sig").splitlines()


def write_lines(path: Path, lines: list[str]) -> None:
    path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_lines(lines: list[str]) -> str:
    digest = hashlib.sha256()
    digest.update(("\n".join(lines) + ("\n" if lines else "")).encode("utf-8"))
    return digest.hexdigest()


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def owner_hint(relative_path: str) -> str:
    path = relative_path.removeprefix("config/")
    first = path.split("/", 1)[0]
    return first.rsplit(".", 1)[0]


def load_default_manifest(paths: InstancePaths) -> dict:
    if not paths.default_manifest.exists():
        return {
            "schemaVersion": 1,
            "updatedAt": "",
            "entries": {},
        }
    data = json.loads(paths.default_manifest.read_text(encoding="utf-8-sig"))
    if not isinstance(data, dict):
        raise SystemExit(f"Invalid defaults manifest: {paths.default_manifest}")
    if not isinstance(data.get("entries"), dict):
        data["entries"] = {}
    data.setdefault("schemaVersion", 1)
    data.setdefault("updatedAt", "")
    return data


def manifest_with_entries(entries: dict[str, dict]) -> dict:
    return {
        "schemaVersion": 1,
        "updatedAt": utc_now(),
        "entries": {path: entries[path] for path in sorted(entries)},
    }


def write_default_manifest(paths: InstancePaths, manifest: dict) -> None:
    paths.mod_config_dir.mkdir(parents=True, exist_ok=True)
    paths.default_manifest.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def update_default_entry(entries: dict[str, dict], relative_path: str, baseline: Path) -> bool:
    file_hash = sha256(baseline)
    file_size = baseline.stat().st_size
    previous = entries.get(relative_path, {})
    captured_at = previous.get("capturedAt", "")
    if previous.get("sha256") != file_hash or previous.get("size") != file_size:
        captured_at = utc_now()

    next_entry = {
        "path": relative_path,
        "ownerHint": owner_hint(relative_path),
        "sha256": file_hash,
        "size": file_size,
        "capturedAt": captured_at or utc_now(),
    }
    if previous == next_entry:
        return False
    entries[relative_path] = next_entry
    return True


def validate_default_manifest(paths: InstancePaths) -> list[str]:
    manifest = load_default_manifest(paths)
    entries = manifest.get("entries", {})
    problems: list[str] = []
    for relative_path, entry in sorted(entries.items()):
        if not isinstance(entry, dict):
            problems.append(f"invalid manifest entry {relative_path}")
            continue
        baseline = resolve_inside(paths.mod_config_dir, f"{DEFAULTS_ROOT}/{relative_path}")
        if not baseline.exists():
            problems.append(f"missing default {relative_path}")
            continue
        expected_hash = entry.get("sha256", "")
        actual_hash = sha256(baseline)
        if expected_hash and expected_hash != actual_hash:
            problems.append(f"hash mismatch {relative_path}")
        expected_size = entry.get("size")
        if isinstance(expected_size, int) and expected_size != baseline.stat().st_size:
            problems.append(f"size mismatch {relative_path}")
    for baseline in (paths.mod_config_dir / DEFAULTS_ROOT).rglob("*") if (paths.mod_config_dir / DEFAULTS_ROOT).exists() else []:
        if not baseline.is_file() or not is_supported_config_file(baseline):
            continue
        relative_path = baseline.relative_to(paths.mod_config_dir / DEFAULTS_ROOT).as_posix()
        if relative_path not in entries:
            problems.append(f"untracked default {relative_path}")
    return problems


def load_world_diff_manifest(paths: InstancePaths, world_id: str) -> dict:
    manifest_path = paths.world_diff_manifest(world_id)
    if not manifest_path.exists():
        return {
            "schemaVersion": 1,
            "worldId": world_id,
            "profileId": "",
            "updatedAt": "",
            "entries": {},
        }
    data = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    if not isinstance(data, dict):
        raise SystemExit(f"Invalid world diff manifest: {manifest_path}")
    if not isinstance(data.get("entries"), dict):
        data["entries"] = {}
    data.setdefault("schemaVersion", 1)
    data.setdefault("worldId", world_id)
    data.setdefault("profileId", "")
    data.setdefault("updatedAt", "")
    return data


def write_world_diff_manifest(paths: InstancePaths, world_id: str, profile_id: str, entries: dict[str, dict]) -> None:
    manifest_path = paths.world_diff_manifest(world_id)
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest = {
        "schemaVersion": 1,
        "worldId": world_id,
        "profileId": profile_id,
        "updatedAt": utc_now(),
        "entries": {path: entries[path] for path in sorted(entries)},
    }
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


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
    target_lines = target.read_text(encoding="utf-8-sig").splitlines() if target.exists() else []
    overlay_entries = collect_toml_entries(overlay.read_text(encoding="utf-8-sig").splitlines())

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
    line = f'activeProfileId = "{profile_id}"'
    config_file.parent.mkdir(parents=True, exist_ok=True)
    if config_file.exists():
        text = config_file.read_text(encoding="utf-8-sig")
        text, count = re.subn(r'activeProfileId\s*=\s*"[^"]*"', line, text)
        if count == 0:
            separator = "" if text.endswith(("\n", "\r")) else "\n"
            text = f"{text}{separator}{line}\n"
        config_file.write_text(text, encoding="utf-8")
    else:
        config_file.write_text(line + "\n", encoding="utf-8")
    return [action]


def apply_pending(args: argparse.Namespace) -> int:
    paths = InstancePaths.from_root(Path(args.instance_root))
    pending = load_pending_profile(paths)
    profile, applied = resolve_profile_for_apply(paths, pending)
    if profile is None or applied is None:
        print(f"No pending profile found and no active preset found for {active_profile_id(paths)}.")
        return 0

    plan = resolve_mod_plan(paths, profile)
    print_validation(plan)
    if plan.errors:
        return 1

    actions = []
    actions.extend(apply_mod_states(paths, profile, args.dry_run, plan))
    actions.extend(apply_config_files(paths, profile, args.dry_run))
    actions.extend(apply_world_config_diffs(paths, applied, profile, args.world_id, args.dry_run))
    actions.extend(set_active_profile(paths, profile, args.dry_run))

    for action in actions:
        print(action)

    if not args.dry_run and not args.keep_pending:
        paths.mod_config_dir.mkdir(parents=True, exist_ok=True)
        paths.applied_profile.write_text(json.dumps(applied, indent=2) + "\n", encoding="utf-8")
        paths.pending_profile.unlink(missing_ok=True)
        paths.pending_selection.unlink(missing_ok=True)
    return 0


def validate_profile(args: argparse.Namespace) -> int:
    paths = InstancePaths.from_root(Path(args.instance_root))
    if args.profile_id:
        _, profile = load_profile(paths, args.profile_id)
    else:
        pending = load_pending_profile(paths)
        profile, _ = resolve_profile_for_apply(paths, pending)

    if profile is None:
        print(f"No pending profile found and no active preset found for {active_profile_id(paths)}.")
        return 0

    plan = resolve_mod_plan(paths, profile)
    print_validation(plan)
    if plan.errors:
        return 1
    if not plan.warnings:
        print(f"Profile {profile.get('id', 'balanced')} is valid.")
    return 0


def export_presets(args: argparse.Namespace) -> int:
    paths = InstancePaths.from_root(Path(args.instance_root))
    destination = Path(args.pack_root).resolve() / "config" / MOD_ID
    for name in (DEFAULTS_ROOT, "presets"):
        source = paths.mod_config_dir / name
        target = destination / name
        if not source.exists():
            target.mkdir(parents=True, exist_ok=True)
            print(f"created {target}")
            continue
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(source, target)
        print(f"exported {source} -> {target}")
    if paths.default_manifest.exists():
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copy2(paths.default_manifest, destination / DEFAULTS_MANIFEST)
        print(f"exported {paths.default_manifest} -> {destination / DEFAULTS_MANIFEST}")
    return 0


def capture_defaults(args: argparse.Namespace) -> int:
    paths = InstancePaths.from_root(Path(args.instance_root))
    actions = capture_default_configs(paths, args.force, args.dry_run)
    for action in actions:
        print(action)
    if not actions:
        print("No default configs needed capturing.")
    return 0


def capture_diffs(args: argparse.Namespace) -> int:
    paths = InstancePaths.from_root(Path(args.instance_root))
    actions = capture_profile_diffs(paths, args.profile_id, args.changed_only, args.capture_missing_defaults, args.dry_run)
    for action in actions:
        print(action)
    if not actions:
        print("No profile config diffs needed capturing.")
    return 0


def capture_world(args: argparse.Namespace) -> int:
    paths = InstancePaths.from_root(Path(args.instance_root))
    actions = capture_world_diffs(
        paths,
        args.world_id,
        args.profile_id,
        args.changed_only,
        args.capture_missing_defaults,
        args.dry_run,
    )
    for action in actions:
        print(action)
    if not actions:
        print("No world config diffs needed capturing.")
    return 0


def validate_defaults(args: argparse.Namespace) -> int:
    paths = InstancePaths.from_root(Path(args.instance_root))
    problems = validate_default_manifest(paths)
    for problem in problems:
        print(problem)
    if problems:
        return 1
    print("Default config manifest is valid.")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Apply Mod Quality Picker profiles to a Prism instance before launch.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    apply = subparsers.add_parser("apply", help="Apply config/mod changes from pending-profile.json.")
    apply.add_argument("--instance-root", default=".", help="Prism instance root or game directory.")
    apply.add_argument("--world-id", default="", help="Apply this world's config diffs after the selected profile.")
    apply.add_argument("--dry-run", action="store_true")
    apply.add_argument("--keep-pending", action="store_true")
    apply.set_defaults(func=apply_pending)

    validate_profile_parser = subparsers.add_parser("validate-profile", help="Check a pending, active, or named profile before applying it.")
    validate_profile_parser.add_argument("--instance-root", default=".", help="Prism instance root or game directory.")
    validate_profile_parser.add_argument("--profile-id", default="", help="Validate this saved preset instead of the pending/active profile.")
    validate_profile_parser.set_defaults(func=validate_profile)

    export = subparsers.add_parser("export-presets", help="Copy in-instance presets into a pack root.")
    export.add_argument("--instance-root", default=".", help="Prism instance root or game directory.")
    export.add_argument("--pack-root", default="pack", help="Pack root to receive config/modqualitypicker/presets.")
    export.set_defaults(func=export_presets)

    defaults = subparsers.add_parser("capture-defaults", help="Capture live generated configs as immutable profile baselines.")
    defaults.add_argument("--instance-root", default=".", help="Prism instance root or game directory.")
    defaults.add_argument("--force", action="store_true", help="Overwrite existing baselines.")
    defaults.add_argument("--dry-run", action="store_true")
    defaults.set_defaults(func=capture_defaults)

    diffs = subparsers.add_parser("capture-diffs", help="Regenerate a profile's config diffs from live configs.")
    diffs.add_argument("--instance-root", default=".", help="Prism instance root or game directory.")
    diffs.add_argument("--profile-id", required=True)
    diffs.add_argument("--changed-only", action="store_true", help="Only keep config entries with non-empty diffs.")
    diffs.add_argument("--capture-missing-defaults", action="store_true", help="Create missing baselines from the live config before diffing.")
    diffs.add_argument("--dry-run", action="store_true")
    diffs.set_defaults(func=capture_diffs)

    world_diffs = subparsers.add_parser("capture-world-diffs", help="Regenerate world-specific config diffs from live configs.")
    world_diffs.add_argument("--instance-root", default=".", help="Prism instance root or game directory.")
    world_diffs.add_argument("--world-id", required=True)
    world_diffs.add_argument("--profile-id", required=True)
    world_diffs.add_argument("--changed-only", action="store_true", help="Only keep config entries with non-empty diffs.")
    world_diffs.add_argument("--capture-missing-defaults", action="store_true", help="Create missing baselines from the live config before diffing.")
    world_diffs.add_argument("--dry-run", action="store_true")
    world_diffs.set_defaults(func=capture_world)

    validate = subparsers.add_parser("validate-defaults", help="Check default config baselines against the manifest.")
    validate.add_argument("--instance-root", default=".", help="Prism instance root or game directory.")
    validate.set_defaults(func=validate_defaults)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
