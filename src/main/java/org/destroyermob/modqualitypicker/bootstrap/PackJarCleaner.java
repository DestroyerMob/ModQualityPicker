package org.destroyermob.modqualitypicker.bootstrap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Conservative pack-owned jar cleanup. Unknown jars are treated as Prism/user-owned. */
final class PackJarCleaner {
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final String STATE_FILE = "jar-cleaner-state.json";
    private static final String MANIFEST_FILE = "managed-jars.json";

    record CleanupPlan(List<Path> deletePaths, List<String> warnings, CleanerState nextState, Path statePath) {
        CleanupPlan {
            deletePaths = List.copyOf(deletePaths);
            warnings = List.copyOf(warnings);
        }
    }

    record CleanerState(int schemaVersion, Set<String> managedFilenames, Set<String> protectedFilenames) {
        CleanerState {
            managedFilenames = managedFilenames == null ? Set.of() : Set.copyOf(managedFilenames);
            protectedFilenames = protectedFilenames == null ? Set.of() : Set.copyOf(protectedFilenames);
        }

        static CleanerState empty() {
            return new CleanerState(SCHEMA_VERSION, Set.of(), Set.of());
        }
    }

    record ManagedJarManifest(int schemaVersion, Set<String> filenames) {
        ManagedJarManifest {
            filenames = filenames == null ? Set.of() : Set.copyOf(filenames);
        }
    }

    private PackJarCleaner() {
    }

    static CleanupPlan plan(Path gameDirectory, Path profileRoot) throws IOException {
        Path modsDirectory = gameDirectory.resolve("mods");
        Path statePath = profileRoot.resolve(STATE_FILE);
        CleanerState previous = readState(statePath);
        Set<String> expected = expectedPackJars(gameDirectory, profileRoot);
        Set<String> presentActive = presentActiveJars(modsDirectory);
        Set<String> protectedNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        protectedNames.addAll(previous.protectedFilenames());

        // Anything never claimed by pack metadata is a Prism/user addition and remains protected.
        for (String fileName : presentActive) {
            if (!expected.contains(fileName) && !previous.managedFilenames().contains(fileName)) {
                protectedNames.add(fileName);
            }
        }

        List<Path> deletes = new ArrayList<>();
        for (String formerlyManaged : previous.managedFilenames()) {
            if (expected.contains(formerlyManaged)
                    || protectedNames.contains(formerlyManaged)
                    || formerlyManaged.toLowerCase().contains("modqualitypicker")
                    || !formerlyManaged.endsWith(".jar")) {
                continue;
            }
            Path stale = modsDirectory.resolve(formerlyManaged);
            if (Files.isRegularFile(stale)) {
                deletes.add(stale);
            }
        }

        List<String> warnings = new ArrayList<>();
        if (Files.isDirectory(modsDirectory)) {
            try (Stream<Path> files = Files.list(modsDirectory)) {
                for (Path duplicate : files.filter(Files::isRegularFile).sorted().toList()) {
                    String duplicateName = duplicate.getFileName().toString();
                    if (!duplicateName.endsWith(".jar.duplicate")) {
                        continue;
                    }
                    String activeName = duplicateName.substring(0, duplicateName.length() - ".duplicate".length());
                    Path active = modsDirectory.resolve(activeName);
                    if (expected.contains(activeName) && Files.isRegularFile(active) && sameContent(active, duplicate)) {
                        deletes.add(duplicate);
                    } else {
                        warnings.add("preserved unverified duplicate artifact " + duplicateName + " (not an identical copy of a current pack-owned jar)");
                    }
                }
            }
        }

        // Do not carry a name as protected if the pack explicitly owns it now.
        protectedNames.removeAll(expected);
        CleanerState next = new CleanerState(
                SCHEMA_VERSION,
                new TreeSet<>(expected),
                new TreeSet<>(protectedNames)
        );
        return new CleanupPlan(List.copyOf(new LinkedHashSet<>(deletes)), warnings, next, statePath);
    }

