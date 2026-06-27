package org.destroyermob.modqualitypicker.profile;

import org.destroyermob.modqualitypicker.runtime.RuntimeSelection;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record ProfileDiff(
        boolean activeProfileMismatch,
        Set<String> missingEnabledMods,
        Set<String> loadedDisabledMods,
        Set<String> configHashMismatches
) {
    public ProfileDiff {
        missingEnabledMods = immutableSet(missingEnabledMods);
        loadedDisabledMods = immutableSet(loadedDisabledMods);
        configHashMismatches = immutableSet(configHashMismatches);
    }

    public boolean hasDifferences() {
        return activeProfileMismatch
                || !missingEnabledMods.isEmpty()
                || !loadedDisabledMods.isEmpty()
                || !configHashMismatches.isEmpty();
    }

    public static ProfileDiff compare(QualityProfile worldProfile, RuntimeSelection currentSelection) {
        Objects.requireNonNull(worldProfile, "worldProfile");
        Objects.requireNonNull(currentSelection, "currentSelection");

        boolean profileMismatch = !worldProfile.id().equals(currentSelection.activeProfileId());
        Set<String> missingEnabled = new TreeSet<>();
        Set<String> loadedDisabled = new TreeSet<>();
        Set<String> configMismatches = new TreeSet<>();

        worldProfile.mods().forEach((modId, desiredState) -> {
            boolean currentlyLoaded = currentSelection.enabledMods().getOrDefault(modId, false);
            if (desiredState.enabled() && !currentlyLoaded) {
                missingEnabled.add(modId);
            }
            if (!desiredState.enabled() && currentlyLoaded) {
                loadedDisabled.add(modId);
            }
        });

        for (ConfigFileOverride configFile : worldProfile.configFiles()) {
            if (configFile.sha256().isBlank()) {
                continue;
            }
            String currentHash = currentSelection.configHashes().get(configFile.path());
            if (!configFile.sha256().equalsIgnoreCase(currentHash)) {
                configMismatches.add(configFile.path());
            }
        }

        return new ProfileDiff(profileMismatch, missingEnabled, loadedDisabled, configMismatches);
    }

    private static Set<String> immutableSet(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}

