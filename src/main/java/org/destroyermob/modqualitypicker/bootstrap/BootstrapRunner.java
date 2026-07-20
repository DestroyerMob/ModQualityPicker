package org.destroyermob.modqualitypicker.bootstrap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.destroyermob.modqualitypicker.configfile.ConfigFileManager;
import org.destroyermob.modqualitypicker.configfile.UnifiedConfigDiff;
import org.destroyermob.modqualitypicker.profile.EffectiveQualitySelection;
import org.destroyermob.modqualitypicker.profile.PendingProfileChange;
import org.destroyermob.modqualitypicker.profile.ProfileStore;
import org.destroyermob.modqualitypicker.profile.QualityPackDefinition;
import org.destroyermob.modqualitypicker.profile.QualityProfile;
import org.destroyermob.modqualitypicker.profile.QualitySelection;
import org.destroyermob.modqualitypicker.profile.QualitySelectionResolver;
import org.destroyermob.modqualitypicker.runtime.ModJarCatalog;
import org.destroyermob.modqualitypicker.runtime.ProfileValidation;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Standalone profile applier loaded by {@link BootstrapLauncher}. */
public final class BootstrapRunner {
    private static final String MOD_ID = "modqualitypicker";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final ProfileStore STORE = new ProfileStore(GSON);
    private static final Pattern ACTIVE_PROFILE = Pattern.compile("(?m)^\\s*activeProfileId\\s*=\\s*\"[^\"]*\"\\s*$");

    private BootstrapRunner() {
    }

    private record InstancePaths(
            Path root,
            Path gameDirectory,
            Path modsDirectory,
            Path configDirectory,
            Path profileRoot
    ) {
        static InstancePaths fromRoot(Path supplied) {
            Path root = supplied.toAbsolutePath().normalize();
            Path game = Files.isDirectory(root.resolve("minecraft")) ? root.resolve("minecraft") : root;
            Path config = game.resolve("config");
            return new InstancePaths(root, game, game.resolve("mods"), config, config.resolve(MOD_ID));
        }

        Path presetsRoot() {
            return profileRoot.resolve("presets");
        }

        Path pendingProfile() {
            return profileRoot.resolve("pending-profile.json");
        }

        Path pendingSelection() {
            return profileRoot.resolve("pending-selection.json");
        }

        Path appliedProfile() {
            return profileRoot.resolve("applied-profile.json");
        }

        Path featureGroups() {
            return profileRoot.resolve("feature-groups.json");
        }

        Path commonConfig() {
            return configDirectory.resolve(MOD_ID + "-common.toml");
        }

        Path worldRoot(String worldId) {
            return gameDirectory.resolve("saves").resolve(worldId).resolve(MOD_ID);
        }
    }

    private record Options(Path instanceRoot, boolean dryRun, boolean keepPending, String profileId, String worldId) {
    }

    private record ResolvedRequest(QualityProfile profile, QualitySelection selection, String reason, String worldId) {
    }

    private record WorldDiffManifest(Map<String, WorldDiffEntry> entries) {
        WorldDiffManifest {
            entries = entries == null ? Map.of() : Map.copyOf(entries);
        }
    }

    private record WorldDiffEntry(String path, String diffFile) {
    }

