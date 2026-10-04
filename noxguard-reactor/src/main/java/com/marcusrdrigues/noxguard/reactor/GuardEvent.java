package com.marcusrdrigues.noxguard.reactor;

import com.marcusrdrigues.noxguard.output.StreamStatus;
import java.util.Objects;

/**
 * What a guarded stream tells the client: text to show, a replacement of everything shown so far, and
 * the end.
 *
 * <p>Map these to your wire format (one JSON line per event, server-sent events). A client that already
 * showed some text must handle {@link Replace}: it swaps the whole answer for the refusal.
 */
public sealed interface GuardEvent permits GuardEvent.Delta, GuardEvent.Replace, GuardEvent.Done {

    /**
     * Text that is safe to show now, appended to what was shown.
     *
     * @param text a non-empty piece of the answer
     */
    record Delta(String text) implements GuardEvent {
        /** Validates the text. */
        public Delta {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * The whole answer shown so far must be replaced by this text.
     *
     * @param text the app's refusal
     * @param reason why the answer was replaced
     */
    record Replace(String text, Reason reason) implements GuardEvent {
        /** Validates both parts. */
        public Replace {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The answer ended. Always the last event of a stream that did not fail.
     *
     * @param status how the stream guard ended: open, tripped or capped
     */
    record Done(StreamStatus status) implements GuardEvent {
        /** Validates the status. */
        public Done {
            Objects.requireNonNull(status, "status");
        }
    }

    /** Why an answer was replaced. */
    enum Reason {
        /** A leak marker appeared; the model call was cancelled. */
        LEAK,
        /** The answer had a link outside the allow list. */
        FOREIGN_LINK,
        /** The model sent no text. */
        EMPTY
    }
}
