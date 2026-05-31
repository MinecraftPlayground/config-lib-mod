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
 * Central manager for YAML configuration files.
 *
 * <p>Usage</p>
 * <pre>{@code
 * public class MyMod implements ModInitializer {
 *
 *     @Override
 *     public void onInitialize() {
 *         // 1. Set the root directory (relative to <game_dir>/config/)
 *         ConfigManager.root("my_mod");
 *
 *         // 2. Register config files
 *         ConfigManager.add("settings.yml", MySettings.class);
 *         ConfigManager.add("messages.yml", MyMessages.class);
 *
 *         // 3. Read values anywhere
 *         String level = ConfigManager.get(MySettings.class).property;
 *     }
 * }
 * }</pre>
 *
 * <p>File location</p>
 * <p>All paths are resolved relative to {@code <game_dir>/config/<root>/}.
 * Sub-directories inside the root are supported:</p>
 * <pre><code>
 * ConfigManager.root("my_mod");
 * ConfigManager.add("modules/chat.yml", ChatConfig.class);
 * // <game_dir>/config/my_mod/modules/chat.yml
 * </code></pre>
 *
 * <h2>Smart merge</h2>
 * <p>When a config file already exists but the class has gained new fields,
 * the missing keys are added automatically using their default values.
 * Keys that no longer exist in the class are kept in the file and marked
 * with a {@code # @deprecated} comment so the user knows they can be removed.</p>
 *
 * <p>Reload</p>
 * <pre><code>
 * ConfigManager.reloadAll();
 * </code></pre>
 */
public final class ConfigManager {

    private ConfigManager() {}

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigManager.class);

    private static String rootDirectory = "";
    private static boolean initialized = false;

    // Keyed by config class for O(1) typed get()
    private static final Map<Class<?>, ConfigEntry<?>> entries = new LinkedHashMap<>();

    /**
     * Sets the root directory for all config files.
     *
     * <p>Must be called before any config is first accessed (typically at the
     * top of {@code onInitialize()}). Calling {@code root()} triggers loading of
     * all configs registered via {@link #add} before this call.</p>
     *
     * @param root Subdirectory under {@code <game_dir>/config/} (e.g. {@code "my_mod"})
     */
    public static void root(String root) {
        rootDirectory = root;
        initialized = true;

        // Load any entries that were registered before root() was called
        entries.forEach((clazz, entry) -> {
            if (!entry.isLoaded()) {
                entry.load(resolve(entry.relativePath));
            }
        });
    }

    /**
     * Registers a config file and loads it from disk (or creates it with defaults).
     *
     * <p>If {@link #root} has not been called yet the entry is queued and loaded
     * once {@link #root} is invoked.</p>
     *
     * @param relativePath Path relative to the root directory (e.g. {@code "settings.yml"})
     * @param configClass The config class — must have a public no-arg constructor
     * @param <ConfigFile> The config type
     * @throws IllegalArgumentException if {@code configClass} is already registered
     */
    public static <ConfigFile> void add(String relativePath, Class<ConfigFile> configClass) {
        if (entries.containsKey(configClass)) {
            throw new IllegalArgumentException(
                "Config class '%s' is already registered.".formatted(configClass.getName())
            );
        }

        ConfigEntry<ConfigFile> entry = new ConfigEntry<>(relativePath, configClass);
        entries.put(configClass, entry);

        if (initialized) {
            entry.load(resolve(relativePath));
        }
    }

    // -------------------------------------------------------------------------
    // Access
    // -------------------------------------------------------------------------

    /**
     * Returns the current config value for the given class.
     *
     * @param configClass The class passed to {@link #add}
     * @param <ConfigFile>         The config type
     * @return The loaded config instance — never {@code null}
     * @throws IllegalStateException if the class was never registered
     */
    @SuppressWarnings("unchecked")
    public static <ConfigFile> ConfigFile get(Class<ConfigFile> configClass) {
        ConfigEntry<?> entry = entries.get(configClass);
        if (entry == null) {
            throw new IllegalStateException(
                "Config '%s' is not registered. Call ConfigManager.add() before accessing it."
                    .formatted(configClass.getName())
            );
        }
        return (ConfigFile) entry.getValue();
    }

    // -------------------------------------------------------------------------
    // Reload
    // -------------------------------------------------------------------------

    /**
     * Reloads all registered config files from disk.
     *
     * <p>Values are updated in-place — existing references obtained via
     * {@link #get} will return the new values on subsequent calls.</p>
     */
    public static void reloadAll() {
        if (!initialized) {
            LOGGER.warn("ConfigManager.reloadAll() called before root() — nothing to reload.");
            return;
        }
        entries.forEach((clazz, entry) -> {
            LOGGER.info("Reloading config '{}'.", entry.relativePath);
            entry.load(resolve(entry.relativePath));
        });
    }

    // -------------------------------------------------------------------------
    // Path resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves a relative config path to an absolute {@link Path} under
     * {@code <game_dir>/config/<root>/}, creating intermediate directories as needed.
     *
     * @param relativePath Path relative to the root directory
     * @return The resolved absolute path
     */
    public static Path resolve(String relativePath) {
        Path base = FabricLoader.getInstance()
            .getConfigDir()
            .resolve(rootDirectory);

        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            LOGGER.error("Failed to create config directory '{}': {}", base, e.getMessage());
        }

        return base.resolve(relativePath);
    }
}
