package org.destroyermob.modqualitypicker.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Pure-JDK executable entry point. The NeoForge side never calls this class.
 */
public final class BootstrapLauncher {
    private static final String GSON_RESOURCE = "/bootstrap-libs/gson.jar";
    private static final String RUNNER_CLASS = "org.destroyermob.modqualitypicker.bootstrap.BootstrapRunner";

    private BootstrapLauncher() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Throwable throwable) {
            System.err.println("Mod Quality Picker pre-launch failed: " + message(throwable));
            throwable.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static int run(String[] args) throws Exception {
        Path self = executableJar();
        Path gson = extractGson();
        URL[] urls = {self.toUri().toURL(), gson.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Thread current = Thread.currentThread();
            ClassLoader previous = current.getContextClassLoader();
            current.setContextClassLoader(loader);
            try {
                Class<?> runner = Class.forName(RUNNER_CLASS, true, loader);
                Method method = runner.getMethod("run", String[].class);
                return (int) method.invoke(null, (Object) args);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof Exception checked) {
                    throw checked;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw exception;
            } finally {
                current.setContextClassLoader(previous);
            }
        } finally {
            Files.deleteIfExists(gson);
        }
    }

    private static Path executableJar() throws IOException, URISyntaxException {
        URL location = BootstrapLauncher.class.getProtectionDomain().getCodeSource().getLocation();
        Path path = Path.of(location.toURI()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IOException("Mod Quality Picker must be run from its built jar: " + path);
        }
        return path;
    }

    private static Path extractGson() throws IOException {
        Path temporary = Files.createTempFile("modqualitypicker-gson-", ".jar");
        try (InputStream input = BootstrapLauncher.class.getResourceAsStream(GSON_RESOURCE)) {
            if (input == null) {
                throw new IOException("Embedded JSON runtime is missing from the Mod Quality Picker jar");
            }
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        return temporary;
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }
}
