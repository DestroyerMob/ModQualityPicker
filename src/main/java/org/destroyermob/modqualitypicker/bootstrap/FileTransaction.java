package org.destroyermob.modqualitypicker.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

final class FileTransaction implements AutoCloseable {
    private static final String STATE = "state.txt";
    private static final String MANIFEST = "manifest.tsv";
    private final Path root;
    private final Path filesRoot;
    private final Map<Path, Backup> backups = new LinkedHashMap<>();
    private boolean committed;

    private record Backup(Path original, boolean existed, Path backup) {
    }

    private FileTransaction(Path root) throws IOException {
        this.root = root;
        this.filesRoot = root.resolve("files");
        Files.createDirectories(filesRoot);
        writeState("preparing");
    }

    static FileTransaction begin(Path profileRoot) throws IOException {
        Path transactions = profileRoot.resolve("transactions");
        Files.createDirectories(transactions);
        String id = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID();
        return new FileTransaction(transactions.resolve(id));
    }

    static void recoverIncomplete(Path profileRoot) throws IOException {
        Path transactions = profileRoot.resolve("transactions");
        if (!Files.isDirectory(transactions)) {
            return;
        }
        try (Stream<Path> entries = Files.list(transactions)) {
            for (Path entry : entries.filter(Files::isDirectory).sorted().toList()) {
                String state = readState(entry);
                if ("applying".equals(state)) {
                    restore(entry);
                    System.out.println("RECOVERY restored interrupted profile transaction " + entry.getFileName());
                }
                deleteTree(entry);
            }
        }
    }

    void track(Iterable<Path> paths) throws IOException {
        for (Path path : paths) {
            track(path);
        }
    }

    void track(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (backups.containsKey(normalized)) {
            return;
        }
        boolean existed = Files.isRegularFile(normalized);
        Path backup = filesRoot.resolve(String.format("%05d.bin", backups.size()));
        if (existed) {
            Files.copy(normalized, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
        backups.put(normalized, new Backup(normalized, existed, backup));
        writeManifest();
    }

    void markApplying() throws IOException {
        writeManifest();
        writeState("applying");
    }

    void commit() throws IOException {
        writeState("committed");
        committed = true;
        deleteTree(root);
    }

    void rollback() throws IOException {
        restoreBackups(new ArrayList<>(backups.values()));
        writeState("rolled-back");
        deleteTree(root);
    }

    @Override
    public void close() throws IOException {
        if (!committed && Files.exists(root)) {
            rollback();
        }
    }

    private void writeManifest() throws IOException {
        List<String> lines = new ArrayList<>();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        for (Backup backup : backups.values()) {
            String path = encoder.encodeToString(backup.original().toString().getBytes(StandardCharsets.UTF_8));
            String file = backup.existed() ? backup.backup().getFileName().toString() : "-";
            lines.add(path + "\t" + backup.existed() + "\t" + file);
        }
        Files.write(root.resolve(MANIFEST), lines, StandardCharsets.UTF_8);
    }

    private void writeState(String state) throws IOException {
        Files.writeString(root.resolve(STATE), state + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static String readState(Path root) {
        try {
            return Files.readString(root.resolve(STATE), StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            return "unknown";
        }
    }

    private static void restore(Path root) throws IOException {
        Path manifest = root.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest)) {
            return;
        }
        Base64.Decoder decoder = Base64.getUrlDecoder();
        List<Backup> backups = new ArrayList<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 3) {
                continue;
            }
            Path original = Path.of(new String(decoder.decode(fields[0]), StandardCharsets.UTF_8));
            boolean existed = Boolean.parseBoolean(fields[1]);
            Path backup = root.resolve("files").resolve(fields[2]);
            backups.add(new Backup(original, existed, backup));
        }
        restoreBackups(backups);
    }

    private static void restoreBackups(List<Backup> backups) throws IOException {
        for (Backup backup : backups) {
            Files.deleteIfExists(backup.original());
            if (backup.existed() && Files.isRegularFile(backup.backup())) {
                Path parent = backup.original().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(backup.backup(), backup.original(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
