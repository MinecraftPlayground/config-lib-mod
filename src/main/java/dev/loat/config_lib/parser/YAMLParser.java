package dev.loat.config_lib.parser;

import dev.loat.config_lib.annotation.Comment;
import dev.loat.config_lib.annotation.ConfigDeprecated;
import dev.loat.config_lib.parser.minecraft.ComponentConstructor;
import dev.loat.config_lib.parser.minecraft.ComponentRepresenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
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
 * <h2>Behavior on load</h2>
 * <ul>
 *   <li><b>File missing</b> — writes a new file from the class defaults and returns the defaults.</li>
 *   <li><b>File up-to-date</b> — parses and returns the file as-is.</li>
 *   <li><b>New keys in class</b> — adds the missing keys with their default values,
 *       rewrites the file, and returns the merged result.</li>
 *   <li><b>Orphaned keys</b> (on disk but removed from the class) — keeps them at the
 *       bottom of the file with an auto-generated {@code # @deprecated} comment.</li>
 *   <li><b>{@link ConfigDeprecated} annotation</b> — writes a descriptive
 *       {@code # @deprecated} comment above the annotated field.</li>
 * </ul>
 *
 * @param <T> The config class type
 */
public class YAMLParser<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(YAMLParser.class);

    private final Path path;
    private final Class<T> configClass;

    public YAMLParser(Path path, Class<T> configClass) {
        this.path = path;
        this.configClass = configClass;
    }

    /**
     * Main entry point. Loads the config, creating or merging as needed.
     *
     * @return The loaded (and possibly migrated) config instance
     */
    public T loadOrCreate() {
        T defaults = createDefault();

        if (!Files.exists(path)) {
            LOGGER.info("Config '{}' not found — writing defaults.", path.getFileName());
            writeFile(buildOrderedMap(objectToRawMap(defaults), Set.of()));
            return defaults;
        }

        Map<String, Object> diskMap = readRawMap();

        if (diskMap.isEmpty()) {
            LOGGER.warn("Config '{}' is empty — writing defaults.", path.getFileName());
            writeFile(buildOrderedMap(objectToRawMap(defaults), Set.of()));
            return defaults;
        }

        Map<String, Object> defaultMap = objectToRawMap(defaults);

        // Keys present in the class but missing from disk → need to be added
        Set<String> newKeys = defaultMap.keySet().stream()
            .filter(k -> !diskMap.containsKey(k))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        // Keys present on disk but no longer in the class → orphaned
        Set<String> orphanedKeys = diskMap.keySet().stream()
            .filter(k -> !defaultMap.containsKey(k))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Object> mergedMap = deepMerge(diskMap, defaultMap);

        if (!newKeys.isEmpty() || !orphanedKeys.isEmpty()) {
            if (!newKeys.isEmpty()) {
                LOGGER.info("Config '{}' — adding {} new key(s): {}", path.getFileName(), newKeys.size(), newKeys);
            }
            if (!orphanedKeys.isEmpty()) {
                LOGGER.info("Config '{}' — {} orphaned key(s) kept with @deprecated comment: {}",
                    path.getFileName(), orphanedKeys.size(), orphanedKeys);
            }
            writeFile(buildOrderedMap(mergedMap, orphanedKeys));
        }

        return rawMapToObject(mergedMap);
    }

    /**
     * Serializes {@code obj} to a raw {@code Map<String, Object>} via a YAML round-trip,
     * using {@link ComponentRepresenter} to handle Minecraft {@code Component} fields.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> objectToRawMap(T obj) {
        DumperOptions opts = dumperOptions();
        Yaml dumpYaml = new Yaml(new ComponentRepresenter(configClass, opts), opts);
        String yaml = dumpYaml.dump(obj);

        // Load back as a plain Map (no class binding)
        Object loaded = new Yaml(plainLoaderOptions()).load(yaml);
        return loaded instanceof Map<?, ?> m
            ? (Map<String, Object>) m
            : new LinkedHashMap<>();
    }

    /**
     * Deserializes a raw {@code Map<String, Object>} into {@code T} via a YAML round-trip,
     * using {@link ComponentConstructor} to reconstruct Minecraft {@code Component} fields.
     */
    private T rawMapToObject(Map<String, Object> map) {
        DumperOptions opts = dumperOptions();
        String yaml = new Yaml(opts).dump(map);
        LoaderOptions loaderOpts = new LoaderOptions();
        loaderOpts.setAllowDuplicateKeys(false);
        return new Yaml(new ComponentConstructor(configClass, loaderOpts)).load(yaml);
    }

    /**
     * Reads the YAML file on disk as a raw {@code Map<String, Object>}.
     * Returns an empty map on parse errors or if the file contains no mappings.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readRawMap() {
        try (InputStream is = Files.newInputStream(path)) {
            Object loaded = new Yaml(plainLoaderOptions()).load(is);
            return loaded instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : new LinkedHashMap<>();
        } catch (IOException e) {
            LOGGER.error("Failed to read config '{}': {}", path, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * Deep-merges {@code disk} and {@code defaults}.
     *
     * <ul>
     *   <li>Values from {@code disk} always win.</li>
     *   <li>Keys missing from {@code disk} are filled from {@code defaults}.</li>
     *   <li>Nested maps are merged recursively.</li>
     * </ul>
     */
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
            // else: keep disk value as-is
        }
        return result;
    }

    /**
     * Builds a map ordered for output:
     * <ol>
     *   <li>Class fields in their declaration order.</li>
     *   <li>Orphaned keys at the end (so they are easy to spot and clean up).</li>
     * </ol>
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

    /**
     * Writes {@code orderedMap} to disk as YAML, inserting comments from
     * {@link Comment} and {@link ConfigDeprecated} annotations and marking
     * orphaned keys with an auto-generated {@code # @deprecated} note.
     */
    private void writeFile(Map<String, Object> orderedMap) {
        try {
            Files.createDirectories(path.getParent());
            DumperOptions opts = dumperOptions();
            String yaml = new Yaml(opts).dump(orderedMap);
            String withComments = insertComments(yaml, classFieldKeys());
            Files.writeString(path, withComments);
        } catch (IOException e) {
            LOGGER.error("Failed to write config '{}': {}", path, e.getMessage());
        }
    }

    /**
     * Post-processes the YAML string to insert comments above top-level keys.
     *
     * <ul>
     *   <li>{@link Comment} → descriptive block comment</li>
     *   <li>{@link ConfigDeprecated} → {@code # @deprecated: ...} comment</li>
     *   <li>Orphaned key (not in class) → auto {@code # @deprecated} comment</li>
     * </ul>
     *
     * <p>Only top-level keys are processed. Nested keys are left as-is since
     * they belong to nested objects whose fields we cannot inspect without
     * walking the full type tree.</p>
     */
    private String insertComments(String yaml, Set<String> classKeys) {
        String[] lines = yaml.split("\n");
        StringBuilder result = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.stripLeading();
            int indent = line.length() - trimmed.length();

            // Only act on top-level, non-comment, non-list key lines
            if (indent == 0
                    && !trimmed.startsWith("#")
                    && !trimmed.startsWith("-")
                    && trimmed.contains(":")) {

                String key = trimmed.substring(0, trimmed.indexOf(":")).trim();

                if (!classKeys.contains(key)) {
                    // Orphaned key — auto-deprecate
                    result.append("# @deprecated: This key is no longer used and can be removed safely.\n");
                } else {
                    Field field = findFieldByName(key);
                    if (field != null) {
                        Comment comment = field.getAnnotation(Comment.class);
                        if (comment != null) {
                            appendCommentBlock(result, comment.value(), "");
                        }

                        ConfigDeprecated dep = field.getAnnotation(ConfigDeprecated.class);
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

    private void appendCommentBlock(StringBuilder sb, String text, String indent) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            sb.append(indent)
              .append(trimmed.isEmpty() ? "#" : "# " + trimmed)
              .append("\n");
        }
    }

    private String buildDeprecatedComment(ConfigDeprecated dep) {
        if (!dep.message().isEmpty()) {
            return "# @deprecated: " + dep.message();
        }

        List<String> parts = new ArrayList<>();
        if (!dep.migratedTo().isEmpty()) {
            parts.add("Migrated to '" + dep.migratedTo() + "'");
        }
        if (!dep.removedIn().isEmpty()) {
            parts.add("Will be removed in version " + dep.removedIn());
        }

        return parts.isEmpty()
            ? "# @deprecated: This field is deprecated and can be removed."
            : "# @deprecated: " + String.join(". ", parts) + ".";
    }

    private T createDefault() {
        try {
            return configClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(
                "Config class '%s' must have a public no-arg constructor.".formatted(configClass.getName()), e
            );
        }
    }

    private Set<String> classFieldKeys() {
        return Arrays.stream(configClass.getDeclaredFields())
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
