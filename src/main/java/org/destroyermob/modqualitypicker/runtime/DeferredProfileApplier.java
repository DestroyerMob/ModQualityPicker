package org.destroyermob.modqualitypicker.runtime;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.destroyermob.modqualitypicker.ModQualityPicker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts the standalone applier while Minecraft is still running so it can apply after this JVM exits. */
public final class DeferredProfileApplier {
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private DeferredProfileApplier() {
    }

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        try {
            Path executableJar = executableJar();
            Path java = javaExecutable();
            Path log = ProfilePaths.instanceRoot().resolve("deferred-apply.log");
            Files.createDirectories(log.getParent());

            ProcessBuilder builder = new ProcessBuilder(
                    java.toString(),
                    "-jar",
                    executableJar.toString(),
                    "--wait-for-pid",
                    Long.toString(ProcessHandle.current().pid()),
                    "apply",
                    "--instance-root",
                    ProfilePaths.gameDirectory().toString()
            );
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            Process helper = builder.start();
            ModQualityPicker.LOGGER.info(
                    "Started deferred Mod Quality Picker applier process {} for queued profile",
                    helper.pid()
            );
        } catch (IOException exception) {
            STARTED.set(false);
            ModQualityPicker.LOGGER.error(
                    "Could not start the deferred Mod Quality Picker applier; a launcher pre-launch hook can still apply the queue",
                    exception
            );
        }
    }

    private static Path executableJar() throws IOException {
        IModFileInfo fileInfo = ModList.get().getModFileById(ModQualityPicker.MOD_ID);
        if (fileInfo == null) {
            throw new IOException("NeoForge did not expose the Mod Quality Picker mod file");
        }
        Path path = fileInfo.getFile().getFilePath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IOException("Mod Quality Picker is not running from an executable jar: " + path);
        }
        return path;
    }

    private static Path javaExecutable() throws IOException {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path java = bin.resolve(windows ? "javaw.exe" : "java");
        if (!Files.isRegularFile(java) && windows) {
            java = bin.resolve("java.exe");
        }
        if (!Files.isRegularFile(java)) {
            throw new IOException("Could not find the running Java executable under " + bin);
        }
        return java;
    }
}
