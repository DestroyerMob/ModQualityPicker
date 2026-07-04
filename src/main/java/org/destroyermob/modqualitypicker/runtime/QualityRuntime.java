package org.destroyermob.modqualitypicker.runtime;

import org.destroyermob.modqualitypicker.ModQualityPicker;
import org.destroyermob.modqualitypicker.config.ModQualityPickerConfig;
import org.destroyermob.modqualitypicker.configfile.ConfigFileManager;
import org.destroyermob.modqualitypicker.profile.ConfigFileOverride;
import org.destroyermob.modqualitypicker.profile.ModState;
import org.destroyermob.modqualitypicker.profile.PendingProfileChange;
import org.destroyermob.modqualitypicker.profile.ProfileOption;
import org.destroyermob.modqualitypicker.profile.ProfileRepository;
import org.destroyermob.modqualitypicker.profile.ProfileStore;
import org.destroyermob.modqualitypicker.profile.QualityProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QualityRuntime {
    public static final String CUSTOM_PROFILE_ID = "custom";
    public static final String CUSTOM_PROFILE_NAME = "Custom";
    private static final ProfileStore PROFILE_STORE = new ProfileStore();
    private static final ProfileRepository PROFILE_REPOSITORY = new ProfileRepository(PROFILE_STORE);
    private static final Pattern ACTIVE_PROFILE_ID = Pattern.compile("(?m)^\\s*activeProfileId\\s*=\\s*\"[^\"]*\"\\s*$");

    private QualityRuntime() {
    }

    public static void bootstrap() {
        try {
            Files.createDirectories(ProfilePaths.instanceRoot());
            Files.createDirectories(ProfilePaths.presetsRoot());
            ConfigFileManager.captureMissingDefaultConfigFiles(ProfilePaths.gameDirectory());

            if (ModQualityPickerConfig.WRITE_LAUNCH_SNAPSHOT.get()) {
                RuntimeSelection selection = currentSelection();
                PROFILE_STORE.writeSelection(ProfilePaths.activeSelection(), selection);
                ModQualityPicker.LOGGER.info("Captured Mod Quality Picker launch snapshot for profile '{}'", selection.activeProfileId());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not initialize Mod Quality Picker storage", exception);
        }
    }

    public static ProfileStore store() {
        return PROFILE_STORE;
    }

    public static ProfileRepository profiles() {
        return PROFILE_REPOSITORY;
    }

    public static Optional<QualityProfile> activeProfile() {
        String activeProfileId = ModQualityPickerConfig.activeProfileId();
        Optional<QualityProfile> preset = PROFILE_REPOSITORY.findPreset(activeProfileId);
        if (preset.isPresent()) {
            return preset;
        }
        return appliedProfile()
                .filter(profile -> profile.id().equals(activeProfileId));
    }

    public static String activeProfileId() {
        return ModQualityPickerConfig.activeProfileId();
    }

    public static RuntimeSelection currentSelection() {
        QualityProfile activeProfile = activeProfile().orElse(QualityProfile.empty(ModQualityPickerConfig.activeProfileId(), ModQualityPickerConfig.activeProfileId()));
        Map<String, String> configHashes = ConfigFileManager.hashConfigFiles(ProfilePaths.gameDirectory(), activeProfile);
        return LoadedModSnapshot.capture(activeProfile.id(), configHashes);
    }

    public static List<String> availableModIds(QualityProfile profile) {
        Set<String> modIds = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        modIds.addAll(LoadedModSnapshot.capture(profile.id()).enabledMods().keySet());
        modIds.addAll(profile.mods().keySet());
        modIds.addAll(ModJarCatalog.discoverModIds(ProfilePaths.gameDirectory()));
        return List.copyOf(modIds);
    }

    public static RuntimeSelection selectionFromProfile(QualityProfile profile) {
        Map<String, Boolean> enabledMods = new LinkedHashMap<>();
        profile.mods().forEach((modId, state) -> enabledMods.put(modId, state.enabled()));

        Map<String, String> configHashes = new LinkedHashMap<>();
        for (ConfigFileOverride configFile : profile.configFiles()) {
            if (!configFile.sha256().isBlank()) {
                configHashes.put(configFile.path(), configFile.sha256());
            }
        }
        return new RuntimeSelection(RuntimeSelection.SCHEMA_VERSION, profile.id(), enabledMods, configHashes);
    }

    public static Optional<QualityProfile> appliedProfile() {
        try {
            return PROFILE_STORE.readPendingProfile(ProfilePaths.appliedProfile())
                    .map(PendingProfileChange::profile);
        } catch (IOException exception) {
            ModQualityPicker.LOGGER.warn("Could not read applied Mod Quality Picker profile", exception);
            return Optional.empty();
        }
    }

    public static Optional<QualityProfile> findMatchingPreset(QualityProfile profile) throws IOException {
        for (QualityProfile preset : PROFILE_REPOSITORY.listPresets()) {
            if (profilesMatch(profile, preset)) {
                return Optional.of(preset);
            }
        }
        return Optional.empty();
    }

    public static QualityProfile profileForWorldSelection(QualityProfile worldProfile) throws IOException {
        return findMatchingPreset(worldProfile).orElseGet(() -> customProfile(worldProfile));
    }

    public static QualityProfile customProfile(QualityProfile source) {
        return new QualityProfile(
                QualityProfile.SCHEMA_VERSION,
                CUSTOM_PROFILE_ID,
                CUSTOM_PROFILE_NAME,
                0,
                "Temporary profile captured from a world.",
                source.mods(),
                source.configFiles(),
                source.options()
        );
    }

    public static boolean profilesMatch(QualityProfile left, QualityProfile right) throws IOException {
        return enabledModIds(left).equals(enabledModIds(right))
                && configHashes(left).equals(configHashes(right));
    }

    public static QualityProfile withRequiredDependencies(QualityProfile profile) throws IOException {
        return ModJarCatalog.withRequiredDependencies(ProfilePaths.gameDirectory(), profile);
    }

    public static QualityProfile captureCurrentProfile(String id, String displayName) {
        RuntimeSelection selection = LoadedModSnapshot.capture(id);
        Map<String, ModState> mods = new LinkedHashMap<>();
        selection.enabledMods().forEach((modId, enabled) -> mods.put(modId, new ModState(enabled, modId.equals(ModQualityPicker.MOD_ID), "")));

        List<ConfigFileOverride> configFiles = activeProfile().map(QualityProfile::configFiles).orElse(List.of()).stream()
                .map(configFile -> withCurrentHash(configFile, ProfilePaths.gameDirectory()))
                .toList();

        return new QualityProfile(
                QualityProfile.SCHEMA_VERSION,
                id,
                displayName,
                PROFILE_REPOSITORY.nextSortOrder(),
                "Captured from the currently loaded mod list.",
                mods,
                configFiles,
                Map.<String, ProfileOption>of()
        );
    }

    public static void queueProfileChange(QualityProfile profile, String reason, String sourceWorldId) throws IOException {
        QualityProfile resolvedProfile = withRequiredDependencies(profile);
        PROFILE_STORE.writePendingProfile(ProfilePaths.pendingProfile(), PendingProfileChange.of(resolvedProfile, reason, sourceWorldId));
        PROFILE_STORE.writeSelection(ProfilePaths.pendingSelection(), selectionFromProfile(resolvedProfile));
        ConfigFileManager.applyProfileConfigFiles(ProfilePaths.gameDirectory(), resolvedProfile);
        List<String> jarActions = prepareModJarsForProfile(resolvedProfile);
        PROFILE_STORE.writePendingProfile(ProfilePaths.appliedProfile(), PendingProfileChange.of(resolvedProfile, "active-profile", sourceWorldId));
        PROFILE_STORE.delete(ProfilePaths.pendingProfile());
        PROFILE_STORE.delete(ProfilePaths.pendingSelection());
        if (jarActions.isEmpty()) {
            ModQualityPicker.LOGGER.info("Applied Mod Quality Picker profile '{}' for the next launch; mod jars already match", profile.id());
        } else {
            ModQualityPicker.LOGGER.info("Applied Mod Quality Picker profile '{}' for the next launch: {}", profile.id(), String.join(", ", jarActions));
        }
    }

    public static void writeWorldProfile(Path worldDirectory, QualityProfile profile) throws IOException {
        PROFILE_STORE.writeProfile(ProfilePaths.worldProfile(worldDirectory), profile);
    }

    private static ConfigFileOverride withCurrentHash(ConfigFileOverride configFile, Path gameDirectory) {
        String hash = "";
        if (!configFile.path().isBlank()) {
            Path path = ConfigFileManager.resolveInside(gameDirectory, configFile.path());
            if (Files.isRegularFile(path)) {
                try {
                    hash = ConfigFileManager.sha256(path);
                } catch (IOException ignored) {
                    hash = "";
                }
            }
        }
        return new ConfigFileOverride(configFile.path(), configFile.mode(), configFile.presetFile(), hash);
    }

    private static Map<String, String> configHashes(QualityProfile profile) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (ConfigFileOverride configFile : profile.configFiles()) {
            if (!configFile.sha256().isBlank()) {
                hashes.put(configFile.path(), configFile.sha256());
            }
        }
        return hashes;
    }

    private static Set<String> enabledModIds(QualityProfile profile) throws IOException {
        Set<String> enabled = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        ModJarCatalog.resolveEnabledMods(ProfilePaths.gameDirectory(), profile).forEach((modId, isEnabled) -> {
            if (isEnabled) {
                enabled.add(modId);
            }
        });
        return enabled;
    }

    private static List<String> prepareModJarsForProfile(QualityProfile profile) throws IOException {
        QualityProfile resolvedProfile = withRequiredDependencies(profile);
        writeActiveProfileConfig(resolvedProfile.id());
        return ModJarCatalog.applyProfile(ProfilePaths.gameDirectory(), resolvedProfile);
    }

    private static void writeActiveProfileConfig(String profileId) throws IOException {
        ModQualityPickerConfig.ACTIVE_PROFILE_ID.set(profileId);

        Path configPath = ProfilePaths.gameDirectory().resolve("config").resolve(ModQualityPicker.MOD_ID + "-common.toml");
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String line = "activeProfileId = \"" + profileId + "\"";
        if (!Files.isRegularFile(configPath)) {
            Files.writeString(configPath, line + System.lineSeparator(), StandardCharsets.UTF_8);
            return;
        }

        String text = Files.readString(configPath, StandardCharsets.UTF_8);
        Matcher matcher = ACTIVE_PROFILE_ID.matcher(text);
        if (matcher.find()) {
            Files.writeString(configPath, matcher.replaceFirst(line), StandardCharsets.UTF_8);
            return;
        }

        String separator = text.endsWith(System.lineSeparator()) || text.endsWith("\n") ? "" : System.lineSeparator();
        Files.writeString(configPath, text + separator + line + System.lineSeparator(), StandardCharsets.UTF_8);
    }
}
