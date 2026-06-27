package org.destroyermob.modqualitypicker.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record RuntimeSelection(
        int schemaVersion,
        String activeProfileId,
        Map<String, Boolean> enabledMods,
        Map<String, String> configHashes
) {
    public static final int SCHEMA_VERSION = 1;

    public RuntimeSelection {
        schemaVersion = schemaVersion <= 0 ? SCHEMA_VERSION : schemaVersion;
        activeProfileId = Objects.requireNonNullElse(activeProfileId, "balanced");
        enabledMods = immutableMap(enabledMods);
        configHashes = immutableMap(configHashes);
    }

    public static RuntimeSelection empty(String activeProfileId) {
        return new RuntimeSelection(SCHEMA_VERSION, activeProfileId, Map.of(), Map.of());
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}

