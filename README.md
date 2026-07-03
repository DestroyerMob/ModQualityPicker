# Mod Quality Picker

Mod Quality Picker is a NeoForge 1.21.1 modpack tool for profile-based quality and performance selections.

The goal is to let a pack ship multiple curated experiences without splitting into separate modpacks. Pack developers define quality profiles, players can choose a profile or tweak allowed options, and worlds can remember the profile they expect.

## Implemented Scope

This project now contains:

- A compileable NeoForge mod project.
- Common config for the currently selected profile and mismatch policy.
- JSON models for quality profiles, config overrides, and runtime selections.
- Startup snapshot writing for the currently loaded mod list.
- Diff logic for comparing a world profile against the active launch selection.
- A player-facing quality selector from the vanilla Options screen.
- A NeoForge mod-list config screen and `/modqualitypicker open` client command.
- A tabbed profile editor with scrollable Profiles and Mods lists.
- Per-profile mod enabled/disabled toggles, locked toggles, and override removal.
- Preset capture, queueing, config application, and pack export actions.
- Automatic world-open mismatch checks through a client-side mixin.
- A three-choice world mismatch screen: continue current, queue world profile, or back out.
- Config file hashing plus replace-file and simple TOML merge application.
- A Prism pre-launch helper at `tools/modqualitypicker_prism.py`.

## Runtime Boundary

Mods are enabled or disabled during launch. This mod should not try to unload mods from inside a running game. Instead, it records the desired profile, prompts the player when a world needs a different profile, and queues the change for a restart/pre-launch applier.

## In-Game Editor

Players choose the pack quality preset from:

```text
Options -> Pack Quality
```

This appears as a vanilla-style cycle option. Clicking it steps through the pack's ordered quality presets and queues the selected preset for the next restart.

Open the editor from:

```text
Main Menu -> Mods -> Mod Quality Picker -> Config
```

Or from an active world:

```text
/modqualitypicker open
```

The editor has two visible tabs:

- `Profiles`: switch, save, capture the current launch, queue the profile for restart, or export presets.
- `Mods`: click a mod in the scrollable list, then toggle whether it should be enabled in the profile, mark it locked, or remove its override.

Config profiles are stored as diffs over generated defaults. The in-game config editor controls are hidden for now, but the helper can capture default baselines and regenerate preset diffs from live configs.

Pack developers control the player-facing cycle order with each profile's `sortOrder` field. The Profiles tab also has Move Up and Move Down controls that rewrite those order values.

## Building

Run:

```sh
./gradlew build
```

The runtime jar will be created under `build/libs/`.

## Prism Pre-Launch

When the in-game UI queues a profile, it writes `minecraft/config/modqualitypicker/pending-profile.json`.

Run this before launching the instance to apply the queued profile:

```sh
python3 tools/modqualitypicker_prism.py apply --instance-root /path/to/Prism/instance
```

For this workspace, from the instance root:

```sh
python3 /Users/ethanhellyer/Documents/minecraft-mod-sources/ModQualityPicker/tools/modqualitypicker_prism.py apply --instance-root .
```

The helper renames mod jars between `.jar` and `.jar.disabled`, regenerates live config files from `defaults + preset diff + world diff`, updates `activeProfileId`, and archives the pending profile as `applied-profile.json`.

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

The full first feature loop is in place for Prism-based pack testing. The current branch can capture profiles, edit per-profile mod state, queue profile changes, prompt on world/profile mismatches, apply queued changes with the Prism helper, store config profiles as defaults plus profile/world diffs, validate default manifests, and export presets back into a pack.

## Supported Versions

- Minecraft 1.21.1
- NeoForge 21.1.234
- Java 21

## Configuration

The common config tracks the active profile, whether launch snapshots are written, whether world-open prompts are enabled, the world mismatch policy, and the preset export path.

## Known Limitations

- Runtime mod unloading is intentionally out of scope; mod enable/disable changes require restart and launcher/pre-launch helper support.
- Very large config files fall back to a simpler prefix/suffix diff to avoid excessive memory use.
- Structured TOML/JSON-aware diffs are still future work; current diffs are line-oriented and validated against their baseline before application.
- Profile dependency validation and a fully automatic Prism launch integration are still future work.
