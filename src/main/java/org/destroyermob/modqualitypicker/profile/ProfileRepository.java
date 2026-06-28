package org.destroyermob.modqualitypicker.profile;

import org.destroyermob.modqualitypicker.ModQualityPicker;
import org.destroyermob.modqualitypicker.runtime.ProfilePaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class ProfileRepository {
    private final ProfileStore store;

    public ProfileRepository(ProfileStore store) {
        this.store = store;
    }

    public List<QualityProfile> listPresets() {
        Path root = ProfilePaths.presetsRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        try (Stream<Path> files = Files.list(root)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .flatMap(this::readProfileStream)
                    .sorted(Comparator
                            .comparingInt(QualityProfile::sortOrder)
                            .thenComparing(QualityProfile::displayName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException exception) {
            ModQualityPicker.LOGGER.warn("Could not list quality profiles in {}", root, exception);
            return List.of();
        }
    }

    public Optional<QualityProfile> findPreset(String id) {
        String normalized = QualityProfile.empty(id, id).id();
        return listPresets().stream()
                .filter(profile -> profile.id().equals(normalized))
                .findFirst();
    }

    public void writePreset(QualityProfile profile) throws IOException {
        store.writeProfile(ProfilePaths.preset(profile.id()), profile);
    }

    public int nextSortOrder() {
        return listPresets().stream()
                .mapToInt(QualityProfile::sortOrder)
                .max()
                .orElse(0) + 10;
    }

    private Stream<QualityProfile> readProfileStream(Path path) {
        try {
            return store.readProfile(path).stream();
        } catch (IOException exception) {
            ModQualityPicker.LOGGER.warn("Could not read quality profile {}", path, exception);
            return Stream.empty();
        }
    }
}
