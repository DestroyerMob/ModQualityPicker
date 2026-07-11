package org.destroyermob.modqualitypicker.bootstrap;

import org.destroyermob.modqualitypicker.profile.ApplyRequirement;
import org.destroyermob.modqualitypicker.profile.ConfigFileOverride;
import org.destroyermob.modqualitypicker.profile.FeatureChoice;
import org.destroyermob.modqualitypicker.profile.FeatureGroup;
import org.destroyermob.modqualitypicker.profile.FeatureScope;
import org.destroyermob.modqualitypicker.profile.ModState;
import org.destroyermob.modqualitypicker.profile.PendingProfileChange;
import org.destroyermob.modqualitypicker.profile.ProfileStore;
import org.destroyermob.modqualitypicker.profile.QualityPackDefinition;
import org.destroyermob.modqualitypicker.profile.QualityProfile;
import org.destroyermob.modqualitypicker.profile.QualitySelection;
import org.destroyermob.modqualitypicker.runtime.ModJarCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class BootstrapApplySmoke {
    private BootstrapApplySmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path game = Files.createTempDirectory("modqualitypicker-bootstrap-smoke-");
        try {
            runFixture(game);
        } finally {
            try (var paths = Files.walk(game)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    private static void runFixture(Path game) throws Exception {
        runCleanerFixture(game.resolve("cleaner-fixture"));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Path profileRoot = Files.createDirectories(game.resolve("config/modqualitypicker"));
        Path presets = Files.createDirectories(profileRoot.resolve("presets"));
        Path featurePreset = profileRoot.resolve("features/ecology/max/ecology-common.toml");
        Files.createDirectories(featurePreset.getParent());
        Files.writeString(featurePreset, "gameplayPreset = \"FULL_SIMULATION\"\n", StandardCharsets.UTF_8);
        Files.writeString(game.resolve("config/ecology-common.toml"), "gameplayPreset = \"VANILLA_SAFE\"\n", StandardCharsets.UTF_8);
        Files.writeString(game.resolve("config/modqualitypicker-common.toml"), "activeProfileId = \"balanced\"\n", StandardCharsets.UTF_8);
        writeModJar(mods.resolve("ecology.jar.disabled"), "ecology");
        writeModJarMetadata(mods.resolve("library-bundle.jar"), """
                modLoader = "javafml"
                [[mods]]
                modId = "library_core"
                version = "1"
                [[mods]]
                modId = "library_api"
                version = "1"
                """);
        writeModJarMetadata(mods.resolve("consumer.jar"), """
                modLoader = "javafml"
                [[mods]]
                modId = "consumer"
                version = "1"
                [[dependencies.consumer]]
                modId = "library_core"
                type = "required"
                """);

        ModJarCatalog.ModInspection inspection = ModJarCatalog.inspectMods(game).get("library_api");
        require(inspection != null, "Bundled mod ID should have an inspection entry");
        require(inspection.providedModIds().equals(List.of("library_api", "library_core")), "Inspection should expose all IDs in the selected jar");
        require(inspection.dependentJars().size() == 1, "Inspection should group dependents by jar");
        require(inspection.dependentJars().getFirst().modIds().equals(List.of("consumer")), "Inspection should identify the dependent mod jar");
        require(inspection.dependentJars().getFirst().requiredProvidedIds().equals(List.of("library_core")), "Inspection should identify the provided ID used as the dependency");

        ConfigFileOverride ecologyConfig = new ConfigFileOverride(
                "config/ecology-common.toml",
                ConfigFileOverride.ConfigApplyMode.MERGE_TOML,
                "features/ecology/max/ecology-common.toml",
                ""
        );
        FeatureChoice off = new FeatureChoice(
                "off", "Off", "", Map.of("ecology", state(false)), List.of(), ApplyRequirement.RESTART, false
        );
        FeatureChoice max = new FeatureChoice(
                "max", "Full", "", Map.of("ecology", state(true)), List.of(ecologyConfig), ApplyRequirement.RESTART, true
        );
        FeatureGroup ecology = new FeatureGroup(
                "ecology", "Ecology", "", 10, FeatureScope.WORLD, true, "off", Map.of("off", off, "max", max)
        );
        QualityPackDefinition definition = new QualityPackDefinition(2, Map.of("ecology", ecology));
        QualityProfile balanced = new QualityProfile(
                2,
                "balanced",
                "Balanced",
                10,
                "",
                Map.of("ecology", state(false)),
                List.of(),
                Map.of(),
                Map.of("ecology", "off")
        );
        QualitySelection selection = QualitySelection.forBase("balanced").withOverride("ecology", "max");

        ProfileStore store = new ProfileStore();
        store.writePackDefinition(profileRoot.resolve("feature-groups.json"), definition);
        store.writeProfile(presets.resolve("balanced.json"), balanced);
        store.writePendingProfile(
                profileRoot.resolve("pending-profile.json"),
                PendingProfileChange.of(balanced, selection, "smoke-test", "")
        );

        int result = BootstrapRunner.run(new String[]{"apply", "--instance-root", game.toString()});
        require(result == 0, "Standalone apply should succeed");
        require(Files.isRegularFile(mods.resolve("ecology.jar")), "Ecology jar should be enabled");
        require(!Files.exists(mods.resolve("ecology.jar.disabled")), "Disabled Ecology jar should be moved");
        require(Files.readString(game.resolve("config/ecology-common.toml")).contains("FULL_SIMULATION"), "Ecology config overlay should apply");
        require(!Files.exists(profileRoot.resolve("pending-profile.json")), "Pending profile should be cleared after success");
        PendingProfileChange applied = store.readPendingProfile(profileRoot.resolve("applied-profile.json")).orElseThrow();
        require("max".equals(applied.selection().featureOverrides().get("ecology")), "Applied receipt should preserve feature overrides");
    }

    private static void runCleanerFixture(Path game) throws Exception {
        Path mods = Files.createDirectories(game.resolve("mods"));
        Path profileRoot = Files.createDirectories(game.resolve("config/modqualitypicker"));
        Path oldPackJar = mods.resolve("pack-old.jar");
        Path prismJar = mods.resolve("prism-added.jar");
        Path disabledJar = mods.resolve("leave-me.jar.disabled");
        writeModJar(oldPackJar, "pack_old");
        writeModJar(prismJar, "prism_added");
        writeModJar(disabledJar, "disabled_mod");
        writePackwizState(game, "pack-old.jar");

        PackJarCleaner.CleanupPlan initial = PackJarCleaner.plan(game, profileRoot);
        require(initial.deletePaths().isEmpty(), "First cleanup pass must not delete unknown Prism jars");
        PackJarCleaner.apply(initial);

        Path newPackJar = mods.resolve("pack-new.jar");
        Path duplicate = mods.resolve("pack-new.jar.duplicate");
        writeModJar(newPackJar, "pack_new");
        Files.copy(newPackJar, duplicate);
        writePackwizState(game, "pack-new.jar");

        PackJarCleaner.CleanupPlan updated = PackJarCleaner.plan(game, profileRoot);
        require(updated.deletePaths().contains(oldPackJar), "A previously pack-owned jar removed from pack metadata should be cleaned");
        require(updated.deletePaths().contains(duplicate), "An identical .jar.duplicate artifact should be cleaned");
        require(!updated.deletePaths().contains(prismJar), "Unknown Prism-added jars must remain protected");
        require(!updated.deletePaths().contains(disabledJar), "Disabled jars must remain outside cleanup scope");
        PackJarCleaner.apply(updated);

        require(!Files.exists(oldPackJar), "Stale pack-owned jar should be removed");
        require(!Files.exists(duplicate), "Verified duplicate artifact should be removed");
        require(Files.isRegularFile(prismJar), "Prism-added jar should be preserved");
        require(Files.isRegularFile(disabledJar), "Disabled jar should be preserved");
    }

    private static void writePackwizState(Path game, String fileName) throws IOException {
        Files.writeString(game.resolve("packwiz.json"), """
                {
                  "cachedFiles": {
                    "mods/example.pw.toml": {
                      "cachedLocation": "mods/%s"
                    }
                  }
                }
                """.formatted(fileName), StandardCharsets.UTF_8);
    }

    private static void writeModJar(Path path, String modId) throws IOException {
        writeModJarMetadata(path, "modLoader = \"javafml\"\n[[mods]]\nmodId = \"" + modId + "\"\nversion = \"1\"\n");
    }

    private static void writeModJarMetadata(Path path, String metadata) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry("META-INF/neoforge.mods.toml"));
            jar.write(metadata.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
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
