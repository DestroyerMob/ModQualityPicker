package org.destroyermob.modqualitypicker.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.destroyermob.modqualitypicker.runtime.RuntimeSelection;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class ProfileStore {
    private final Gson gson;

    public ProfileStore() {
        this(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create());
    }

    public ProfileStore(Gson gson) {
        this.gson = gson;
    }

    public Optional<QualityProfile> readProfile(Path path) throws IOException {
        return read(path, QualityProfile.class);
    }

    public void writeProfile(Path path, QualityProfile profile) throws IOException {
        write(path, profile);
    }

    public Optional<RuntimeSelection> readSelection(Path path) throws IOException {
        return read(path, RuntimeSelection.class);
    }

    public void writeSelection(Path path, RuntimeSelection selection) throws IOException {
        write(path, selection);
    }

    public Optional<PendingProfileChange> readPendingProfile(Path path) throws IOException {
        return read(path, PendingProfileChange.class);
    }

    public void writePendingProfile(Path path, PendingProfileChange change) throws IOException {
        write(path, change);
    }

    public void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    private <T> Optional<T> read(Path path, Class<T> type) throws IOException {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            return Optional.ofNullable(gson.fromJson(reader, type));
        } catch (JsonParseException exception) {
            throw new IOException("Invalid JSON in " + path, exception);
        }
    }

    private void write(Path path, Object value) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(path)) {
            gson.toJson(value, writer);
            writer.write(System.lineSeparator());
        }
    }
}
