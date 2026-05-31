package dev.loat.config_lib.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Marks a config field as deprecated in the serialized YAML file.
 *
 * <p>A {@code # @deprecated} comment is written above the field when the YAML file
 * is created or updated. This is intended to inform users of the config file
 * that a key has been superseded and may be removed in a future version.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @ConfigDeprecated(migratedTo = "logLevel", removedIn = "2.0.0")
 * public String log_level = "INFO";
 *
 * public String logLevel = "INFO";
 * }</pre>
 *
 * <p>Produces in YAML:</p>
 * <pre>{@code
 * # @deprecated: Migrated to 'logLevel'. Will be removed in version 2.0.0.
 * log_level: INFO
 *
 * logLevel: INFO
 * }</pre>
 *
 * <p>For keys that no longer exist in the class at all (orphaned keys), a
 * {@code # @deprecated} comment is added automatically without needing this annotation.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigDeprecated {

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
     * A custom message shown in the YAML comment.
     * If provided, {@link #removedIn} and {@link #migratedTo} are ignored.
     */
    String message() default "";
}
