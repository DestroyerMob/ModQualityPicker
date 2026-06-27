package org.destroyermob.modqualitypicker.runtime;

import org.destroyermob.modqualitypicker.ModQualityPicker;
import org.destroyermob.modqualitypicker.config.ModQualityPickerConfig;
import org.destroyermob.modqualitypicker.profile.ProfileStore;

import java.io.IOException;
import java.nio.file.Files;

public final class QualityRuntime {
    private static final ProfileStore PROFILE_STORE = new ProfileStore();

    private QualityRuntime() {
    }

    public static void bootstrap() {
        try {
            Files.createDirectories(ProfilePaths.instanceRoot());
            Files.createDirectories(ProfilePaths.presetsRoot());

            if (ModQualityPickerConfig.WRITE_LAUNCH_SNAPSHOT.get()) {
                RuntimeSelection selection = LoadedModSnapshot.capture(ModQualityPickerConfig.activeProfileId());
                PROFILE_STORE.writeSelection(ProfilePaths.activeSelection(), selection);
                ModQualityPicker.LOGGER.info("Captured Mod Quality Picker launch snapshot for profile '{}'", selection.activeProfileId());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not initialize Mod Quality Picker storage", exception);
        }
    }
}

