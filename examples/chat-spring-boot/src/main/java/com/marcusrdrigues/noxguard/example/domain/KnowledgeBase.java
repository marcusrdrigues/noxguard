package com.marcusrdrigues.noxguard.example.domain;

import java.util.List;

/** Port: finds the published content that answers a question (the R in RAG). */
public interface KnowledgeBase {

    /** Up to {@code limit} passages, best first; empty when nothing matches. */
    List<Passage> search(String question, int limit);
}
