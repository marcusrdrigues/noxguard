package com.marcusrdrigues.noxguard.agent;

import com.marcusrdrigues.noxguard.internal.Text;
import java.util.ArrayList;
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
 * <p><strong>Size limits are removed</strong> ({@code minLength}, {@code maxLength} and the like), so
 * they must still be checked in code before running the tool. An optional field becomes a required
 * field that accepts {@code null} ({@code anyOf} with {@code {"type": "null"}}).
 *
 * <p>The schema is a {@code Map<String, Object>} (as any JSON library reads it), so the core needs no
 * JSON dependency.
 */
public final class StrictSchema {

    private static final Set<String> KEYS = Set.of("type", "properties", "required", "additionalProperties", "description", "enum", "items", "anyOf");

    private StrictSchema() {}

    /**
     * The strict version of a schema. The input is not changed; the result is unmodifiable and keeps
     * the order of the properties.
     *
     * @throws IllegalArgumentException when {@code properties}, {@code items} or {@code anyOf} has the
     *     wrong shape
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
                    out.put(key, Collections.unmodifiableMap(properties));
                }
                case "items" -> out.put(key, of(asMap(value, "items")));
                case "anyOf" -> {
                    if (!(value instanceof List<?> list)) {
                        throw new IllegalArgumentException("anyOf must be a list");
                    }
                    List<Object> options = new ArrayList<>();
                    for (Object option : list) {
                        options.add(of(asMap(option, "anyOf item")));
                    }
                    out.put(key, List.copyOf(options));
                }
                default -> out.put(key, value);
            }
        }
        if ("object".equals(out.get("type"))) {
            Map<String, Object> properties = asMap(out.getOrDefault("properties", Map.of()), "properties");
            out.put("properties", properties);
            out.put("required", List.copyOf(properties.keySet()));
            out.put("additionalProperties", false);
        }
        return Collections.unmodifiableMap(out);
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
