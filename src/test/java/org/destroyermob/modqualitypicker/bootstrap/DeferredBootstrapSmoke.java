package org.destroyermob.modqualitypicker.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

public final class DeferredBootstrapSmoke {
    private DeferredBootstrapSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--block".equals(args[0])) {
            Files.writeString(Path.of(args[1]), "ready", StandardCharsets.UTF_8);
            Thread.sleep(Duration.ofMinutes(1));
            return;
        }
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected the executable Mod Quality Picker jar path");
        }

        Path temporary = Files.createTempDirectory("modqualitypicker-deferred-smoke-");
        Process blocker = null;
        Process helper = null;
        try {
            Path marker = temporary.resolve("blocker-ready");
            String java = javaExecutable().toString();
            blocker = new ProcessBuilder(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    DeferredBootstrapSmoke.class.getName(),
                    "--block",
                    marker.toString()
            ).start();
            waitForFile(marker, blocker);

            helper = new ProcessBuilder(
                    java,
                    "-jar",
                    Path.of(args[0]).toAbsolutePath().toString(),
                    "--wait-for-pid",
                    Long.toString(blocker.pid()),
                    "version"
            ).redirectErrorStream(true).start();

            Thread.sleep(300);
            require(helper.isAlive(), "Deferred bootstrap should wait while the target process is alive");
            blocker.destroy();
            require(blocker.waitFor(10, TimeUnit.SECONDS), "Blocker process did not exit");
            require(helper.waitFor(10, TimeUnit.SECONDS), "Deferred bootstrap did not finish after the target exited");
            String output = new String(helper.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            require(helper.exitValue() == 0, "Deferred bootstrap failed: " + output);
            require(output.contains("Mod Quality Picker bootstrap"), "Deferred bootstrap did not run the requested command");
        } finally {
            destroy(helper);
            destroy(blocker);
            try (var paths = Files.walk(temporary)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    private static Path javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path java = bin.resolve(System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
        return java.toAbsolutePath();
    }

    private static void waitForFile(Path marker, Process blocker) throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        while (!Files.exists(marker)) {
            require(blocker.isAlive(), "Blocker exited before becoming ready");
            require(Instant.now().isBefore(deadline), "Timed out waiting for blocker process");
            Thread.sleep(25);
        }
    }

    private static void destroy(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
