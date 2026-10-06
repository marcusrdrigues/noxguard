package com.marcusrdrigues.noxguard.springai;

import com.marcusrdrigues.noxguard.agent.ProposalGate;
import com.marcusrdrigues.noxguard.agent.ToolCall;
import com.marcusrdrigues.noxguard.agent.ToolDecision;
import com.marcusrdrigues.noxguard.agent.ToolSession;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.tool.ToolCallback;

/**
 * The tools of one answer: every callback shares one {@link ToolSession}, so the policy's limits count this
 * answer's calls. Get one from {@link GuardedToolCallbacks#forNewAnswer()} for each request.
 *
 * <pre>{@code
 * AnswerTools answer = guarded.forNewAnswer();
 * String text = chatClient.prompt(question).toolCallbacks(answer.callbacks()).call().content();
 * answer.release(isRefusal(text)).ifPresent(held -> showForConfirmation(held.call()));
 * }</pre>
 *
 * <p>Decisions are synchronized, so tool calls that Spring AI runs in parallel still count one by one. After
 * {@link #release(boolean)} the answer is over: any further call is denied and its tool does not run.
 */
public final class AnswerTools {

    static final String ANSWER_ENDED = "Error: this answer already ended; no tool can run.";

    private final ToolSession session;
    private final ProposalGate<HeldCall> gate = ProposalGate.create();
    private final List<ToolCallback> callbacks;
    private boolean ended;

    AnswerTools(GuardedToolCallbacks guarded, List<ToolCallback> tools) {
        this.session = guarded.policy().session();
        this.callbacks = tools.stream().<ToolCallback>map(tool -> new GuardedToolCallback(tool, this, guarded)).toList();
    }

    /** The guarded callbacks, for {@code ChatClient.prompt().toolCallbacks(...)} or a {@code ToolCallingChatOptions}. */
    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    /**
     * Ends the answer and gives the held call, if any, when the answer is not a refusal (the rule from Nox: a
     * model that proposed an action and then refused does not get the action shown).
     *
     * @param refused whether the final answer is a refusal
     * @throws IllegalStateException when called twice
     */
    public synchronized Optional<HeldCall> release(boolean refused) {
        ended = true;
        return gate.release(refused);
    }

    /** Whether a call is held for the person's confirmation. */
    public synchronized boolean isHolding() {
        return gate.isHolding();
    }

    /** Calls decided in this answer so far, denied ones included. */
    public synchronized int used() {
        return session.used();
    }

    /** The decision for one call; {@code args} is {@code null} when the arguments could not be read. */
    synchronized Optional<ToolDecision> decide(String tool, Map<String, Object> args) {
        if (ended) {
            return Optional.empty();
        }
        return Optional.of(args == null ? session.invalidArguments(tool) : session.decide(new ToolCall(tool, args)));
    }

    /** Holds a call for confirmation; {@code false} when one is already held. */
    synchronized boolean hold(HeldCall call) {
        return !ended && gate.hold(call);
    }
}
