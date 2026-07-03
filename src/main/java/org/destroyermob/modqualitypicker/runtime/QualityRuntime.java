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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class QualityRuntime {
    private static final ProfileStore PROFILE_STORE = new ProfileStore();
    private static final ProfileRepository PROFILE_REPOSITORY = new ProfileRepository(PROFILE_STORE);

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
        return PROFILE_REPOSITORY.findPreset(ModQualityPickerConfig.activeProfileId());
    }

    public static RuntimeSelection currentSelection() {
        QualityProfile activeProfile = activeProfile().orElse(QualityProfile.empty(ModQualityPickerConfig.activeProfileId(), ModQualityPickerConfig.activeProfileId()));
        Map<String, String> configHashes = ConfigFileManager.hashConfigFiles(ProfilePaths.gameDirectory(), activeProfile);
        return LoadedModSnapshot.capture(activeProfile.id(), configHashes);
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
        PROFILE_STORE.writePendingProfile(ProfilePaths.pendingProfile(), PendingProfileChange.of(profile, reason, sourceWorldId));
        PROFILE_STORE.writeSelection(ProfilePaths.pendingSelection(), selectionFromProfile(profile));
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
}
