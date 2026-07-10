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
