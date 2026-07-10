package org.destroyermob.modqualitypicker.profile;

public enum ApplyRequirement {
    LIVE,
    RESOURCE_RELOAD,
    RESTART,
    NEW_WORLD;

    public boolean requiresRestart() {
        return this == RESTART || this == NEW_WORLD;
    }
}
