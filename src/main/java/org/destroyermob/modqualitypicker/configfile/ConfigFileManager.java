package org.destroyermob.modqualitypicker.configfile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.destroyermob.modqualitypicker.profile.ConfigFileOverride;
import org.destroyermob.modqualitypicker.profile.QualityProfile;
import org.destroyermob.modqualitypicker.runtime.ProfilePaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ConfigFileManager {
    private static final String MOD_ID = "modqualitypicker";
    private static final Pattern SECTION = Pattern.compile("^\\s*\\[([^\\[\\]]+)]\\s*(?:#.*)?$");
    private static final Pattern KEY_VALUE = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*(.+)$");
    private static final String DEFAULTS_ROOT = "defaults";
    private static final String DEFAULTS_MANIFEST = "defaults-manifest.json";
    private static final String DIFF_EXTENSION = ".diff";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private ConfigFileManager() {
    }

    public static void captureMissingDefaultConfigFiles(Path gameDirectory) throws IOException {
        ConfigBaselineManifest manifest = readDefaultManifest();
        Map<String, ConfigBaselineEntry> entries = new LinkedHashMap<>(manifest.entries());
        boolean changed = false;
        for (String relativePath : listConfigFiles(gameDirectory)) {
            Path baseline = captureDefaultConfigFileIfMissing(gameDirectory, relativePath);
            if (baseline != null && Files.isRegularFile(baseline)) {
                changed = updateDefaultManifestEntry(entries, relativePath, baseline) || changed;
            }
        }
        if (changed) {
            writeDefaultManifest(manifest.withEntries(entries));
        }
    }

    public static Map<String, String> hashConfigFiles(Path gameDirectory, QualityProfile profile) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (ConfigFileOverride configFile : profile.configFiles()) {
            if (configFile.path().isBlank()) {
                continue;
            }

            Path path = resolveInside(gameDirectory, configFile.path());
            if (!Files.isRegularFile(path)) {
                hashes.put(configFile.path(), "");
                continue;
            }

            try {
                hashes.put(configFile.path(), sha256(path));
            } catch (IOException exception) {
                hashes.put(configFile.path(), "");
            }
        }
        return hashes;
    }

    public static void applyProfileConfigFiles(Path gameDirectory, QualityProfile profile) throws IOException {
        applyProfileConfigFiles(gameDirectory, ProfilePaths.instanceRoot(), profile);
    }

    public static void applyProfileConfigFiles(Path gameDirectory, Path profileRoot, QualityProfile profile) throws IOException {
        for (ConfigFileOverride configFile : profile.configFiles()) {
            if (configFile.path().isBlank() || configFile.mode() == ConfigFileOverride.ConfigApplyMode.KEEP_PLAYER) {
                continue;
            }

            Path target = resolveInside(gameDirectory, configFile.path());
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            if (configFile.mode() == ConfigFileOverride.ConfigApplyMode.APPLY_DIFF) {
                applyDiffConfigFile(target, profileRoot, configFile);
            } else if (configFile.mode() == ConfigFileOverride.ConfigApplyMode.MERGE_TOML) {
                Path preset = resolvePreset(profileRoot, configFile);
                if (!Files.isRegularFile(preset)) {
                    throw new IOException("Missing config preset: " + configFile.presetFile());
                }
                mergeTomlOverlay(target, preset);
            } else {
                Path preset = resolvePreset(profileRoot, configFile);
                if (!Files.isRegularFile(preset)) {
                    throw new IOException("Missing config preset: " + configFile.presetFile());
                }
                Files.copy(preset, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    public static List<String> validateProfileConfigFiles(Path gameDirectory, Path profileRoot, QualityProfile profile) {
        List<String> errors = new ArrayList<>();
        for (ConfigFileOverride configFile : profile.configFiles()) {
            if (configFile.path().isBlank()) {
                errors.add("Config entry has no path");
                continue;
            }
            if (configFile.mode() == ConfigFileOverride.ConfigApplyMode.KEEP_PLAYER) {
                continue;
            }

            try {
                resolveInside(gameDirectory, configFile.path());
                if (configFile.mode() == ConfigFileOverride.ConfigApplyMode.APPLY_DIFF) {
                    Path baseline = resolveDefault(profileRoot, configFile.path());
                    Path diff = resolvePreset(profileRoot, configFile);
                    if (!Files.isRegularFile(baseline)) {
                        errors.add("Missing default config baseline: " + configFile.path());
                        continue;
                    }
                    if (!Files.isRegularFile(diff)) {
                        errors.add("Missing config diff: " + configFile.presetFile());
                        continue;
                    }
                    UnifiedConfigDiff.apply(Files.readAllLines(baseline), Files.readAllLines(diff));
                } else {
                    Path preset = resolvePreset(profileRoot, configFile);
                    if (!Files.isRegularFile(preset)) {
                        errors.add("Missing config preset: " + configFile.presetFile());
                    } else if (configFile.mode() == ConfigFileOverride.ConfigApplyMode.MERGE_TOML) {
                        collectTomlEntries(Files.readAllLines(preset));
                    }
                }
            } catch (IOException | IllegalArgumentException exception) {
                errors.add("Invalid config rule " + configFile.path() + ": " + exception.getMessage());
            }
        }
        return List.copyOf(errors);
    }

    public static List<Path> configTargetPaths(Path gameDirectory, QualityProfile profile) {
        List<Path> paths = new ArrayList<>();
        for (ConfigFileOverride configFile : profile.configFiles()) {
            if (!configFile.path().isBlank() && configFile.mode() != ConfigFileOverride.ConfigApplyMode.KEEP_PLAYER) {
                paths.add(resolveInside(gameDirectory, configFile.path()));
            }
        }
        return List.copyOf(paths);
    }

    public static List<String> listConfigFiles(Path gameDirectory) {
        Path configRoot = gameDirectory.resolve("config");
        if (!Files.isDirectory(configRoot)) {
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(configRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !isManagedConfigFile(configRoot, path))
                    .filter(ConfigFileManager::isSupportedConfigFile)
                    .map(path -> gameDirectory.relativize(path).toString().replace('\\', '/'))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    public static ConfigFileOverride captureConfigFile(
            Path gameDirectory,
            QualityProfile profile,
            String relativePath,
            ConfigFileOverride.ConfigApplyMode mode
    ) throws IOException {
        return captureConfigFile(gameDirectory, "presets/" + profile.id(), relativePath, mode);
    }

    public static ConfigFileOverride captureConfigFile(
            Path gameDirectory,
            String ownerRoot,
            String relativePath,
            ConfigFileOverride.ConfigApplyMode mode
    ) throws IOException {
        Path source = resolveInside(gameDirectory, relativePath);
        if (!Files.isRegularFile(source)) {
            throw new IOException("Config file does not exist: " + relativePath);
        }

        if (mode == ConfigFileOverride.ConfigApplyMode.KEEP_PLAYER) {
            return new ConfigFileOverride(relativePath, mode, "", sha256(source));
        }

        Path baseline = captureDefaultConfigFileIfMissing(gameDirectory, relativePath);
        if (baseline == null || !Files.isRegularFile(baseline)) {
            throw new IOException("Missing default config baseline: " + relativePath);
        }
        updateDefaultManifest(relativePath, baseline);

        String suffix = mode == ConfigFileOverride.ConfigApplyMode.APPLY_DIFF ? DIFF_EXTENSION : "";
        String presetFile = ownerRoot + "/" + relativePath + suffix;
        Path destination = resolveInside(ProfilePaths.instanceRoot(), presetFile);
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (mode == ConfigFileOverride.ConfigApplyMode.APPLY_DIFF) {
            List<String> diff = UnifiedConfigDiff.create(relativePath, Files.readAllLines(baseline), Files.readAllLines(source));
            Files.write(destination, diff);
        } else {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return new ConfigFileOverride(relativePath, mode, presetFile, sha256(source));
    }

    public static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }

        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        StringBuilder output = new StringBuilder();
        for (byte b : digest.digest()) {
            output.append(String.format(Locale.ROOT, "%02x", b));
        }
        return output.toString();
    }

    public static Path resolveInside(Path root, String relativePath) {
        Path relative = Path.of(relativePath);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Profile paths must be relative: " + relativePath);
        }

        Path normalizedRoot = root.normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Profile path escapes its root: " + relativePath);
        }
        return resolved;
    }

    private static Path resolvePreset(ConfigFileOverride configFile) {
        return resolvePreset(ProfilePaths.instanceRoot(), configFile);
    }

    private static Path resolvePreset(Path profileRoot, ConfigFileOverride configFile) {
        String presetFile = configFile.presetFile().isBlank() ? configFile.path() : configFile.presetFile();
        return resolveInside(profileRoot, presetFile);
    }

    private static Path resolveDefault(String relativePath) {
        return resolveDefault(ProfilePaths.instanceRoot(), relativePath);
    }

    private static Path resolveDefault(Path profileRoot, String relativePath) {
        return resolveInside(profileRoot, DEFAULTS_ROOT + "/" + relativePath);
    }

    private static Path captureDefaultConfigFileIfMissing(Path gameDirectory, String relativePath) throws IOException {
        Path baseline = resolveDefault(relativePath);
        if (Files.isRegularFile(baseline)) {
            return baseline;
        }

        Path source = resolveInside(gameDirectory, relativePath);
        if (!Files.isRegularFile(source)) {
            return null;
        }

        Path parent = baseline.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source, baseline, StandardCopyOption.COPY_ATTRIBUTES);
        return baseline;
    }

    private static ConfigBaselineManifest readDefaultManifest() throws IOException {
        Path manifest = ProfilePaths.instanceRoot().resolve(DEFAULTS_MANIFEST);
        if (!Files.isRegularFile(manifest)) {
            return ConfigBaselineManifest.empty();
        }
        try (Reader reader = Files.newBufferedReader(manifest)) {
            ConfigBaselineManifest parsed = GSON.fromJson(reader, ConfigBaselineManifest.class);
            return parsed == null ? ConfigBaselineManifest.empty() : parsed;
        } catch (JsonParseException exception) {
            throw new IOException("Invalid default config manifest: " + manifest, exception);
        }
    }

    private static void writeDefaultManifest(ConfigBaselineManifest manifest) throws IOException {
        Path path = ProfilePaths.instanceRoot().resolve(DEFAULTS_MANIFEST);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(manifest, writer);
            writer.write(System.lineSeparator());
        }
    }

    private static void updateDefaultManifest(String relativePath, Path baseline) throws IOException {
        ConfigBaselineManifest manifest = readDefaultManifest();
        Map<String, ConfigBaselineEntry> entries = new LinkedHashMap<>(manifest.entries());
        if (updateDefaultManifestEntry(entries, relativePath, baseline)) {
            writeDefaultManifest(manifest.withEntries(entries));
        }
    }

    private static boolean updateDefaultManifestEntry(Map<String, ConfigBaselineEntry> entries, String relativePath, Path baseline) throws IOException {
        ConfigBaselineEntry previous = entries.get(relativePath);
        String hash = sha256(baseline);
        long size = Files.size(baseline);
        String capturedAt = previous != null && previous.sha256().equals(hash) && previous.size() == size
                ? previous.capturedAt()
                : Instant.now().toString();
        ConfigBaselineEntry next = new ConfigBaselineEntry(relativePath, ownerHint(relativePath), hash, size, capturedAt);
        if (next.equals(previous)) {
            return false;
        }
        entries.put(relativePath, next);
        return true;
    }

    private static String ownerHint(String relativePath) {
        String path = relativePath.startsWith("config/") ? relativePath.substring("config/".length()) : relativePath;
        int slash = path.indexOf('/');
        String first = slash >= 0 ? path.substring(0, slash) : path;
        int dot = first.lastIndexOf('.');
        return dot > 0 ? first.substring(0, dot) : first;
    }

    private static void applyDiffConfigFile(Path target, Path profileRoot, ConfigFileOverride configFile) throws IOException {
        Path baseline = resolveDefault(profileRoot, configFile.path());
        if (!Files.isRegularFile(baseline)) {
            throw new IOException("Missing default config baseline: " + configFile.path());
        }

        List<String> baseLines = Files.readAllLines(baseline);
        Path diff = resolvePreset(profileRoot, configFile);
        if (!Files.isRegularFile(diff)) {
            throw new IOException("Missing config diff: " + configFile.presetFile());
        }
        List<String> diffLines = Files.readAllLines(diff);
        Files.write(target, UnifiedConfigDiff.apply(baseLines, diffLines));
    }

    private static boolean isSupportedConfigFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".toml")
                || fileName.endsWith(".json")
                || fileName.endsWith(".json5")
                || fileName.endsWith(".cfg")
                || fileName.endsWith(".properties")
                || fileName.endsWith(".snbt")
                || fileName.endsWith(".yaml")
                || fileName.endsWith(".yml")
                || fileName.endsWith(".ini")
                || fileName.endsWith(".txt");
    }

    private static boolean isManagedConfigFile(Path configRoot, Path path) {
        String relative = configRoot.relativize(path).toString().replace('\\', '/');
        return relative.equals(MOD_ID) || relative.startsWith(MOD_ID + "/");
    }

    private static void mergeTomlOverlay(Path target, Path overlay) throws IOException {
        List<String> targetLines = Files.exists(target) ? Files.readAllLines(target) : new ArrayList<>();
        List<String> overlayLines = Files.readAllLines(overlay);
        Map<String, String> overlayEntries = collectTomlEntries(overlayLines);
        List<String> merged = new ArrayList<>(targetLines);

        for (Map.Entry<String, String> entry : overlayEntries.entrySet()) {
            String section = sectionFromEntryKey(entry.getKey());
            String key = keyFromEntryKey(entry.getKey());
            int sectionStart = findSectionStart(merged, section);
            if (sectionStart < 0) {
                if (!merged.isEmpty() && !merged.get(merged.size() - 1).isBlank()) {
                    merged.add("");
                }
                if (!section.isBlank()) {
                    merged.add("[" + section + "]");
                }
                merged.add(entry.getValue());
                continue;
            }

            int sectionEnd = findSectionEnd(merged, sectionStart);
            int keyIndex = findKeyInSection(merged, key, sectionStart, sectionEnd);
            if (keyIndex >= 0) {
                merged.set(keyIndex, entry.getValue());
            } else {
                merged.add(sectionEnd, entry.getValue());
            }
        }

        Files.write(target, merged);
    }

    private static Map<String, String> collectTomlEntries(List<String> lines) {
        Map<String, String> entries = new LinkedHashMap<>();
        String section = "";
        for (String line : lines) {
            Matcher sectionMatcher = SECTION.matcher(line);
            if (sectionMatcher.matches()) {
                section = sectionMatcher.group(1).trim();
                continue;
            }

            Matcher keyMatcher = KEY_VALUE.matcher(line);
            if (keyMatcher.matches()) {
                entries.put(section + "\u0000" + keyMatcher.group(1), line);
            }
        }
        return entries;
    }

    private static String sectionFromEntryKey(String entryKey) {
        int split = entryKey.indexOf('\u0000');
        return split < 0 ? "" : entryKey.substring(0, split);
    }

    private static String keyFromEntryKey(String entryKey) {
        int split = entryKey.indexOf('\u0000');
        return split < 0 ? entryKey : entryKey.substring(split + 1);
    }

    private static int findSectionStart(List<String> lines, String section) {
        if (section.isBlank()) {
            return 0;
        }

        for (int index = 0; index < lines.size(); index++) {
            Matcher matcher = SECTION.matcher(lines.get(index));
            if (matcher.matches() && matcher.group(1).trim().equals(section)) {
                return index + 1;
            }
        }
        return -1;
    }

    private static int findSectionEnd(List<String> lines, int sectionStart) {
        for (int index = sectionStart; index < lines.size(); index++) {
            if (SECTION.matcher(lines.get(index)).matches()) {
                return index;
            }
        }
        return lines.size();
    }

    private static int findKeyInSection(List<String> lines, String key, int start, int end) {
        for (int index = start; index < end; index++) {
            Matcher matcher = KEY_VALUE.matcher(lines.get(index));
            if (matcher.matches() && matcher.group(1).equals(key)) {
                return index;
            }
        }
        return -1;
    }
}
