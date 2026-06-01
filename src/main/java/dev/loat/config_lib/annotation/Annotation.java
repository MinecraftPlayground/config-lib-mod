package dev.loat.config_lib.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Annotations for config fields, used to add comments and deprecation notices in the serialized YAML file.
 */
public final class Annotation {

    private Annotation() {}

    /**
     * Specifies a description for the entire config file. The description is written as a comment at the top of the YAML file when it is created or updated.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * @Annotation.FileDescription("""
     *     This is the configuration file for MyMod.
     *     It contains various settings that can be customized by the user.
     *     Please refer to the documentation for more details.
     * """)
     * public class MyConfig {
     *     // config fields...
     * }
     * }</pre>
     * 
     * <p>Output in YAML:</p>
     * <pre><code class="language-yaml">
     * # This is the configuration file for MyMod.
     * # It contains various settings that can be customized by the user.
     * # Please refer to the documentation for more details.
     * ...
     * </code></pre>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface FileDescription {
        String value();
    }

    /**
     * Adds a comment above the annotated field in the serialized YAML file.
     * The comment is specified as the value of the annotation and can span multiple lines using {@code \n}.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * @Annotation.Comment("This is the log level for the application.\nValid values are: DEBUG, INFO, WARN, ERROR.")
     * public static String logLevel = "INFO";
     * 
     * @Annotation.Comment("""
     *     This is a block comment.
     *     It can span multiple lines.
     *       And it preserves indentation.
     * """)
     * public static String anotherField = "value";
     * 
     * }</pre>
     * 
     * <p>Output in YAML:</p>
     * <pre><code class="language-yaml">
     * # This is the log level for the application.
     * # Valid values are: DEBUG, INFO, WARN, ERROR.
     * logLevel: INFO
     * # This is a block comment.
     * # It can span multiple lines.
     * #   And it preserves indentation.
     * anotherField: value
     * </code></pre>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Comment {
        String value();
    }

    /**
     * Marks a config field as deprecated in the serialized YAML file.
     *
     * <p>A {@code # @deprecated} comment is written above the field when the
     * YAML file is created or updated, informing users that the key has been
     * superseded and may be removed in a future version.</p>
     *
     * <p>Example usage:</p>
     * <pre>{@code
     * @Annotation.Deprecated(migratedTo = "logLevel", removedIn = "2.0.0")
     * public static String log_level = "INFO";
     *
     * public static String logLevel = "INFO";
     * }</pre>
     *
     * <p>Output in YAML:</p>
     * <pre><code class="language-yaml">
     * # @deprecated: Migrated to 'logLevel'. Will be removed in version 2.0.0.
     * log_level: INFO
     *
     * logLevel: INFO
     * </code></pre>
     *
     * <p>Keys that are no longer present in the class at all (orphaned keys) receive
     * an auto-generated {@code # @deprecated} comment without needing this annotation.</p>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Deprecated {

        /**
         * The version in which this key will be removed.
         * Shown in the YAML comment if provided.
         */
        String removedIn() default "";

        /**
         * The new key this field was migrated to.
         * Shown in the YAML comment if provided.
         */
        String migratedTo() default "";

        /**
         * Custom message shown in the YAML comment.
         * If set, {@link #removedIn} and {@link #migratedTo} are ignored.
         */
        String message() default "";
    }
}
