package org.destroyermob.modqualitypicker.profile;

import java.util.Objects;

public record ProfileOption(
        String label,
        String value,
        boolean locked,
        String description
) {
    public ProfileOption {
        label = Objects.requireNonNullElse(label, "");
        value = Objects.requireNonNullElse(value, "");
        description = Objects.requireNonNullElse(description, "");
    }
}

