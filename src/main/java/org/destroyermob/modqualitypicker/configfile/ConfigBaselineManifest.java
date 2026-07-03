package org.destroyermob.modqualitypicker.configfile;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ConfigBaselineManifest(
        int schemaVersion,
        String updatedAt,
        Map<String, ConfigBaselineEntry> entries
) {
    public static final int SCHEMA_VERSION = 1;

    public ConfigBaselineManifest {
        schemaVersion = schemaVersion <= 0 ? SCHEMA_VERSION : schemaVersion;
        updatedAt = Objects.requireNonNullElse(updatedAt, "");
        entries = immutableMap(entries);
    }

    public static ConfigBaselineManifest empty() {
        return new ConfigBaselineManifest(SCHEMA_VERSION, "", Map.of());
    }

    public ConfigBaselineManifest withEntries(Map<String, ConfigBaselineEntry> entries) {
        return new ConfigBaselineManifest(SCHEMA_VERSION, Instant.now().toString(), entries);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
