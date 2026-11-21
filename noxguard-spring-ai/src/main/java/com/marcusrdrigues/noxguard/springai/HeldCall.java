package com.marcusrdrigues.noxguard.springai;

import com.marcusrdrigues.noxguard.agent.ToolCall;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

/**
 * A call held for the person's confirmation ({@link ConfirmMode#HOLD}). Show {@link #call()} to the person; when
 * they confirm, {@link #run()} runs the original tool once, with the arguments the policy accepted and the tool
 * context of the call that proposed it.
 *
 * <p>It holds the tool context until it runs, so a held call kept for long (in a session, for example) keeps the
 * context's objects alive with it, request-scoped ones included.
 *
 * <p>Thread-safe: a second {@code run()} throws, even from another thread.
 */
public final class HeldCall {

    private final ToolCall call;
    private final ToolCallback tool;
    private final String toolInput;
    private final ToolContext toolContext;
    private final AtomicBoolean ran = new AtomicBoolean();

    HeldCall(ToolCall call, ToolCallback tool, String toolInput, ToolContext toolContext) {
        this.call = call;
        this.tool = tool;
        this.toolInput = toolInput;
        this.toolContext = toolContext;
    }

    /** The tool and the arguments the policy accepted, to show the person. */
    public ToolCall call() {
        return call;
    }

    /**
     * Runs the original tool, once, with the arguments and the tool context of the call that proposed it.
     *
     * @return the tool's result
     * @throws IllegalStateException when it already ran
     */
    public String run() {
        if (!ran.compareAndSet(false, true)) {
            throw new IllegalStateException("this held call already ran: " + call.name());
        }
        return tool.call(toolInput, toolContext);
    }

    /** Whether {@link #run()} was called. */
    public boolean ran() {
        return ran.get();
    }
}
