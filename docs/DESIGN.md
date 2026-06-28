# Mod Quality Picker Design Notes

Mod Quality Picker is a pack-developer tool for shipping multiple performance and quality experiences in one modpack. The important boundary is that Minecraft mods are discovered during launch, so the in-game UI can choose and queue a profile, but profile application must happen before the next launch finishes loading mods.

## Core Model

- A quality profile is a named preset with an explicit `sortOrder`, mod states, config file rules, and exposed player options.
- The current launch writes `config/modqualitypicker/active-selection.json`.
- Pending changes are written to `config/modqualitypicker/pending-selection.json` for the launcher/pre-launch applier to consume.
- Each world stores its desired profile at `<world>/modqualitypicker/quality-profile.json`.
- When a player opens a world, a client-side mixin compares the world profile to the active launch snapshot before vanilla continues.

## World Open Choices

When the world profile differs from the current launch, the UI should offer:

1. Continue using the currently loaded mod/config set.
2. Use the world's profile, write it as pending, and restart the instance.
3. Back out to the world list.

The implementation avoids altering loaded mods in-process. Enabling and disabling mods is handled by the Prism helper before NeoForge completes mod discovery on the next launch.

## Pack Developer Flow

Players can open `Options -> Pack Quality` to cycle through the pack's ordered quality presets. Choosing a preset writes it as pending and asks for a restart instead of trying to unload or load mods in-process.

Pack developers can open the Mod Quality Picker screen from the mod list/config menu or `/modqualitypicker open`. The editor is intentionally tabbed:

- `Profiles` handles profile switching, saving, capture, queueing, and exporting.
- `Mods` shows a scrollable list of loaded mod ids and handles enabled/disabled state, locked state, and override removal.

Config-file rules remain in the data model and launcher helper, but their in-game editor controls are hidden until that workflow is clearer.

Profile order is stored in `sortOrder`. The Profiles tab can move presets up or down, rewriting the order values that the player-facing cycle button uses.

## Implemented Pieces

- Client profile editor screen.
- World-list mismatch prompt.
- Config file hashing and TOML merge support.
- Pre-launch applier for Prism Launcher instances.
- Pack export command that copies presets into the pack root.

## Next Hardening Pass

- Replace the simple TOML key overlay with a full TOML AST merge if nested arrays become important.
- Add a dependency validator so profiles cannot disable a required library while leaving dependents enabled.
- Add richer option widgets for arbitrary pack-defined profile settings.
- Add launcher documentation for running the Prism helper automatically before instance launch.
