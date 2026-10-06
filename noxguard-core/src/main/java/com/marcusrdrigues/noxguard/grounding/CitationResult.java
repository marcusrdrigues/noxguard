package com.marcusrdrigues.noxguard.grounding;

import com.marcusrdrigues.noxguard.internal.Text;
import java.util.List;

/**
 * The answer after the {@link CitationGuard}.
 *
 * @param text the answer to show: the kept sentences, with citations corrected where needed
 * @param sentences what happened to each sentence, in order
 * @param removed sentences removed (an ungrounded detail, or depending on a removed sentence)
 * @param recited sentences kept with a corrected citation
 * @param changed whether {@link #text()} differs from the answer: a sentence removed or a citation corrected or dropped
 * @param empty no cited sentence is left: show your "not confirmed" text instead of {@link #text()}
 * @param cites the source numbers the final text cites, in order, for links
 */
public record CitationResult(String text, List<SentenceCheck> sentences, int removed, int recited, boolean changed, boolean empty, List<Integer> cites) {

    /** A result; the lists are copied. */
    public CitationResult {
        Text.required(text, "text");
        sentences = List.copyOf(Text.required(sentences, "sentences"));
        cites = List.copyOf(Text.required(cites, "cites"));
    }
}
