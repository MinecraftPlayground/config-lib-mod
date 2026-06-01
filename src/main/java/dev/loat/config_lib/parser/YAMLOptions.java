package dev.loat.config_lib.parser;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;


/**
 * Shared SnakeYAML option factories used across the parser package.
 */
final class YAMLOptions {

    private YAMLOptions() {}

    /**
     * Block-style YAML output - human-readable, no flow syntax.
     * 
     * @return The dumper options.
     */
    static DumperOptions block() {
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setPrettyFlow(true);
        dumperOptions.setIndent(2);
        dumperOptions.setExplicitStart(false);
        dumperOptions.setExplicitEnd(false);
        return dumperOptions;
    }

    /**
     * Plain loader - no class binding, returns raw Maps and primitives.
     * 
     * @return The loader options.
     */
    static LoaderOptions plain() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        return loaderOptions;
    }
}
