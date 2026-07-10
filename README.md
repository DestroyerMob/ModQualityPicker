# Mod Quality Picker

Mod Quality Picker is a NeoForge 1.21.1 modpack tool for profile-based quality and performance selections.

The goal is to let a pack ship multiple curated experiences without splitting into separate modpacks. Pack developers define quality profiles, players can choose a profile or tweak allowed options, and worlds can remember the profile they expect.

## Project Facts

- Mod id: `modqualitypicker`
- Current version: `0.1.0`
- Target: Minecraft 1.21.1, NeoForge 21.1.234, Java 21
- Common config: `config/modqualitypicker-common.toml`

## Implemented Scope

This project now contains:

- A compileable NeoForge mod project.
- Common config for the currently selected profile and mismatch policy.
- JSON models for base presets, composable feature groups, per-player overrides, config overrides, and runtime selections.
- Startup snapshot writing for the currently loaded mod list.
- Diff logic for comparing a world profile against the active launch selection.
- A full player-facing quality screen from vanilla Options with base presets, independent feature choices, current/desired mod state, and restart requirements.
- A NeoForge mod-list config screen and `/modqualitypicker open` client command.
- A tabbed profile editor with scrollable Profiles and Mods lists.
- Per-profile mod enabled/disabled toggles, locked toggles, and override removal.
- Preset capture, queueing, config application, and pack export actions.
- Profile validation for dependency conflicts before applying queued changes.
- Automatic world-open mismatch checks through a client-side mixin.
- A three-choice world mismatch screen: continue current, queue world profile, or back out.
- Config file hashing plus replace-file and simple TOML merge application.
- A transactional, self-contained Java pre-launch applier inside the normal mod jar.

## Runtime Boundary

Mods are enabled or disabled during launch. This mod should not try to unload mods from inside a running game. Instead, it records the desired profile, prompts the player when a world needs a different profile, and queues the change for a restart/pre-launch applier.

## In-Game Editor

Players open the pack quality screen from:

```text
Options -> Pack Quality
```

The screen starts with a base preset, then lets players override pack-defined feature groups independently. For example, a player can use the Balanced base while selecting Full Ecology, without inheriting every setting from Max. The Mods tab shows whether each resolved mod is loaded, disabled, or pending a restart, and which feature group controls it.

Open the same player screen from:

```text
Main Menu -> Mods -> Mod Quality Picker -> Config
```

Or from an active world:

```text
/modqualitypicker open
```

The detailed pack-developer editor remains available from the `Developer` button or:

```text
/modqualitypicker developer
```

Its two visible tabs are:

- `Profiles`: switch, save, capture the current launch, queue the profile for restart, or export presets.
- `Mods`: click a mod in the scrollable list, then toggle whether it should be enabled in the profile, mark it locked, or remove its override.

Config profiles are stored as diffs over generated defaults. The in-game config editor controls are hidden for now, but the helper can capture default baselines and regenerate preset diffs from live configs.

Pack developers control base-preset order with each profile's `sortOrder` field. Player-adjustable groups and their choices are defined in `config/modqualitypicker/feature-groups.json`; each choice can own mod states, config overlays, scope, and an application requirement such as restart or new world.

## Building

Run:

```sh
./gradlew build
```

The runtime jar will be created under `build/libs/`.

## Prism Pre-Launch

When the in-game UI queues a profile, it writes `minecraft/config/modqualitypicker/pending-profile.json`.

The built mod jar is executable as a standalone Java tool. Run the same jar installed in the instance before launching Minecraft:

```sh
java -jar /path/to/instance/minecraft/mods/modqualitypicker-local.jar apply --instance-root /path/to/Prism/instance
```

No Python installation, source checkout, or separate helper is required at runtime. The jar renames mods between `.jar` and `.jar.disabled`, applies config overlays and world diffs, updates `activeProfileId`, and archives the pending selection as `applied-profile.json`.
Before any files are changed, it validates the selected profile and refuses hard dependency conflicts, such as enabling a mod while locking one of its required dependencies disabled.
Validation output includes `DEPENDENCY` lines for auto-enabled requirements and suggests the closest discovered mod id/jar when a profile contains a stale fallback id.

