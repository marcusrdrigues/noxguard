package com.marcusrdrigues.noxguard.example.domain;

/** A piece of the model's reply: text to stream, or a proposed action (a tool call). */
public sealed interface ModelChunk permits ModelChunk.Text, ModelChunk.Proposal {

    /** A piece of the answer text. */
    record Text(String text) implements ModelChunk {}

    /** The model asked to propose a message to the store. */
    record Proposal(MessageDraft draft) implements ModelChunk {}
}
