package com.marcusrdrigues.noxguard.history;

import com.marcusrdrigues.noxguard.internal.Text;
import java.util.Optional;

/**
 * One message of a conversation history, as the client sends it back.
 *
 * @param role who wrote it
 * @param content the text
 * @param signature the server's signature, for assistant turns; empty for user turns
 */
public record Turn(Role role, String content, Optional<String> signature) {

    /** Who wrote a turn. */
    public enum Role {
        /** The person using the chat. */
        USER,
        /** The model, through the server. */
        ASSISTANT
    }

    /** Validates that every part is present. */
    public Turn {
        Text.required(role, "role");
        Text.required(content, "content");
        Text.required(signature, "signature");
    }

    /** A user turn. */
    public static Turn user(String content) {
        return new Turn(Role.USER, content, Optional.empty());
    }

    /** An assistant turn with the signature the client sent back (it may be missing or forged). */
    public static Turn assistant(String content, Optional<String> signature) {
        return new Turn(Role.ASSISTANT, content, signature);
    }
}
