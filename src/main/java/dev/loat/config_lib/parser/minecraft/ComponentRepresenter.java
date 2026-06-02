package dev.loat.config_lib.parser.minecraft;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.util.Set;


/**
 * SnakeYAML representer with Minecraft chat {@link Component} support.
 *
 * This representer encodes {@link Component} instances as JSON objects using Minecraft's built-in JSON serialization.
 * The resulting YAML will contain the JSON representation of the component, but without any explicit type tags,
 * making it more human-readable and compatible with standard YAML parsers.
 */
public class ComponentRepresenter extends Representer {

    /**
     * Creates a new ComponentRepresenter with the specified configuration class and DumperOptions.
     * 
     * @param configClass The class of the configuration objects being represented.
     * @param dumperOptions The DumperOptions to use for YAML output formatting.
     */
    public ComponentRepresenter(Class<?> configClass, DumperOptions dumperOptions) {
        super(dumperOptions);

        this.addClassTag(configClass, Tag.MAP);

        /**
         * Serialize any enum as its plain name string to avoid !!ClassName tags.
         * Without this, SnakeYAML serializes enums in nested objects as tagged Java
         * beans, which the plain loader cannot
         * resolve, silently dropping the field from the raw map.
         * multiRepresenters is checked for superclass/interface matches, so
         * Enum.class catches every enum type without needing per-type registration.
         */
        this.multiRepresenters.put(Enum.class, data -> representScalar(Tag.STR, ((Enum<?>) data).name()));

        this.representers.put(Component.class, data -> representComponent((Component) data));
        this.representers.put(MutableComponent.class, data -> representComponent((Component) data));
    }

    /**
     * Suppresses {@code !!ClassName} tags for all nested Java beans so that
     * every nested object appears as a plain YAML mapping.
     * 
     * @param properties The set of properties for the Java bean.
     * @param javaBean The Java bean instance being represented.
     * 
     * @return A YAML MappingNode representing the Java bean without explicit type tags.
     */
    @Override
    protected MappingNode representJavaBean(Set<Property> properties, Object javaBean) {
        addClassTag(javaBean.getClass(), Tag.MAP);
        return super.representJavaBean(properties, javaBean);
    }

    /**
     * Represents a Minecraft {@link Component} as a JSON object in YAML.
     * 
     * @param component The Minecraft Component to represent.
     * 
     * @return A YAML Node representing the Component as a JSON object.
     */
    private Node representComponent(Component component) {
        JsonElement json = ComponentSerialization.CODEC
            .encodeStart(JsonOps.INSTANCE, component)
            .getOrThrow(err -> new IllegalStateException("Failed to encode Component: " + err));
        return represent(new Gson().fromJson(json, Object.class));
    }
}
