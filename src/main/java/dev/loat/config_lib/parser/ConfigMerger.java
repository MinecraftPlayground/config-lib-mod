package dev.loat.config_lib.parser;

import org.yaml.snakeyaml.Yaml;

import dev.loat.config_lib.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;


/**
 * Pure utility methods for reading, merging, and ordering config maps.
 * The main purpose of this class is to separate the merging logic from the comment insertion logic in {@link CommentWriter}.
 */
final class ConfigMerger {

    private ConfigMerger() {}

    /**
     * Reads the YAML file at {@code path} as a raw {@code Map<String, Object>}.
     * 
     * @param path The file path to read the YAML content from
     * 
     * @return A map representing the YAML content, or an empty map if the file could not be read or parsed.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readRawMap(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            Object loaded = new Yaml(YAMLOptions.plain()).load(is);
            return loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
        } catch (IOException e) {
            Logger.error("Failed to read config '%s': %s".formatted(path.getFileName(), e.getMessage()));
            return new LinkedHashMap<>();
        }
    }

    /**
     * Recursively merges {@code disk} and {@code defaults}.
     * Values present on disk always win; missing keys are filled from defaults;
     * nested maps are merged recursively.
     * 
     * @param disk The map representing the YAML content read from disk
     * @param defaults The map representing the default config values from the class
     * 
     * @return A new map containing the merged config values, with disk values taking precedence over defaults.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> disk, Map<String, Object> defaults) {
        Map<String, Object> result = new LinkedHashMap<>(disk);
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            if (!result.containsKey(key)) {
                result.put(key, entry.getValue());
            } else if (entry.getValue() instanceof Map && result.get(key) instanceof Map) {
                result.put(key, ConfigMerger.deepMerge(
                    (Map<String, Object>) result.get(key),
                    (Map<String, Object>) entry.getValue()
                ));
            }
        }
        return result;
    }

    /**
     * Builds a map ordered for YAML output:
     * class fields appear first in their declaration order,
     * followed by orphaned keys at the end (for easy user cleanup).
     * 
     * @param merged The merged config map
     * @param orphanedKeys The set of orphaned keys
     * @param configClass The config class
     * 
     * @return A new map containing the ordered config values
     */
    static Map<String, Object> buildOrderedMap(
        Map<String, Object> merged,
        Set<String> orphanedKeys,
        Class<?> configClass
    ) {
        Map<String, Object> ordered = new LinkedHashMap<>();

        for (Field field : configClass.getDeclaredFields()) {
            String key = field.getName();
            if (merged.containsKey(key)) {
                ordered.put(key, merged.get(key));
            }
        }

        for (String key : orphanedKeys) {
            ordered.put(key, merged.get(key));
        }

        return ordered;
    }
}
