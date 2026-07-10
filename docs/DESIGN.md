# Mod Quality Picker Design Notes

Mod Quality Picker is a pack-developer tool for shipping multiple performance and quality experiences in one modpack. The important boundary is that Minecraft mods are discovered during launch, so the in-game UI can choose and queue a profile, but profile application must happen before the next launch finishes loading mods.

## Core Model

- A base profile is a named preset with an explicit `sortOrder`, mod states, config file rules, and default feature choices.
- `feature-groups.json` defines independent, pack-owned quality axes. Each choice can contribute mod states and config rules without replacing unrelated choices.
- A player selection stores a base profile id plus only the feature overrides that differ from that base.
- The current launch writes `config/modqualitypicker/active-selection.json`.
- Pending changes are written to `config/modqualitypicker/pending-selection.json` for the launcher/pre-launch applier to consume.
- Generated config defaults live under `config/modqualitypicker/defaults`.
- Default metadata lives in `config/modqualitypicker/defaults-manifest.json`.
- Presets store config changes as unified diffs under `config/modqualitypicker/presets/<profile>/...`.
- Each world stores its desired profile at `<world>/modqualitypicker/quality-profile.json`.
- Optional world-specific config changes live under `<world>/modqualitypicker/config-diffs`.
- When a player opens a world, a client-side mixin compares the world profile to the active launch snapshot before vanilla continues.

## World Open Choices

When the world profile differs from the current launch, the UI should offer:

1. Continue using the currently loaded mod/config set.
2. Use the world's profile, write it as pending, and restart the instance.
3. Back out to the world list.

The implementation avoids altering loaded mods in-process. Enabling and disabling mods is handled by the executable mod jar before NeoForge completes mod discovery on the next launch.

## Pack Developer Flow

Players can open `Options -> Pack Quality` to select a base preset and independently cycle through player-adjustable feature groups. The screen resolves the final mod set immediately for inspection, but queues changes for the next launch instead of trying to unload or load mods in-process.

Pack developers can open the detailed editor from the player screen or `/modqualitypicker developer`. The editor is intentionally tabbed:

- `Profiles` handles profile switching, saving, capture, queueing, and exporting.
- `Mods` shows a scrollable list of loaded mod ids and handles enabled/disabled state, locked state, and override removal.

Config-file rules remain in the data model and launcher helper, but their in-game editor controls are hidden until that workflow is clearer. Live config files are treated as disposable output: the helper rebuilds them from the captured defaults, then the selected profile's diff, then any world-specific diff before launch.

Profile order is stored in `sortOrder`. The Profiles tab can move presets up or down, rewriting the order values that the player-facing cycle button uses.

## Implemented Pieces

- Client profile editor screen.
- World-list mismatch prompt.
- Config file hashing, default manifest validation, legacy TOML merge support, and default-plus-diff config application.
- Profile validation that refuses locked-disabled dependencies required by enabled mods, reports dependency auto-enables, and suggests matching discovered jar ids for stale profile entries.
- Transactional pre-launch applier packaged in the normal mod jar, with no Python/runtime sidecar dependency.
- Composable feature-group resolution with base defaults and per-player overrides.
- Player-facing current/desired/disabled mod inspection and restart/new-world requirements.
- Preflight validation that rejects missing or malformed profile/world config layers before any jars are renamed.
- Profile/catalog reconciliation for adding newly installed mods and pruning stale entries.
- Pack export command that copies defaults and presets into the pack root.
- Helper smoke tests for default capture and preset/world diff layering.

## Next Hardening Pass

- Add a clean throwaway-launch workflow for refreshing default baselines when mods update their generated config format.
- Add richer option widgets for numeric and non-cyclic pack-defined settings.
- Add a launcher-agnostic installer for pre-launch hooks; individual packs currently own that launcher configuration.
