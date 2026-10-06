package com.marcusrdrigues.noxguard.agent;

import com.marcusrdrigues.noxguard.internal.Text;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tool call the model asked for: the tool name and its arguments, already parsed from the model's
 * JSON.
 *
 * <p>The arguments are a {@code Map<String, Object>} (as any JSON library reads them), so the core needs
 * no JSON dependency. A {@code null} value means the argument is absent, as strict tool calling sends
 * {@code null} for an optional field. The map is copied and cannot be changed afterwards.
 *
 * @param name the tool the model asked for, exactly as it sent it
 * @param args the arguments, by name
 */
public record ToolCall(String name, Map<String, Object> args) {

    /**
     * A call with its arguments copied.
     *
     * @throws NullPointerException when {@code name}, {@code args} or an argument name is {@code null}
     */
    public ToolCall {
        Text.required(name, "name");
        Text.required(args, "args");
        Map<String, Object> copy = new LinkedHashMap<>();
        args.forEach((key, value) -> copy.put(Text.required(key, "argument name"), value));
        args = Collections.unmodifiableMap(copy);
    }

    /** A call without arguments. */
    public static ToolCall of(String name) {
        return new ToolCall(name, Map.of());
    }
}
