package com.marcusrdrigues.noxguard.agent;

import com.marcusrdrigues.noxguard.internal.Text;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a {@link ToolSession} decided about one tool call: run it, ask the user first, or deny it.
 *
 * <p>Sealed, so a {@code switch} covers every case:
 *
 * <pre>{@code
 * switch (session.decide(call)) {
 *     case ToolDecision.Run run       -> result = execute(run.call());
 *     case ToolDecision.Confirm c     -> gate.hold(draftFrom(c.call()));
 *     case ToolDecision.Deny deny     -> result = deny.messageForModel();
 * }
 * }</pre>
 */
public sealed interface ToolDecision {

    /** The call this decision is about. */
    ToolCall call();

    /**
     * The arguments the policy allows in logs ({@code logArgs} in the tool's rules); empty by default and
     * always empty for a {@link Deny}, whose arguments were not accepted.
     */
    Map<String, Object> loggableArgs();

    /**
     * The tool may run now.
     *
     * @param call the accepted call
     * @param loggableArgs the arguments allowed in logs
     */
    record Run(ToolCall call, Map<String, Object> loggableArgs) implements ToolDecision {
        /** A decision with the loggable arguments copied. */
        public Run {
            Text.required(call, "call");
            loggableArgs = copy(loggableArgs);
        }
    }

    /**
     * The tool has a side effect: hand the call to a {@link ProposalGate} and run it only after the user
     * confirms. The policy never returns {@link Run} for such a tool.
     *
     * @param call the accepted call
     * @param loggableArgs the arguments allowed in logs
     */
    record Confirm(ToolCall call, Map<String, Object> loggableArgs) implements ToolDecision {
        /** A decision with the loggable arguments copied. */
        public Confirm {
            Text.required(call, "call");
            loggableArgs = copy(loggableArgs);
        }
    }

    /**
     * The call must not run. Give the model {@link #messageForModel()} as the tool result (the API
     * requires a result for every call), so it can recover or answer.
     *
     * <p>The message never contains a value or a name the model sent, only names the app declared.
     *
     * @param call the refused call
     * @param reason why it was refused, for logs and metrics
     * @param argument the declared argument that broke a rule, when the reason is {@link Reason#ARGUMENT}
     *     and the argument is a declared one
     * @param messageForModel the tool result to send back, such as
     *     {@code "Error: unknown tool. Available: search_site, get_case_study."}
     */
    record Deny(ToolCall call, Reason reason, Optional<String> argument, String messageForModel) implements ToolDecision {
        /** A denial; every component is required. */
        public Deny {
            Text.required(call, "call");
            Text.required(reason, "reason");
            Text.required(argument, "argument");
            Text.required(messageForModel, "messageForModel");
        }

        /** Always empty: a denied call's arguments were not accepted. */
        @Override
        public Map<String, Object> loggableArgs() {
            return Map.of();
        }
    }

    /** Why a call was denied. */
    enum Reason {
        /** The tool is not declared in the policy (deny by default). */
        UNKNOWN_TOOL,
        /** An argument broke a rule, or an undeclared argument was sent. */
        ARGUMENT,
        /** The answer used all its calls, or all the calls of this tool. */
        LIMIT
    }

    private static Map<String, Object> copy(Map<String, Object> map) {
        Text.required(map, "loggableArgs");
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
