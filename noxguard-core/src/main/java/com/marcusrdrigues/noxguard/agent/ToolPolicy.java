package com.marcusrdrigues.noxguard.agent;

import com.marcusrdrigues.noxguard.internal.Text;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Decides, before every tool call, whether the call the model asked for may run: deny by default, a
 * rule per tool, and a decision the app can act on.
 *
 * <p>It is to an agent's tools what Spring Security is to a web app's endpoints. The model can ask for
 * anything; the policy decides what runs (OWASP LLM06, Excessive Agency).
 *
 * <pre>{@code
 * ToolPolicy policy = ToolPolicy.builder()
 *     .tool("search_site", t -> t
 *         .arg("query", ArgRule.required(), ArgRule.maxLength(200)))
 *     .tool("get_case_study", t -> t
 *         .arg("slug", ArgRule.required(), ArgRule.maxLength(60), ArgRule.matches("[a-z0-9-]+"))
 *         .logArgs("slug"))
 *     .tool("send_message", t -> t
 *         .confirm()
 *         .arg("email", ArgRule.maxLength(120))
 *         .arg("body", ArgRule.required(), ArgRule.maxLength(2000))
 *         .maxCalls(1))
 *     .maxCalls(3)
 *     .build();
 *
 * ToolSession session = policy.session();   // one per answer
 * }</pre>
 *
 * <ul>
 *   <li><strong>Deny by default.</strong> A tool that is not declared is denied, and so is an argument
 *       that is not declared (as strict tool calling's {@code additionalProperties: false}), unless the
 *       tool calls {@link Tool#allowOtherArgs()}.
 *   <li><strong>Arguments</strong> are checked on every call by their {@link ArgRule}s, in the order
 *       they were declared.
 *   <li><strong>Limits</strong> per answer: {@link Builder#maxCalls(int)} for all tools (required, so an
 *       agent loop always ends) and {@link Tool#maxCalls(int)} for one tool.
 *   <li><strong>Confirmation.</strong> A tool marked {@link Tool#confirm()} never gets
 *       {@link ToolDecision.Run}: it gets {@link ToolDecision.Confirm}, for a {@link ProposalGate} and the
 *       user.
 * </ul>
 *
 * <p>Immutable and safe to share between threads. Each answer gets its own {@link #session()}.
 *
 * <p>The policy decides on the call the model asked for; it does not make the tool itself safe. A slug
 * that matches a pattern is well formed, not authorized: whether this user may see that case is still
 * the app's decision.
 */
public final class ToolPolicy {

    /** Tool names as the model APIs accept them. */
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final Map<String, ToolRules> tools;
    private final int maxCalls;

    private ToolPolicy(Map<String, ToolRules> tools, int maxCalls) {
        this.tools = tools;
        this.maxCalls = maxCalls;
    }

    /** Starts a policy. */
    public static Builder builder() {
        return new Builder();
    }

    /** A new session for one answer, with its own counts. */
    public ToolSession session() {
        return new ToolSession(this);
    }

    /** The declared tools, in declaration order. */
    public List<String> toolNames() {
        return List.copyOf(tools.keySet());
    }

    /** Whether a declared tool needs the user's confirmation; {@code false} for an unknown tool. */
    public boolean requiresConfirmation(String tool) {
        ToolRules rules = tools.get(tool);
        return rules != null && rules.confirm();
    }

    /** The cap on all tool calls in one answer. */
    public int maxCalls() {
        return maxCalls;
    }

    ToolRules rules(String tool) {
        return tools.get(tool);
    }

    /** The rules of one tool, frozen when it was declared. */
    record ToolRules(String name, Map<String, List<ArgRule>> args, Set<String> logArgs, boolean confirm, int maxCalls, boolean otherArgs) {}

    /** Builds a {@link ToolPolicy}. */
    public static final class Builder {

        private final Map<String, ToolRules> tools = new LinkedHashMap<>();
        private Integer maxCalls;

        private Builder() {}

        /**
         * Declares a tool without arguments.
         *
         * @throws IllegalArgumentException when the name is not a valid tool name or is already declared
         */
        public Builder tool(String name) {
            return tool(name, tool -> {});
        }

        /**
         * Declares a tool and its rules.
         *
         * @throws IllegalArgumentException when the name is not a valid tool name ({@code [A-Za-z0-9_-]},
         *     1 to 64 characters), is already declared, or the rules are inconsistent
         */
        public Builder tool(String name, Consumer<Tool> rules) {
            Text.required(name, "name");
            Text.required(rules, "rules");
            if (!TOOL_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid tool name: use letters, digits, '_' or '-', up to 64 characters");
            }
            if (tools.containsKey(name)) {
                throw new IllegalArgumentException("tool declared twice: " + name);
            }
            Tool tool = new Tool(name);
            rules.accept(tool);
            tools.put(name, tool.freeze());
            return this;
        }

        /**
         * Caps all tool calls in one answer. Required: an agent loop must always end.
         *
         * @throws IllegalArgumentException when {@code max} is less than 1
         */
        public Builder maxCalls(int max) {
            if (max < 1) {
                throw new IllegalArgumentException("maxCalls must be at least 1, got " + max + "; an agent without tool calls needs no policy");
            }
            this.maxCalls = max;
            return this;
        }

        /**
         * The policy.
         *
         * @throws IllegalStateException when no tool is declared or {@link #maxCalls(int)} was not set
         */
        public ToolPolicy build() {
            if (tools.isEmpty()) {
                throw new IllegalStateException("declare at least one tool: anything not declared is denied");
            }
            if (maxCalls == null) {
                throw new IllegalStateException("maxCalls is required, so an agent loop always ends");
            }
            return new ToolPolicy(Collections.unmodifiableMap(new LinkedHashMap<>(tools)), maxCalls);
        }
    }

    /** The rules of one tool, given to {@link Builder#tool(String, Consumer)}. */
    public static final class Tool {

        private final String name;
        private final Map<String, List<ArgRule>> args = new LinkedHashMap<>();
        private final Set<String> logArgs = new LinkedHashSet<>();
        private boolean confirm;
        private int maxCalls = Integer.MAX_VALUE;
        private boolean otherArgs;

        private Tool(String name) {
            this.name = name;
        }

        /**
         * Declares an argument and its rules, checked in this order. With no rule, the argument is only
         * allowed (any value is accepted).
         *
         * @throws IllegalArgumentException when the argument is blank or already declared
         */
        public Tool arg(String argument, ArgRule... rules) {
            Text.required(argument, "argument");
            Text.required(rules, "rules");
            if (argument.isBlank()) {
                throw new IllegalArgumentException("argument name must not be blank (tool " + name + ")");
            }
            if (args.containsKey(argument)) {
                throw new IllegalArgumentException("argument declared twice: " + name + "." + argument);
            }
            List<ArgRule> list = new ArrayList<>();
            for (ArgRule rule : rules) {
                list.add(Text.required(rule, "rule"));
            }
            args.put(argument, List.copyOf(list));
            return this;
        }

        /**
         * Arguments that may be written to logs, through {@link ToolDecision#loggableArgs()}. Default:
         * none. Log what identifies the call (a slug, a subject), never free text from the user.
         */
        public Tool logArgs(String... arguments) {
            for (String argument : Text.required(arguments, "arguments")) {
                logArgs.add(Text.required(argument, "argument"));
            }
            return this;
        }

        /** The tool has a side effect: it gets {@link ToolDecision.Confirm}, never {@link ToolDecision.Run}. */
        public Tool confirm() {
            this.confirm = true;
            return this;
        }

        /**
         * Caps the calls of this tool in one answer (the total cap still applies).
         *
         * @throws IllegalArgumentException when {@code max} is less than 1 (to forbid a tool, leave it
         *     out of the policy)
         */
        public Tool maxCalls(int max) {
            if (max < 1) {
                throw new IllegalArgumentException("maxCalls must be at least 1 (tool " + name + "); to forbid a tool, do not declare it");
            }
            this.maxCalls = max;
            return this;
        }

        /** Accepts arguments that are not declared (they are not checked). Off by default. */
        public Tool allowOtherArgs() {
            this.otherArgs = true;
            return this;
        }

        private ToolRules freeze() {
            if (!otherArgs) {
                for (String argument : logArgs) {
                    if (!args.containsKey(argument)) {
                        throw new IllegalArgumentException("logArgs names an argument that is not declared: " + name + "." + argument);
                    }
                }
            }
            return new ToolRules(name, Collections.unmodifiableMap(new LinkedHashMap<>(args)), Set.copyOf(logArgs), confirm, maxCalls, otherArgs);
        }
    }
}
