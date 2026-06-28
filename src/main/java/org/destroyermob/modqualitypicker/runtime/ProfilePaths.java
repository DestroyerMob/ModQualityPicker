package org.destroyermob.modqualitypicker.runtime;

import net.neoforged.fml.loading.FMLPaths;
import org.destroyermob.modqualitypicker.ModQualityPicker;
import org.destroyermob.modqualitypicker.config.ModQualityPickerConfig;

import java.nio.file.Path;

public final class ProfilePaths {
    private ProfilePaths() {
    }

    public static Path instanceRoot() {
        return FMLPaths.CONFIGDIR.get().resolve(ModQualityPicker.MOD_ID);
    }

    public static Path gameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    public static Path presetsRoot() {
        return instanceRoot().resolve("presets");
    }

    public static Path preset(String profileId) {
        String fileName = profileId.replaceAll("[^a-z0-9_.-]", "_") + ".json";
        return presetsRoot().resolve(fileName);
    }

    public static Path activeSelection() {
        return instanceRoot().resolve("active-selection.json");
    }

    public static Path pendingSelection() {
        return instanceRoot().resolve("pending-selection.json");
    }

    public static Path pendingProfile() {
        return instanceRoot().resolve("pending-profile.json");
    }

    public static Path packExportRoot() {
        Path configured = Path.of(ModQualityPickerConfig.PACK_EXPORT_ROOT.get());
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        return gameDirectory().resolve(configured).normalize();
    }

    public static Path worldRoot(Path worldDirectory) {
        return worldDirectory.resolve(ModQualityPicker.MOD_ID);
    }

    public static Path worldProfile(Path worldDirectory) {
        return worldRoot(worldDirectory).resolve("quality-profile.json");
    }
}
