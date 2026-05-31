package dev.loat.config_lib;

import dev.loat.config_lib.parser.YAMLParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;


/**
 * Internal holder for a single registered config file.
 *
 * <p>Not part of the public API — all interaction goes through {@link ConfigManager}.</p>
 *
 * @param <ConfigFile> The config class type
 */
final class ConfigEntry<ConfigFile> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigEntry.class);

    final String relativePath;
    final Class<ConfigFile> configClass;

    private ConfigFile value;
    private boolean loaded = false;

    ConfigEntry(String relativePath, Class<ConfigFile> configClass) {
        this.relativePath = relativePath;
        this.configClass = configClass;
        this.value = createDefault(); // safe non-null initial value
    }

    /**
     * Loads (or reloads) the config from the given absolute path.
     * On any error the previous value (or the default) is retained.
     */
    void load(Path absolutePath) {
        try {
            value = new YAMLParser<>(absolutePath, configClass).loadOrCreate();
            loaded = true;
        } catch (Exception e) {
            LOGGER.error("Failed to load config '{}' — retaining previous value. Cause: {}",
                relativePath, e.getMessage());
        }
    }

    ConfigFile getValue() {
        return value;
    }

    boolean isLoaded() {
        return loaded;
    }

    private ConfigFile createDefault() {
        try {
            return configClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(
                "Config class '%s' must have a public no-arg constructor.".formatted(configClass.getName()), e
            );
        }
    }
}
