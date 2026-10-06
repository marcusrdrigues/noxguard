package com.marcusrdrigues.noxguard.springai;

import com.marcusrdrigues.noxguard.agent.ToolDecision;

/**
 * Hears every decision, so the app logs or counts it its own way: noxguard depends on no logging library.
 *
 * <pre>{@code
 * (tool, decision) -> log.info("tool={} decision={} args={}", tool,
 *         decision.getClass().getSimpleName(), decision.loggableArgs())
 * }</pre>
 *
 * <p>Called once per call, after the decision and before the tool runs. {@link ToolDecision#loggableArgs()} has
 * only the arguments the policy lets go to logs. An exception thrown here stops the call: the tool does not run.
 */
@FunctionalInterface
public interface ToolDecisionListener {

    /**
     * One decision.
     *
     * @param tool the tool's name, as declared
     * @param decision what the policy decided
     */
    void onDecision(String tool, ToolDecision decision);
}
