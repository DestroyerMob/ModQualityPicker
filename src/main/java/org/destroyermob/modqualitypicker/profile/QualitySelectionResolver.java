package org.destroyermob.modqualitypicker.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QualitySelectionResolver {
    private QualitySelectionResolver() {
    }

    public static EffectiveQualitySelection resolve(
            QualityProfile baseProfile,
            QualityPackDefinition definition,
            QualitySelection selection
    ) {
        Map<String, ModState> mods = new LinkedHashMap<>(baseProfile.mods());
        Map<String, ConfigFileOverride> configs = new LinkedHashMap<>();
        for (ConfigFileOverride config : baseProfile.configFiles()) {
            configs.put(config.path(), config);
        }

        Map<String, String> effectiveChoices = new LinkedHashMap<>();
        for (FeatureGroup group : definition.orderedGroups()) {
            String choiceId = selection.featureOverrides().getOrDefault(
                    group.id(),
                    baseProfile.featureChoices().getOrDefault(group.id(), group.defaultChoice())
            );
            FeatureChoice choice = group.findChoice(choiceId).orElseThrow(() ->
                    new IllegalArgumentException("Unknown choice '" + choiceId + "' for feature group '" + group.id() + "'"));
            effectiveChoices.put(group.id(), choice.id());
            mods.putAll(choice.mods());
            for (ConfigFileOverride config : choice.configFiles()) {
                configs.put(config.path(), config);
            }
        }

        QualityProfile resolved = new QualityProfile(
                QualityProfile.SCHEMA_VERSION,
                baseProfile.id(),
                baseProfile.displayName(),
                baseProfile.sortOrder(),
                baseProfile.description(),
                mods,
                new ArrayList<>(configs.values()),
                baseProfile.options(),
                effectiveChoices
        );
        return new EffectiveQualitySelection(selection, resolved, effectiveChoices);
    }
}
