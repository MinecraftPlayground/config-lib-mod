package dev.loat.config_lib.parser;

import dev.loat.config_lib.annotation.Annotation;
import dev.loat.config_lib.logging.Logger;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
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
     * class fields appear first in their declaration order (using {@link Annotation.Key}
     * names where present), followed by orphaned keys at the end for easy user cleanup.
     * 
     * @param merged The merged config map (keys are YAML names)
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
            if (field.isSynthetic()) continue;
            String key = ConfigMerger.getYamlKey(field);
            if (merged.containsKey(key)) {
                ordered.put(key, merged.get(key));
            }
        }

        for (String key : orphanedKeys) {
            ordered.put(key, merged.get(key));
        }

        return ordered;
    }

    /**
     * Returns the YAML key name for the given field.
     *
     * <p>If the field has an {@link Annotation.Key} annotation, its trimmed value is returned.
     * Otherwise the Java field name is used.</p>
     *
     * @param field The field to get the YAML key for
     *
     * @return The YAML key name
     */
    static String getYamlKey(Field field) {
        Annotation.Key keyAnnotation = field.getAnnotation(Annotation.Key.class);
        return keyAnnotation != null ? keyAnnotation.value().trim() : field.getName();
    }

    /**
     * Returns {@code true} if {@code type} is a custom config class whose fields
     * should be recursively processed (renamed, reordered, annotated).
     *
     * <p>Excludes primitives, {@link String}, arrays, enums, collections, maps,
     * and boxed types - these are serialized as scalars or sequences.</p>
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

    /**
     * Extracts the element type {@code T} from a {@code List<T>} field declaration.
     * Returns {@code null} for wildcards, nested generics, or non-parameterized types.
     *
     * @param field The field to extract the element type from
     *
     * @return The element class, or {@code null} if it cannot be determined
     */
    static Class<?> getListElementType(Field field) {
        Type genericType = field.getGenericType();
        if (!(genericType instanceof ParameterizedType paramType)) return null;
        Type[] args = paramType.getActualTypeArguments();
        if (args.length != 1 || !(args[0] instanceof Class<?> elementClass)) return null;
        return elementClass;
    }
}
