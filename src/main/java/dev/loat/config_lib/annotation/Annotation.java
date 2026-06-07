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
     * Adds a comment in the serialized YAML file.
     *
     * <p>When placed on a <b>class</b>, the comment is written as a banner at the
     * very top of the file, before any keys:</p>
     * <pre>{@code
     * @Annotation.Comment("Main configuration file for MyMod.")
     * public class MyConfig { ... }
     * }</pre>
     *
     * <p>When placed on a <b>field</b>, the comment is written above that key:</p>
     * <pre>{@code
     * @Annotation.Comment("The log level.\nCan be DEBUG, INFO, WARN or ERROR.")
     * public String logLevel = "INFO";
     * }</pre>
     *
     * <p>Multi-line comments and relative indentation are preserved:</p>
     * <pre>{@code
     * @Annotation.Comment("""
     *     First line.
     *       Indented sub-line.
     *     Back to normal.
     * """)
     * public String logLevel = "INFO";
     * }</pre>
     *
     * <p>Output:</p>
     * <pre><code class="language-yaml">
     * # First line.
     * #   Indented sub-line.
     * # Back to normal.
     * # Default: 'INFO'
     * logLevel: INFO
     * </code></pre>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD})
    public @interface Comment {
        String value();
    }

    /**
     * Overrides the YAML key name for a field.
     *
     * <p>By default the Java field name is used as the YAML key. This annotation
     * allows using a different naming convention (e.g. kebab-case) in the config
     * file while keeping standard Java field naming in the class.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * @Annotation.Key("my-number")
     * public int myNumber = 20;
     * }</pre>
     *
     * <p>Output in YAML:</p>
     * <pre><code class="language-yaml">
     * # @default: 20
     * position-notification-interval-ticks: 20
     * </code></pre>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Key {

        /**
         * The YAML key name to use instead of the Java field name.
         */
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
