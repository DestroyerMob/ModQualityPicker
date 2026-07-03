package org.destroyermob.modqualitypicker.profile;

import java.util.Objects;

public record ConfigFileOverride(
        String path,
        ConfigApplyMode mode,
        String presetFile,
        String sha256
) {
    public ConfigFileOverride {
        path = Objects.requireNonNullElse(path, "");
        mode = mode == null ? ConfigApplyMode.REPLACE_FILE : mode;
        presetFile = Objects.requireNonNullElse(presetFile, "");
        sha256 = Objects.requireNonNullElse(sha256, "");
    }

    public enum ConfigApplyMode {
        APPLY_DIFF,
        REPLACE_FILE,
        MERGE_TOML,
        KEEP_PLAYER
    }
}
