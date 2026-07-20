package org.destroyermob.modqualitypicker.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PresetEditorModel {
    private PresetEditorModel() {
    }

    public static QualityProfile putPresetConfig(QualityProfile profile, ConfigFileOverride config) {
        return withPresetConfigs(profile, putConfig(profile.configFiles(), config));
    }

    public static QualityProfile removePresetConfig(QualityProfile profile, String path) {
        return withPresetConfigs(profile, removeConfig(profile.configFiles(), path));
    }

    public static QualityProfile putModConfig(QualityProfile profile, String modId, ConfigFileOverride config) {
        ModState state = profile.mods().getOrDefault(modId, ModState.implicitChoice());
        ModState updatedState = new ModState(state.enabled(), state.locked(), state.reason(), putConfig(state.configFiles(), config));
        return putModState(profile, modId, updatedState);
    }

    public static QualityProfile removeModConfig(QualityProfile profile, String modId, String path) {
        ModState state = profile.mods().getOrDefault(modId, ModState.implicitChoice());
        ModState updatedState = new ModState(state.enabled(), state.locked(), state.reason(), removeConfig(state.configFiles(), path));
        return putModState(profile, modId, updatedState);
    }

    public static QualityProfile putModState(QualityProfile profile, String modId, ModState state) {
        Map<String, ModState> mods = new LinkedHashMap<>(profile.mods());
        mods.put(modId, state);
        return copyProfile(profile, mods, profile.configFiles(), profile.featureChoices());
    }

    public static QualityProfile withFeatureChoice(QualityProfile profile, String groupId, String choiceId) {
        Map<String, String> choices = new LinkedHashMap<>(profile.featureChoices());
        choices.put(groupId, choiceId);
        return copyProfile(profile, profile.mods(), profile.configFiles(), choices);
    }

    public static QualityProfile withoutFeatureChoice(QualityProfile profile, String groupId) {
        Map<String, String> choices = new LinkedHashMap<>(profile.featureChoices());
        choices.remove(groupId);
        return copyProfile(profile, profile.mods(), profile.configFiles(), choices);
    }

    public static QualityPackDefinition putFeatureConfig(
            QualityPackDefinition definition,
            String groupId,
            String choiceId,
            ConfigFileOverride config
    ) {
        return updateFeatureChoice(definition, groupId, choiceId, choice -> choice.withConfigFiles(putConfig(choice.configFiles(), config)));
    }

    public static QualityPackDefinition removeFeatureConfig(
            QualityPackDefinition definition,
            String groupId,
            String choiceId,
            String path
    ) {
        return updateFeatureChoice(definition, groupId, choiceId, choice -> choice.withConfigFiles(removeConfig(choice.configFiles(), path)));
    }

    public static QualityPackDefinition putFeatureModState(
            QualityPackDefinition definition,
            String groupId,
            String choiceId,
            String modId,
            ModState state
    ) {
        return updateFeatureChoice(definition, groupId, choiceId, choice -> {
            Map<String, ModState> mods = new LinkedHashMap<>(choice.mods());
            mods.put(modId, state);
            return choice.withMods(mods);
        });
    }

    public static QualityPackDefinition removeFeatureModState(
            QualityPackDefinition definition,
            String groupId,
            String choiceId,
            String modId
    ) {
        return updateFeatureChoice(definition, groupId, choiceId, choice -> {
            Map<String, ModState> mods = new LinkedHashMap<>(choice.mods());
            mods.remove(modId);
            return choice.withMods(mods);
        });
    }

    public static List<ConfigFileOverride> putConfig(List<ConfigFileOverride> configs, ConfigFileOverride config) {
        List<ConfigFileOverride> updated = new ArrayList<>(configs);
        updated.removeIf(item -> item.path().equals(config.path()));
        updated.add(config);
        return List.copyOf(updated);
    }

    public static List<ConfigFileOverride> removeConfig(List<ConfigFileOverride> configs, String path) {
        List<ConfigFileOverride> updated = new ArrayList<>(configs);
        updated.removeIf(item -> item.path().equals(path));
        return List.copyOf(updated);
    }

    private static QualityPackDefinition updateFeatureChoice(
            QualityPackDefinition definition,
            String groupId,
            String choiceId,
            java.util.function.UnaryOperator<FeatureChoice> update
    ) {
        FeatureGroup group = definition.groups().get(groupId);
        if (group == null) {
            throw new IllegalArgumentException("Unknown feature group: " + groupId);
        }
        FeatureChoice choice = group.choices().get(choiceId);
        if (choice == null) {
            throw new IllegalArgumentException("Unknown feature choice '" + choiceId + "' in " + groupId);
        }
        Map<String, FeatureChoice> choices = new LinkedHashMap<>(group.choices());
        choices.put(choiceId, update.apply(choice));
        return definition.withGroup(group.withChoices(choices));
    }

    private static QualityProfile withPresetConfigs(QualityProfile profile, List<ConfigFileOverride> configs) {
        return copyProfile(profile, profile.mods(), configs, profile.featureChoices());
    }

    private static QualityProfile copyProfile(
            QualityProfile profile,
            Map<String, ModState> mods,
            List<ConfigFileOverride> configFiles,
            Map<String, String> featureChoices
    ) {
        return new QualityProfile(
                QualityProfile.SCHEMA_VERSION,
                profile.id(),
                profile.displayName(),
                profile.sortOrder(),
                profile.description(),
                mods,
                configFiles,
                profile.options(),
                featureChoices
        );
    }
}
