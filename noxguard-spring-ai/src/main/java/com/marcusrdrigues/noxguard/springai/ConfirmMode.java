package com.marcusrdrigues.noxguard.springai;

/**
 * What happens to a call the policy answers with {@link com.marcusrdrigues.noxguard.agent.ToolDecision.Confirm}:
 * a tool with a side effect, declared with {@code confirm()}. There is no default: the app chooses.
 */
public enum ConfirmMode {
    /**
     * The tool does not run and the model is told the action needs a confirmation this assistant cannot ask
     * for. Right for a voice or batch flow with no screen to ask on.
     */
    DENY,
    /**
     * The call is held for the answer: the model is told it waits for the person's review, and
     * {@link AnswerTools#release(boolean)} gives it to the app when the answer is not a refusal. The tool runs
     * only when the app calls {@link HeldCall#run()}, after the person confirms.
     */
    HOLD
}
