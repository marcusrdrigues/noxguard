package com.marcusrdrigues.noxguard.springai;

import com.marcusrdrigues.noxguard.agent.ToolPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.ai.tool.ToolCallback;

/**
 * Spring AI tools under a {@link ToolPolicy}: before any tool runs, the policy decides. Deny by default, a rule per
 * argument, a cap per answer and per tool, and a confirmation step for tools with side effects.
 *
 * <pre>{@code
 * GuardedToolCallbacks guarded = GuardedToolCallbacks.builder(policy)
 *         .tools(List.of(ToolCallbacks.from(new StoreTools())))
 *         .onConfirm(ConfirmMode.HOLD)              // required when a tool is declared with confirm()
 *         .listener((tool, decision) -> log.info("tool={} decision={}", tool, decision))
 *         .build();                                 // throws when a tool has no rule in the policy
 *
 * AnswerTools answer = guarded.forNewAnswer();      // one per request
 * String text = chatClient.prompt(question).toolCallbacks(answer.callbacks()).call().content();
 * }</pre>
 *
 * <p>Use {@link #forNewAnswer()} on every request. Registering one answer's callbacks as the client's default
 * tools would make every answer share one session; that fails closed (every call past the cap is denied), but
 * the limits stop meaning "per answer".
 *
 * <p>Immutable and thread-safe: a Spring bean.
 */
public final class GuardedToolCallbacks {

    private static final ToolDecisionListener SILENT = (tool, decision) -> {};

    private final ToolPolicy policy;
    private final List<ToolCallback> tools;
    private final Optional<ConfirmMode> confirmMode;
    private final ToolDecisionListener listener;

    private GuardedToolCallbacks(Builder builder) {
        this.policy = builder.policy;
        this.tools = List.copyOf(builder.tools);
        this.confirmMode = Optional.ofNullable(builder.confirmMode);
        this.listener = builder.listener;
    }

    /** Starts the configuration for this policy. */
    public static Builder builder(ToolPolicy policy) {
        return new Builder(Objects.requireNonNull(policy, "policy"));
    }

    /** The tools of one new answer, sharing one new session. */
    public AnswerTools forNewAnswer() {
        return new AnswerTools(this, tools);
    }

    /** The names of the guarded tools, in the order they were given. */
    public List<String> toolNames() {
        return tools.stream().map(tool -> tool.getToolDefinition().name()).toList();
    }

    /** What happens to a call that needs confirmation; empty when no guarded tool needs it. */
    public Optional<ConfirmMode> confirmMode() {
        return confirmMode;
    }

    ToolPolicy policy() {
        return policy;
    }

    ToolDecisionListener listener() {
        return listener;
    }

    /** Configuration of {@link GuardedToolCallbacks}; validated on {@link #build()}. */
    public static final class Builder {

        private final ToolPolicy policy;
        private final List<ToolCallback> tools = new ArrayList<>();
        private ConfirmMode confirmMode;
        private ToolDecisionListener listener = SILENT;

        private Builder(ToolPolicy policy) {
            this.policy = policy;
        }

        /** Adds tools, such as {@code ToolCallbacks.from(bean)} or a provider's {@code getToolCallbacks()}. */
        public Builder tools(Collection<? extends ToolCallback> tools) {
            for (ToolCallback tool : Objects.requireNonNull(tools, "tools")) {
                this.tools.add(Objects.requireNonNull(tool, "a tool callback is null"));
            }
            return this;
        }

        /** Adds tools. */
        public Builder tools(ToolCallback... tools) {
            return tools(List.of(Objects.requireNonNull(tools, "tools")));
        }

        /** What happens to a call that needs confirmation. Required when a given tool is declared with {@code confirm()}. */
        public Builder onConfirm(ConfirmMode confirmMode) {
            this.confirmMode = Objects.requireNonNull(confirmMode, "confirmMode");
            return this;
        }

        /** Hears every decision, for logs and metrics. Optional. */
        public Builder listener(ToolDecisionListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /**
         * Validates the configuration.
         *
         * @throws IllegalStateException when no tool was given, a name repeats, a tool has no rule in the policy, or a
         *     tool needs confirmation and no {@link ConfirmMode} was chosen
         */
        public GuardedToolCallbacks build() {
            if (tools.isEmpty()) {
                throw new IllegalStateException("no tools to guard: add them with tools(...)");
            }
            Set<String> names = new LinkedHashSet<>();
            Set<String> repeated = new LinkedHashSet<>();
            List<String> undeclared = new ArrayList<>();
            List<String> confirming = new ArrayList<>();
            for (ToolCallback tool : tools) {
                String name = Objects.requireNonNull(tool.getToolDefinition(), "a tool has no definition").name();
                if (!names.add(name)) {
                    repeated.add(name);
                }
                if (!policy.toolNames().contains(name)) {
                    undeclared.add(name);
                } else if (policy.requiresConfirmation(name)) {
                    confirming.add(name);
                }
            }
            if (!repeated.isEmpty()) {
                throw new IllegalStateException("tools given twice: " + String.join(", ", repeated));
            }
            if (!undeclared.isEmpty()) {
                throw new IllegalStateException("tools exposed to the model without a rule in the ToolPolicy: " + String.join(", ", undeclared));
            }
            if (!confirming.isEmpty() && confirmMode == null) {
                throw new IllegalStateException(String.join(", ", confirming) + " needs the person's confirmation: choose "
                        + "onConfirm(ConfirmMode.DENY) or onConfirm(ConfirmMode.HOLD)");
            }
            return new GuardedToolCallbacks(this);
        }
    }
}
