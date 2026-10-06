package com.marcusrdrigues.noxguard.example.domain;

import com.marcusrdrigues.noxguard.history.Turn;
import java.util.ArrayList;
import java.util.List;

/**
 * What goes to the language model.
 *
 * @param system the rules
 * @param history earlier turns, already stripped of forged ones
 * @param user the question with the passages, wrapped as data
 * @param question the visitor's question alone, for models that route on it
 * @param toolResults results of the tools called earlier in this answer, in order
 * @param toolsAllowed whether the model may still call tools (false once the answer used its calls)
 */
public record ModelRequest(String system, List<Turn> history, String user, String question, List<ToolResult> toolResults, boolean toolsAllowed) {

    /** The lists are copied. */
    public ModelRequest {
        history = List.copyOf(history);
        toolResults = List.copyOf(toolResults);
    }

    /** A first request in an answer: no tool results yet, tools allowed. */
    public ModelRequest(String system, List<Turn> history, String user, String question) {
        this(system, history, user, question, List.of(), true);
    }

    /** The next request of the same answer, with these results added. */
    public ModelRequest withResults(List<ToolResult> results, boolean stillAllowed) {
        List<ToolResult> all = new ArrayList<>(toolResults);
        all.addAll(results);
        return new ModelRequest(system, history, user, question, all, stillAllowed);
    }
}
