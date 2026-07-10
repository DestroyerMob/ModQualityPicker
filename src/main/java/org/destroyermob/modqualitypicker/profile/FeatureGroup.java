package org.destroyermob.modqualitypicker.profile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record FeatureGroup(
        String id,
        String displayName,
        String description,
        int sortOrder,
        FeatureScope scope,
        boolean playerAdjustable,
        String defaultChoice,
        Map<String, FeatureChoice> choices
) {
    public FeatureGroup {
        id = Objects.requireNonNullElse(id, "unnamed");
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        description = Objects.requireNonNullElse(description, "");
        sortOrder = Math.max(0, sortOrder);
        scope = scope == null ? FeatureScope.INSTANCE : scope;
        defaultChoice = Objects.requireNonNullElse(defaultChoice, "off");
        choices = choices == null || choices.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(choices));
    }

    public Optional<FeatureChoice> findChoice(String choiceId) {
        return Optional.ofNullable(choices.get(choiceId));
    }

    public FeatureChoice defaultChoiceDefinition() {
        FeatureChoice choice = choices.get(defaultChoice);
        return choice != null ? choice : choices.values().stream().findFirst().orElse(null);
    }
}
