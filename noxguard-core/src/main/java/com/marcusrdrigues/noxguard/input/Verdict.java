package com.marcusrdrigues.noxguard.input;

import com.marcusrdrigues.noxguard.internal.Text;

/**
 * What an {@link InputClassifier} said about one text.
 *
 * @param flagged whether the classifier calls it an attack; your threshold decides this, not noxguard
 * @param score the classifier's confidence that it is an attack, from 0 to 1
 * @param label why it was flagged, such as {@code "injection"}; empty when it was not
 */
public record Verdict(boolean flagged, double score, String label) {

    /**
     * A verdict.
     *
     * @throws IllegalArgumentException when the score is not between 0 and 1
     */
    public Verdict {
        if (!(score >= 0 && score <= 1)) {
            throw new IllegalArgumentException("score must be between 0 and 1, was " + score);
        }
        Text.required(label, "label");
    }

    /** An attack, with the score and the reason. */
    public static Verdict flagged(double score, String label) {
        return new Verdict(true, score, label);
    }

    /** Not an attack. */
    public static Verdict clean(double score) {
        return new Verdict(false, score, "");
    }
}
