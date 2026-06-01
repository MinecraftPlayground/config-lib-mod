package dev.loat.config_lib;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.loat.config_lib.logging.Logger;


/**
 * Manages YAML configuration files under a given root directory.
 * 
 * <p>Minimal setup</p>
 * <pre><code>
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
 * </code></pre>
 * 
 * Call {@code Config.register()} in {@code onInitialize()}. Access values
 * anywhere via {@code Config.get().myField}.
 * 
 * <p>File locations</p>
 * All paths are resolved under {@code <game_dir>/config/<root>/}.
 * Sub-directories are supported:
 * <pre>{@code
 * MANAGER.add("modules/chat.yml", ChatConfig.class);
 * // <game_dir>/config/my_mod/modules/chat.yml
 * }</pre>
 */
public final class ConfigManager {

    private final String rootDirectory;
    private final Map<Class<?>, ConfigEntry<?>> entries = new LinkedHashMap<>();

    /**
     * Creates a config manager with the given root directory.
     *
     * @param rootDirectory Subdirectory under {@code <game_dir>/config/} (ex. {@code "my_mod" -> config/my_mod/})
     */
    public ConfigManager(String rootDirectory) {
        Logger.setLoggerClass(ConfigManager.class);
        this.rootDirectory = rootDirectory;
    }

    /**
     * Registers a config file and loads it immediately.
     *
     * @param <ConfigClass> The config type
     * @param relativePath Path to the config file, relative to the root directory
     * @param configClass The config class, used for loading and as a key for retrieval
     * 
     * @throws IllegalArgumentException if the class is already registered
     */
    public <ConfigClass> void add(String relativePath, Class<ConfigClass> configClass) {
        if (this.entries.containsKey(configClass)) {
            throw new IllegalArgumentException(
                "Config class '%s' is already registered.".formatted(configClass.getName())
            );
        }

        ConfigEntry<ConfigClass> entry = new ConfigEntry<>(relativePath, configClass);
        this.entries.put(configClass, entry);
        entry.load(this.resolve(relativePath));
    }

    /**
     * Returns the current config value for the given class.
     *
     * @param <ConfigClass> The config type
     * @param configClass The config class, used as a key for retrieval
     * 
     * @return The current config value, or {@code null} if loading failed and no previous value exists
     * @throws IllegalStateException if the class is not registered
     */
    @SuppressWarnings("unchecked")
    public <ConfigClass> ConfigClass get(Class<ConfigClass> configClass) {
        ConfigEntry<?> entry = this.entries.get(configClass);
        if (entry == null) {
            throw new IllegalStateException(
                "Config '%s' is not registered. Call add() before get().".formatted(configClass.getName())
            );
        }
        return (ConfigClass) entry.getValue();
    }

    /**
     * Reloads all registered config files from disk.
     */
    public void reloadAll() {
        this.entries.forEach((clazz, entry) -> {
            Logger.info("Reloading config '%s'.".formatted(entry.relativePath));
            entry.load(this.resolve(entry.relativePath));
        });
    }

    /**
     * Resolves a relative path to an absolute {@link Path} under
     * {@code <game_dir>/config/<root>/}, creating intermediate directories as needed.
     *
     * @param relativePath Path relative to the root directory
     * 
     * @return The resolved absolute path
     */
    public Path resolve(String relativePath) {
        Path base = FabricLoader.getInstance()
            .getConfigDir()
            .resolve(this.rootDirectory);

        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            Logger.error("Failed to create config directory '%s': %s".formatted(base, e.getMessage()));
        }

        return base.resolve(relativePath);
    }
}
