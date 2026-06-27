# Mod Quality Picker

Mod Quality Picker is a NeoForge 1.21.1 modpack tool for profile-based quality and performance selections.

The goal is to let a pack ship multiple curated experiences without splitting into separate modpacks. Pack developers define quality profiles, players can choose a profile or tweak allowed options, and worlds can remember the profile they expect.

## Initial Scope

This first scaffold contains:

- A compileable NeoForge mod project.
- Common config for the currently selected profile and mismatch policy.
- JSON models for quality profiles, config overrides, and runtime selections.
- Startup snapshot writing for the currently loaded mod list.
- Diff logic for comparing a world profile against the active launch selection.
- Design notes for the world-load and restart flow.

## Runtime Boundary

Mods are enabled or disabled during launch. This mod should not try to unload mods from inside a running game. Instead, it records the desired profile, prompts the player when a world needs a different profile, and queues the change for a restart/pre-launch applier.

## Building

Run:

```sh
./gradlew build
```

The runtime jar will be created under `build/libs/`.

## Current Status

Early scaffold. The profile model and launch snapshot are in place; the in-game editor, world-list prompt, and pre-launch applier are still upcoming.

