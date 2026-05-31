package dev.loat.config_lib.parser.minecraft;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;


/**
 * SnakeYAML representer with Minecraft chat {@link Component} support.
 *
 * <p>Extends the default representer to serialize Minecraft {@link Component}
 * instances into YAML via {@link ComponentSerialization}.</p>
 *
 * <p>Also suppresses the {@code !!} class tag on the root config object
 * so the YAML file stays clean and human-readable.</p>
 */
public class ComponentRepresenter extends Representer {

    public ComponentRepresenter(Class<?> configClass, DumperOptions options) {
        super(options);

        // Suppress the !!fully.qualified.ClassName tag on the root object
        this.addClassTag(configClass, Tag.MAP);

        this.representers.put(Component.class, data -> representComponent((Component) data));
        this.representers.put(MutableComponent.class, data -> representComponent((Component) data));
    }

    private Node representComponent(Component component) {
        JsonElement json = ComponentSerialization.CODEC
            .encodeStart(JsonOps.INSTANCE, component)
            .getOrThrow(err -> new IllegalStateException("Failed to encode Component: " + err));

        // Convert JsonElement to a plain Java object that SnakeYAML can represent natively
        return represent(new Gson().fromJson(json, Object.class));
    }
}
