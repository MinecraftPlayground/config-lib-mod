package dev.loat.config_lib.parser.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.mojang.serialization.JsonOps;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;


/**
 * SnakeYAML constructor with Minecraft chat {@link Component} support.
 *
 * <p>Extends the default constructor to deserialize YAML nodes into
 * Minecraft {@link Component} instances via {@link ComponentSerialization}.</p>
 */
public class ComponentConstructor extends Constructor {

    public ComponentConstructor(Class<?> configClass, LoaderOptions options) {
        super(configClass, options);
    }

    @Override
    protected Object constructObject(Node node) {
        if (Component.class.isAssignableFrom(node.getType())
                || MutableComponent.class.isAssignableFrom(node.getType())) {
            return constructComponent(node);
        }
        return super.constructObject(node);
    }

    private Component constructComponent(Node node) {
        JsonElement json = nodeToJson(node);
        return ComponentSerialization.CODEC
            .decode(JsonOps.INSTANCE, json)
            .getOrThrow(error -> new IllegalStateException("Failed to decode Component: " + error))
            .getFirst();
    }

    private JsonElement nodeToJson(Node node) {
        if (node instanceof ScalarNode scalar) {
            Object value = constructScalar(scalar);
            if (value == null) return JsonNull.INSTANCE;
            if (node.getTag().equals(Tag.BOOL))  return new JsonPrimitive(Boolean.parseBoolean(value.toString()));
            if (node.getTag().equals(Tag.INT))   return new JsonPrimitive(Integer.parseInt(value.toString()));
            if (node.getTag().equals(Tag.FLOAT)) return new JsonPrimitive(Double.parseDouble(value.toString()));
            return new JsonPrimitive(String.valueOf(value).translateEscapes());
        }

        if (node instanceof SequenceNode sequence) {
            JsonArray array = new JsonArray();
            for (Node child : sequence.getValue()) {
                array.add(nodeToJson(child));
            }
            return array;
        }

        if (node instanceof MappingNode mapping) {
            JsonObject object = new JsonObject();
            for (NodeTuple tuple : mapping.getValue()) {
                String key = constructScalar((ScalarNode) tuple.getKeyNode());
                object.add(key, nodeToJson(tuple.getValueNode()));
            }
            return object;
        }

        throw new IllegalArgumentException("Unsupported node type: " + node.getClass().getName());
    }
}
