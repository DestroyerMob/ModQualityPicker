package org.destroyermob.modqualitypicker.runtime;

import net.neoforged.fml.loading.FMLPaths;
import org.destroyermob.modqualitypicker.ModQualityPicker;

import java.nio.file.Path;

public final class ProfilePaths {
    private ProfilePaths() {
    }

    public static Path instanceRoot() {
        return FMLPaths.CONFIGDIR.get().resolve(ModQualityPicker.MOD_ID);
    }

    public static Path presetsRoot() {
        return instanceRoot().resolve("presets");
    }

    public static Path activeSelection() {
        return instanceRoot().resolve("active-selection.json");
    }

    public static Path pendingSelection() {
        return instanceRoot().resolve("pending-selection.json");
    }

    public static Path worldRoot(Path worldDirectory) {
        return worldDirectory.resolve(ModQualityPicker.MOD_ID);
    }

    public static Path worldProfile(Path worldDirectory) {
        return worldRoot(worldDirectory).resolve("quality-profile.json");
    }
}