    public static int run(String[] args) throws Exception {
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printHelp();
            return 0;
        }
        String command = args[0];
        Options options = parseOptions(args);
        return switch (command) {
            case "apply" -> apply(options);
            case "clean-jars" -> cleanJars(options);
            case "validate-profile" -> validateProfile(options);
            case "version", "--version" -> {
                System.out.println("Mod Quality Picker bootstrap 2");
                yield 0;
            }
            default -> {
                System.err.println("Unknown Mod Quality Picker command: " + command);
                printHelp();
                yield 2;
            }
        };
    }

    private static int apply(Options options) throws IOException {
        InstancePaths paths = InstancePaths.fromRoot(options.instanceRoot());
        Files.createDirectories(paths.profileRoot());
        FileTransaction.recoverIncomplete(paths.profileRoot());
        PackJarCleaner.CleanupPlan cleanupPlan = PackJarCleaner.plan(paths.gameDirectory(), paths.profileRoot());

        Optional<PendingProfileChange> pending = STORE.readPendingProfile(paths.pendingProfile());
        Optional<PendingProfileChange> previousApplied = STORE.readPendingProfile(paths.appliedProfile());
        ResolvedRequest request = resolveRequest(paths, pending, previousApplied);
        if (request == null) {
            printCleanupPlan(cleanupPlan);
            if (!options.dryRun()) {
                applyCleanupTransaction(paths, cleanupPlan);
            }
            System.out.println("No pending profile and no active preset were found.");
            return 0;
        }

        cleanupPlan = preserveEnabledProfileJars(paths, request.profile(), cleanupPlan);
        printCleanupPlan(cleanupPlan);

        ProfileValidation validation = ModJarCatalog.validateProfile(paths.gameDirectory(), request.profile());
        validation.actions().forEach(action -> System.out.println("DEPENDENCY " + action));
        validation.warnings().forEach(warning -> System.out.println("WARNING " + warning));
        validation.errors().forEach(error -> System.out.println("ERROR " + error));

        List<String> configErrors = new ArrayList<>(ConfigFileManager.validateProfileConfigFiles(
                paths.gameDirectory(), paths.profileRoot(), request.profile()
        ));
        configErrors.addAll(validateWorldDiffs(paths, request));
        configErrors.forEach(error -> System.out.println("ERROR " + error));
        if (validation.hasErrors() || !configErrors.isEmpty()) {
            return 1;
        }

        ModJarCatalog.JarPlan jarPlan = ModJarCatalog.planProfileChanges(paths.gameDirectory(), request.profile());
        jarPlan.operations().forEach(operation -> System.out.println(operation.action()));
        request.profile().configFiles().forEach(config -> {
            if (!config.path().isBlank()) {
                System.out.println(config.mode().name().toLowerCase(Locale.ROOT) + " " + config.path());
            }
        });
        worldDiffEntries(paths, request).forEach((target, diff) -> System.out.println("world_diff " + request.worldId() + ":" + paths.gameDirectory().relativize(target)));
        System.out.println("set activeProfileId = " + request.profile().id());
        if (options.dryRun()) {
            return 0;
        }

        QualityProfile appliedProfile = ModJarCatalog.withRequiredDependencies(paths.gameDirectory(), request.profile());
        PendingProfileChange receipt = new PendingProfileChange(
                PendingProfileChange.SCHEMA_VERSION,
                pending.isPresent() ? request.reason() : "active-profile",
                request.worldId(),
                Instant.now().toString(),
                appliedProfile,
                request.selection()
        );

        Set<Path> affected = new LinkedHashSet<>();
        jarPlan.operations().forEach(operation -> affected.addAll(operation.affectedPaths()));
        affected.addAll(ConfigFileManager.configTargetPaths(paths.gameDirectory(), request.profile()));
        affected.addAll(worldDiffEntries(paths, request).keySet());
        affected.add(paths.commonConfig());
        affected.add(paths.appliedProfile());
        affected.add(paths.pendingProfile());
        affected.add(paths.pendingSelection());
        affected.addAll(cleanupPlan.deletePaths());
        affected.add(cleanupPlan.statePath());

        try (FileTransaction transaction = FileTransaction.begin(paths.profileRoot())) {
            transaction.track(affected);
            transaction.markApplying();
            PackJarCleaner.apply(cleanupPlan);
            ModJarCatalog.applyJarOperations(jarPlan.operations());
            ConfigFileManager.applyProfileConfigFiles(paths.gameDirectory(), paths.profileRoot(), request.profile());
            applyWorldDiffs(paths, request);
            setActiveProfile(paths.commonConfig(), request.profile().id());
            STORE.writePendingProfile(paths.appliedProfile(), receipt);
            if (!options.keepPending()) {
                STORE.delete(paths.pendingProfile());
                STORE.delete(paths.pendingSelection());
            }
            transaction.commit();
        }
        return 0;
    }

    private static int cleanJars(Options options) throws IOException {
        InstancePaths paths = InstancePaths.fromRoot(options.instanceRoot());
        Files.createDirectories(paths.profileRoot());
        FileTransaction.recoverIncomplete(paths.profileRoot());
        PackJarCleaner.CleanupPlan plan = PackJarCleaner.plan(paths.gameDirectory(), paths.profileRoot());
        printCleanupPlan(plan);
        if (!options.dryRun()) {
            applyCleanupTransaction(paths, plan);
        }
        return 0;
    }

    private static PackJarCleaner.CleanupPlan preserveEnabledProfileJars(
            InstancePaths paths,
            QualityProfile profile,
            PackJarCleaner.CleanupPlan cleanupPlan
    ) throws IOException {
        Map<String, Boolean> enabled = ModJarCatalog.resolveEnabledMods(paths.gameDirectory(), profile);
        Map<String, ModJarCatalog.ModJar> jars = ModJarCatalog.discoverModJars(paths.gameDirectory());
        Set<Path> protectedPaths = new LinkedHashSet<>();
        enabled.forEach((modId, isEnabled) -> {
            ModJarCatalog.ModJar jar = jars.get(modId);
            if (Boolean.TRUE.equals(isEnabled) && jar != null) {
                protectedPaths.add(jar.path());
            }
        });
        return PackJarCleaner.preserve(cleanupPlan, protectedPaths);
    }

    private static void printCleanupPlan(PackJarCleaner.CleanupPlan plan) {
        plan.warnings().forEach(warning -> System.out.println("CLEANUP WARNING " + warning));
        plan.deletePaths().forEach(path -> System.out.println("CLEANUP remove " + path.getFileName()));
        if (plan.deletePaths().isEmpty()) {
            System.out.println("CLEANUP no stale pack-owned jars");
        }
    }

    private static void applyCleanupTransaction(InstancePaths paths, PackJarCleaner.CleanupPlan plan) throws IOException {
        Set<Path> affected = new LinkedHashSet<>(plan.deletePaths());
        affected.add(plan.statePath());
        try (FileTransaction transaction = FileTransaction.begin(paths.profileRoot())) {
            transaction.track(affected);
            transaction.markApplying();
            PackJarCleaner.apply(plan);
            transaction.commit();
        }
    }

    private static int validateProfile(Options options) throws IOException {
        InstancePaths paths = InstancePaths.fromRoot(options.instanceRoot());
        QualityPackDefinition definition = loadDefinition(paths);
        String profileId = options.profileId().isBlank() ? activeProfileId(paths.commonConfig()) : options.profileId();
        QualityProfile base = loadProfile(paths, profileId).orElseThrow(() -> new IOException("Profile not found: " + profileId));
        EffectiveQualitySelection effective = QualitySelectionResolver.resolve(base, loadProfiles(paths), definition, QualitySelection.forBase(profileId));
        ProfileValidation validation = ModJarCatalog.validateProfile(paths.gameDirectory(), effective.profile());
        validation.actions().forEach(action -> System.out.println("DEPENDENCY " + action));
        validation.warnings().forEach(warning -> System.out.println("WARNING " + warning));
        validation.errors().forEach(error -> System.out.println("ERROR " + error));
        List<String> configErrors = ConfigFileManager.validateProfileConfigFiles(paths.gameDirectory(), paths.profileRoot(), effective.profile());
        configErrors.forEach(error -> System.out.println("ERROR " + error));
        if (validation.hasErrors() || !configErrors.isEmpty()) {
            return 1;
        }
        System.out.println("Profile " + profileId + " is valid.");
        return 0;
    }

    private static ResolvedRequest resolveRequest(
            InstancePaths paths,
            Optional<PendingProfileChange> pending,
            Optional<PendingProfileChange> previousApplied
    ) throws IOException {
        QualityPackDefinition definition = loadDefinition(paths);
        if (pending.isPresent()) {
            PendingProfileChange change = pending.get();
            if ("world-profile".equals(change.reason())) {
                return new ResolvedRequest(change.profile(), change.selection(), change.reason(), change.sourceWorldId());
            }
            Optional<QualityProfile> base = loadProfile(paths, change.selection().baseProfileId());
            if (base.isPresent()) {
                EffectiveQualitySelection effective = QualitySelectionResolver.resolve(base.get(), loadProfiles(paths), definition, change.selection());
                return new ResolvedRequest(effective.profile(), effective.selection(), change.reason(), change.sourceWorldId());
            }
            return new ResolvedRequest(change.profile(), change.selection(), change.reason(), change.sourceWorldId());
        }

        String activeId = activeProfileId(paths.commonConfig());
        QualitySelection selection = previousApplied
                .map(PendingProfileChange::selection)
                .filter(candidate -> candidate.baseProfileId().equals(activeId))
                .orElseGet(() -> QualitySelection.forBase(activeId));
        Optional<QualityProfile> base = loadProfile(paths, selection.baseProfileId());
        if (base.isPresent()) {
            EffectiveQualitySelection effective = QualitySelectionResolver.resolve(base.get(), loadProfiles(paths), definition, selection);
            return new ResolvedRequest(effective.profile(), effective.selection(), "active-profile", "");
        }
        return previousApplied
                .filter(change -> change.profile().id().equals(activeId))
                .map(change -> new ResolvedRequest(change.profile(), change.selection(), "active-profile", ""))
                .orElse(null);
    }

    private static QualityPackDefinition loadDefinition(InstancePaths paths) throws IOException {
        return STORE.readPackDefinition(paths.featureGroups()).orElseGet(QualityPackDefinition::empty);
    }

    private static Optional<QualityProfile> loadProfile(InstancePaths paths, String profileId) throws IOException {
        String file = profileId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_") + ".json";
        return STORE.readProfile(paths.presetsRoot().resolve(file));
    }

    private static List<QualityProfile> loadProfiles(InstancePaths paths) throws IOException {
        if (!Files.isDirectory(paths.presetsRoot())) {
            return List.of();
        }
        List<QualityProfile> profiles = new ArrayList<>();
        try (var files = Files.list(paths.presetsRoot())) {
            for (Path path : files.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().toList()) {
                STORE.readProfile(path).ifPresent(profiles::add);
            }
        }
        return profiles;
    }

    private static List<String> validateWorldDiffs(InstancePaths paths, ResolvedRequest request) {
        List<String> errors = new ArrayList<>();
        try {
            for (Map.Entry<Path, Path> entry : worldDiffEntries(paths, request).entrySet()) {
                if (!Files.isRegularFile(entry.getValue())) {
                    errors.add("Missing world config diff: " + entry.getValue());
                    continue;
                }
                List<String> base = Files.isRegularFile(entry.getKey()) ? Files.readAllLines(entry.getKey()) : List.of();
                UnifiedConfigDiff.apply(base, Files.readAllLines(entry.getValue()));
            }
        } catch (IOException | IllegalArgumentException exception) {
            errors.add("Invalid world config diff: " + exception.getMessage());
        }
        return errors;
    }

    private static void applyWorldDiffs(InstancePaths paths, ResolvedRequest request) throws IOException {
        for (Map.Entry<Path, Path> entry : worldDiffEntries(paths, request).entrySet()) {
            List<String> base = Files.isRegularFile(entry.getKey()) ? Files.readAllLines(entry.getKey()) : List.of();
            List<String> output = UnifiedConfigDiff.apply(base, Files.readAllLines(entry.getValue()));
            Path parent = entry.getKey().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(entry.getKey(), output, StandardCharsets.UTF_8);
        }
    }

    private static Map<Path, Path> worldDiffEntries(InstancePaths paths, ResolvedRequest request) throws IOException {
        if (request.worldId() == null || request.worldId().isBlank()) {
            return Map.of();
        }
        Path worldRoot = paths.worldRoot(request.worldId());
        Path manifestPath = worldRoot.resolve("config-diffs.json");
        if (!Files.isRegularFile(manifestPath)) {
            return Map.of();
        }
        WorldDiffManifest manifest = read(manifestPath, WorldDiffManifest.class);
        Map<Path, Path> entries = new LinkedHashMap<>();
        for (Map.Entry<String, WorldDiffEntry> item : manifest.entries().entrySet()) {
            WorldDiffEntry value = item.getValue();
            String relativePath = value != null && value.path() != null && !value.path().isBlank() ? value.path() : item.getKey();
            if (value == null || value.diffFile() == null || value.diffFile().isBlank()) {
                continue;
            }
            Path target = ConfigFileManager.resolveInside(paths.gameDirectory(), relativePath);
            Path diff = ConfigFileManager.resolveInside(worldRoot, value.diffFile());
            entries.put(target, diff);
        }
        return Map.copyOf(entries);
    }

    private static String activeProfileId(Path configFile) {
        if (!Files.isRegularFile(configFile)) {
            return "balanced";
        }
        try {
            String text = Files.readString(configFile, StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("activeProfileId\\s*=\\s*\"([^\"]+)\"").matcher(text);
            return matcher.find() ? matcher.group(1) : "balanced";
        } catch (IOException exception) {
            return "balanced";
        }
    }

    private static void setActiveProfile(Path configFile, String profileId) throws IOException {
        Path parent = configFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String line = "activeProfileId = \"" + profileId + "\"";
        if (!Files.isRegularFile(configFile)) {
            Files.writeString(configFile, line + System.lineSeparator(), StandardCharsets.UTF_8);
            return;
        }
        String text = Files.readString(configFile, StandardCharsets.UTF_8);
        Matcher matcher = ACTIVE_PROFILE.matcher(text);
        String updated = matcher.find()
                ? matcher.replaceFirst(Matcher.quoteReplacement(line))
                : text + (text.endsWith("\n") ? "" : System.lineSeparator()) + line + System.lineSeparator();
        Files.writeString(configFile, updated, StandardCharsets.UTF_8);
    }

    private static <T> T read(Path path, Class<T> type) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            T value = GSON.fromJson(reader, type);
            if (value == null) {
                throw new IOException("Empty JSON file: " + path);
            }
            return value;
        } catch (JsonParseException exception) {
            throw new IOException("Invalid JSON in " + path, exception);
        }
    }

    private static Options parseOptions(String[] args) {
        Path instanceRoot = Path.of(".");
        boolean dryRun = false;
        boolean keepPending = false;
        String profileId = "";
        String worldId = "";
        for (int index = 1; index < args.length; index++) {
            switch (args[index]) {
                case "--instance-root" -> instanceRoot = Path.of(requireValue(args, ++index, "--instance-root"));
                case "--profile-id" -> profileId = requireValue(args, ++index, "--profile-id");
                case "--world-id" -> worldId = requireValue(args, ++index, "--world-id");
                case "--dry-run" -> dryRun = true;
                case "--keep-pending" -> keepPending = true;
                default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
            }
        }
        return new Options(instanceRoot, dryRun, keepPending, profileId, worldId);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }

    private static void printHelp() {
        System.out.println("Mod Quality Picker (self-contained Java pre-launch tool)");
        System.out.println("  apply --instance-root <path> [--world-id <id>] [--dry-run] [--keep-pending]");
        System.out.println("  clean-jars --instance-root <path> [--dry-run]");
        System.out.println("  validate-profile --instance-root <path> [--profile-id <id>]");
        System.out.println("  version");
    }
}
