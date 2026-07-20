package org.destroyermob.modqualitypicker.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record FeatureChoice(
        String id,
        String displayName,
        String description,
        Map<String, ModState> mods,
        List<ConfigFileOverride> configFiles,
        ApplyRequirement applyRequirement,
        boolean experimental
) {
    public FeatureChoice {
        id = Objects.requireNonNullElse(id, "unnamed");
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        description = Objects.requireNonNullElse(description, "");
        mods = mods == null || mods.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(mods));
        configFiles = configFiles == null || configFiles.isEmpty()
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(configFiles));
        applyRequirement = applyRequirement == null ? ApplyRequirement.RESTART : applyRequirement;
    }

    public FeatureChoice withDisplayName(String displayName) {
        return new FeatureChoice(id, displayName, description, mods, configFiles, applyRequirement, experimental);
    }

    public FeatureChoice withMods(Map<String, ModState> mods) {
        return new FeatureChoice(id, displayName, description, mods, configFiles, applyRequirement, experimental);
    }

    public FeatureChoice withConfigFiles(List<ConfigFileOverride> configFiles) {
        return new FeatureChoice(id, displayName, description, mods, configFiles, applyRequirement, experimental);
    }

    public FeatureChoice withApplyRequirement(ApplyRequirement applyRequirement) {
        return new FeatureChoice(id, displayName, description, mods, configFiles, applyRequirement, experimental);
    }

    public FeatureChoice withExperimental(boolean experimental) {
        return new FeatureChoice(id, displayName, description, mods, configFiles, applyRequirement, experimental);
    }
}
