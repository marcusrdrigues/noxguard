package com.marcusrdrigues.noxguard.agent;

/** What the next model call may do with tools, as in the {@code tool_choice} parameter. */
public enum ToolChoice {
    /** The model may call a tool or answer. */
    AUTO,
    /** The model must answer, without tools. */
    NONE
}
