# Mod Quality Picker Design Notes

Mod Quality Picker is a pack-developer tool for shipping multiple performance and quality experiences in one modpack. The important boundary is that Minecraft mods are discovered during launch, so the in-game UI can choose and queue a profile, but profile application must happen before the next launch finishes loading mods.

## Core Model

- A base profile is a named preset with an explicit `sortOrder`, mod states, config file rules, and default feature choices.
- Each mod state may own config overlays for that preset. A player can point an individual mod at another shipped preset, which takes that preset's enabled state and mod-owned config without changing unrelated mods.
- `feature-groups.json` defines independent, pack-owned quality axes. Each choice can contribute mod states and config rules without replacing unrelated choices.
- A player selection stores a base profile id plus only the feature and per-mod preset overrides that differ from that base. Selecting another base profile clears both kinds of overrides, so the global preset controls every mod by default.
- The current launch writes `config/modqualitypicker/active-selection.json`.
- Pending changes are written to `config/modqualitypicker/pending-selection.json`; a helper spawned from the mod jar waits for Minecraft to exit and then consumes them.
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

Players can open `Options -> Pack Quality` to select a base preset, independently cycle pack-defined quality presets for individual mods, and adjust any player-facing feature groups. The screen resolves the final mod and config set immediately for inspection, but queues changes for the next launch instead of trying to unload or load mods in-process.

Pack developers can open the detailed editor from the player screen or `/modqualitypicker developer`. The editor is intentionally tabbed:

- `Profiles` handles profile switching, saving, capture, queueing, and exporting.
- `Mods` shows a searchable list of loaded mod ids and handles enabled/disabled state, locked state, per-mod config ownership, dependency-aware disabling, and override removal.
- `Features` authors pack-owned feature groups and choices, selects each preset's default choice, and opens the selected choice's mod/config editors.
- `Configs` searches live config files, assigns each file to the whole preset, one mod, or one feature choice, and captures it as a diff, replacement, TOML merge, or keep-player rule.

Live config files are treated as disposable authoring output. `Capture` records the current live file for the selected owner and mode. The helper rebuilds configs from the captured defaults, then preset-wide rules, feature and mod-owned rules, and finally any world-specific diff before launch.

Profile order is stored in `sortOrder`. The Profiles tab can move presets up or down, rewriting the order values that the player-facing cycle button uses.

## Implemented Pieces

- Client profile editor screen.
- World-list mismatch prompt.
- Config file hashing, default manifest validation, legacy TOML merge support, and default-plus-diff config application.
- Profile validation that refuses locked-disabled dependencies required by enabled mods, reports dependency auto-enables, and suggests matching discovered jar ids for stale profile entries.
- Transactional standalone applier packaged in the normal mod jar, with an automatically spawned deferred helper and no Python/runtime sidecar dependency.
- Composable feature-group resolution with base defaults and per-player overrides.
- Player-facing current/desired/disabled mod inspection and restart/new-world requirements.
- Preflight validation that rejects missing or malformed profile/world config layers before any jars are renamed.
- Profile/catalog reconciliation for adding newly installed mods and pruning stale entries.
- Pack export command that copies defaults, presets, feature definitions, and feature overlays into the pack root.
- Helper smoke tests for default capture and preset/world diff layering.

## Next Hardening Pass

- Add a clean throwaway-launch workflow for refreshing default baselines when mods update their generated config format.
- Add richer option widgets for numeric and non-cyclic pack-defined settings.
- Re-schedule any pending queue found at startup so helper-start failures and upgrades from older releases recover without launcher integration.
