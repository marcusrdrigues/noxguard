package com.marcusrdrigues.noxguard.springai;

import com.marcusrdrigues.noxguard.agent.ToolDecision;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.util.JsonHelper;

/**
 * Asks the answer's session before the original tool runs. {@code Run} calls it; {@code Deny} returns the
 * policy's message, which never repeats what the model sent; {@code Confirm} follows the {@link ConfirmMode}.
 */
final class GuardedToolCallback implements ToolCallback {

    static final String NEEDS_CONFIRMATION = "Error: this action needs the person's confirmation, which this assistant cannot ask for.";
    static final String HELD = "Held for the person to review; nothing ran yet. Tell them it is waiting for their confirmation.";
    static final String ALREADY_HELD = "Error: an action is already waiting for the person's confirmation in this answer.";

    private static final JsonHelper JSON = new JsonHelper();

    private final ToolCallback tool;
    private final AnswerTools answer;
    private final GuardedToolCallbacks guarded;

    GuardedToolCallback(ToolCallback tool, AnswerTools answer, GuardedToolCallbacks guarded) {
        this.tool = tool;
        this.answer = answer;
        this.guarded = guarded;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return tool.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return tool.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = tool.getToolDefinition().name();
        Optional<ToolDecision> decided = answer.decide(name, parse(toolInput));
        if (decided.isEmpty()) {
            return AnswerTools.ANSWER_ENDED;
        }
        ToolDecision decision = decided.get();
        guarded.listener().onDecision(name, decision);
        return switch (decision) {
            case ToolDecision.Run run -> tool.call(toolInput, toolContext);
            case ToolDecision.Deny deny -> deny.messageForModel();
            case ToolDecision.Confirm confirm -> confirm(confirm, toolInput);
        };
    }

    private String confirm(ToolDecision.Confirm confirm, String toolInput) {
        if (guarded.confirmMode().orElse(ConfirmMode.DENY) == ConfirmMode.DENY) {
            return NEEDS_CONFIRMATION;
        }
        return answer.hold(new HeldCall(confirm.call(), tool, toolInput)) ? HELD : ALREADY_HELD;
    }

    /** The model's arguments as a map; {@code null} when they are not a JSON object. No input means no arguments. */
    private static Map<String, Object> parse(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.fromJsonToMap(toolInput);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
