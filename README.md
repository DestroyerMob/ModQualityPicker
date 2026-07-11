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
- A transactional, self-contained Java applier inside the normal mod jar, launched automatically when changes are queued.

## Runtime Boundary

Mods are enabled or disabled between launches. This mod does not try to unload mods from inside a running game. Instead, it records the desired profile, prompts the player when a world needs a different profile, and starts a helper that waits for Minecraft to exit before applying the queue.

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

## Automatic Apply After Exit

When the in-game UI queues a profile, it writes `minecraft/config/modqualitypicker/pending-profile.json`.

The mod starts a small helper process from its own executable jar. The helper waits for the current Minecraft process to exit, then applies the queued mod and config changes before the next launch. This is launcher-independent and requires no player setup.

## Optional Standalone Recovery

The built mod jar is also executable as a standalone Java tool. Pack developers may run it directly for diagnostics or recovery:

```sh
java -jar /path/to/instance/minecraft/mods/modqualitypicker-local.jar apply --instance-root /path/to/Prism/instance
```

No Python installation, source checkout, separate helper download, or launcher configuration is required at runtime. The jar renames mods between `.jar` and `.jar.disabled`, applies config overlays and world diffs, updates `activeProfileId`, and archives the pending selection as `applied-profile.json`.
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

The legacy Python utility in `tools/` remains optional for pack-developer maintenance operations such as catalog reconciliation and baseline capture. It is not used by the player UI, automatic helper, or launcher safety-net flow.

### Conservative JAR cleanup

The standalone/deferred applier also maintains `config/modqualitypicker/jar-cleaner-state.json`.
It removes a JAR only when the same filename was previously recorded as pack-owned and
is no longer present in current pack metadata. Unknown JARs are recorded as protected
Prism/user additions and are never inferred to be stale. Files ending in
`.jar.disabled` are outside cleanup scope.

Pack ownership is read from `minecraft/packwiz.json`. Packs can declare local or
otherwise externally managed build artifacts in
`config/modqualitypicker/managed-jars.json`:

```json
{
  "schemaVersion": 1,
  "filenames": [
    "example-local.jar"
  ]
}
```

An artifact ending in `.jar.duplicate` is removed only when its corresponding current
pack-owned JAR exists and both files are byte-for-byte identical. Different versions
are preserved with a warning. Cleanup runs automatically during `apply`, or can be
previewed and run independently:

```sh
java -jar modqualitypicker-local.jar clean-jars --instance-root /path/to/Prism/instance --dry-run
java -jar modqualitypicker-local.jar clean-jars --instance-root /path/to/Prism/instance
```

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

Minecraft Beyond bundles curated profiles, feature groups, and feature config overlays under `pack/config/modqualitypicker/` and treats those files as packwiz-managed config. Queued changes use the mod's automatic deferred helper, while the `scripts/modpack` workflow preserves the local active profile when packwiz updates the instance, re-applies jar enable/disable state after local mod syncing, and can promote in-instance preset edits back into the distributable pack with:

```sh
./scripts/modpack sync-quality-presets --profile <profile-id>
```

The mod repository owns profile semantics and the automatic/standalone applier; the pack repository owns the shipped preset contents.

## Known Limitations

- Runtime mod unloading is intentionally out of scope; mod enable/disable changes take effect after Minecraft exits and the instance is launched again.
- Very large config files fall back to a simpler prefix/suffix diff to avoid excessive memory use.
- Structured TOML/JSON-aware diffs are still future work; current diffs are line-oriented and validated against their baseline before application.
- A pending queue found on a later startup is automatically scheduled again, covering helper-start failures and upgrades from older releases.
