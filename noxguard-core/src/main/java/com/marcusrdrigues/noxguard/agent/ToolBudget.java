package com.marcusrdrigues.noxguard.agent;

/**
 * Caps the tool calls of one question, so an agent loop always ends.
 *
 * <pre>{@code
 * ToolBudget budget = ToolBudget.of(3);
 * for (ToolCall call : calls) {
 *     String result = budget.tryUse() ? run(call) : "Error: tool limit reached; answer now.";
 *     ...
 * }
 * request.toolChoice(budget.nextChoice());   // NONE once the cap is reached
 * }</pre>
 *
 * <p>Every call the model asked for still gets a result (the API requires one), so over the cap the
 * result is the error text, not a silent drop.
 *
 * <p><strong>Not thread-safe.</strong> One budget belongs to one question.
 */
public final class ToolBudget {

    private final int max;
    private int used;

    private ToolBudget(int max) {
        this.max = max;
    }

    /**
     * Budget of {@code max} calls.
     *
     * @throws IllegalArgumentException when {@code max} is negative (zero means the model must answer
     *     without tools)
     */
    public static ToolBudget of(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max must not be negative, got " + max);
        }
        return new ToolBudget(max);
    }

    /**
     * Counts one call.
     *
     * @return {@code true} when the call is within the budget and may run
     */
    public boolean tryUse() {
        boolean allowed = used < max;
        used++;
        return allowed;
    }

    /** {@link ToolChoice#AUTO} while calls are left, then {@link ToolChoice#NONE}. */
    public ToolChoice nextChoice() {
        return used >= max ? ToolChoice.NONE : ToolChoice.AUTO;
    }

    /** Calls counted so far, including those over the cap. */
    public int used() {
        return used;
    }

    /** Calls left within the budget. */
    public int remaining() {
        return Math.max(0, max - used);
    }
}
