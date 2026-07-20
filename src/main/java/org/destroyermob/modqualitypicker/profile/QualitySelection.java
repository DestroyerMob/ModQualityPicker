package org.destroyermob.modqualitypicker.profile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record QualitySelection(
        int schemaVersion,
        String baseProfileId,
        Map<String, String> featureOverrides,
        Map<String, String> modProfileOverrides
) {
    public static final int SCHEMA_VERSION = 3;

    public QualitySelection {
        schemaVersion = SCHEMA_VERSION;
        baseProfileId = normalizeId(Objects.requireNonNullElse(baseProfileId, "balanced"));
        featureOverrides = immutableMap(featureOverrides);
        modProfileOverrides = immutableMap(modProfileOverrides);
    }

    public QualitySelection(int schemaVersion, String baseProfileId, Map<String, String> featureOverrides) {
        this(schemaVersion, baseProfileId, featureOverrides, Map.of());
    }

    public static QualitySelection forBase(String profileId) {
        return new QualitySelection(SCHEMA_VERSION, profileId, Map.of(), Map.of());
    }

    public QualitySelection withBaseProfile(String profileId) {
        return new QualitySelection(SCHEMA_VERSION, profileId, Map.of(), Map.of());
    }

    public QualitySelection withOverride(String featureId, String choiceId) {
        Map<String, String> updated = new LinkedHashMap<>(featureOverrides);
        if (choiceId == null || choiceId.isBlank()) {
            updated.remove(featureId);
        } else {
            updated.put(normalizeId(featureId), normalizeId(choiceId));
        }
        return new QualitySelection(SCHEMA_VERSION, baseProfileId, updated, modProfileOverrides);
    }

    public QualitySelection withoutOverride(String featureId) {
        return withOverride(featureId, "");
    }

    public QualitySelection withModProfileOverride(String modId, String profileId) {
        Map<String, String> updated = new LinkedHashMap<>(modProfileOverrides);
        if (profileId == null || profileId.isBlank()) {
            updated.remove(normalizeId(modId));
        } else {
            updated.put(normalizeId(modId), normalizeId(profileId));
        }
        return new QualitySelection(SCHEMA_VERSION, baseProfileId, featureOverrides, updated);
    }

    public QualitySelection withoutModProfileOverride(String modId) {
        return withModProfileOverride(modId, "");
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
