package org.destroyermob.modqualitypicker.profile;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record QualityPackDefinition(
        int schemaVersion,
        Map<String, FeatureGroup> groups
) {
    public static final int SCHEMA_VERSION = 2;

    public QualityPackDefinition {
        schemaVersion = schemaVersion <= 0 ? SCHEMA_VERSION : schemaVersion;
        groups = groups == null || groups.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(groups));
        validate(groups);
    }

    public static QualityPackDefinition empty() {
        return new QualityPackDefinition(SCHEMA_VERSION, Map.of());
    }

    public List<FeatureGroup> orderedGroups() {
        return groups.values().stream()
                .sorted(Comparator.comparingInt(FeatureGroup::sortOrder).thenComparing(FeatureGroup::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public String ownerOfMod(String modId) {
        for (FeatureGroup group : orderedGroups()) {
            boolean controls = group.choices().values().stream().anyMatch(choice -> choice.mods().containsKey(modId));
            if (controls) {
                return group.id();
            }
        }
        return "";
    }

    public QualityPackDefinition withGroup(FeatureGroup group) {
        Map<String, FeatureGroup> updated = new LinkedHashMap<>(groups);
        updated.put(group.id(), group);
        return new QualityPackDefinition(SCHEMA_VERSION, updated);
    }

    public QualityPackDefinition withoutGroup(String groupId) {
        Map<String, FeatureGroup> updated = new LinkedHashMap<>(groups);
        updated.remove(groupId);
        return new QualityPackDefinition(SCHEMA_VERSION, updated);
    }

    private static void validate(Map<String, FeatureGroup> groups) {
        Map<String, String> modOwners = new LinkedHashMap<>();
        for (Map.Entry<String, FeatureGroup> entry : groups.entrySet()) {
            FeatureGroup group = entry.getValue();
            if (group == null) {
                throw new IllegalArgumentException("Feature group is null: " + entry.getKey());
            }
            if (group.choices().isEmpty()) {
                throw new IllegalArgumentException("Feature group has no choices: " + group.id());
            }
            if (!group.choices().containsKey(group.defaultChoice())) {
                throw new IllegalArgumentException("Feature group '" + group.id() + "' has an unknown default choice: " + group.defaultChoice());
            }
            for (FeatureChoice choice : group.choices().values()) {
                for (String modId : choice.mods().keySet()) {
                    String previous = modOwners.putIfAbsent(modId, group.id());
                    if (previous != null && !previous.equals(group.id())) {
                        throw new IllegalArgumentException("Mod '" + modId + "' is controlled by both '" + previous + "' and '" + group.id() + "'");
                    }
                }
            }
        }
    }
}