    static void apply(CleanupPlan plan) throws IOException {
        for (Path path : plan.deletePaths()) {
            String name = path.getFileName().toString();
            if (name.endsWith(".jar.disabled") || name.contains(".jar.disabled.")) {
                continue;
            }
            Files.deleteIfExists(path);
        }
        Files.createDirectories(plan.statePath().getParent());
        Files.writeString(plan.statePath(), GSON.toJson(plan.nextState()) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    static CleanupPlan preserve(CleanupPlan plan, Set<Path> protectedPaths) {
        Set<Path> normalized = new LinkedHashSet<>();
        protectedPaths.forEach(path -> normalized.add(path.toAbsolutePath().normalize()));
        List<Path> deletes = new ArrayList<>();
        List<String> warnings = new ArrayList<>(plan.warnings());
        Set<String> retainedManaged = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        retainedManaged.addAll(plan.nextState().managedFilenames());
        for (Path path : plan.deletePaths()) {
            if (normalized.contains(path.toAbsolutePath().normalize())) {
                warnings.add("preserved formerly pack-managed jar still enabled by the selected profile: " + path.getFileName());
                retainedManaged.add(path.getFileName().toString());
            } else {
                deletes.add(path);
            }
        }
        CleanerState next = new CleanerState(
                plan.nextState().schemaVersion(),
                retainedManaged,
                plan.nextState().protectedFilenames()
        );
        return new CleanupPlan(deletes, warnings, next, plan.statePath());
    }

    private static Set<String> expectedPackJars(Path gameDirectory, Path profileRoot) throws IOException {
        Set<String> expected = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Path packwizState = gameDirectory.resolve("packwiz.json");
        if (Files.isRegularFile(packwizState)) {
            try (Reader reader = Files.newBufferedReader(packwizState, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject cachedFiles = root.has("cachedFiles") && root.get("cachedFiles").isJsonObject()
                        ? root.getAsJsonObject("cachedFiles")
                        : new JsonObject();
                for (JsonElement element : cachedFiles.asMap().values()) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonElement location = element.getAsJsonObject().get("cachedLocation");
                    if (location == null || !location.isJsonPrimitive()) {
                        continue;
                    }
                    addManagedLocation(expected, location.getAsString());
                }
            } catch (RuntimeException exception) {
                throw new IOException("Invalid packwiz ownership state: " + packwizState, exception);
            }
        }

        Path explicitManifest = profileRoot.resolve(MANIFEST_FILE);
        if (Files.isRegularFile(explicitManifest)) {
            try (Reader reader = Files.newBufferedReader(explicitManifest, StandardCharsets.UTF_8)) {
                ManagedJarManifest manifest = GSON.fromJson(reader, ManagedJarManifest.class);
                if (manifest != null) {
                    for (String fileName : manifest.filenames()) {
                        addFileName(expected, fileName);
                    }
                }
            } catch (RuntimeException exception) {
                throw new IOException("Invalid managed jar manifest: " + explicitManifest, exception);
            }
        }
        return Set.copyOf(expected);
    }

    private static void addManagedLocation(Set<String> expected, String location) {
        String normalized = location.replace('\\', '/');
        if (!normalized.startsWith("mods/") || normalized.substring("mods/".length()).contains("/")) {
            return;
        }
        addFileName(expected, normalized.substring("mods/".length()));
    }

    private static void addFileName(Set<String> expected, String rawName) {
        if (rawName == null) {
            return;
        }
        String fileName = Path.of(rawName).getFileName().toString();
        if (fileName.endsWith(".jar")) {
            expected.add(fileName);
        }
    }

    private static Set<String> presentActiveJars(Path modsDirectory) throws IOException {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (!Files.isDirectory(modsDirectory)) {
            return names;
        }
        try (Stream<Path> files = Files.list(modsDirectory)) {
            files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".jar"))
                    .forEach(names::add);
        }
        return names;
    }

    private static CleanerState readState(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return CleanerState.empty();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            CleanerState state = GSON.fromJson(reader, CleanerState.class);
            return state == null ? CleanerState.empty() : state;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid jar cleaner state: " + path, exception);
        }
    }

    private static boolean sameContent(Path left, Path right) throws IOException {
        if (Files.size(left) != Files.size(right)) {
            return false;
        }
        return digest(left).equals(digest(right));
    }

    private static String digest(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
