package org.destroyermob.modqualitypicker.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.destroyermob.modqualitypicker.profile.ModState;
import org.destroyermob.modqualitypicker.profile.QualityProfile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ModJarCatalog {
    private static final String MOD_ID = "modqualitypicker";
    private static final System.Logger LOGGER = System.getLogger(ModJarCatalog.class.getName());
    private static final Pattern MOD_HEADER = Pattern.compile("^\\s*\\[\\[mods]]\\s*(?:#.*)?$");
    private static final Pattern DEPENDENCY_HEADER = Pattern.compile("^\\s*\\[\\[dependencies\\.[^]]+]]\\s*(?:#.*)?$");
    private static final Pattern TOML_STRING_VALUE = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*(['\"])(.*?)\\2\\s*(?:#.*)?$");
    private static final Pattern INLINE_MOD_ID = Pattern.compile("\\bmodId\\s*=\\s*(['\"])([^'\"]+)\\1");
    private static final Pattern TOML_BOOLEAN_VALUE = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*(true|false)\\s*(?:#.*)?$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_NESTED_JAR_DEPTH = 3;
    private static final Set<String> IGNORED_REQUIRED_MOD_IDS = Set.of(
            "fabricloader",
            "forge",
            "java",
            "minecraft",
            "neoforge"
    );

    public record ModJar(Path path, Set<String> modIds, Set<String> selectableModIds, Set<String> requiredModIds) {
    }

    public record DependentJar(String fileName, List<String> modIds, List<String> requiredProvidedIds) {
        public DependentJar {
            fileName = fileName == null ? "" : fileName;
            modIds = modIds == null ? List.of() : List.copyOf(modIds);
            requiredProvidedIds = requiredProvidedIds == null ? List.of() : List.copyOf(requiredProvidedIds);
        }
    }

    public record ModInspection(
            String selectedModId,
            String fileName,
            boolean jarCurrentlyEnabled,
            List<String> providedModIds,
            List<String> requiredModIds,
            List<DependentJar> dependentJars
    ) {
        public ModInspection {
            selectedModId = selectedModId == null ? "" : selectedModId;
            fileName = fileName == null ? "" : fileName;
            providedModIds = providedModIds == null ? List.of() : List.copyOf(providedModIds);
            requiredModIds = requiredModIds == null ? List.of() : List.copyOf(requiredModIds);
            dependentJars = dependentJars == null ? List.of() : List.copyOf(dependentJars);
        }
    }

    public enum JarOperationType {
        DELETE,
        MOVE,
        MOVE_REPLACE
    }

    public record JarOperation(JarOperationType type, Path source, Path target, String action) {
        public JarOperation {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(source, "source");
            action = action == null ? "" : action;
        }

        public Set<Path> affectedPaths() {
            return target == null ? Set.of(source) : Set.of(source, target);
        }
    }

    public record JarPlan(List<String> dependencyActions, List<JarOperation> operations) {
        public JarPlan {
            dependencyActions = dependencyActions == null ? List.of() : List.copyOf(dependencyActions);
            operations = operations == null ? List.of() : List.copyOf(operations);
        }

        public List<String> actions() {
            List<String> actions = new ArrayList<>(dependencyActions);
            operations.stream().map(JarOperation::action).forEach(actions::add);
            return List.copyOf(actions);
        }
    }

    public enum DisableStrategy {
        DEPENDENTS,
        JAR,
        UNUSED_DEPENDENCIES
    }

    public record DisableEntry(String modId, String reason) {
        public DisableEntry {
            modId = modId == null ? "" : modId;
            reason = reason == null ? "" : reason;
        }
    }

    public record DisablePlan(String rootModId, DisableStrategy strategy, List<DisableEntry> entries) {
        public DisablePlan {
            rootModId = rootModId == null ? "" : rootModId;
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        public List<String> modIds() {
            List<String> ids = new ArrayList<>();
            for (DisableEntry entry : entries) {
                ids.add(entry.modId());
            }
            return List.copyOf(ids);
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    private record ModMetadata(Set<String> modIds, Set<String> selectableModIds, Set<String> requiredModIds) {
        private static ModMetadata empty() {
            return new ModMetadata(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>());
        }
    }

    private record ResolvedProfile(Map<String, ModJar> discovered, Map<String, Boolean> desiredByMod, List<String> actions, Set<String> lockedDisabledModIds) {
    }

    private record CatalogCache(Path modsDirectory, List<String> fingerprint, Map<String, ModJar> discovered) {
    }

    private static CatalogCache catalogCache;

    private ModJarCatalog() {
    }

    public static Set<String> discoverModIds(Path gameDirectory) {
        Path modsDirectory = gameDirectory.resolve("mods");
        Set<String> discovered = new LinkedHashSet<>();
        if (!Files.isDirectory(modsDirectory)) {
            return discovered;
        }

        try {
            discoverModJars(gameDirectory).values().stream()
                    .distinct()
                    .forEach(jar -> discovered.addAll(jar.modIds()));
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not discover Mod Quality Picker jar catalog in " + modsDirectory, exception);
        }
        return discovered;
    }

    public static synchronized Map<String, ModJar> discoverModJars(Path gameDirectory) throws IOException {
        Path modsDirectory = gameDirectory.resolve("mods");
        if (!Files.isDirectory(modsDirectory)) {
            return Map.of();
        }

        List<String> fingerprint = catalogFingerprint(modsDirectory);
        if (catalogCache != null && catalogCache.modsDirectory().equals(modsDirectory) && catalogCache.fingerprint().equals(fingerprint)) {
            return catalogCache.discovered();
        }

        Map<String, ModJar> discovered = new HashMap<>();
        try (Stream<Path> files = Files.list(modsDirectory)) {
            for (Path path : files.sorted().toList()) {
                if (!isManagedJar(path)) {
                    continue;
                }

                ModMetadata metadata = readModMetadata(path);
                Set<String> modIds = new LinkedHashSet<>(metadata.modIds());
                Set<String> selectableModIds = new LinkedHashSet<>(metadata.selectableModIds());
                if (modIds.isEmpty()) {
                    String fallbackId = fallbackModId(path);
                    modIds.add(fallbackId);
                    selectableModIds.add(fallbackId);
                } else if (selectableModIds.isEmpty()) {
                    selectableModIds.addAll(modIds);
                }

                ModJar jar = new ModJar(path, Set.copyOf(modIds), Set.copyOf(selectableModIds), Set.copyOf(metadata.requiredModIds()));
                for (String modId : modIds) {
                    discovered.putIfAbsent(modId, jar);
                }
            }
        }
        Map<String, ModJar> cached = Map.copyOf(discovered);
        catalogCache = new CatalogCache(modsDirectory, fingerprint, cached);
        return cached;
    }

    public static Map<String, ModInspection> inspectMods(Path gameDirectory) throws IOException {
        Map<String, ModJar> discovered = discoverModJars(gameDirectory);
        Map<String, ModInspection> inspections = new LinkedHashMap<>();
        Set<ModJar> jars = new LinkedHashSet<>(discovered.values());
        for (ModJar selectedJar : jars) {
            List<String> providedIds = sortedIds(selectedJar.modIds());
            List<String> requiredIds = sortedIds(selectedJar.requiredModIds());
            List<DependentJar> dependents = new ArrayList<>();
            for (ModJar candidate : jars) {
                if (candidate.equals(selectedJar)) {
                    continue;
                }
                Set<String> matched = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (String requiredId : candidate.requiredModIds()) {
                    if (selectedJar.modIds().contains(requiredId)) {
                        matched.add(requiredId);
                    }
                }
                if (!matched.isEmpty()) {
                    dependents.add(new DependentJar(
                            candidate.path().getFileName().toString(),
                            sortedIds(candidate.modIds()),
                            List.copyOf(matched)
                    ));
                }
            }
            dependents.sort((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.fileName(), right.fileName()));
            boolean enabled = selectedJar.path().getFileName().toString().endsWith(".jar");
            for (String modId : selectedJar.modIds()) {
                inspections.put(modId, new ModInspection(
                        modId,
                        selectedJar.path().getFileName().toString(),
                        enabled,
                        providedIds,
                        requiredIds,
                        dependents
                ));
            }
        }
        return Map.copyOf(inspections);
    }

    private static List<String> sortedIds(Set<String> ids) {
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(sorted);
    }

    public static List<String> applyProfile(Path gameDirectory, QualityProfile profile) throws IOException {
        JarPlan plan = planProfileChanges(gameDirectory, profile);
        applyJarOperations(plan.operations());
        return plan.actions();
    }

    public static JarPlan planProfileChanges(Path gameDirectory, QualityProfile profile) throws IOException {
        ResolvedProfile resolved = resolveProfile(gameDirectory, profile);
        Map<String, ModJar> discovered = resolved.discovered();
        Map<String, Boolean> desiredByMod = resolved.desiredByMod();

        Map<Path, Boolean> desiredByJar = new HashMap<>();
        for (ModJar jar : new LinkedHashSet<>(discovered.values())) {
            boolean enabled = jar.selectableModIds().stream().anyMatch(modId -> Objects.equals(desiredByMod.get(modId), true));
            desiredByJar.put(jar.path(), enabled);
        }

        List<JarOperation> operations = new ArrayList<>();
        for (Map.Entry<Path, Boolean> entry : desiredByJar.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            Path jar = entry.getKey();
            boolean enabled = entry.getValue();
            String fileName = jar.getFileName().toString();
            if (enabled && fileName.endsWith(".jar.disabled")) {
                Path target = jar.resolveSibling(fileName.substring(0, fileName.length() - ".disabled".length()));
                if (Files.exists(target)) {
                    operations.add(new JarOperation(
                            JarOperationType.DELETE,
                            jar,
                            null,
                            "enable " + fileName + " already satisfied by " + target.getFileName() + "; removing duplicate disabled copy"
                    ));
                } else {
                    operations.add(new JarOperation(JarOperationType.MOVE, jar, target, "enable " + fileName + " -> " + target.getFileName()));
                }
            } else if (enabled && fileName.endsWith(".jar")) {
                Path duplicateDisabled = jar.resolveSibling(fileName + ".disabled");
                if (Files.exists(duplicateDisabled)) {
                    operations.add(new JarOperation(
                            JarOperationType.DELETE,
                            duplicateDisabled,
                            null,
                            "enable " + fileName + " already satisfied; removing duplicate disabled copy " + duplicateDisabled.getFileName()
                    ));
                }
            } else if (!enabled && fileName.endsWith(".jar")) {
                Path target = jar.resolveSibling(fileName + ".disabled");
                operations.add(new JarOperation(JarOperationType.MOVE_REPLACE, jar, target, "disable " + fileName + " -> " + target.getFileName()));
            } else if (!enabled && fileName.endsWith(".jar.disabled")) {
                Path duplicateEnabled = jar.resolveSibling(fileName.substring(0, fileName.length() - ".disabled".length()));
                if (Files.exists(duplicateEnabled)) {
                    operations.add(new JarOperation(
                            JarOperationType.DELETE,
                            duplicateEnabled,
                            null,
                            "disable " + fileName + " already satisfied; removing duplicate enabled copy " + duplicateEnabled.getFileName()
                    ));
                }
            }
        }

        return new JarPlan(resolved.actions(), operations);
    }

    public static void applyJarOperations(List<JarOperation> operations) throws IOException {
        for (JarOperation operation : operations) {
            switch (operation.type()) {
                case DELETE -> Files.deleteIfExists(operation.source());
                case MOVE -> Files.move(operation.source(), operation.target());
                case MOVE_REPLACE -> Files.move(operation.source(), operation.target(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public static QualityProfile withRequiredDependencies(Path gameDirectory, QualityProfile profile) throws IOException {
        ResolvedProfile resolved = resolveProfile(gameDirectory, profile);
        Map<String, ModState> mods = new LinkedHashMap<>(profile.mods());

        for (Map.Entry<String, Boolean> entry : resolved.desiredByMod().entrySet()) {
            String modId = entry.getKey();
            ModState current = mods.get(modId);
            if (entry.getValue() && current == null) {
                mods.put(modId, new ModState(true, modId.equals(MOD_ID), requiredReason(modId, resolved.actions())));
            } else if (entry.getValue() && !current.enabled()) {
                mods.put(modId, new ModState(true, true, requiredReason(modId, resolved.actions()), current.configFiles()));
            }
        }

        return new QualityProfile(
                profile.schemaVersion(),
                profile.id(),
                profile.displayName(),
                profile.sortOrder(),
                profile.description(),
                mods,
                profile.configFiles(),
                profile.options(),
                profile.featureChoices()
        );
    }

    public static Map<String, Boolean> resolveEnabledMods(Path gameDirectory, QualityProfile profile) throws IOException {
        return Map.copyOf(resolveProfile(gameDirectory, profile).desiredByMod());
    }

    public static ProfileValidation validateProfile(Path gameDirectory, QualityProfile profile) throws IOException {
        ResolvedProfile resolved = resolveProfile(gameDirectory, profile);
        return new ProfileValidation(
                validationErrors(resolved.discovered(), resolved.desiredByMod(), resolved.lockedDisabledModIds()),
                validationWarnings(resolved.discovered(), resolved.desiredByMod(), profile),
                resolved.actions()
        );
    }

    public static DisablePlan planDisable(Path gameDirectory, QualityProfile profile, String modId, DisableStrategy strategy) throws IOException {
        String rootModId = modId == null ? "" : modId;
        DisableStrategy resolvedStrategy = strategy == null ? DisableStrategy.DEPENDENTS : strategy;
        if (rootModId.isBlank()) {
            return new DisablePlan(rootModId, resolvedStrategy, List.of());
        }

        ResolvedProfile resolved = resolveProfile(gameDirectory, profile);
        Map<String, String> reasons = new LinkedHashMap<>();
        switch (resolvedStrategy) {
            case DEPENDENTS -> planDisableDependents(resolved.discovered(), resolved.desiredByMod(), rootModId, reasons);
            case JAR -> planDisableJar(resolved.discovered(), rootModId, reasons);
            case UNUSED_DEPENDENCIES -> planDisableUnusedDependencies(resolved.discovered(), resolved.desiredByMod(), rootModId, reasons);
        }

        List<DisableEntry> entries = new ArrayList<>();
        reasons.forEach((id, reason) -> entries.add(new DisableEntry(id, reason)));
        return new DisablePlan(rootModId, resolvedStrategy, entries);
    }

    private static ResolvedProfile resolveProfile(Path gameDirectory, QualityProfile profile) throws IOException {
        Map<String, ModJar> discovered = discoverModJars(gameDirectory);
        Map<String, Boolean> desiredByMod = new LinkedHashMap<>();
        List<String> actions = new ArrayList<>();

        for (ModJar jar : new LinkedHashSet<>(discovered.values())) {
            for (String modId : jar.modIds()) {
                desiredByMod.put(modId, false);
            }
        }
        for (Map.Entry<String, ModState> entry : profile.mods().entrySet()) {
            desiredByMod.put(entry.getKey(), entry.getValue().enabled());
        }
        desiredByMod.put(MOD_ID, true);

        Set<String> lockedDisabledModIds = lockedDisabledModIds(discovered, profile);
        actions.addAll(enableRequiredDependencies(discovered, desiredByMod, lockedDisabledModIds));
        enableProvidedRuntimeModules(desiredByMod);
        return new ResolvedProfile(discovered, desiredByMod, actions, lockedDisabledModIds);
    }

    private static void planDisableDependents(Map<String, ModJar> discovered, Map<String, Boolean> desiredByMod, String rootModId, Map<String, String> reasons) {
        addDisableReason(reasons, rootModId, "selected mod");
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String modId : new TreeSet<>(desiredByMod.keySet())) {
                if (!Objects.equals(desiredByMod.get(modId), true) || reasons.containsKey(modId)) {
                    continue;
                }

                ModJar jar = discovered.get(modId);
                if (jar == null) {
                    continue;
                }

                for (String disabledModId : new ArrayList<>(reasons.keySet())) {
                    if (jar.requiredModIds().contains(disabledModId)) {
                        int before = reasons.size();
                        addDisableReason(reasons, modId, "requires " + disabledModId);
                        changed = changed || reasons.size() != before;
                        break;
                    }
                }
            }
        }
    }

    private static void planDisableJar(Map<String, ModJar> discovered, String rootModId, Map<String, String> reasons) {
        ModJar jar = discovered.get(rootModId);
        if (jar == null) {
            addDisableReason(reasons, rootModId, "selected mod");
            return;
        }

        for (String siblingId : new TreeSet<>(jar.selectableModIds())) {
            addDisableReason(reasons, siblingId, siblingId.equals(rootModId) ? "selected mod" : "provided by the same jar");
        }
    }

    private static void planDisableUnusedDependencies(Map<String, ModJar> discovered, Map<String, Boolean> desiredByMod, String rootModId, Map<String, String> reasons) {
        addDisableReason(reasons, rootModId, "selected mod");
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<String> candidates = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (String disabledModId : reasons.keySet()) {
                ModJar jar = discovered.get(disabledModId);
                if (jar != null) {
                    candidates.addAll(jar.requiredModIds());
                }
            }

            for (String candidate : candidates) {
                if (reasons.containsKey(candidate) || !Objects.equals(desiredByMod.get(candidate), true) || !discovered.containsKey(candidate)) {
                    continue;
                }
                if (hasEnabledDependentOutside(discovered, desiredByMod, reasons.keySet(), candidate)) {
                    continue;
                }
                if (hasEnabledSiblingOutside(discovered, desiredByMod, reasons.keySet(), candidate)) {
                    continue;
                }

                int before = reasons.size();
                addDisableReason(reasons, candidate, "unused dependency");
                changed = changed || reasons.size() != before;
            }
        }
    }

    private static boolean hasEnabledDependentOutside(Map<String, ModJar> discovered, Map<String, Boolean> desiredByMod, Set<String> disabledModIds, String requiredModId) {
        for (Map.Entry<String, Boolean> entry : desiredByMod.entrySet()) {
            String modId = entry.getKey();
            if (!Objects.equals(entry.getValue(), true) || disabledModIds.contains(modId)) {
                continue;
            }

            ModJar jar = discovered.get(modId);
            if (jar != null && jar.requiredModIds().contains(requiredModId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEnabledSiblingOutside(Map<String, ModJar> discovered, Map<String, Boolean> desiredByMod, Set<String> disabledModIds, String modId) {
        ModJar jar = discovered.get(modId);
        if (jar == null) {
            return false;
        }

        for (String siblingId : jar.selectableModIds()) {
            if (!siblingId.equals(modId) && !disabledModIds.contains(siblingId) && Objects.equals(desiredByMod.get(siblingId), true)) {
                return true;
            }
        }
        return false;
    }

    private static void addDisableReason(Map<String, String> reasons, String modId, String reason) {
        if (modId == null || modId.isBlank() || modId.equals(MOD_ID)) {
            return;
        }
        reasons.putIfAbsent(modId, reason);
    }

    private static List<String> validationErrors(Map<String, ModJar> discovered, Map<String, Boolean> desiredByMod, Set<String> lockedDisabledModIds) {
        Set<String> errors = new LinkedHashSet<>();
        for (Map.Entry<String, Boolean> entry : desiredByMod.entrySet()) {
            if (!Objects.equals(entry.getValue(), true)) {
                continue;
            }

            ModJar jar = discovered.get(entry.getKey());
            if (jar == null) {
                continue;
            }

            for (String requiredModId : jar.requiredModIds()) {
                if (lockedDisabledModIds.contains(requiredModId) && discovered.containsKey(requiredModId)) {
                    errors.add("profile locks " + requiredModId + " disabled, but " + entry.getKey() + " requires it");
                }
            }
        }
        for (String modId : lockedDisabledModIds) {
            if (!discovered.containsKey(modId)) {
                continue;
            }
            List<String> siblings = enabledSiblingModIds(discovered, desiredByMod, modId);
            if (!siblings.isEmpty()) {
                errors.add(lockedBundledModError(modId, siblings));
            }
        }
        return List.copyOf(errors);
    }

    private static List<String> validationWarnings(Map<String, ModJar> discovered, Map<String, Boolean> desiredByMod, QualityProfile profile) {
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, ModState> entry : profile.mods().entrySet()) {
            String modId = entry.getKey();
            if (entry.getValue().enabled() && !discovered.containsKey(modId) && !modId.equals(MOD_ID) && !IGNORED_REQUIRED_MOD_IDS.contains(modId)) {
                warnings.add(missingModWarning(modId, discovered));
            }
            if (!entry.getValue().enabled() && !entry.getValue().locked() && discovered.containsKey(modId)) {
                if (Objects.equals(desiredByMod.get(modId), true)) {
                    List<String> dependents = enabledDependents(discovered, desiredByMod, modId);
                    if (!dependents.isEmpty()) {
                        warnings.add(disabledDependencyWarning(modId, dependents));
                    }
                }
                List<String> siblings = enabledSiblingModIds(discovered, desiredByMod, modId);
                if (!siblings.isEmpty()) {
                    warnings.add(disabledBundledModWarning(modId, siblings));
                }
            }
        }
        return warnings;
    }

    private static List<String> enabledDependents(Map<String, ModJar> discovered, Map<String, Boolean> desiredByMod, String requiredModId) {
        List<String> dependents = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : desiredByMod.entrySet()) {
            String modId = entry.getKey();
            if (!Objects.equals(entry.getValue(), true) || modId.equals(requiredModId)) {
                continue;
            }

            ModJar jar = discovered.get(modId);
            if (jar != null && jar.requiredModIds().contains(requiredModId)) {
                dependents.add(modId);
            }
        }
        dependents.sort(String.CASE_INSENSITIVE_ORDER);
        return dependents;
    }

    private static List<String> enabledSiblingModIds(Map<String, ModJar> discovered, Map<String, Boolean> desiredByMod, String modId) {
        ModJar jar = discovered.get(modId);
        if (jar == null) {
            return List.of();
        }

        List<String> siblings = new ArrayList<>();
        for (String siblingId : jar.selectableModIds()) {
            if (!siblingId.equals(modId) && Objects.equals(desiredByMod.get(siblingId), true)) {
                siblings.add(siblingId);
            }
        }
        siblings.sort(String.CASE_INSENSITIVE_ORDER);
        return siblings;
    }

    private static String disabledDependencyWarning(String modId, List<String> dependents) {
        String verb = dependents.size() == 1 ? "requires" : "require";
        return "profile disables " + modId + ", but it will stay enabled because " + String.join(", ", dependents) + " " + verb + " it";
    }

    private static String disabledBundledModWarning(String modId, List<String> siblings) {
        String label = siblings.size() == 1 ? "enabled mod id " : "enabled mod ids ";
        return "profile disables " + modId + ", but its jar will stay enabled because the same jar also provides " + label + String.join(", ", siblings);
    }

    private static String lockedBundledModError(String modId, List<String> siblings) {
        String label = siblings.size() == 1 ? "enabled mod id " : "enabled mod ids ";
        return "profile locks " + modId + " disabled, but the same jar also provides " + label + String.join(", ", siblings);
    }

    private static String missingModWarning(String modId, Map<String, ModJar> discovered) {
        List<String> suggestions = relatedModSuggestions(modId, discovered);
        if (suggestions.isEmpty()) {
            return "profile enables " + modId + ", but no matching jar is installed";
        }
        return "profile enables " + modId + ", but no matching jar is installed; closest discovered: " + String.join(", ", suggestions);
    }

    private static List<String> relatedModSuggestions(String modId, Map<String, ModJar> discovered) {
        String query = compactIdentity(modId);
        Set<String> queryTokens = identityTokens(modId);
        List<Suggestion> suggestions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, ModJar> entry : discovered.entrySet()) {
            String text = entry.getKey() + " (" + entry.getValue().path().getFileName() + ")";
            if (!seen.add(text)) {
                continue;
            }

            int score = relationScore(query, queryTokens, entry.getKey(), entry.getValue().path().getFileName().toString());
            if (score > 0) {
                suggestions.add(new Suggestion(text, score));
            }
        }

        suggestions.sort((left, right) -> {
            int score = Integer.compare(right.score(), left.score());
            return score == 0 ? left.text().compareTo(right.text()) : score;
        });
        return suggestions.stream().limit(3).map(Suggestion::text).toList();
    }

    private static int relationScore(String query, Set<String> queryTokens, String... values) {
        int score = 0;
        for (String value : values) {
            String compact = compactIdentity(value);
            if (!compact.isBlank() && (query.contains(compact) || compact.contains(query))) {
                score += 8;
            }
            Set<String> valueTokens = identityTokens(value);
            valueTokens.retainAll(queryTokens);
            score += valueTokens.size() * 2;
        }
        return score;
    }

    private static String compactIdentity(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static Set<String> identityTokens(String value) {
        Set<String> ignored = Set.of("jar", "disabled", "forge", "neoforge", "mc", "mod", "mr");
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() > 1 && !token.chars().allMatch(Character::isDigit) && !ignored.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private record Suggestion(String text, int score) {
    }

    private static Set<String> lockedDisabledModIds(Map<String, ModJar> discovered, QualityProfile profile) {
        Set<String> blocked = new LinkedHashSet<>();
        for (Map.Entry<String, ModState> entry : profile.mods().entrySet()) {
            ModState state = entry.getValue();
            if (state.enabled() || !state.locked()) {
                continue;
            }

            blocked.add(entry.getKey());
        }
        return blocked;
    }

    private static String requiredReason(String modId, List<String> actions) {
        for (String action : actions) {
            String prefix = "require " + modId + " because ";
            if (action.startsWith(prefix)) {
                return "Required because " + action.substring(prefix.length()) + ".";
            }
        }
        if (modId.equals(MOD_ID)) {
            return "Required by Mod Quality Picker.";
        }
        return "Required by an enabled mod.";
    }

    private static void enableProvidedRuntimeModules(Map<String, Boolean> desiredByMod) {
        if (!Objects.equals(desiredByMod.get("fabric_api"), true) && !Objects.equals(desiredByMod.get("forgified_fabric_api"), true)) {
            return;
        }

        for (String modId : new ArrayList<>(desiredByMod.keySet())) {
            if (modId.startsWith("fabric_")) {
                desiredByMod.put(modId, true);
            }
        }
        if (desiredByMod.containsKey("forgified_fabric_api")) {
            desiredByMod.put("forgified_fabric_api", true);
        }
    }

    private static List<String> enableRequiredDependencies(Map<String, ModJar> discovered, Map<String, Boolean> desiredByMod, Set<String> blockedModIds) {
        List<String> actions = new ArrayList<>();
        boolean changed;
        do {
            changed = false;
            for (String modId : new ArrayList<>(desiredByMod.keySet())) {
                if (!Objects.equals(desiredByMod.get(modId), true)) {
                    continue;
                }

                ModJar jar = discovered.get(modId);
                if (jar == null) {
                    continue;
                }

                for (String requiredModId : jar.requiredModIds()) {
                    ModJar requiredJar = discovered.get(requiredModId);
                    if (blockedModIds.contains(requiredModId)) {
                        actions.add("skip " + requiredModId + " because it is locked disabled");
                        continue;
                    }
                    if (requiredJar == null) {
                        continue;
                    }

                    if (!Objects.equals(desiredByMod.get(requiredModId), true)) {
                        desiredByMod.put(requiredModId, true);
                        actions.add("require " + requiredModId + " because " + modId + " requires it");
                        changed = true;
                    }

                    for (String bundledId : requiredJar.modIds()) {
                        if (!blockedModIds.contains(bundledId) && !Objects.equals(desiredByMod.get(bundledId), true)) {
                            desiredByMod.put(bundledId, true);
                            changed = true;
                        }
                    }
                }
            }
        } while (changed);

        return actions;
    }
    private static boolean isManagedJar(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".jar") || fileName.endsWith(".jar.disabled");
    }

    private static List<String> catalogFingerprint(Path modsDirectory) throws IOException {
        try (Stream<Path> files = Files.list(modsDirectory)) {
            return files
                    .filter(ModJarCatalog::isManagedJar)
                    .sorted()
                    .map(ModJarCatalog::fingerprintPart)
                    .toList();
        }
    }

    private static String fingerprintPart(Path path) {
        try {
            return path.getFileName() + ":" + Files.size(path) + ":" + Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return path.getFileName() + ":missing";
        }
    }

    private static Set<String> readModIds(Path path) {
        return readModMetadata(path).modIds();
    }

    private static ModMetadata readModMetadata(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            ModMetadata metadata = readModMetadata(input, 0);
            if (metadata.modIds().isEmpty()) {
                Set<String> fallback = new LinkedHashSet<>();
                fallback.add(fallbackModId(path));
                return new ModMetadata(fallback, fallback, metadata.requiredModIds());
            }
            return metadata;
        } catch (IOException exception) {
            Set<String> fallback = new LinkedHashSet<>();
            fallback.add(fallbackModId(path));
            return new ModMetadata(fallback, fallback, new LinkedHashSet<>());
        }
    }

    private static Set<String> readModIds(InputStream input, int depth) throws IOException {
        return readModMetadata(input, depth).modIds();
    }

    private static ModMetadata readModMetadata(InputStream input, int depth) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> selectableIds = new LinkedHashSet<>();
        Set<String> fabricIds = new LinkedHashSet<>();
        Set<String> required = new LinkedHashSet<>();
        Set<String> fabricRequired = new LinkedHashSet<>();
        List<byte[]> nestedJars = new ArrayList<>();

        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String name = entry.getName();
                if ("META-INF/neoforge.mods.toml".equals(name)) {
                    String metadata = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    ids.addAll(readNeoForgeModIds(metadata));
                    required.addAll(readNeoForgeRequiredModIds(metadata));
                } else if ("fabric.mod.json".equals(name)) {
                    String metadata = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    addFabricModId(fabricIds, metadata);
                    fabricRequired.addAll(readFabricRequiredModIds(metadata));
                } else if (depth < MAX_NESTED_JAR_DEPTH && name.endsWith(".jar")) {
                    nestedJars.add(zip.readAllBytes());
                }
            }
        }

        if (ids.isEmpty()) {
            ids.addAll(fabricIds);
            required.addAll(fabricRequired);
        }
        selectableIds.addAll(ids);
        for (byte[] nestedJar : nestedJars) {
            ModMetadata nested = readModMetadata(new ByteArrayInputStream(nestedJar), depth + 1);
            ids.addAll(nested.modIds());
            required.addAll(nested.requiredModIds());
        }
        return new ModMetadata(ids, selectableIds, required);
    }

    private static Set<String> readNeoForgeModIds(String metadata) {
        Set<String> ids = new LinkedHashSet<>();
        Map<String, String> block = new HashMap<>();
        boolean inMod = false;

        for (String line : metadata.split("\\R")) {
            if (line.trim().startsWith("[[")) {
                if (inMod) {
                    addNeoForgeModId(ids, block);
                    block.clear();
                }
                inMod = MOD_HEADER.matcher(line).matches();
                continue;
            }

            if (!inMod) {
                continue;
            }

            Matcher stringMatcher = TOML_STRING_VALUE.matcher(line);
            if (stringMatcher.matches()) {
                block.put(stringMatcher.group(1), stringMatcher.group(3));
            }
        }

        if (inMod) {
            addNeoForgeModId(ids, block);
        }
        boolean inInlineMods = false;
        for (String line : metadata.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("mods") && trimmed.contains("[")) {
                inInlineMods = true;
            }
            if (!inInlineMods) {
                continue;
            }
            Matcher inline = INLINE_MOD_ID.matcher(line);
            while (inline.find()) {
                ids.add(inline.group(2));
            }
            if (trimmed.contains("]")) {
                inInlineMods = false;
            }
        }
        return ids;
    }

    private static void addNeoForgeModId(Set<String> ids, Map<String, String> block) {
        String modId = block.getOrDefault("modId", "");
        if (!modId.isBlank()) {
            ids.add(modId);
        }
    }

    private static void addFabricModId(Set<String> ids, String metadata) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(metadata);
        } catch (RuntimeException exception) {
            return;
        }
        if (!parsed.isJsonObject()) {
            return;
        }

        JsonObject object = parsed.getAsJsonObject();
        JsonElement id = object.get("id");
        if (id == null || !id.isJsonPrimitive()) {
            return;
        }

        String rawId = id.getAsString();
        addModIdAliases(ids, rawId);
    }

    private static Set<String> readNeoForgeRequiredModIds(String metadata) {
        Set<String> required = new LinkedHashSet<>();
        Map<String, String> block = new HashMap<>();
        boolean inDependency = false;

        for (String line : metadata.split("\\R")) {
            if (line.trim().startsWith("[[")) {
                if (inDependency) {
                    addRequiredNeoForgeDependency(required, block);
                    block.clear();
                }
                inDependency = DEPENDENCY_HEADER.matcher(line).matches();
                continue;
            }

            if (!inDependency) {
                continue;
            }

            Matcher stringMatcher = TOML_STRING_VALUE.matcher(line);
            if (stringMatcher.matches()) {
                block.put(stringMatcher.group(1), stringMatcher.group(3));
                continue;
            }

            Matcher booleanMatcher = TOML_BOOLEAN_VALUE.matcher(line);
            if (booleanMatcher.matches()) {
                block.put(booleanMatcher.group(1), booleanMatcher.group(2).toLowerCase(Locale.ROOT));
            }
        }

        if (inDependency) {
            addRequiredNeoForgeDependency(required, block);
        }
        return required;
    }

    private static void addRequiredNeoForgeDependency(Set<String> required, Map<String, String> block) {
        String modId = block.getOrDefault("modId", "");
        if (modId.isBlank()) {
            return;
        }

        String type = block.getOrDefault("type", "").toLowerCase(Locale.ROOT);
        String mandatory = block.getOrDefault("mandatory", "");
        if ("false".equals(mandatory) || Set.of("optional", "incompatible", "discouraged").contains(type)) {
            return;
        }
        if (type.isBlank() || "required".equals(type) || "mandatory".equals(type) || "true".equals(mandatory)) {
            addRequiredAliases(required, modId);
        }
    }

    private static Set<String> readFabricRequiredModIds(String metadata) {
        Set<String> required = new LinkedHashSet<>();
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(metadata);
        } catch (RuntimeException exception) {
            return required;
        }
        if (!parsed.isJsonObject()) {
            return required;
        }

        JsonObject depends = parsed.getAsJsonObject().getAsJsonObject("depends");
        if (depends == null) {
            return required;
        }
        for (String modId : depends.keySet()) {
            addRequiredAliases(required, modId);
        }
        return required;
    }

    private static void addRequiredAliases(Set<String> ids, String modId) {
        if (IGNORED_REQUIRED_MOD_IDS.contains(modId)) {
            return;
        }
        addModIdAliases(ids, modId);
    }

    private static void addModIdAliases(Set<String> ids, String modId) {
        ids.add(modId);
        ids.add(modId.replace('-', '_'));
    }

    private static String fallbackModId(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".disabled")) {
            name = name.substring(0, name.length() - ".disabled".length());
        }
        if (name.endsWith(".jar")) {
            name = name.substring(0, name.length() - ".jar".length());
        }
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
    }
}
