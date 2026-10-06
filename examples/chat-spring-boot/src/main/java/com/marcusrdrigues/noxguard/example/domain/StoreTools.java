package com.marcusrdrigues.noxguard.example.domain;

import com.marcusrdrigues.noxguard.agent.ToolCall;

/** Runs the store's read-only tools. Called only for calls the tool policy decided to run. */
public interface StoreTools {

    /** The tool's output, as text for the model. */
    String run(ToolCall call);
}
