package org.destroyermob.modqualitypicker.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record ModState(boolean enabled, boolean locked, String reason, List<ConfigFileOverride> configFiles) {
    public ModState {
        reason = Objects.requireNonNullElse(reason, "");
        configFiles = configFiles == null || configFiles.isEmpty()
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(configFiles));
    }

    public ModState(boolean enabled, boolean locked, String reason) {
        this(enabled, locked, reason, List.of());
    }

    public static ModState enabledChoice() {
        return new ModState(true, false, "");
    }

    public static ModState implicitChoice() {
        return disabledChoice("");
    }

    public static ModState disabledChoice(String reason) {
        return new ModState(false, false, reason);
    }
}
