package com.marcusrdrigues.noxguard.example.domain;

import java.util.List;

/** What the chat sends to the client, in order: deltas, maybe a replacement, maybe a draft, then done. */
public sealed interface ChatEvent permits ChatEvent.Delta, ChatEvent.Replace, ChatEvent.Draft, ChatEvent.Done {

    /** Text to append to the answer shown. */
    record Delta(String text) implements ChatEvent {}

    /**
     * Replace the whole answer shown with this text.
     *
     * @param reason LEAK, FOREIGN_LINK, EMPTY, CITATIONS (sentences removed or citations corrected) or
     *     NOT_CONFIRMED (no cited sentence left)
     */
    record Replace(String text, String reason) implements ChatEvent {}

    /** A message draft for the visitor to review; shown only when the answer is not a refusal. */
    record Draft(MessageDraft draft) implements ChatEvent {}

    /**
     * The answer ended.
     *
     * @param answer the final answer as the visitor sees it
     * @param signature the signature to send back with this answer in the next request's history
     * @param tools the tools the model called, in order
     * @param passages the published content the answer was given
     */
    record Done(String answer, String signature, List<String> tools, List<Passage> passages) implements ChatEvent {}
}
