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
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


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
     * @param defaultMap The map containing default values for the config fields, used for generating default value comments
     */
    static void write(
        Path path,
        Map<String, Object> orderedMap,
        Class<?> rootClass,
        Map<String, Object> defaultMap
    ) {
        try {
            Files.createDirectories(path.getParent());
            String yaml = new Yaml(YAMLOptions.block()).dump(orderedMap);
            Files.writeString(path, CommentWriter.insertComments(yaml, rootClass, defaultMap));
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
     *   <li>List item start lines ({@code - key: value}) are handled separately so the first key's comments
     *       appear on the {@code - } line, and a blank line is inserted between consecutive items.</li>
     * </ul>
     *
     * @param yamlContent The original YAML content as a string
     * @param rootClass The root config class to use for annotation lookup
     * @param defaultMap The map containing default values for the config fields
     * 
     * @return A new YAML string with comments inserted.
     */
    @SuppressWarnings("unchecked")
    private static String insertComments(
        String yamlContent,
        Class<?> rootClass,
        Map<String, Object> defaultMap
    ) {
        String[] lines = yamlContent.split("\n");
        StringBuilder result = new StringBuilder();

        // Class-level @Annotation.Comment → file header banner
        Annotation.Comment classComment = rootClass.getAnnotation(Annotation.Comment.class);
        if (classComment != null) {
            CommentWriter.appendCommentBlock(result, classComment.value(), "");
            result.append("\n");
        }

        // Stack: (class, indentOfParentKey, defaults, fromList)
        // Root entry uses -1 so that all top-level keys (indent 0) fall inside it.
        Deque<ScopeEntry> stack = new ArrayDeque<>();
        stack.push(new ScopeEntry(rootClass, -1, defaultMap, false));

        for (String line : lines) {
            String trimmed = line.stripLeading();
            int indent = line.length() - trimmed.length();

            // Pass through: empty lines, existing comments, lines without a colon
            if (
                trimmed.isEmpty() ||
                trimmed.startsWith("#") ||
                !trimmed.contains(":")
            ) {
                result.append(line).append("\n");
                continue;
            }

            // List item start lines ("- key: value"):
            // Do NOT pop the scope stack here - the list element scope must stay
            // active for the entire duration of all list items.
            if (trimmed.startsWith("- ")) {
                String itemContent = trimmed.substring(2);
                ScopeEntry top = stack.peek();
                if (top.fromList && itemContent.contains(":")) {
                    CommentWriter.handleListItemLine(result, itemContent, indent, " ".repeat(indent), top);
                } else {
                    result.append(line).append("\n");
                }
                continue;
            }

            String key = trimmed.substring(0, trimmed.indexOf(":")).trim();
            String indentStr = " ".repeat(indent);

            // Leave only the scopes that are still active at this indentation level
            while (stack.size() > 1 && stack.peek().parentIndent >= indent) {
                stack.pop();
            }

            Class<?> currentClass = stack.peek().clazz;
            Map<String, Object> currentDefaults = stack.peek().defaults;
            Field field = CommentWriter.findField(currentClass, key);
            Object defaultValue = currentDefaults != null ? currentDefaults.get(key) : null;

            // Write all comment lines for this key
            for (String commentLine : CommentWriter.buildFieldCommentLines(field, key, indentStr, currentDefaults)) {
                result.append(commentLine).append("\n");
            }

            // Push nested scope with the nested defaults map
            if (field != null && ConfigMerger.isNestedConfigType(field.getType())) {
                Map<String, Object> nestedDefaults = defaultValue instanceof Map
                    ? (Map<String, Object>) defaultValue
                    : Map.of();
                stack.push(new ScopeEntry(field.getType(), indent, nestedDefaults, false));
            }

            // Push nested scope for List<NestedConfigType> so list item keys
            // resolve correctly and are not falsely marked as deprecated.
            // The first item's defaults map is used for # Default value comments on
            // the list item fields (all items share the same class defaults).
            if (field != null && java.util.Collection.class.isAssignableFrom(field.getType())) {
                Class<?> elementType = ConfigMerger.getListElementType(field);
                if (elementType != null && ConfigMerger.isNestedConfigType(elementType)) {
                    Map<String, Object> elementDefaults = Map.of();
                    if (
                        defaultValue instanceof List<?> list &&
                        !list.isEmpty() &&
                        list.get(0) instanceof Map<?, ?> firstItem
                    ) {
                        elementDefaults = (Map<String, Object>) firstItem;
                    }
                    stack.push(new ScopeEntry(elementType, indent, elementDefaults, true));
                }
            }

            result
                .append(line)
                .append("\n");
        }

        return result.toString();
    }

    /**
     * Handles a list item start line (e.g. {@code - key1: value1}).
     *
     * <p>The first key's comments are placed on the {@code - } line itself.
     * A blank line is inserted before every non-first item for readability.</p>
     *
     * @param result The {@link StringBuilder} to append the output to
     * @param itemContent The content after {@code "- "} (e.g. {@code "key1: value1"})
     * @param listIndent The indentation level of the {@code - } character
     * @param listIndentStr The indentation string for the {@code - } character
     * @param listScope The active {@link ScopeEntry} for the list element type
     */
    private static void handleListItemLine(
        StringBuilder result,
        String itemContent,
        int listIndent,
        String listIndentStr,
        ScopeEntry listScope
    ) {
        String itemKey = itemContent.substring(0, itemContent.indexOf(":")).trim();
        String itemValuePart = itemContent.substring(itemContent.indexOf(":")); // ": value" or ":"
        String itemIndentStr = " ".repeat(listIndent + 2);

        // Add blank line before non-first items
        if (!listScope.isFirstItem) {
            result.append("\n");
        }
        listScope.isFirstItem = false;

        List<String> commentLines = CommentWriter.buildFieldCommentLines(
            CommentWriter.findField(listScope.clazz, itemKey),
            itemKey,
            itemIndentStr,
            listScope.defaults
        );

        if (commentLines.isEmpty()) {
            // No comments: output the line as-is
            result.append(listIndentStr).append("- ").append(itemContent).append("\n");
        } else {
            // First comment on the same line as "- ", remaining on subsequent lines
            result.append(listIndentStr).append("- ").append(commentLines.get(0).stripLeading()).append("\n");
            for (int i = 1; i < commentLines.size(); i++) {
                result.append(commentLines.get(i)).append("\n");
            }
            result.append(itemIndentStr).append(itemKey).append(itemValuePart).append("\n");
        }
    }

    /**
     * Builds the list of formatted comment lines for a field.
     * Returns a single deprecated notice line if the field is {@code null} (orphaned key).
     *
     * @param field The field to build comments for, or {@code null} for orphaned keys
     * @param key The YAML key name
     * @param indentStr The indentation string to prepend to each comment line
     * @param defaults The map of default values for the current scope
     * 
     * @return An ordered list of comment lines, each already indented and prefixed with {@code #}
     */
    private static List<String> buildFieldCommentLines(
        Field field,
        String key,
        String indentStr,
        Map<String, Object> defaults
    ) {
        List<String> lines = new ArrayList<>();

        if (field == null) {
            // Key exists on disk but is no longer in the class
            lines.add(indentStr + "# @deprecated: This key is no longer used and can be removed safely.");
            return lines;
        }

        // 1. @Annotation.Comment
        Annotation.Comment comment = field.getAnnotation(Annotation.Comment.class);
        if (comment != null) {
            for (String commentLine : CommentWriter.normalizeCommentLines(comment.value())) {
                lines.add(indentStr + (commentLine.isEmpty() ? "#" : "# " + commentLine));
            }
        }

        // 2. # Possible values: A | B | C - only for enum fields
        if (field.getType().isEnum()) {
            String values = Arrays.stream(field.getType().getEnumConstants())
                .map(Object::toString)
                .collect(Collectors.joining(" | "));
            lines.add(indentStr + "# @possible: " + values);
        }

        // 3. # Default value: <value> - skipped for nested objects (Map) and lists of objects (List<Map>)
        Object defaultValue = defaults != null ? defaults.get(key) : null;
        String formatted = CommentWriter.formatDefault(defaultValue);
        if (formatted != null) {
            lines.add(indentStr + "# @default: " + formatted);
        }

        // 4. @Annotation.Deprecated
        Annotation.Deprecated dep = field.getAnnotation(Annotation.Deprecated.class);
        if (dep != null) {
            lines.add(indentStr + CommentWriter.buildDeprecatedComment(dep));
        }

        return lines;
    }

    /**
     * Appends a block of comments to the result builder, preserving relative indentation.
     *
     * @param stringBuilder The {@link StringBuilder} to append the comments to
     * @param text The comment text to append
     * @param indent The indentation string to use
     */
    private static void appendCommentBlock(StringBuilder stringBuilder, String text, String indent) {
        for (String line : CommentWriter.normalizeCommentLines(text)) {
            stringBuilder
                .append(indent)
                .append(line.isEmpty() ? "#" : "# " + line)
                .append("\n");
        }
    }

    /**
     * Formats a default value for inclusion in a YAML comment.
     *
     * @param value The default value to format
     * 
     * @return The formatted string, or {@code null} if the value is null or should be skipped
     */
    @SuppressWarnings("unchecked")
    private static String formatDefault(Object value) {
        if (value == null) {return null;}
        if (value instanceof Map) {return null;} // nested object - each field has its own Default value line
        if (value instanceof String s) {return "'%s'".formatted(s);}
        if (value instanceof List<?> list) {
            if (!list.isEmpty() && list.get(0) instanceof Map) return null; // list of objects - skip
            String items = ((List<Object>) list).stream()
                .map(item -> item instanceof String ? "'%s'".formatted(item) : String.valueOf(item))
                .collect(Collectors.joining(", "));
            return "[%s]".formatted(items);
        }
        return String.valueOf(value);
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
     * Finds the field in {@code clazz} whose YAML key matches {@code yamlKey}.
     *
     * <p>The match respects {@link Annotation.Key}: a field annotated with
     * {@code @Annotation.Key("custom-key")} is found when {@code yamlKey} is
     * {@code "custom-key"}. For unannotated fields the Java field name is used.</p>
     *
     * @param clazz The class to search in
     * @param yamlKey The YAML key to match
     * 
     * @return The matching {@link Field}, or {@code null} if none found
     */
    private static Field findField(Class<?> clazz, String yamlKey) {
        for (Field field : clazz.getDeclaredFields()) {
            if (!field.isSynthetic() && ConfigMerger.getYamlKey(field).equals(yamlKey)) {
                return field;
            }
        }
        return null;
    }

    /**
     * Strips the common leading whitespace from all non-blank lines in {@code text}
     * and removes trailing blank lines.
     *
     * <p>This is more robust than {@link String#stripIndent()} for annotation values
     * because Java text blocks with a closing {@code """} at column 0 do not strip
     * any indentation at compile time, leaving leading spaces in the annotation value.</p>
     *
     * @param text The raw comment text
     * 
     * @return A list of lines with common indentation removed and trailing blank lines dropped
     */
    private static List<String> normalizeCommentLines(String text) {
        String[] rawLines = text.split("\n", -1);

        // Find the minimum number of leading spaces across all non-blank lines
        int minIndent = Integer.MAX_VALUE;
        for (String line : rawLines) {
            if (!line.isBlank()) {
                int spaces = 0;
                while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
                minIndent = Math.min(minIndent, spaces);
            }
        }
        if (minIndent == Integer.MAX_VALUE) minIndent = 0;

        // Strip common indent and trailing whitespace from each line
        List<String> result = new ArrayList<>();
        for (String line : rawLines) {
            result.add(line.isBlank() ? "" : line.substring(Math.min(minIndent, line.length())).stripTrailing());
        }

        // Remove trailing blank lines
        while (!result.isEmpty() && result.get(result.size() - 1).isBlank()) {
            result.remove(result.size() - 1);
        }

        return result;
    }

    /**
     * A class representing a scope entry for nested config classes.
     *
     * <p>Using a class instead of a record so that {@link #isFirstItem} can be mutated
     * as list items are processed.</p>
     *
     * @see #fromList
     * @see #isFirstItem
     */
    private static final class ScopeEntry {

        /** The Java class active at this scope level. */
        final Class<?> clazz;

        /** The indentation level of the key that introduced this scope. */
        final int parentIndent;

        /** Default values for the fields in this scope. */
        final Map<String, Object> defaults;

        /** {@code true} if this scope was pushed for a {@code List<NestedConfigType>} field. */
        final boolean fromList;

        /** Tracks whether the first list item has been written. Only relevant when {@link #fromList} is {@code true}. */
        boolean isFirstItem = true;

        ScopeEntry(Class<?> clazz, int parentIndent, Map<String, Object> defaults, boolean fromList) {
            this.clazz = clazz;
            this.parentIndent = parentIndent;
            this.defaults = defaults;
            this.fromList = fromList;
        }
    }
}
