package com.marcusrdrigues.noxguard.example.web;

import java.util.List;

/**
 * The body of a chat request.
 *
 * @param question the visitor's question
 * @param history earlier turns as the client kept them; assistant turns carry {@code sig}
 */
public record ChatRequest(String question, List<HistoryItem> history) {

    /**
     * One earlier turn.
     *
     * @param role "user" or "assistant"
     * @param content the text
     * @param sig the signature the server sent with an assistant answer
     */
    public record HistoryItem(String role, String content, String sig) {}
}
