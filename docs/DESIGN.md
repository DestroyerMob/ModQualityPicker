# Mod Quality Picker Design Notes

Mod Quality Picker is a pack-developer tool for shipping multiple performance and quality experiences in one modpack. The important boundary is that Minecraft mods are discovered during launch, so the in-game UI can choose and queue a profile, but profile application must happen before the next launch finishes loading mods.

## Core Model

- A quality profile is a named preset with mod states, config file rules, and exposed player options.
- The current launch writes `config/modqualitypicker/active-selection.json`.
- Pending changes are written to `config/modqualitypicker/pending-selection.json` for the launcher/pre-launch applier to consume.
- Each world stores its desired profile at `<world>/modqualitypicker/quality-profile.json`.
- When a player tries to open a world, the client compares the world profile to the active launch snapshot.

## World Open Choices

When the world profile differs from the current launch, the UI should offer:

1. Continue using the currently loaded mod/config set.
2. Use the world's profile, write it as pending, and restart the instance.
3. Back out to the world list.

The first implementation should avoid altering loaded mods in-process. Enabling and disabling mods must be handled before NeoForge completes mod discovery on the next launch.

## Pack Developer Flow

Pack developers should be able to open the Mod Quality Picker screen from the mod list/config menu, create or edit profiles, mark mods as locked or player-editable, capture selected config files, and export those profiles into a distributable preset folder.

## Future Pieces

- Client profile editor screen.
- World-list mismatch prompt.
- Config file hashing and TOML merge support.
- Pre-launch applier for Prism Launcher instances.
- Pack export command that copies presets into the pack root.

