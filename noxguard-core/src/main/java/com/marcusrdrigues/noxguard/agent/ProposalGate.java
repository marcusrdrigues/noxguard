package com.marcusrdrigues.noxguard.agent;

import com.marcusrdrigues.noxguard.internal.Text;
import java.util.Optional;

/**
 * Holds an action the model proposed until its answer is complete, and releases it only if the final
 * answer is not a refusal.
 *
 * <p>A prompt rule lowers how often a model proposes an action it should not; it does not stop it. In
 * Nox, asked to email HR on the owner's behalf, the model proposed a message once in six attempts and
 * then refused in its answer. Held by this gate, the draft was dropped every time: the model tried, the
 * code said no.
 *
 * <pre>{@code
 * ProposalGate<Draft> gate = ProposalGate.create();
 * // in the tool handler:
 * if (!gate.hold(draft)) return "A draft was already proposed in this answer.";
 * // after the answer streamed:
 * gate.release(isRefusal(answer)).ifPresent(this::showForConfirmation);
 * }</pre>
 *
 * <p>The gate does not execute anything: what it releases still needs the user's confirmation, and the
 * action behind it should have a fixed target (in Nox, the message can only go to the site owner).
 *
 * <p><strong>Not thread-safe.</strong> One gate belongs to one answer.
 *
 * @param <T> the proposed action
 */
public final class ProposalGate<T> {

    private T held;
    private boolean released;

    private ProposalGate() {}

    /** A gate for one answer. */
    public static <T> ProposalGate<T> create() {
        return new ProposalGate<>();
    }

    /**
     * Holds a proposal until the answer ends. Only one per answer.
     *
     * @return {@code false} when a proposal is already held (tell the model it was already proposed)
     * @throws IllegalStateException after {@link #release(boolean)}
     */
    public boolean hold(T proposal) {
        Text.required(proposal, "proposal");
        if (released) {
            throw new IllegalStateException("hold after release: create a new ProposalGate for each answer");
        }
        if (held != null) {
            return false;
        }
        held = proposal;
        return true;
    }

    /** Whether a proposal is being held. */
    public boolean isHolding() {
        return held != null && !released;
    }

    /**
     * Ends the answer and decides.
     *
     * @param refused whether the final answer is a refusal
     * @return the proposal when there is one and the answer is not a refusal; empty otherwise
     * @throws IllegalStateException if called twice
     */
    public Optional<T> release(boolean refused) {
        if (released) {
            throw new IllegalStateException("release called twice");
        }
        released = true;
        return refused ? Optional.empty() : Optional.ofNullable(held);
    }
}
