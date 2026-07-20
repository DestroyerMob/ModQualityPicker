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

    public FeatureGroup withDisplayName(String displayName) {
        return new FeatureGroup(id, displayName, description, sortOrder, scope, playerAdjustable, defaultChoice, choices);
    }

    public FeatureGroup withScope(FeatureScope scope) {
        return new FeatureGroup(id, displayName, description, sortOrder, scope, playerAdjustable, defaultChoice, choices);
    }

    public FeatureGroup withPlayerAdjustable(boolean playerAdjustable) {
        return new FeatureGroup(id, displayName, description, sortOrder, scope, playerAdjustable, defaultChoice, choices);
    }

    public FeatureGroup withDefaultChoice(String defaultChoice) {
        return new FeatureGroup(id, displayName, description, sortOrder, scope, playerAdjustable, defaultChoice, choices);
    }

    public FeatureGroup withChoices(Map<String, FeatureChoice> choices) {
        String nextDefault = choices.containsKey(defaultChoice)
                ? defaultChoice
                : choices.keySet().stream().findFirst().orElse(defaultChoice);
        return new FeatureGroup(id, displayName, description, sortOrder, scope, playerAdjustable, nextDefault, choices);
    }
}
