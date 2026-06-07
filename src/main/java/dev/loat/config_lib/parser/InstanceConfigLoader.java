package dev.loat.config_lib.parser;

import dev.loat.config_lib.annotation.Annotation;
import dev.loat.config_lib.logging.Logger;
import dev.loat.config_lib.parser.minecraft.ComponentConstructor;
import dev.loat.config_lib.parser.minecraft.ComponentRepresenter;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
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

        Map<String, Object> cleanMap = new LinkedHashMap<>(mergedMap);
        orphanedKeys.forEach(cleanMap::remove);
        return this.rawMapToObject(cleanMap);
    }

    /**
     * Serializes a config instance into a raw map via SnakeYAML round-trip, then:
     * <ol>
     *   <li>Reorders keys to match the field declaration order of the config class.</li>
     *   <li>Renames keys according to {@link Annotation.Key} where present.</li>
     * </ol>
     *
     * @param instance The config instance to serialize
     * 
     * @return A raw map with YAML-ready keys in declaration order.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> objectToRawMap(ConfigClass instance) {
        Yaml dumpYaml = new Yaml(new ComponentRepresenter(this.configClass, YAMLOptions.block()), YAMLOptions.block());
        String yamlContent = dumpYaml.dump(instance);
        Object loaded = new Yaml(YAMLOptions.plain()).load(yamlContent);
        Map<String, Object> rawMap = loaded instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : new LinkedHashMap<>();
        return InstanceConfigLoader.reorderAndRenameKeys(rawMap, this.configClass);
    }

    /**
     * Deserializes a raw map back into {@code ConfigClass} via SnakeYAML round-trip.
     * YAML key names are first reversed back to Java field names (undoing any
     * {@link Annotation.Key} renaming) so SnakeYAML can match them to bean properties.
     * Uses {@link ComponentConstructor} to reconstruct Minecraft {@code Component} fields.
     * 
     * @param map The raw map to deserialize (keys are YAML names, i.e. post-{@link Annotation.Key})
     * 
     * @return An instance of {@code ConfigClass} populated with the data from the map.
     */
    private ConfigClass rawMapToObject(Map<String, Object> map) {
        Map<String, Object> reversedMap = InstanceConfigLoader.reverseRenameKeys(map, this.configClass);
        String yamlContent = new Yaml(YAMLOptions.block()).dump(reversedMap);
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

    // -------------------------------------------------------------------------
    // Key rename helpers
    // -------------------------------------------------------------------------

    /**
     * Reorders the entries of {@code map} to match the field declaration order of {@code clazz},
     * and renames each key to the value of {@link Annotation.Key} if present on the field.
     *
     * <p>Nested config objects are processed recursively.
     * Keys that have no matching field are appended at the end unchanged.</p>
     *
     * @param map   A map whose keys are Java field names (as produced by SnakeYAML)
     * @param clazz The config class whose field order and key annotations drive the rename
     *
     * @return A new map with YAML key names in declaration order.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> reorderAndRenameKeys(Map<String, Object> map, Class<?> clazz) {
        Map<String, Object> ordered = new LinkedHashMap<>();

        for (Field field : clazz.getDeclaredFields()) {
            if (field.isSynthetic()) continue;

            String fieldName = field.getName();
            if (!map.containsKey(fieldName)) continue;

            String yamlKey = ConfigMerger.getYamlKey(field);
            Object value = map.get(fieldName);

            if (value instanceof Map && InstanceConfigLoader.isNestedConfigType(field.getType())) {
                value = InstanceConfigLoader.reorderAndRenameKeys((Map<String, Object>) value, field.getType());
            }

            ordered.put(yamlKey, value);
        }

        // Append any keys not present in the class (orphaned disk entries)
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!ordered.containsKey(entry.getKey())) {
                ordered.put(entry.getKey(), entry.getValue());
            }
        }

        return ordered;
    }

    /**
     * Reverses the key renaming applied by {@link #reorderAndRenameKeys}: maps YAML key names
     * back to Java field names so SnakeYAML can bind them to bean properties.
     *
     * <p>Nested config objects are processed recursively.
     * Keys that have no matching field (orphaned) are kept as-is.</p>
     *
     * @param map   A map whose keys are YAML names (post-{@link Annotation.Key} rename)
     * @param clazz The config class used to build the reverse mapping
     *
     * @return A new map with Java field names as keys.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> reverseRenameKeys(Map<String, Object> map, Class<?> clazz) {
        // Build: yamlKey -> field
        Map<String, Field> yamlKeyToField = new LinkedHashMap<>();
        for (Field field : clazz.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                yamlKeyToField.put(ConfigMerger.getYamlKey(field), field);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Field field = yamlKeyToField.get(entry.getKey());
            String outputKey = field != null ? field.getName() : entry.getKey();
            Object value = entry.getValue();

            if (field != null && value instanceof Map && InstanceConfigLoader.isNestedConfigType(field.getType())) {
                value = InstanceConfigLoader.reverseRenameKeys((Map<String, Object>) value, field.getType());
            }

            result.put(outputKey, value);
        }

        return result;
    }

    /**
     * Returns {@code true} if {@code type} is a custom config class whose fields
     * should be recursively renamed.
     *
     * <p>Excludes primitives, {@link String}, arrays, enums, collections, maps,
     * and boxed types — these are serialized as scalars or sequences.</p>
     *
     * @param type The class to check
     *
     * @return {@code true} if the type is a custom nested config class
     */
    static boolean isNestedConfigType(Class<?> type) {
        if (
            type.isPrimitive() ||
            type == String.class ||
            type.isArray() ||
            type.isEnum() ||
            Collection.class.isAssignableFrom(type) ||
            Map.class.isAssignableFrom(type) ||
            Number.class.isAssignableFrom(type) ||
            type == Boolean.class ||
            type == Character.class
        ) {
            return false;
        }
        return true;
    }
}
