package com.marcusrdrigues.noxguard.agent;

import com.marcusrdrigues.noxguard.internal.Text;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A rule for one argument of a tool call, checked in code on every call by a {@link ToolSession}.
 *
 * <p>Strict tool calling guarantees the shape of the arguments, not their content: it cannot carry
 * {@code maxLength} or a pattern (see {@link StrictSchema}). These rules close that gap.
 *
 * <p>A rule only sees arguments that are present. An absent argument (missing or {@code null}) passes
 * every rule except {@link #required()}.
 *
 * <p>An app rule returns the violation as a short phrase that completes "invalid argument "name": ...",
 * such as {@code "is not a known case"}. <strong>It must not contain the value</strong>: the model reads
 * the message, and the value may be exactly what an attacker wanted to smuggle back into the
 * conversation. As a safety net, a message that contains a string value of three or more characters is
 * replaced by {@code "is not accepted"}, and a rule that throws counts as a violation (fail closed).
 *
 * <pre>{@code
 * ArgRule knownCase = value -> cases.contains(value) ? Optional.empty() : Optional.of("is not a known case");
 * }</pre>
 */
@FunctionalInterface
public interface ArgRule {

    /**
     * Checks a present argument.
     *
     * @param value the argument as parsed from JSON (a {@code String}, {@code Number}, {@code Boolean},
     *     {@code List} or {@code Map}); never {@code null}
     * @return the violation, or empty when the value is accepted
     */
    Optional<String> check(Object value);

    /** The argument must be present and not {@code null}. */
    static ArgRule required() {
        return RequiredRule.INSTANCE;
    }

    /**
     * The argument must be a string matching the whole pattern.
     *
     * <p>Put a {@link #maxLength(int)} before it, so a very long value is refused before the regex runs.
     *
     * @throws java.util.regex.PatternSyntaxException when the pattern is invalid
     */
    static ArgRule matches(String regex) {
        Pattern pattern = Pattern.compile(Text.required(regex, "regex"));
        return value -> {
            if (!(value instanceof String text)) {
                return Optional.of("must be a string");
            }
            return pattern.matcher(text).matches() ? Optional.empty() : Optional.of("does not match the expected format");
        };
    }

    /**
     * The argument must be a string of at most {@code max} characters, counted as Unicode code points
     * (as JSON Schema's {@code maxLength} counts them).
     *
     * @throws IllegalArgumentException when {@code max} is negative
     */
    static ArgRule maxLength(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max must not be negative, got " + max);
        }
        return value -> {
            if (!(value instanceof String text)) {
                return Optional.of("must be a string");
            }
            return text.codePointCount(0, text.length()) <= max ? Optional.empty() : Optional.of("is longer than " + max + " characters");
        };
    }

    /**
     * The argument must be one of these strings, exactly.
     *
     * <p>The violation lists the accepted values (they come from the app, not from the model).
     *
     * @throws IllegalArgumentException when no value is given
     */
    static ArgRule oneOf(String... values) {
        List<String> accepted = List.of(Text.required(values, "values"));
        if (accepted.isEmpty()) {
            throw new IllegalArgumentException("oneOf needs at least one value");
        }
        String message = "must be one of: " + String.join(", ", accepted);
        return value -> value instanceof String text && accepted.contains(text) ? Optional.empty() : Optional.of(message);
    }

    /**
     * The argument must be of this JSON type: {@code String.class}, {@code Number.class} (any number a
     * JSON library returns: {@code Integer}, {@code Long}, {@code Double}, {@code BigDecimal}) or
     * {@code Boolean.class}.
     *
     * @throws IllegalArgumentException for any other class
     */
    static ArgRule type(Class<?> type) {
        Text.required(type, "type");
        String name;
        if (type == String.class) {
            name = "a string";
        } else if (type == Number.class) {
            name = "a number";
        } else if (type == Boolean.class) {
            name = "a boolean";
        } else {
            throw new IllegalArgumentException("type must be String.class, Number.class or Boolean.class, got " + type.getName());
        }
        String message = "must be " + name;
        return value -> type.isInstance(value) ? Optional.empty() : Optional.of(message);
    }
}