To check the pending or active profile without applying it:

```sh
java -jar modqualitypicker-local.jar validate-profile --instance-root /path/to/Prism/instance
```

To validate a saved preset directly:

```sh
java -jar modqualitypicker-local.jar validate-profile --instance-root /path/to/Prism/instance --profile-id balanced
```

The legacy Python utility in `tools/` remains optional for pack-developer maintenance operations such as catalog reconciliation and baseline capture. It is not used by the player UI or Prism pre-launch flow.

When installed jars change, reconcile a saved preset with the discovered catalog before shipping it:

```sh
python3 tools/modqualitypicker_prism.py sync-profile-mods --instance-root /path/to/Prism/instance --profile-id balanced --prune-missing
```

New entries follow their jars' current enabled/disabled state by default. Use `--new-mod-state enabled` when refreshing a maximum-quality preset that should opt into every newly installed mod.

## Config Baselines

After a clean launch has generated normal mod configs, capture the immutable baseline set:

```sh
python3 tools/modqualitypicker_prism.py capture-defaults --instance-root /path/to/Prism/instance
```

The baseline capture writes `config/modqualitypicker/defaults-manifest.json`, which tracks each default file's path, owner hint, size, hash, and capture time. Validate that manifest before export or release:

```sh
python3 tools/modqualitypicker_prism.py validate-defaults --instance-root /path/to/Prism/instance
```

After editing live configs into the desired state for a preset, regenerate that preset's diffs:

```sh
python3 tools/modqualitypicker_prism.py capture-diffs --instance-root /path/to/Prism/instance --profile-id balanced
```

If a world needs changes on top of the selected preset, capture only that world's extra layer:

```sh
python3 tools/modqualitypicker_prism.py capture-world-diffs --instance-root /path/to/Prism/instance --world-id "New World" --profile-id balanced --changed-only
```

New in-game config captures also write `APPLY_DIFF` entries. Existing `REPLACE_FILE` and `MERGE_TOML` entries remain supported for older profiles.

## Pack Export

From the in-game profile screen, the Export button copies defaults and presets to the configured pack export path. The same operation can be run from the helper:

```sh
python3 tools/modqualitypicker_prism.py export-presets --instance-root /path/to/Prism/instance --pack-root /path/to/pack
```

## Current Status

The player loop is in place for Prism-based pack testing: choose a base preset, override independent feature groups, inspect resolved mod state and restart requirements, queue the selection, and let the executable mod jar apply it transactionally before the next launch. The developer editor, world/profile mismatch handling, config baselines/diffs, dependency validation, and pack export remain available.

## Supported Versions

- Minecraft 1.21.1
- NeoForge 21.1.234
- Java 21

## Configuration

The common config tracks the active profile, whether launch snapshots are written, whether world-open prompts are enabled, the world mismatch policy, and the preset export path.

## Minecraft Beyond Integration

Minecraft Beyond bundles curated profiles, feature groups, and feature config overlays under `pack/config/modqualitypicker/` and treats those files as packwiz-managed config. Its Prism instance executes `modqualitypicker-local.jar` as a pre-launch command, so an in-game queued selection is applied before NeoForge discovers mods on the next launch. The `scripts/modpack` workflow also preserves the local active profile when packwiz updates the instance, re-applies jar enable/disable state after local mod syncing, and can promote in-instance preset edits back into the distributable pack with:

```sh
./scripts/modpack sync-quality-presets --profile <profile-id>
```

The mod repository owns profile semantics and the pre-launch applier; the pack repository owns the shipped preset contents.

## Known Limitations

- Runtime mod unloading is intentionally out of scope; mod enable/disable changes require a restart and launcher pre-launch command.
- Very large config files fall back to a simpler prefix/suffix diff to avoid excessive memory use.
- Structured TOML/JSON-aware diffs are still future work; current diffs are line-oriented and validated against their baseline before application.
- Installing the Prism pre-launch command remains the responsibility of each pack or launcher instance; the mod does not rewrite launcher settings.
