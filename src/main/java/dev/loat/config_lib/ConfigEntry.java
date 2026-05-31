package dev.loat.config_lib;

import dev.loat.config_lib.parser.YAMLParser;
import org.slf4j.Logger;

import java.nio.file.Path;


/**
 * Internal holder for a single registered config file.
 * Not part of the public API — all interaction goes through {@link ConfigManager}.
 *
 * @param <T> The config class type
 */
final class ConfigEntry<T> {

    final String relativePath;
    final Class<T> configClass;

    private final Logger logger;
    private T value;
    private boolean loaded = false;

    ConfigEntry(String relativePath, Class<T> configClass, Logger logger) {
        this.relativePath = relativePath;
        this.configClass = configClass;
        this.logger = logger;
        this.value = null;
    }

    /**
     * Loads (or reloads) the config from the given absolute path.
     * On any error the previous value (or {@code null}) is retained.
     */
    void load(Path absolutePath) {
        try {
            value = new YAMLParser<>(absolutePath, configClass, logger).loadOrCreate();
            loaded = true;
        } catch (Exception e) {
            logger.error("Failed to load config '{}' — retaining previous value. Cause: {}",
                relativePath, e.getMessage());
        }
    }

    T getValue() {
        return value;
    }

    boolean isLoaded() {
        return loaded;
    }
}
