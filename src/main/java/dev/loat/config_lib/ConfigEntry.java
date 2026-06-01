package dev.loat.config_lib;

import dev.loat.config_lib.logging.Logger;
import dev.loat.config_lib.parser.YAMLParser;

import java.nio.file.Path;


/**
 * Internal holder for a single registered config file.
 * Not part of the public API - all interaction goes through {@link ConfigManager}.
 *
 * @param <ConfigClass> The config class type
 */
final class ConfigEntry<ConfigClass> {

    final String relativePath;
    final Class<ConfigClass> configClass;

    private ConfigClass value;
    private boolean loaded = false;

    /**
     * Creates a new config entry with the given relative path and config class.
     *
     * @param relativePath The relative path to the config file
     * @param configClass The config class
     */
    ConfigEntry(String relativePath, Class<ConfigClass> configClass) {
        this.relativePath = relativePath;
        this.configClass = configClass;
        this.value = null;
    }

    /**
     * Loads the config from the given absolute path, replacing the current value if successful.
     * 
     * @param absolutePath The absolute path to load the config from
     */
    void load(Path absolutePath) {
        try {
            this.value = new YAMLParser<>(absolutePath, this.configClass).loadOrCreate();
            this.loaded = true;
        } catch (Exception e) {
            Logger.error("Failed to load config '%s' - retaining previous value. Cause: %s".formatted(this.relativePath, e.getMessage()));
        }
    }

    /**
     * Returns the current config value.
     *
     * @return The current config value, or {@code null} if loading failed and no previous value exists
     */
    ConfigClass getValue() {
        return this.value;
    }

    /**
     * Whether the config has been successfully loaded at least once.
     * Note that a failed load does not reset this flag, so it only indicates whether the config has ever been loaded, not whether the current value is valid.
     * 
     * @return {@code true} if the config has been successfully loaded at least once, {@code false} otherwise
     */
    boolean isLoaded() {
        return this.loaded;
    }
}
