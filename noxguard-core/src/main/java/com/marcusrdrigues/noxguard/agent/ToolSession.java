package com.marcusrdrigues.noxguard.agent;

import com.marcusrdrigues.noxguard.agent.ToolDecision.Reason;
import com.marcusrdrigues.noxguard.internal.Text;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The decisions of one answer under a {@link ToolPolicy}: it counts the calls, so it belongs to one
 * answer, as a {@link ToolBudget} does.
 *
 * <p>Order of the checks for each call:
 *
 * <ol>
 *   <li>the answer's total cap ({@link ToolDecision.Reason#LIMIT}, "tool limit reached; answer now");
 *   <li>the tool is declared ({@link ToolDecision.Reason#UNKNOWN_TOOL}, with the declared tools listed,
 *       so the model can recover);
 *   <li>no undeclared argument, then every rule of every declared argument
 *       ({@link ToolDecision.Reason#ARGUMENT});
 *   <li>the tool's own cap ({@link ToolDecision.Reason#LIMIT});
 *   <li>{@link ToolDecision.Confirm} for a tool marked {@code confirm()}, {@link ToolDecision.Run}
 *       otherwise.
 * </ol>
 *
 * <p>Every call counts against the total, denied or not, so a model that keeps sending bad calls still
 * reaches the cap and the loop ends. Only an accepted call ({@code Run} or {@code Confirm}) counts
 * against its tool's own cap, so a malformed attempt does not use up a tool allowed once.
 *
 * <p>A denial's message never contains what the model sent (a value, an unknown tool name or an
 * undeclared argument name), only names the app declared.
 *
 * <p><strong>Not thread-safe.</strong> One session belongs to one answer.
 */
public final class ToolSession {

    private static final String UNACCEPTED = "is not accepted";

    private final ToolPolicy policy;
    private final Map<String, Integer> accepted = new HashMap<>();
    private int used;

    ToolSession(ToolPolicy policy) {
        this.policy = policy;
    }

    /** Decides one call and counts it. */
    public ToolDecision decide(ToolCall call) {
        Text.required(call, "call");
        if (used >= policy.maxCalls()) {
            used++;
            return deny(call, Reason.LIMIT, null, "tool limit reached; answer now.");
        }
        used++;
        ToolPolicy.ToolRules rules = policy.rules(call.name());
        if (rules == null) {
            return deny(call, Reason.UNKNOWN_TOOL, null, "unknown tool. Available: " + String.join(", ", policy.toolNames()) + ".");
        }
        Optional<ToolDecision> invalid = checkArgs(call, rules);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        int count = accepted.getOrDefault(rules.name(), 0);
        if (count >= rules.maxCalls()) {
            return deny(call, Reason.LIMIT, null, "the limit of " + rules.name() + " in this answer was reached; use another tool or answer now.");
        }
        accepted.put(rules.name(), count + 1);
        Map<String, Object> loggable = new LinkedHashMap<>();
        for (String argument : rules.logArgs()) {
            Object value = call.args().get(argument);
            if (value != null) {
                loggable.put(argument, value);
            }
        }
        return rules.confirm() ? new ToolDecision.Confirm(call, loggable) : new ToolDecision.Run(call, loggable);
    }

    /**
     * Denies a call whose arguments could not be read, such as JSON that is not an object, and counts it
     * against the answer's total like any call. It never counts against the tool's own cap: nothing ran.
     *
     * <pre>{@code
     * Map<String, Object> args = tryParse(json);
     * ToolDecision decision = args == null ? session.invalidArguments(name) : session.decide(new ToolCall(name, args));
     * }</pre>
     *
     * @param tool the tool the model named
     * @return {@link ToolDecision.Reason#LIMIT} when the answer used its calls, {@link ToolDecision.Reason#UNKNOWN_TOOL}
     *     for an undeclared tool, otherwise {@link ToolDecision.Reason#ARGUMENT} with no argument name and a message
     *     that lists the declared arguments
     */
    public ToolDecision.Deny invalidArguments(String tool) {
        ToolCall call = ToolCall.of(Text.required(tool, "tool"));
        if (used >= policy.maxCalls()) {
            used++;
            return deny(call, Reason.LIMIT, null, "tool limit reached; answer now.");
        }
        used++;
        ToolPolicy.ToolRules rules = policy.rules(tool);
        if (rules == null) {
            return deny(call, Reason.UNKNOWN_TOOL, null, "unknown tool. Available: " + String.join(", ", policy.toolNames()) + ".");
        }
        String expected = rules.args().isEmpty()
                ? "send an empty object {}."
                : "send an object with: " + String.join(", ", rules.args().keySet()) + ".";
        return deny(call, Reason.ARGUMENT, null, "the arguments are not a valid JSON object; " + expected);
    }

    /** {@link ToolChoice#AUTO} while calls are left in this answer, then {@link ToolChoice#NONE}. */
    public ToolChoice nextChoice() {
        return used >= policy.maxCalls() ? ToolChoice.NONE : ToolChoice.AUTO;
    }

    /** Calls decided so far, denied ones included. */
    public int used() {
        return used;
    }

    /** Calls left in this answer. */
    public int remaining() {
        return Math.max(0, policy.maxCalls() - used);
    }

    private Optional<ToolDecision> checkArgs(ToolCall call, ToolPolicy.ToolRules rules) {
        if (!rules.otherArgs()) {
            for (Map.Entry<String, Object> entry : call.args().entrySet()) {
                // An undeclared argument sent as null is the same as absent: it carries nothing.
                if (entry.getValue() != null && !rules.args().containsKey(entry.getKey())) {
                    String expected = rules.args().isEmpty()
                            ? rules.name() + " takes no arguments."
                            : "the arguments of " + rules.name() + " are: " + String.join(", ", rules.args().keySet()) + ".";
                    return Optional.of(deny(call, Reason.ARGUMENT, null, "unexpected argument; " + expected));
                }
            }
        }
        for (Map.Entry<String, List<ArgRule>> entry : rules.args().entrySet()) {
            String argument = entry.getKey();
            Object value = call.args().get(argument);
            Optional<String> violation = value == null ? absent(entry.getValue()) : present(entry.getValue(), value);
            if (violation.isPresent()) {
                return Optional.of(deny(call, Reason.ARGUMENT, argument, "invalid argument \"" + argument + "\": " + violation.get() + "."));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> absent(List<ArgRule> rules) {
        return rules.contains(RequiredRule.INSTANCE) ? Optional.of("is required") : Optional.empty();
    }

    private static Optional<String> present(List<ArgRule> rules, Object value) {
        for (ArgRule rule : rules) {
            Optional<String> violation;
            try {
                violation = rule.check(value);
            } catch (RuntimeException e) {
                return Optional.of(UNACCEPTED); // fail closed: a rule that breaks does not let the call through
            }
            if (violation == null) {
                return Optional.of(UNACCEPTED);
            }
            if (violation.isPresent()) {
                return Optional.of(safe(violation.get(), value));
            }
        }
        return Optional.empty();
    }

    /** An app rule's message, unless it is blank or echoes the value back to the model. */
    private static String safe(String message, Object value) {
        if (message.isBlank()) {
            return UNACCEPTED;
        }
        String text = Text.trim(message);
        while (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        if (value instanceof String s && s.length() >= 3 && text.toLowerCase(Locale.ROOT).contains(s.toLowerCase(Locale.ROOT))) {
            return UNACCEPTED;
        }
        return text.isEmpty() ? UNACCEPTED : text;
    }

    private static ToolDecision.Deny deny(ToolCall call, Reason reason, String argument, String message) {
        return new ToolDecision.Deny(call, reason, Optional.ofNullable(argument), "Error: " + message);
    }
}
