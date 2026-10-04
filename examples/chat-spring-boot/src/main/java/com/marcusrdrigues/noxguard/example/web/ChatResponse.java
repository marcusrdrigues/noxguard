package com.marcusrdrigues.noxguard.example.web;

import com.marcusrdrigues.noxguard.example.domain.MessageDraft;
import java.util.List;

/**
 * The whole answer at once, for clients that don't stream (and for noxeval).
 *
 * @param answer the final answer as the visitor sees it
 * @param sig the signature to send back with this answer
 * @param replaced why the answer was replaced by the refusal (LEAK, FOREIGN_LINK, EMPTY), or null
 * @param draft the message draft to review, or null
 * @param toolCalls the tools the model called
 * @param context the published content the answer was given
 */
public record ChatResponse(String answer, String sig, String replaced, MessageDraft draft, List<ToolCall> toolCalls, List<String> context) {

    /** A tool call as noxeval reads it. */
    public record ToolCall(String name) {}
}
