package dev.loat.config_lib;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * Manages YAML configuration files under a given root directory.
 *
 * <p>Create one instance per mod (typically as a private static field in a dedicated
 * {@code Config} class), set the root directory in the constructor, then call
 * {@link #add} to register config files.</p>
 *
 * <h2>Minimal setup</h2>
 * <pre>{@code
 * public final class Config {
 *     private Config() {}
 *
 *     private static final ConfigManager MANAGER = new ConfigManager("my_mod");
 *
 *     public static void register() {
 *         MANAGER.add("config.yml", MyConfig.class);
 *     }
 *
 *     public static MyConfig get() {
 *         return MANAGER.get(MyConfig.class);
 *     }
 * }
 * }</pre>
 *
 * <p>Call {@code Config.register()} in {@code onInitialize()}. Access values
 * anywhere via {@code Config.get().myField} — or, for static-field configs,
 * directly via {@code MyConfig.myField}.</p>
 *
 * <h2>Custom logger</h2>
 * <pre>{@code
 * private static final ConfigManager MANAGER = new ConfigManager(
 *     "my_mod",
 *     LoggerFactory.getLogger(MyMod.class)
 * );
 * }</pre>
 *
 * <h2>File locations</h2>
 * <p>All paths are resolved under {@code <game_dir>/config/<root>/}.
 * Sub-directories are supported:</p>
 * <pre>{@code
 * MANAGER.add("modules/chat.yml", ChatConfig.class);
 * // → <game_dir>/config/my_mod/modules/chat.yml
 * }</pre>
 */
public final class ConfigManager {

    private final String rootDirectory;
    private final Logger logger;
    private final Map<Class<?>, ConfigEntry<?>> entries = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Creates a new manager with a default SLF4J logger.
     *
     * @param rootDirectory Subdirectory under {@code <game_dir>/config/}
     *                      (e.g. {@code "my_mod"} → {@code config/my_mod/})
     */
    public ConfigManager(String rootDirectory) {
        this(rootDirectory, LoggerFactory.getLogger(ConfigManager.class));
    }

    /**
     * Creates a new manager with a custom logger.
     *
     * @param rootDirectory Subdirectory under {@code <game_dir>/config/}
     * @param logger        The logger to use for info, warning, and error messages
     */
    public ConfigManager(String rootDirectory, Logger logger) {
        this.rootDirectory = rootDirectory;
        this.logger = logger;
    }

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Registers a config file and loads it immediately.
     *
     * <p>If the file does not exist it is created with default values derived
     * from the config class. If it exists but is missing keys, those keys are
     * added with their default values and the file is rewritten.</p>
     *
     * @param relativePath Path relative to the root directory (e.g. {@code "config.yml"})
     * @param configClass  The config class — must have a no-arg constructor
     *                     (may be private for static-field configs)
     * @param <T>          The config type
     * @throws IllegalArgumentException if {@code configClass} is already registered
     */
    public <T> void add(String relativePath, Class<T> configClass) {
        if (entries.containsKey(configClass)) {
            throw new IllegalArgumentException(
                "Config class '%s' is already registered.".formatted(configClass.getName())
            );
        }

        ConfigEntry<T> entry = new ConfigEntry<>(relativePath, configClass, logger);
        entries.put(configClass, entry);
        entry.load(resolve(relativePath));
    }

    // -------------------------------------------------------------------------
    // Access
    // -------------------------------------------------------------------------

    /**
     * Returns the current config value for the given class.
     *
     * <p>For static-field configs this returns a dummy instance — access the actual
     * values via the static fields directly ({@code MyConfig.myField}) or through
     * the returned reference ({@code manager.get(MyConfig.class).myField}), both work.</p>
     *
     * @param configClass The class passed to {@link #add}
     * @param <T>         The config type
     * @return The loaded config instance — {@code null} only if loading failed
     * @throws IllegalStateException if the class was never registered
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> configClass) {
        ConfigEntry<?> entry = entries.get(configClass);
        if (entry == null) {
            throw new IllegalStateException(
                "Config '%s' is not registered. Call add() before get().".formatted(configClass.getName())
            );
        }
        return (T) entry.getValue();
    }

    // -------------------------------------------------------------------------
    // Reload
    // -------------------------------------------------------------------------

    /**
     * Reloads all registered config files from disk.
     *
     * <p>For static-field configs the static field values are updated in-place.
     * For instance configs subsequent {@link #get} calls return the refreshed values.</p>
     */
    public void reloadAll() {
        entries.forEach((clazz, entry) -> {
            logger.info("Reloading config '{}'.", entry.relativePath);
            entry.load(resolve(entry.relativePath));
        });
    }

    // -------------------------------------------------------------------------
    // Path resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves a relative path to an absolute {@link Path} under
     * {@code <game_dir>/config/<root>/}, creating intermediate directories as needed.
     *
     * @param relativePath Path relative to the root directory
     * @return The resolved absolute path
     */
    public Path resolve(String relativePath) {
        Path base = FabricLoader.getInstance()
            .getConfigDir()
            .resolve(rootDirectory);

        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            logger.error("Failed to create config directory '{}': {}", base, e.getMessage());
        }

        return base.resolve(relativePath);
    }
}
