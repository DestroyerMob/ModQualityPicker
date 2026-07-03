package org.destroyermob.modqualitypicker.export;

import org.destroyermob.modqualitypicker.runtime.ProfilePaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

public final class ProfileExporter {
    private ProfileExporter() {
    }

    public static Path exportPresets(Path destinationRoot) throws IOException {
        copyTree(ProfilePaths.defaultsRoot(), destinationRoot.resolve("defaults"));
        copyTree(ProfilePaths.presetsRoot(), destinationRoot.resolve("presets"));
        if (Files.isRegularFile(ProfilePaths.defaultsManifest())) {
            Files.createDirectories(destinationRoot);
            Files.copy(ProfilePaths.defaultsManifest(), destinationRoot.resolve("defaults-manifest.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return destinationRoot;
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source)) {
            Files.createDirectories(destination);
            return;
        }

        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
