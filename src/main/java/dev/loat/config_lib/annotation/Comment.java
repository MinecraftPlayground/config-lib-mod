package dev.loat.config_lib.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Adds a comment above this field in the serialized YAML file.
 *
 * <p>Multi-line comments are supported — use {@code \n} to separate lines:</p>
 * <pre><code>
 * @Comment("The log level.\nCan be DEBUG, INFO, WARN or ERROR.")
 * public String logLevel = "INFO";
 * </code></pre>
 *
 * <p>Produces:</p>
 * <pre><code>
 * # The log level.
 * # Can be DEBUG, INFO, WARN or ERROR.
 * logLevel: INFO
 * </code></pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Comment {
    String value();
}
