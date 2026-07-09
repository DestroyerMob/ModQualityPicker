package org.destroyermob.modqualitypicker.runtime;

import java.util.List;

public record ProfileValidation(List<String> errors, List<String> warnings, List<String> actions) {
    public ProfileValidation {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public String exceptionMessage(String profileName) {
        String name = profileName == null || profileName.isBlank() ? "profile" : profileName;
        return "Profile " + name + " cannot be applied: " + String.join("; ", errors);
    }
}
