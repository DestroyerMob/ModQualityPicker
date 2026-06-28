package org.destroyermob.modqualitypicker.configfile;

import org.destroyermob.modqualitypicker.profile.ConfigFileOverride;
import org.destroyermob.modqualitypicker.profile.QualityProfile;
import org.destroyermob.modqualitypicker.runtime.ProfilePaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private static final Pattern SECTION = Pattern.compile("^\\s*\\[([^\\[\\]]+)]\\s*(?:#.*)?$");
    private static final Pattern KEY_VALUE = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*(.+)$");

    private ConfigFileManager() {
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
        for (ConfigFileOverride configFile : profile.configFiles()) {
            if (configFile.path().isBlank() || configFile.mode() == ConfigFileOverride.ConfigApplyMode.KEEP_PLAYER) {
                continue;
            }

            Path target = resolveInside(gameDirectory, configFile.path());
            Path preset = resolvePreset(configFile);
            if (!Files.isRegularFile(preset)) {
                continue;
            }

            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            if (configFile.mode() == ConfigFileOverride.ConfigApplyMode.MERGE_TOML) {
                mergeTomlOverlay(target, preset);
            } else {
                Files.copy(preset, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    public static List<String> listConfigFiles(Path gameDirectory) {
        Path configRoot = gameDirectory.resolve("config");
        if (!Files.isDirectory(configRoot)) {
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(configRoot)) {
            return paths
                    .filter(Files::isRegularFile)
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
        Path source = resolveInside(gameDirectory, relativePath);
        if (!Files.isRegularFile(source)) {
            throw new IOException("Config file does not exist: " + relativePath);
        }

        String presetFile = "presets/" + profile.id() + "/" + relativePath;
        Path destination = resolveInside(ProfilePaths.instanceRoot(), presetFile);
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
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
        String presetFile = configFile.presetFile().isBlank() ? configFile.path() : configFile.presetFile();
        return resolveInside(ProfilePaths.instanceRoot(), presetFile);
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
