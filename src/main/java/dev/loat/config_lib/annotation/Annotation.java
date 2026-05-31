package dev.loat.config_lib.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Container for all config field annotations.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * import dev.loat.yaml_config_lib.annotation.Annotation;
 *
 * public class MyConfig {
 *     private MyConfig() {}
 *
 *     @Annotation.Comment("The log level.\nCan be DEBUG, INFO, WARN or ERROR.")
 *     public static String logLevel = "INFO";
 *
 *     @Annotation.Deprecated(migratedTo = "logLevel", removedIn = "2.0.0")
 *     public static String log_level = "INFO";
 * }
 * }</pre>
 */
public final class Annotation {

    private Annotation() {}

    // -------------------------------------------------------------------------

    /**
     * Adds a description comment above this field in the serialized YAML file.
     *
     * <p>Multi-line comments are supported via {@code \n}:</p>
     * <pre>{@code
     * @Annotation.Comment("The log level.\nCan be DEBUG, INFO, WARN or ERROR.")
     * public static String logLevel = "INFO";
     * }</pre>
     *
     * <p>Output:</p>
     * <pre>{@code
     * # The log level.
     * # Can be DEBUG, INFO, WARN or ERROR.
     * logLevel: INFO
     * }</pre>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Comment {
        String value();
    }

    // -------------------------------------------------------------------------

    /**
     * Marks a config field as deprecated in the serialized YAML file.
     *
     * <p>A {@code # @deprecated} comment is written above the field when the
     * YAML file is created or updated, informing users that the key has been
     * superseded and may be removed in a future version.</p>
     *
     * <pre>{@code
     * @Annotation.Deprecated(migratedTo = "logLevel", removedIn = "2.0.0")
     * public static String log_level = "INFO";
     *
     * public static String logLevel = "INFO";
     * }</pre>
     *
     * <p>Output:</p>
     * <pre>{@code
     * # @deprecated: Migrated to 'logLevel'. Will be removed in version 2.0.0.
     * log_level: INFO
     *
     * logLevel: INFO
     * }</pre>
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
