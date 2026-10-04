/**
 * noxguard: deterministic guardrails for LLM chats and agents.
 *
 * <p>Only the API packages are exported. {@code com.marcusrdrigues.noxguard.internal} stays hidden.
 */
module com.marcusrdrigues.noxguard {
    exports com.marcusrdrigues.noxguard.output;
    exports com.marcusrdrigues.noxguard.data;
    exports com.marcusrdrigues.noxguard.history;
    exports com.marcusrdrigues.noxguard.agent;
    exports com.marcusrdrigues.noxguard.input;
}
