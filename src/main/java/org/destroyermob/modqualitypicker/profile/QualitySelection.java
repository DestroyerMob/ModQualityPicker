package org.destroyermob.modqualitypicker.profile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record QualitySelection(
        int schemaVersion,
        String baseProfileId,
        Map<String, String> featureOverrides
) {
    public static final int SCHEMA_VERSION = 2;

    public QualitySelection {
        schemaVersion = schemaVersion <= 0 ? SCHEMA_VERSION : schemaVersion;
        baseProfileId = normalizeId(Objects.requireNonNullElse(baseProfileId, "balanced"));
        featureOverrides = immutableMap(featureOverrides);
    }

    public static QualitySelection forBase(String profileId) {
        return new QualitySelection(SCHEMA_VERSION, profileId, Map.of());
    }

    public QualitySelection withBaseProfile(String profileId) {
        return new QualitySelection(schemaVersion, profileId, Map.of());
    }

    public QualitySelection withOverride(String featureId, String choiceId) {
        Map<String, String> updated = new LinkedHashMap<>(featureOverrides);
        if (choiceId == null || choiceId.isBlank()) {
            updated.remove(featureId);
        } else {
            updated.put(normalizeId(featureId), normalizeId(choiceId));
        }
        return new QualitySelection(schemaVersion, baseProfileId, updated);
    }

    public QualitySelection withoutOverride(String featureId) {
        return withOverride(featureId, "");
    }

    private static String normalizeId(String value) {
        if (value == null || value.isBlank()) {
            return "unnamed";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    private static Map<String, String> immutableMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
