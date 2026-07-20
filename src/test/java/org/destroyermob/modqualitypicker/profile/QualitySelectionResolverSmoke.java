package org.destroyermob.modqualitypicker.profile;

import java.util.List;
import java.util.Map;

public final class QualitySelectionResolverSmoke {
    private QualitySelectionResolverSmoke() {
    }

    public static void main(String[] args) {
        QualityPackDefinition definition = new QualityPackDefinition(2, Map.of(
                "ecology", group(
                        "ecology",
                        "off",
                        Map.of(
                                "off", choice("off", Map.of("ecology", state(false))),
                                "max", choice("max", Map.of("ecology", state(true)))
                        )
                ),
                "world_generation", group(
                        "world_generation",
                        "vanilla",
                        Map.of(
                                "vanilla", choice("vanilla", Map.of("terralith", state(false), "tectonic", state(false))),
                                "combined", choice("combined", Map.of("terralith", state(true), "tectonic", state(true)))
                        )
                )
        ));

        QualityProfile balanced = profile("balanced", Map.of("ecology", "off", "world_generation", "vanilla"));
        QualitySelection balancedWithEcology = QualitySelection.forBase("balanced").withOverride("ecology", "max");
        EffectiveQualitySelection mixed = QualitySelectionResolver.resolve(balanced, definition, balancedWithEcology);

        require(mixed.profile().mods().get("ecology").enabled(), "Balanced + Ecology Max should enable Ecology");
        require(!mixed.profile().mods().get("terralith").enabled(), "Balanced world generation should remain vanilla");
        require(!mixed.profile().mods().get("tectonic").enabled(), "Feature overrides must not leak into other groups");
        require("max".equals(mixed.effectiveChoices().get("ecology")), "Ecology override should be recorded");

        QualityProfile max = profile("max", Map.of("ecology", "max", "world_generation", "combined"));
        QualitySelection maxWithoutEcology = QualitySelection.forBase("max").withOverride("ecology", "off");
        EffectiveQualitySelection reduced = QualitySelectionResolver.resolve(max, definition, maxWithoutEcology);

        require(!reduced.profile().mods().get("ecology").enabled(), "Max + Ecology Off should disable Ecology");
        require(reduced.profile().mods().get("terralith").enabled(), "Max world generation should keep Terralith");
        require(reduced.profile().mods().get("tectonic").enabled(), "Max world generation should keep Tectonic");

        ConfigFileOverride balancedConfig = new ConfigFileOverride(
                "config/particles.toml",
                ConfigFileOverride.ConfigApplyMode.APPLY_DIFF,
                "presets/balanced/config/particles.toml.diff",
                ""
        );
        ConfigFileOverride maxConfig = new ConfigFileOverride(
                "config/particles.toml",
                ConfigFileOverride.ConfigApplyMode.APPLY_DIFF,
                "presets/max/config/particles.toml.diff",
                ""
        );
        QualityProfile balancedMods = modProfile("balanced", new ModState(true, false, "", List.of(balancedConfig)));
        QualityProfile maxMods = modProfile("max", new ModState(true, false, "", List.of(maxConfig)));
        QualitySelection perModMax = QualitySelection.forBase("balanced").withModProfileOverride("particular", "max");
        EffectiveQualitySelection perMod = QualitySelectionResolver.resolve(
                balancedMods,
                List.of(balancedMods, maxMods),
                QualityPackDefinition.empty(),
                perModMax
        );

        require("max".equals(perMod.effectiveModProfiles().get("particular")), "Per-mod preset source should be recorded");
        require(perMod.profile().configFiles().size() == 1, "Only one per-mod config overlay should resolve");
        require(perMod.profile().configFiles().getFirst().presetFile().contains("/max/"), "Per-mod override should use the selected preset config");
        require(perModMax.withBaseProfile("max").modProfileOverrides().isEmpty(), "Changing the global preset should reset per-mod overrides");

        ConfigFileOverride ownedConfig = new ConfigFileOverride(
                "config/owned.toml",
                ConfigFileOverride.ConfigApplyMode.APPLY_DIFF,
                "presets/max/config/owned.toml.diff",
                "hash"
        );
        QualityProfile editedProfile = PresetEditorModel.putModConfig(maxMods, "particular", ownedConfig);
        require(editedProfile.mods().get("particular").configFiles().contains(ownedConfig), "Developer model should attach config to a mod");
        require(editedProfile.configFiles().isEmpty(), "Mod-owned config must not leak into preset-wide configs");

        QualityPackDefinition editedDefinition = PresetEditorModel.putFeatureConfig(definition, "ecology", "max", ownedConfig);
        require(editedDefinition.groups().get("ecology").choices().get("max").configFiles().contains(ownedConfig),
                "Developer model should attach config to a feature choice");
        QualityProfile featureDefault = PresetEditorModel.withFeatureChoice(balanced, "ecology", "max");
        require("max".equals(featureDefault.featureChoices().get("ecology")), "Developer model should update a preset's feature default");

        List<String> configOwners = List.of("ecology", "embeddium", "distanthorizons");
        require("ecology".equals(ConfigOwnerResolver.inferModId("config/ecology-common.toml", configOwners)),
                "Config owner inference should recognize common filename suffixes");
        require("embeddium".equals(ConfigOwnerResolver.inferModId("config/embeddium-options.json", configOwners)),
                "Config owner inference should recognize option filename suffixes");
        require("distanthorizons".equals(ConfigOwnerResolver.inferModId("config/DistantHorizons.toml", configOwners)),
                "Config owner inference should ignore filename case");
    }

    private static QualityProfile profile(String id, Map<String, String> featureChoices) {
        return new QualityProfile(
                QualityProfile.SCHEMA_VERSION,
                id,
                id,
                0,
                "",
                Map.of(
                        "ecology", state(false),
                        "terralith", state(false),
                        "tectonic", state(false)
                ),
                List.of(),
                Map.of(),
                featureChoices
        );
    }

    private static FeatureGroup group(String id, String defaultChoice, Map<String, FeatureChoice> choices) {
        return new FeatureGroup(id, id, "", 0, FeatureScope.INSTANCE, true, defaultChoice, choices);
    }

    private static QualityProfile modProfile(String id, ModState state) {
        return new QualityProfile(
                QualityProfile.SCHEMA_VERSION,
                id,
                id,
                0,
                "",
                Map.of("particular", state),
                List.of(),
                Map.of(),
                Map.of()
        );
    }

    private static FeatureChoice choice(String id, Map<String, ModState> mods) {
        return new FeatureChoice(id, id, "", mods, List.of(), ApplyRequirement.RESTART, false);
    }

    private static ModState state(boolean enabled) {
        return new ModState(enabled, false, "");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
