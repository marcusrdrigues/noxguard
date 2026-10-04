package com.marcusrdrigues.noxguard.output;

/**
 * State of a {@link StreamGuard}: still open, tripped by a leak marker, or capped by the length limit.
 *
 * <p>A sealed type instead of booleans, so a {@code switch} over it is checked for every case:
 *
 * <pre>{@code
 * switch (guard.status()) {
 *     case StreamStatus.Open open -> { }
 *     case StreamStatus.Tripped tripped -> replaceWithRefusal();
 *     case StreamStatus.Capped capped -> cancelModelCall();
 * }
 * }</pre>
 */
public sealed interface StreamStatus permits StreamStatus.Open, StreamStatus.Tripped, StreamStatus.Capped {

    /** Nothing happened yet: text is being released as it arrives. */
    record Open() implements StreamStatus {}

    /**
     * A leak marker appeared. Nothing more is released; the app cancels the model call and replaces
     * the answer with its refusal.
     *
     * @param marker the marker that was found
     */
    record Tripped(String marker) implements StreamStatus {}

    /**
     * The released text reached the length limit. The app can cancel the model call.
     *
     * @param maxChars the limit that was reached
     */
    record Capped(int maxChars) implements StreamStatus {}
}
