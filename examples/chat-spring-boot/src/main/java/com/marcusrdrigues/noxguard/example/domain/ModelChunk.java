package com.marcusrdrigues.noxguard.example.domain;

import com.marcusrdrigues.noxguard.agent.ToolCall;

/** A piece of the model's reply: text to stream, or a tool the model asked to call. */
public sealed interface ModelChunk permits ModelChunk.Text, ModelChunk.ToolUse {

    /** A piece of the answer text. */
    record Text(String text) implements ModelChunk {}

    /** The model asked to call a tool. Nothing runs until the tool policy decides. */
    record ToolUse(ToolCall call) implements ModelChunk {}
}
