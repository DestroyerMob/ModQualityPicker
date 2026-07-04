package org.destroyermob.modqualitypicker.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record QualityProfile(
        int schemaVersion,
        String id,
        String displayName,
        int sortOrder,
        String description,
        Map<String, ModState> mods,
        List<ConfigFileOverride> configFiles,
        Map<String, ProfileOption> options
) {
    public static final int SCHEMA_VERSION = 1;

    public QualityProfile {
        schemaVersion = schemaVersion <= 0 ? SCHEMA_VERSION : schemaVersion;
        id = normalizeId(id);
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        sortOrder = Math.max(0, sortOrder);
        description = Objects.requireNonNullElse(description, "");
        mods = immutableMap(mods);
        configFiles = immutableList(configFiles);
        options = immutableMap(options);
    }

    public static QualityProfile empty(String id, String displayName) {
        return new QualityProfile(SCHEMA_VERSION, id, displayName, 0, "", Map.of(), List.of(), Map.of());
    }

    public QualityProfile withSortOrder(int sortOrder) {
        return new QualityProfile(schemaVersion, id, displayName, sortOrder, description, mods, configFiles, options);
    }

    public QualityProfile withDisplayName(String displayName) {
        return new QualityProfile(schemaVersion, id, displayName, sortOrder, description, mods, configFiles, options);
    }

    private static String normalizeId(String id) {
        if (id == null || id.isBlank()) {
            return "unnamed";
        }
        return id.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static <T> List<T> immutableList(List<T> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
