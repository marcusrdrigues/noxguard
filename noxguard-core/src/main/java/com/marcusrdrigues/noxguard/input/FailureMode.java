package com.marcusrdrigues.noxguard.input;

/**
 * What happens to a message when the classifier times out or fails. There is no default: the app
 * chooses.
 */
public enum FailureMode {
    /**
     * The message passes as {@link ClassifierOutcome.Unavailable}, so it can be logged and counted.
     * Right when other guards still run after the model, as in Nox.
     */
    FAIL_OPEN,
    /**
     * The message is blocked, as {@link ClassifierOutcome.Blocked} with the failure. Right when a missed
     * attack costs more than a refused question.
     */
    FAIL_CLOSED
}
