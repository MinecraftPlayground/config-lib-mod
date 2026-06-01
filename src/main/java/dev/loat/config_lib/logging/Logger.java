package dev.loat.config_lib.logging;

import org.slf4j.LoggerFactory;


/**
 * This class represents a logger for the console.
 */
public class Logger {
    private static org.slf4j.Logger INSTANCE;

    /**
     * This static function is used to set the class for logging.
     *
     * @param classInstance The instance to use for logging.
     */
    public static void setLoggerClass(Class<?> classInstance) {
        Logger.INSTANCE = LoggerFactory.getLogger(classInstance);
    }

    /**
     * This static function is used for logging a message at debug level.
     *
     * @param message The message to log.
     */
    public static void debug(String message) {
        Logger.INSTANCE.debug(message);
    }

    /**
     * This static function is used for logging a message at info level.
     *
     * @param message The message to log.
     */
    public static void info(String message) {
        Logger.INSTANCE.info(message);
    }

    /**
     * This static function is used for logging a message at warning level.
     *
     * @param message The message to log.
     */
    public static void warning(String message) {
        Logger.INSTANCE.warn(message);
    }

    /**
     * This static function is used for logging a message at error level.
     *
     * @param message The message to log.
     */
    public static void error(String message) {
        Logger.INSTANCE.error(message);
    }
}
