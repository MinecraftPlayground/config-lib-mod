package dev.loat.config_lib.parser;

import java.nio.file.Path;


/**
 * Public entry point for loading a YAML config file.
 * Delegates all logic to {@link InstanceConfigLoader}.
 *
 * @param <ConfigClass> The config class type
 */
public final class YAMLParser<ConfigClass> {

    private final Path path;
    private final Class<ConfigClass> configClass;

    /**
     * Creates a new YAML parser for the specified config class.
     *
     * @param path The path to the YAML config file
     * @param configClass The config class type
     */
    public YAMLParser(Path path, Class<ConfigClass> configClass) {
        this.path = path;
        this.configClass = configClass;
    }

    /**
     * Loads the config from disk, creating or merging the file as needed.
     *
     * @return The populated config instance
     */
    public ConfigClass loadOrCreate() {
        return new InstanceConfigLoader<>(this.path, this.configClass).load();
    }
}
