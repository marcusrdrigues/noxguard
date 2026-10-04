package com.marcusrdrigues.noxguard.agent;

import com.marcusrdrigues.noxguard.internal.Text;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a JSON Schema into the shape strict tool calling accepts.
 *
 * <p>With strict mode, the model's arguments always match the schema. It accepts only closed objects
 * ({@code additionalProperties: false}) with every property required, and only some keywords. This
 * keeps {@code type}, {@code properties}, {@code required}, {@code additionalProperties},
 * {@code description}, {@code enum}, {@code items} and {@code anyOf}, recursively.
 *
 * <ul>
 *   <li><strong>Optional fields become nullable.</strong> A property missing from the object's
 *       {@code required} list is wrapped in {@code anyOf: [schema, {"type": "null"}]}, so the model
 *       sends {@code null} instead of inventing a value. A property that already accepts null is kept
 *       as it is.
 *   <li><strong>Size limits are removed</strong> ({@code minLength}, {@code maxLength} and the like),
 *       so they must still be checked in code before running the tool.
 * </ul>
 *
 * <p>The schema is a {@code Map<String, Object>} (as any JSON library reads it), so the core needs no
 * JSON dependency. The input is not changed, and the result is a deep, unmodifiable copy.
 */
public final class StrictSchema {

    private static final Set<String> KEYS = Set.of("type", "properties", "required", "additionalProperties", "description", "enum", "items", "anyOf");
    private static final Map<String, Object> NULL_TYPE = Map.of("type", "null");

    private StrictSchema() {}

    /**
     * The strict version of a schema, keeping the order of the properties.
     *
     * @throws IllegalArgumentException when {@code properties}, {@code items} or {@code anyOf} has the
     *     wrong shape, or a value is {@code null}
     */
    public static Map<String, Object> of(Map<String, ?> schema) {
        Text.required(schema, "schema");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : schema.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!KEYS.contains(key)) {
                continue;
            }
            switch (key) {
                case "properties" -> {
                    Map<String, Object> properties = new LinkedHashMap<>();
                    asMap(value, "properties").forEach((name, sub) -> properties.put(name, of(asMap(sub, "properties." + name))));
                    out.put(key, properties);
                }
                case "items" -> out.put(key, of(asMap(value, "items")));
                case "anyOf" -> {
                    List<Object> options = new ArrayList<>();
                    for (Object option : asList(value, "anyOf")) {
                        options.add(of(asMap(option, "anyOf item")));
                    }
                    out.put(key, List.copyOf(options));
                }
                default -> out.put(key, freeze(value, key));
            }
        }
        if (isObject(out.get("type"))) {
            close(out, schema.get("required"));
        }
        return freezeMap(out);
    }

    /** Closes an object: every property required, optional ones nullable, no extra properties. */
    @SuppressWarnings("unchecked")
    private static void close(Map<String, Object> out, Object originalRequired) {
        Map<String, Object> properties = (Map<String, Object>) out.getOrDefault("properties", new LinkedHashMap<>());
        List<?> wasRequired = originalRequired == null ? List.of() : asList(originalRequired, "required");
        Map<String, Object> closed = new LinkedHashMap<>();
        properties.forEach((name, sub) -> {
            Map<String, Object> property = (Map<String, Object>) sub;
            closed.put(name, wasRequired.contains(name) || acceptsNull(property) ? property : Map.of("anyOf", List.of(property, NULL_TYPE)));
        });
        out.put("properties", closed);
        out.put("required", List.copyOf(closed.keySet()));
        out.put("additionalProperties", false);
    }

    private static boolean isObject(Object type) {
        return "object".equals(type) || (type instanceof Collection<?> types && types.contains("object"));
    }

    private static boolean acceptsNull(Map<String, Object> schema) {
        Object type = schema.get("type");
        if ("null".equals(type) || (type instanceof Collection<?> types && types.contains("null"))) {
            return true;
        }
        if (schema.get("anyOf") instanceof List<?> options) {
            for (Object option : options) {
                if (option instanceof Map<?, ?> map && "null".equals(map.get("type"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Deep, unmodifiable copy of a plain value (lists and maps inside are copied too). */
    private static Object freeze(Object value, String where) {
        if (value == null) {
            throw new IllegalArgumentException(where + " must not be null");
        }
        if (value instanceof Map<?, ?>) {
            return freezeMap(asMap(value, where));
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(freeze(item, where + " item"));
            }
            return List.copyOf(copy);
        }
        return value;
    }

    private static Map<String, Object> freezeMap(Map<String, Object> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(key, freeze(value, key)));
        return Collections.unmodifiableMap(copy);
    }

    private static List<?> asList(Object value, String where) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(where + " must be a list");
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String where) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(where + " must be an object");
        }
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw new IllegalArgumentException(where + " must have string keys");
            }
        }
        return (Map<String, Object>) map;
    }
}
