package dev.loat.config_lib.parser;

import dev.loat.config_lib.annotation.Annotation;
import dev.loat.config_lib.logging.Logger;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;


/**
 * Utility for inserting comments into YAML content based on config class annotations.
 */
final class CommentWriter {

    private CommentWriter() {}

    /**
     * Writes the given ordered map to the specified path as YAML, inserting comments based on the annotations
     * present in the config class.
     * 
     * @param path The file path to write the YAML content to
     * @param orderedMap The map containing the config data to be serialized to YAML
     * @param rootClass The root config class to use for annotation lookup when inserting comments
     */
    static void write(Path path, Map<String, Object> orderedMap, Class<?> rootClass) {
        try {
            Files.createDirectories(path.getParent());
            String yaml = new Yaml(YAMLOptions.block()).dump(orderedMap);
            Files.writeString(path, CommentWriter.insertComments(yaml, rootClass));
        } catch (IOException e) {
            Logger.error("Failed to write config '%s': %s".formatted(path.getFileName(), e.getMessage()));
        }
    }

    /**
     * Inserts comments into the given YAML content based on the annotations present in the config class.
      * Comments are added above each key according to the following rules:
      * <ul>
      *   <li>If the corresponding field has a {@link Annotation.Comment}, its value is inserted as a block comment.</li>
      *   <li>If the field has a {@link Annotation.Deprecated}, a deprecation warning comment is generated based on its properties.</li>
      *   <li>If a key exists in the YAML content but has no corresponding field in the class, an orphaned key comment is added.</li>
      * </ul>
      *
      * @param yamlContent The original YAML content as a string
      * @param rootClass The root config class to use for annotation lookup
      * 
      * @return A new YAML string with comments inserted.
     */
    private static String insertComments(String yamlContent, Class<?> rootClass) {
        String[] lines = yamlContent.split("\n");
        StringBuilder result = new StringBuilder();

        // Add file description if present
        Annotation.FileDescription fileDesc = rootClass.getAnnotation(Annotation.FileDescription.class);
        if (fileDesc != null) {
            CommentWriter.appendCommentBlock(result, fileDesc.value(), "");
            result.append("\n");
        }

        // Stack: (class, indentOfParentKey)
        // Root entry uses -1 so that all top-level keys (indent 0) fall inside it.
        Deque<ScopeEntry> stack = new ArrayDeque<>();
        stack.push(new ScopeEntry(rootClass, -1));

        for (String line : lines) {
            String trimmed = line.stripLeading();
            int indent = line.length() - trimmed.length();

            // Pass through: empty lines, existing comments, list items, lines without a colon
            if (
                trimmed.isEmpty() ||
                trimmed.startsWith("#") ||
                trimmed.startsWith("-") ||
                !trimmed.contains(":")
            ) {
                result.append(line).append("\n");
                continue;
            }

            String key = trimmed.substring(0, trimmed.indexOf(":")).trim();
            String indentStr = " ".repeat(indent);

            // Leave only the scopes that are still active at this indentation level
            while (stack.size() > 1 && stack.peek().parentIndent() >= indent) {
                stack.pop();
            }

            Class<?> currentClass = stack.peek().clazz();
            Field field = CommentWriter.findField(currentClass, key);

            if (field == null) {
                // Key exists on disk but is no longer in the class
                result
                    .append(indentStr)
                    .append("# @deprecated: This key is no longer used and can be removed safely.\n");
            } else {
                Annotation.Comment comment = field.getAnnotation(Annotation.Comment.class);
                if (comment != null) {
                    CommentWriter.appendCommentBlock(result, comment.value(), indentStr);
                }

                Annotation.Deprecated dep = field.getAnnotation(Annotation.Deprecated.class);
                if (dep != null) {
                    result
                        .append(indentStr)
                        .append(CommentWriter.buildDeprecatedComment(dep))
                        .append("\n");
                }

                // If the field holds a nested config object, push its type so that
                // its fields can be annotated at the next indentation level.
                if (CommentWriter.isNestedConfigType(field.getType())) {
                    stack.push(new ScopeEntry(field.getType(), indent));
                }
            }

            result
                .append(line)
                .append("\n");
        }

        return result.toString();
    }

    /**
     * Appends a block of comments to the result builder.
     *
     * @param stringBuilder The {@link StringBuilder} to append the comments to
     * @param text The comment text to append
     * @param indent The indentation string to use
     */
    private static void appendCommentBlock(StringBuilder stringBuilder, String text, String indent) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            stringBuilder
                .append(indent)
                .append(trimmed.isEmpty() ? "#" : "# " + trimmed)
                .append("\n");
        }
    }

    /**
     * Builds a deprecation comment based on the properties of the given annotation.
      * If a message is provided, it is included verbatim. Otherwise, the presence
      * of other properties determines the content of the comment.
      * 
      * @param deprecationAnnotation The {@link Annotation.Deprecated} instance to build the comment from
      * 
      * @return A string containing the formatted deprecation comment
     */
    private static String buildDeprecatedComment(Annotation.Deprecated deprecationAnnotation) {
        if (!deprecationAnnotation.message().isEmpty()) {
            return "# @deprecated: " + deprecationAnnotation.message();
        }
        List<String> parts = new ArrayList<>();
        if (!deprecationAnnotation.migratedTo().isEmpty()) {
            parts.add("Migrated to '" + deprecationAnnotation.migratedTo() + "'");
        }
        if (!deprecationAnnotation.removedIn().isEmpty()) {
            parts.add("Will be removed in version " + deprecationAnnotation.removedIn());
        }
        return parts.isEmpty()
            ? "# @deprecated: This field is deprecated and can be removed."
            : "# @deprecated: " + String.join(". ", parts) + ".";
    }

    /**
     * Finds a declared field with the given name in the class, or returns {@code null} if not found.
     * 
     * @param clazz The class to search for the field
     * @param name The field name to find
     * 
     * @return The {@link Field} if found, or {@code null} if no such field exists in the class.
     */
    private static Field findField(Class<?> clazz, String name) {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /**
     * Determines if a type should be treated as a nested config class for comment insertion purposes.
     * 
     * @param type The class to check
     * 
     * @return {@code true} if the type is a nested config class, or {@code false} if it is a primitive,
     * String, Collection, Map, array, enum, or boxed type.
     */
    private static boolean isNestedConfigType(Class<?> type) {
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
        };
        return true;
    }

    /**
     * A record representing a scope entry for nested config classes.
     *
     * @param clazz The class of the scope entry
     * @param parentIndent The indentation level of the parent scope
     */
    private record ScopeEntry(Class<?> clazz, int parentIndent) {}
}
