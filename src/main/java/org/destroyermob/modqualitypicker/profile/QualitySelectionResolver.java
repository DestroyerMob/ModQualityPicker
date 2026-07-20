package org.destroyermob.modqualitypicker.profile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class QualitySelectionResolver {
    private QualitySelectionResolver() {
    }

    public static EffectiveQualitySelection resolve(
            QualityProfile baseProfile,
            QualityPackDefinition definition,
            QualitySelection selection
    ) {
        return resolve(baseProfile, List.of(baseProfile), definition, selection);
    }

    public static EffectiveQualitySelection resolve(
            QualityProfile baseProfile,
            Collection<QualityProfile> availableProfiles,
            QualityPackDefinition definition,
            QualitySelection selection
    ) {
        Map<String, QualityProfile> profilesById = new LinkedHashMap<>();
        for (QualityProfile profile : availableProfiles) {
            profilesById.put(profile.id(), profile);
        }
        profilesById.putIfAbsent(baseProfile.id(), baseProfile);

        Map<String, ModState> mods = new LinkedHashMap<>(baseProfile.mods());
        Map<String, ConfigFileOverride> configs = new LinkedHashMap<>();
        for (ConfigFileOverride config : baseProfile.configFiles()) {
            configs.put(config.path(), config);
        }
        Map<String, Set<String>> knownModConfigPaths = knownModConfigPaths(profilesById.values(), definition);
        for (Map.Entry<String, ModState> entry : baseProfile.mods().entrySet()) {
            applyModConfig(configs, knownModConfigPaths.getOrDefault(entry.getKey(), Set.of()), entry.getValue());
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
            for (Map.Entry<String, ModState> entry : choice.mods().entrySet()) {
                mods.put(entry.getKey(), entry.getValue());
                applyModConfig(configs, knownModConfigPaths.getOrDefault(entry.getKey(), Set.of()), entry.getValue());
            }
            for (ConfigFileOverride config : choice.configFiles()) {
                configs.put(config.path(), config);
            }
        }

        Map<String, String> effectiveModProfiles = new LinkedHashMap<>();
        for (String modId : mods.keySet()) {
            effectiveModProfiles.put(modId, baseProfile.id());
        }
        for (Map.Entry<String, String> override : selection.modProfileOverrides().entrySet()) {
            QualityProfile sourceProfile = profilesById.get(override.getValue());
            if (sourceProfile == null) {
                throw new IllegalArgumentException("Unknown quality preset '" + override.getValue() + "' for mod '" + override.getKey() + "'");
            }
            ModState sourceState = sourceProfile.mods().get(override.getKey());
            if (sourceState == null) {
                throw new IllegalArgumentException("Quality preset '" + sourceProfile.id() + "' does not define mod '" + override.getKey() + "'");
            }
            mods.put(override.getKey(), sourceState);
            effectiveModProfiles.put(override.getKey(), sourceProfile.id());
            applyModConfig(configs, knownModConfigPaths.getOrDefault(override.getKey(), Set.of()), sourceState);
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
        return new EffectiveQualitySelection(selection, resolved, effectiveChoices, effectiveModProfiles);
    }

    private static Map<String, Set<String>> knownModConfigPaths(
            Collection<QualityProfile> profiles,
            QualityPackDefinition definition
    ) {
        Map<String, Set<String>> paths = new LinkedHashMap<>();
        for (QualityProfile profile : profiles) {
            profile.mods().forEach((modId, state) -> addConfigPaths(paths, modId, state));
        }
        for (FeatureGroup group : definition.orderedGroups()) {
            for (FeatureChoice choice : group.choices().values()) {
                choice.mods().forEach((modId, state) -> addConfigPaths(paths, modId, state));
            }
        }
        return paths;
    }

    private static void addConfigPaths(Map<String, Set<String>> paths, String modId, ModState state) {
        Set<String> modPaths = paths.computeIfAbsent(modId, ignored -> new HashSet<>());
        for (ConfigFileOverride config : state.configFiles()) {
            modPaths.add(config.path());
        }
    }

    private static void applyModConfig(
            Map<String, ConfigFileOverride> configs,
            Set<String> knownPaths,
            ModState state
    ) {
        knownPaths.forEach(configs::remove);
        if (!state.enabled()) {
            return;
        }
        for (ConfigFileOverride config : state.configFiles()) {
            configs.put(config.path(), config);
        }
    }
}
