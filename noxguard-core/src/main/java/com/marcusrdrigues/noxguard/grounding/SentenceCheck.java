package com.marcusrdrigues.noxguard.grounding;

import com.marcusrdrigues.noxguard.internal.Text;
import java.util.List;

/**
 * What the {@link CitationGuard} did with one sentence of the answer.
 *
 * @param text the sentence as it is in the final answer (with corrected citations), or as the model wrote it when
 *     removed
 * @param cites the source numbers it cites, starting at 1
 * @param outcome kept, recited or removed
 * @param ungrounded details that were in no source received (the reason for {@link Outcome#REMOVED})
 */
public record SentenceCheck(String text, List<Integer> cites, Outcome outcome, List<Detail> ungrounded) {

    /** What happened to the sentence. */
    public enum Outcome {
        /** Every detail is in a source the sentence cites. */
        KEPT,
        /** Every detail is in a source the model received, but not in the cited one: the citation was corrected. */
        RECITED,
        /** A detail is in no source the model received: the sentence must not reach the user. */
        REMOVED,
        /** The sentence depended on the first one, which was removed ("He...", "That..."). */
        REMOVED_WITH_PREVIOUS
    }

    /** A check; the lists are copied. */
    public SentenceCheck {
        Text.required(text, "text");
        Text.required(outcome, "outcome");
        cites = List.copyOf(Text.required(cites, "cites"));
        ungrounded = List.copyOf(Text.required(ungrounded, "ungrounded"));
    }

    /** Whether the sentence is in the final answer. */
    public boolean kept() {
        return outcome == Outcome.KEPT || outcome == Outcome.RECITED;
    }
}
