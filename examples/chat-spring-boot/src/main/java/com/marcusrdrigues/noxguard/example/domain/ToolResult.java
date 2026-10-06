package com.marcusrdrigues.noxguard.example.domain;

import com.marcusrdrigues.noxguard.agent.ToolCall;

/**
 * What the model gets back for one tool call: the tool's output, or the policy's message when the
 * call was denied or held for the visitor.
 *
 * @param call the call the model made
 * @param content the text the model reads
 * @param source the number the model cites it by, after the passages: with two passages, the first result is [3]
 */
public record ToolResult(ToolCall call, String content, int source) {}
