package dev.loat.config_lib.parser;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import dev.loat.config_lib.annotation.Annotation;
import dev.loat.config_lib.parser.minecraft.ComponentConstructor;
import dev.loat.config_lib.parser.minecraft.ComponentRepresenter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Handles loading, creating, and smart-merging YAML config files.
 *
 * <p>Supports two config class styles:</p>
 *
 * <p><b>Static fields</b> (recommended) — the class has a private constructor and all
 * config fields are {@code public static}. After loading, values are written directly
 * into the static fields via reflection and are accessible anywhere as
 * {@code MyConfig.myField}. {@link #loadOrCreate()} returns a dummy instance so that
 * {@code configManager.get(MyConfig.class).myField} also works syntactically.</p>
 *
 * <p><b>Instance fields</b> — standard Java bean style. {@link #loadOrCreate()} returns
 * the populated instance.</p>
 *
 * <h2>Merge behavior</h2>
 * <ul>
 *   <li><b>File missing</b> — writes defaults and returns.</li>
 *   <li><b>New keys in class</b> — added with default values; file is rewritten.</li>
 *   <li><b>Orphaned keys</b> — kept at the bottom of the file with a
 *       {@code # @deprecated} comment.</li>
 *   <li><b>{@link Annotation.Deprecated}</b> — descriptive deprecation comment
 *       written above the annotated field.</li>
 * </ul>
 *
 * @param <T> The config class type
 */
public class YAMLParser<T> {

    private final Path path;
    private final Class<T> configClass;
    private final Logger logger;
    private final boolean isStaticConfig;

    public YAMLParser(Path path, Class<T> configClass, Logger logger) {
        this.path = path;
        this.configClass = configClass;
        this.logger = logger;
        this.isStaticConfig = detectStaticConfig();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Loads the config from disk, creating or merging the file as needed.
     *
     * <p>For static-field configs, all public static fields of the config class are
     * populated directly. The returned instance is a reflectively created dummy — it
     * holds no state of its own, but {@code instance.fieldName} correctly reads the
     * static field.</p>
     *
     * @return The populated config instance, or a dummy instance for static configs.
     */
    public T loadOrCreate() {
        if (isStaticConfig) {
            return loadOrCreateStatic();
        }
        return loadOrCreateInstance();
    }

    // -------------------------------------------------------------------------
    // Static field config
    // -------------------------------------------------------------------------

    private T loadOrCreateStatic() {
        // Snapshot the current static field values — these are the class-defined defaults
        Map<String, Object> defaultMap = staticFieldsToMap();

        if (!Files.exists(path)) {
            logger.info("Config '{}' not found — writing defaults.", path.getFileName());
            writeFile(buildOrderedMap(defaultMap, Set.of()));
            return createDummyInstance();
        }

        Map<String, Object> diskMap = readRawMap();

        if (diskMap.isEmpty()) {
            logger.warn("Config '{}' is empty — writing defaults.", path.getFileName());
            writeFile(buildOrderedMap(defaultMap, Set.of()));
            return createDummyInstance();
        }

        Set<String> newKeys = defaultMap.keySet().stream()
            .filter(k -> !diskMap.containsKey(k))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> orphanedKeys = diskMap.keySet().stream()
            .filter(k -> !defaultMap.containsKey(k))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Object> mergedMap = deepMerge(diskMap, defaultMap);

        if (!newKeys.isEmpty() || !orphanedKeys.isEmpty()) {
            if (!newKeys.isEmpty()) {
                logger.info("Config '{}' — adding {} new key(s): {}", path.getFileName(), newKeys.size(), newKeys);
            }
            if (!orphanedKeys.isEmpty()) {
                logger.info("Config '{}' — {} orphaned key(s) kept with @deprecated comment: {}",
                    path.getFileName(), orphanedKeys.size(), orphanedKeys);
            }
            writeFile(buildOrderedMap(mergedMap, orphanedKeys));
        }

        applyMapToStaticFields(mergedMap);
        return createDummyInstance();
    }

    /**
     * Reads all public static fields of the config class into a raw map.
     * Component fields are converted to their plain-Java representation.
     */
    private Map<String, Object> staticFieldsToMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Field field : configClass.getDeclaredFields()) {
            if (!isRelevantStaticField(field)) continue;
            field.setAccessible(true);
            try {
                map.put(field.getName(), toYamlCompatible(field.get(null)));
            } catch (Exception e) {
                logger.error("Failed to read static field '{}' in '{}': {}",
                    field.getName(), configClass.getSimpleName(), e.getMessage());
            }
        }
        return map;
    }

    /**
     * Applies values from {@code map} back to the static fields of the config class.
     */
    private void applyMapToStaticFields(Map<String, Object> map) {
        for (Field field : configClass.getDeclaredFields()) {
            if (!isRelevantStaticField(field)) continue;
            if (!map.containsKey(field.getName())) continue;
            field.setAccessible(true);
            try {
                field.set(null, convertToType(field.getType(), map.get(field.getName())));
            } catch (Exception e) {
                logger.error("Failed to apply value to static field '{}' in '{}': {}",
                    field.getName(), configClass.getSimpleName(), e.getMessage());
            }
        }
    }

    /**
     * Creates a dummy instance of the config class bypassing its private constructor.
     * For static configs, this is only used so that {@code get().fieldName} syntactically
     * accesses the static field — the instance itself carries no state.
     */
    private T createDummyInstance() {
        try {
            java.lang.reflect.Constructor<T> ctor = configClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            logger.error("Could not create dummy instance of '{}': {}",
                configClass.getSimpleName(), e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Instance field config
    // -------------------------------------------------------------------------

    private T loadOrCreateInstance() {
        T defaults = createInstance();

        if (!Files.exists(path)) {
            logger.info("Config '{}' not found — writing defaults.", path.getFileName());
            writeFile(buildOrderedMap(objectToRawMap(defaults), Set.of()));
            return defaults;
        }

        Map<String, Object> diskMap = readRawMap();

        if (diskMap.isEmpty()) {
            logger.warn("Config '{}' is empty — writing defaults.", path.getFileName());
            writeFile(buildOrderedMap(objectToRawMap(defaults), Set.of()));
            return defaults;
        }

        Map<String, Object> defaultMap = objectToRawMap(defaults);

        Set<String> newKeys = defaultMap.keySet().stream()
            .filter(k -> !diskMap.containsKey(k))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> orphanedKeys = diskMap.keySet().stream()
            .filter(k -> !defaultMap.containsKey(k))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Object> mergedMap = deepMerge(diskMap, defaultMap);

        if (!newKeys.isEmpty() || !orphanedKeys.isEmpty()) {
            if (!newKeys.isEmpty()) {
                logger.info("Config '{}' — adding {} new key(s): {}", path.getFileName(), newKeys.size(), newKeys);
            }
            if (!orphanedKeys.isEmpty()) {
                logger.info("Config '{}' — {} orphaned key(s) kept with @deprecated comment: {}",
                    path.getFileName(), orphanedKeys.size(), orphanedKeys);
            }
            writeFile(buildOrderedMap(mergedMap, orphanedKeys));
        }

        return rawMapToObject(mergedMap);
    }

    /**
     * Serializes {@code obj} to a raw {@code Map} via SnakeYAML round-trip,
     * using {@link ComponentRepresenter} to handle Minecraft {@link Component} fields.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> objectToRawMap(T obj) {
        DumperOptions opts = dumperOptions();
        Yaml dumpYaml = new Yaml(new ComponentRepresenter(configClass, opts), opts);
        String yaml = dumpYaml.dump(obj);
        Object loaded = new Yaml(plainLoaderOptions()).load(yaml);
        return loaded instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    /**
     * Deserializes a raw {@code Map} into {@code T} via SnakeYAML round-trip,
     * using {@link ComponentConstructor} to reconstruct Minecraft {@link Component} fields.
     */
    private T rawMapToObject(Map<String, Object> map) {
        DumperOptions opts = dumperOptions();
        String yaml = new Yaml(opts).dump(map);
        LoaderOptions loaderOpts = new LoaderOptions();
        loaderOpts.setAllowDuplicateKeys(false);
        return new Yaml(new ComponentConstructor(configClass, loaderOpts)).load(yaml);
    }

    private T createInstance() {
        try {
            java.lang.reflect.Constructor<T> ctor = configClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(
                "Config class '%s' must have a no-arg constructor.".formatted(configClass.getName()), e);
        }
    }

    // -------------------------------------------------------------------------
    // Shared merge logic
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRawMap() {
        try (InputStream is = Files.newInputStream(path)) {
            Object loaded = new Yaml(plainLoaderOptions()).load(is);
            return loaded instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        } catch (IOException e) {
            logger.error("Failed to read config '{}': {}", path, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepMerge(Map<String, Object> disk, Map<String, Object> defaults) {
        Map<String, Object> result = new LinkedHashMap<>(disk);
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            if (!result.containsKey(key)) {
                result.put(key, entry.getValue());
            } else if (entry.getValue() instanceof Map && result.get(key) instanceof Map) {
                result.put(key, deepMerge(
                    (Map<String, Object>) result.get(key),
                    (Map<String, Object>) entry.getValue()
                ));
            }
        }
        return result;
    }

    /**
     * Returns an ordered map for output: class fields in declaration order first,
     * then orphaned keys at the end so they are easy to spot and clean up.
     */
    private Map<String, Object> buildOrderedMap(Map<String, Object> merged, Set<String> orphanedKeys) {
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

    // -------------------------------------------------------------------------
    // File writing with comments
    // -------------------------------------------------------------------------

    private void writeFile(Map<String, Object> orderedMap) {
        try {
            Files.createDirectories(path.getParent());
            DumperOptions opts = dumperOptions();
            String yaml = new Yaml(opts).dump(orderedMap);
            String withComments = insertComments(yaml, classFieldKeys());
            Files.writeString(path, withComments);
        } catch (IOException e) {
            logger.error("Failed to write config '{}': {}", path, e.getMessage());
        }
    }

    /**
     * Post-processes the raw YAML string and inserts comments above top-level keys:
     * <ul>
     *   <li>{@link Annotation.Comment} → descriptive block comment</li>
     *   <li>{@link Annotation.Deprecated} → {@code # @deprecated} comment</li>
     *   <li>Orphaned key (not in class) → auto {@code # @deprecated} comment</li>
     * </ul>
     */
    private String insertComments(String yaml, Set<String> classKeys) {
        String[] lines = yaml.split("\n");
        StringBuilder result = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.stripLeading();
            int indent = line.length() - trimmed.length();

            if (indent == 0
                    && !trimmed.startsWith("#")
                    && !trimmed.startsWith("-")
                    && trimmed.contains(":")) {

                String key = trimmed.substring(0, trimmed.indexOf(":")).trim();

                if (!classKeys.contains(key)) {
                    result.append("# @deprecated: This key is no longer used and can be removed safely.\n");
                } else {
                    Field field = findFieldByName(key);
                    if (field != null) {
                        Annotation.Comment comment = field.getAnnotation(Annotation.Comment.class);
                        if (comment != null) {
                            appendCommentBlock(result, comment.value());
                        }

                        Annotation.Deprecated dep = field.getAnnotation(Annotation.Deprecated.class);
                        if (dep != null) {
                            result.append(buildDeprecatedComment(dep)).append("\n");
                        }
                    }
                }
            }

            result.append(line).append("\n");
        }

        return result.toString();
    }

    // -------------------------------------------------------------------------
    // Type conversion helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a Java field value to a YAML-compatible representation.
     * Minecraft {@link Component} instances are converted to their plain-Java
     * (Map/String) form so SnakeYAML can serialize them without custom tags.
     */
    private Object toYamlCompatible(Object value) {
        if (value instanceof Component comp) {
            JsonElement json = ComponentSerialization.CODEC
                .encodeStart(JsonOps.INSTANCE, comp)
                .getOrThrow(e -> new RuntimeException("Failed to encode Component: " + e));
            return new Gson().fromJson(json, Object.class);
        }
        return value;
    }

    /**
     * Converts a YAML-loaded value to the target Java field type.
     * Handles primitives, {@link String}, and Minecraft {@link Component} fields.
     * For other types a best-effort direct cast is attempted.
     */
    private Object convertToType(Class<?> type, Object yamlValue) {
        if (yamlValue == null) return null;

        // Minecraft Component
        if (Component.class.isAssignableFrom(type) || MutableComponent.class.isAssignableFrom(type)) {
            JsonElement json = objectToJson(yamlValue);
            return ComponentSerialization.CODEC
                .decode(JsonOps.INSTANCE, json)
                .getOrThrow(e -> new IllegalStateException("Failed to decode Component: " + e))
                .getFirst();
        }

        if (type == String.class)                          return String.valueOf(yamlValue);
        if (type == int.class    || type == Integer.class) return ((Number) yamlValue).intValue();
        if (type == long.class   || type == Long.class)    return ((Number) yamlValue).longValue();
        if (type == double.class || type == Double.class)  return ((Number) yamlValue).doubleValue();
        if (type == float.class  || type == Float.class)   return ((Number) yamlValue).floatValue();
        if (type == boolean.class || type == Boolean.class) return (Boolean) yamlValue;

        return yamlValue; // best-effort for other types
    }

    /**
     * Converts a plain Java object (from YAML load) to a {@link JsonElement}
     * for use with {@link ComponentSerialization}.
     */
    @SuppressWarnings("unchecked")
    private JsonElement objectToJson(Object obj) {
        if (obj == null)             return JsonNull.INSTANCE;
        if (obj instanceof Boolean b) return new JsonPrimitive(b);
        if (obj instanceof Number n)  return new JsonPrimitive(n);
        if (obj instanceof String s)  return new JsonPrimitive(s);

        if (obj instanceof Map<?, ?> map) {
            JsonObject jsonObj = new JsonObject();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) map).entrySet()) {
                jsonObj.add(entry.getKey(), objectToJson(entry.getValue()));
            }
            return jsonObj;
        }

        if (obj instanceof List<?> list) {
            JsonArray arr = new JsonArray();
            for (Object item : list) arr.add(objectToJson(item));
            return arr;
        }

        return new JsonPrimitive(obj.toString());
    }

    // -------------------------------------------------------------------------
    // Comment building helpers
    // -------------------------------------------------------------------------

    private void appendCommentBlock(StringBuilder sb, String text) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            sb.append(trimmed.isEmpty() ? "#" : "# " + trimmed).append("\n");
        }
    }

    private String buildDeprecatedComment(Annotation.Deprecated dep) {
        if (!dep.message().isEmpty()) {
            return "# @deprecated: " + dep.message();
        }
        List<String> parts = new ArrayList<>();
        if (!dep.migratedTo().isEmpty()) parts.add("Migrated to '" + dep.migratedTo() + "'");
        if (!dep.removedIn().isEmpty())  parts.add("Will be removed in version " + dep.removedIn());
        return parts.isEmpty()
            ? "# @deprecated: This field is deprecated and can be removed."
            : "# @deprecated: " + String.join(". ", parts) + ".";
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if all declared non-synthetic fields are static,
     * indicating this is a static-field config class.
     */
    private boolean detectStaticConfig() {
        Field[] fields = configClass.getDeclaredFields();
        boolean hasAny = false;
        for (Field f : fields) {
            if (f.isSynthetic()) continue;
            hasAny = true;
            if (!Modifier.isStatic(f.getModifiers())) return false;
        }
        return hasAny;
    }

    private boolean isRelevantStaticField(Field field) {
        return Modifier.isStatic(field.getModifiers())
            && !Modifier.isTransient(field.getModifiers())
            && !field.isSynthetic();
    }

    private Set<String> classFieldKeys() {
        return Arrays.stream(configClass.getDeclaredFields())
            .filter(f -> !f.isSynthetic())
            .map(Field::getName)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Field findFieldByName(String name) {
        try {
            return configClass.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // SnakeYAML options
    // -------------------------------------------------------------------------

    private DumperOptions dumperOptions() {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setIndent(2);
        opts.setExplicitStart(false);
        opts.setExplicitEnd(false);
        return opts;
    }

    private LoaderOptions plainLoaderOptions() {
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        return opts;
    }
}
