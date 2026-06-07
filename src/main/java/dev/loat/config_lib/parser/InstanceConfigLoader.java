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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

    InstanceConfigLoader(Path path, Class<ConfigClass> configClass) {
        this.path = path;
        this.configClass = configClass;
    }

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

    private ConfigClass rawMapToObject(Map<String, Object> map) {
        Map<String, Object> reversedMap = InstanceConfigLoader.reverseRenameKeys(map, this.configClass);
        String yamlContent = new Yaml(YAMLOptions.block()).dump(reversedMap);
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        return new Yaml(new ComponentConstructor(this.configClass, loaderOptions)).load(yamlContent);
    }

    private void writeDefaults(Map<String, Object> defaultMap, Set<String> orphanedKeys) {
        CommentWriter.write(
            this.path,
            ConfigMerger.buildOrderedMap(defaultMap, orphanedKeys, this.configClass),
            this.configClass,
            defaultMap
        );
    }

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

    /**
     * Reorders the entries of {@code map} to match the field declaration order of
     * {@code clazz} and renames each key according to {@link Annotation.Key} where present.
     *
     * <p>Nested config objects and lists of nested config objects are processed recursively.</p>
     *
     * <p>Keys with no matching field (truly orphaned) are appended at the end. The check
     * uses the <em>original Java field names</em> that were processed, not the potentially
     * renamed YAML keys stored in {@code ordered} — without this distinction, any field
     * whose name differs from its {@link Annotation.Key} would be incorrectly appended a
     * second time.</p>
     *
     * @param map   A map whose keys are Java field names (as produced by SnakeYAML)
     * @param clazz The config class whose field order and key annotations drive the rename
     * @return A new map with YAML key names in declaration order
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> reorderAndRenameKeys(Map<String, Object> map, Class<?> clazz) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        Set<String> processedJavaNames = new LinkedHashSet<>();

        for (Field field : clazz.getDeclaredFields()) {
            if (field.isSynthetic()) continue;

            String fieldName = field.getName();
            if (!map.containsKey(fieldName)) continue;

            processedJavaNames.add(fieldName);
            String yamlKey = ConfigMerger.getYamlKey(field);
            Object value = map.get(fieldName);

            if (value instanceof Map && ConfigMerger.isNestedConfigType(field.getType())) {
                value = InstanceConfigLoader.reorderAndRenameKeys((Map<String, Object>) value, field.getType());
            } else if (value instanceof List<?> list) {
                Class<?> elementType = ConfigMerger.getListElementType(field);
                if (elementType != null && ConfigMerger.isNestedConfigType(elementType)) {
                    List<Object> renamedList = new ArrayList<>();
                    for (Object item : list) {
                        renamedList.add(item instanceof Map
                            ? InstanceConfigLoader.reorderAndRenameKeys((Map<String, Object>) item, elementType)
                            : item
                        );
                    }
                    value = renamedList;
                }
            }

            ordered.put(yamlKey, value);
        }

        // Append keys that have no matching field in the class (genuinely orphaned).
        // Must check against processedJavaNames (original keys), not ordered (YAML keys):
        // a field whose @Annotation.Key differs from its Java name would otherwise be
        // appended a second time because ordered only contains the renamed YAML key.
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!processedJavaNames.contains(entry.getKey())) {
                ordered.put(entry.getKey(), entry.getValue());
            }
        }

        return ordered;
    }

    /**
     * Reverses the key renaming applied by {@link #reorderAndRenameKeys}: maps YAML key
     * names back to Java field names so SnakeYAML can bind them to bean properties.
     *
     * <p>Nested config objects and lists of nested config objects are processed recursively.
     * Keys with no matching field are kept as-is.</p>
     *
     * @param map   A map whose keys are YAML names (post-{@link Annotation.Key} rename)
     * @param clazz The config class used to build the reverse mapping
     * @return A new map with Java field names as keys
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> reverseRenameKeys(Map<String, Object> map, Class<?> clazz) {
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

            if (field != null) {
                if (value instanceof Map && ConfigMerger.isNestedConfigType(field.getType())) {
                    value = InstanceConfigLoader.reverseRenameKeys((Map<String, Object>) value, field.getType());
                } else if (value instanceof List<?> list) {
                    Class<?> elementType = ConfigMerger.getListElementType(field);
                    if (elementType != null && ConfigMerger.isNestedConfigType(elementType)) {
                        List<Object> reversedList = new ArrayList<>();
                        for (Object item : list) {
                            reversedList.add(item instanceof Map
                                ? InstanceConfigLoader.reverseRenameKeys((Map<String, Object>) item, elementType)
                                : item
                            );
                        }
                        value = reversedList;
                    }
                }
            }

            result.put(outputKey, value);
        }

        return result;
    }
}
