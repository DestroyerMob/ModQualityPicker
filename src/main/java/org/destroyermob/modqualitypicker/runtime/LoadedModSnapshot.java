package org.destroyermob.modqualitypicker.runtime;

import net.neoforged.fml.ModList;

import java.util.Map;
import java.util.TreeMap;

public final class LoadedModSnapshot {
    private LoadedModSnapshot() {
    }

    public static RuntimeSelection capture(String activeProfileId) {
        Map<String, Boolean> loadedMods = new TreeMap<>();
        for (var modInfo : ModList.get().getMods()) {
            loadedMods.put(modInfo.getModId(), true);
        }
        return new RuntimeSelection(RuntimeSelection.SCHEMA_VERSION, activeProfileId, loadedMods, Map.of());
    }
}

