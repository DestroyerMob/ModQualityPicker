package org.destroyermob.modqualitypicker.profile;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class ConfigOwnerResolver {
    private ConfigOwnerResolver() {
    }

    public static String inferModId(String relativePath, Collection<String> modIds) {
        if (relativePath == null || relativePath.isBlank() || modIds == null || modIds.isEmpty()) {
            return "";
        }

        String normalizedPath = relativePath.replace('\\', '/');
        if (normalizedPath.startsWith("config/")) {
            normalizedPath = normalizedPath.substring("config/".length());
        }
        String firstSegment = normalizedPath.contains("/")
                ? normalizedPath.substring(0, normalizedPath.indexOf('/'))
                : normalizedPath;
        String fileName = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1);
        int extension = fileName.indexOf('.');
        String stem = extension > 0 ? fileName.substring(0, extension) : fileName;
        List<String> candidates = List.of(normalize(firstSegment), normalize(stem));

        String best = "";
        int bestScore = -1;
        for (String modId : modIds) {
            if (modId == null || modId.isBlank()) {
                continue;
            }
            String normalizedId = normalize(modId);
            for (String candidate : candidates) {
                int score = matchScore(candidate, normalizedId);
                if (score > bestScore || (score == bestScore && modId.length() > best.length())) {
                    best = modId;
                    bestScore = score;
                }
            }
        }
        return bestScore < 0 ? "" : best;
    }

    private static int matchScore(String candidate, String modId) {
        if (candidate.isBlank() || modId.isBlank()) {
            return -1;
        }
        if (candidate.equals(modId)) {
            return 10_000 + modId.length();
        }
        if (candidate.startsWith(modId) || modId.startsWith(candidate)) {
            return 1_000 + Math.min(candidate.length(), modId.length());
        }
        return -1;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
