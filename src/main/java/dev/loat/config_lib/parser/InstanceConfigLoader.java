package dev.loat.config_lib.parser;

import dev.loat.config_lib.logging.Logger;
import dev.loat.config_lib.parser.minecraft.ComponentConstructor;
import dev.loat.config_lib.parser.minecraft.ComponentRepresenter;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Loads a YAML config file into an instance of {@code ConfigClass}.
 *
 * @param <ConfigClass> The config class type
 */
final class InstanceConfigLoader<ConfigClass> {

    private final Path path;
    private final Class<ConfigClass> configClass;

    /**
     * Creates a new instance of the config loader.
     * 
     * @param path Absolute path to the config file on disk
     * @param configClass The config class type
     */
    InstanceConfigLoader(Path path, Class<ConfigClass> configClass) {
        this.path = path;
        this.configClass = configClass;
    }

    /**
     * Loads the config from disk, creating or merging the file as needed.
     * On any error, the previous value (or {@code null}) is retained and the error is logged.
     * 
     * @return The populated config instance.
     */
    ConfigClass load() {
        ConfigClass defaults = this.createInstance();

        if (!Files.exists(this.path)) {
            Logger.info("Config '%s' not found. Writing defaults.".formatted(this.path.getFileName()));
            this.writeDefaults(this.objectToRawMap(defaults), Set.of());
            return defaults;
        }

        Map<String, Object> diskMap = ConfigMerger.readRawMap(this.path);

        if (diskMap.isEmpty()) {
            Logger.warning("Config '%s' is empty. Writing defaults.".formatted(this.path.getFileName()));
            this.writeDefaults(this.objectToRawMap(defaults), Set.of());
            return defaults;
        }

        Map<String, Object> defaultMap = this.objectToRawMap(defaults);

        Set<String> newKeys = defaultMap.keySet().stream()
            .filter(k -> !diskMap.containsKey(k))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> orphanedKeys = diskMap.keySet().stream()
            .filter(k -> !defaultMap.containsKey(k))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Object> mergedMap = ConfigMerger.deepMerge(diskMap, defaultMap);

        if (!newKeys.isEmpty() || !orphanedKeys.isEmpty()) {
            if (!newKeys.isEmpty()) {
                Logger.info("Config '%s' - adding %d new key(s): %s".formatted(
                    this.path.getFileName(), newKeys.size(), newKeys
                ));
            }
            if (!orphanedKeys.isEmpty()) {
                Logger.info("Config '%s' - %d orphaned key(s) kept with @deprecated comment: %s".formatted(
                    this.path.getFileName(), orphanedKeys.size(), orphanedKeys
                ));
            }
            CommentWriter.write(
                this.path,
                ConfigMerger.buildOrderedMap(mergedMap, orphanedKeys, this.configClass),
                this.configClass,
                defaultMap
            );
        }

        return this.rawMapToObject(mergedMap);
    }

    /**
     * Serializes a config instance into a raw map via SnakeYAML round-trip.
     * Uses {@link ComponentRepresenter} to convert Minecraft {@code Component} fields into plain Java objects
     * that SnakeYAML can handle without custom tags.
     * 
     * @param configClass The config instance to serialize
     * 
     * @return A raw map representation of the config instance.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> objectToRawMap(ConfigClass configClass) {
        Yaml dumpYaml = new Yaml(new ComponentRepresenter(this.configClass, YAMLOptions.block()), YAMLOptions.block());
        String yaml = dumpYaml.dump(configClass);
        Object loaded = new Yaml(YAMLOptions.plain()).load(yaml);
        return loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    /**
     * Deserializes a raw map back into {@code ConfigClass} via SnakeYAML round-trip.
     * Uses {@link ComponentConstructor} to reconstruct Minecraft {@code Component} fields.
     * 
     * @param map The raw map to deserialize
     * 
     * @return An instance of {@code ConfigClass} populated with the data from the map.
     */
    private ConfigClass rawMapToObject(Map<String, Object> map) {
        String yamlContent = new Yaml(YAMLOptions.block()).dump(map);
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        return new Yaml(new ComponentConstructor(this.configClass, loaderOptions)).load(yamlContent);
    }

    /**
     * Writes the default config values to disk, preserving any orphaned keys with a @deprecated comment.
     * 
     * @param defaultMap The default config values as a raw map
     * @param orphanedKeys The set of keys that are present on disk but not in the default config,
     * which should be preserved with a @deprecated comment
     */
    private void writeDefaults(Map<String, Object> defaultMap, Set<String> orphanedKeys) {
        CommentWriter.write(
            this.path,
            ConfigMerger.buildOrderedMap(defaultMap, orphanedKeys, this.configClass),
            this.configClass,
            defaultMap
        );
    }

    /**
     * Creates a new instance of the config class using its no-arg constructor.
     * 
     * @return A new instance of the config class with default values.
     * 
     * @throws RuntimeException if the config class does not have a no-arg constructor
     * or if instantiation fails for any reason.
     */
    private ConfigClass createInstance() {
        try {
            Constructor<ConfigClass> ctor = this.configClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(
                "Config class '%s' must have a no-arg constructor.".formatted(this.configClass.getName()), e
            );
        }
    }
}
