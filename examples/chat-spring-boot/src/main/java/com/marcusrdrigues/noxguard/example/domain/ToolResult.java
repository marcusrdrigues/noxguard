package com.marcusrdrigues.noxguard.example.domain;

import com.marcusrdrigues.noxguard.agent.ToolCall;

/**
 * What the model gets back for one tool call: the tool's output, or the policy's message when the
 * call was denied or held for the visitor.
 *
 * @param call the call the model made
 * @param content the text the model reads
 */
public record ToolResult(ToolCall call, String content) {}
