package org.destroyermob.modqualitypicker.profile;

import java.util.Objects;

public record ModState(boolean enabled, boolean locked, String reason) {
    public ModState {
        reason = Objects.requireNonNullElse(reason, "");
    }

    public static ModState enabledChoice() {
        return new ModState(true, false, "");
    }

    public static ModState disabledChoice(String reason) {
        return new ModState(false, false, reason);
    }
}

