package com.marcusrdrigues.noxguard.output;

import com.marcusrdrigues.noxguard.internal.Text;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Guards a streamed LLM answer against prompt leaks and runaway length, without buffering it.
 *
 * <p>The model's text arrives in pieces. Before releasing any piece, the guard holds back the last
 * {@code longest marker - 1} characters, so a leak marker is always seen whole before any of its
 * characters is released. When a marker appears, nothing more is released and the status becomes
 * {@link StreamStatus.Tripped}. When the released text reaches the limit, the status becomes
 * {@link StreamStatus.Capped}. An answer of exactly the limit is released whole, without the mark.
 *
 * <pre>{@code
 * StreamGuard guard = StreamGuard.builder()
 *         .leakMarkers(List.of("<context>", "You are Acme's assistant"))
 *         .maxChars(1200)
 *         .build();
 * for (String delta : modelStream) {
 *     String safe = guard.push(delta);
 *     if (!safe.isEmpty()) send(safe);
 *     if (!(guard.status() instanceof StreamStatus.Open)) break;
 * }
 * send(guard.end());
 * }</pre>
 *
 * <p>Markers are matched exactly (case-sensitive). A paraphrased leak passes: the defense there is
 * keeping no secret in the prompt.
 *
 * <p><strong>Not thread-safe.</strong> One guard belongs to one answer; create a new one per answer.
 */
public final class StreamGuard {

    private static final String ELLIPSIS = "…";

    private final List<String> markers;
    private final int maxChars;
    private final int holdback;
    private final StringBuilder received = new StringBuilder();
    private int emitted;
    private StreamStatus status = new StreamStatus.Open();
    private boolean ended;

    private StreamGuard(List<String> markers, int maxChars) {
        this.markers = markers;
        this.maxChars = maxChars;
        this.holdback = markers.stream().mapToInt(String::length).max().orElse(1) - 1;
    }

    /** Starts the configuration of a guard. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Receives a piece of the model's text and returns what is safe to show now.
     *
     * @param delta the next piece from the model
     * @return the text that can be released, or {@code ""} when nothing can be released yet (or ever
     *     again, once the guard tripped or capped)
     * @throws IllegalStateException if {@link #end()} was already called
     */
    public String push(String delta) {
        Text.required(delta, "delta");
        if (ended) {
            throw new IllegalStateException("push after end: create a new StreamGuard for each answer");
        }
        if (!(status instanceof StreamStatus.Open)) {
            return "";
        }
        // A marker can only be new where the new text is, or straddling it: search from there.
        int from = Math.max(0, received.length() - holdback);
        received.append(delta);
        for (String marker : markers) {
            if (received.indexOf(marker, from) >= 0) {
                status = new StreamStatus.Tripped(marker);
                return "";
            }
        }
        return take(received.length() - holdback);
    }

    /**
     * Ends the stream and returns the rest that is safe to show.
     *
     * @return the held-back rest; {@code ""} when tripped; ending in {@code "…"} when capped
     * @throws IllegalStateException if called twice
     */
    public String end() {
        if (ended) {
            throw new IllegalStateException("end called twice");
        }
        ended = true;
        if (status instanceof StreamStatus.Tripped) {
            return "";
        }
        if (status instanceof StreamStatus.Capped) {
            return ELLIPSIS;
        }
        String rest = take(received.length());
        return status instanceof StreamStatus.Capped ? rest.stripTrailing() + ELLIPSIS : rest;
    }

    /** Current state: open, tripped by a marker, or capped by the length limit. */
    public StreamStatus status() {
        return status;
    }

    /**
     * Text received until now, released or not, for logs and end-of-answer checks. Once the guard
     * trips or caps, later pieces are no longer kept.
     */
    public String received() {
        return received.toString();
    }

    private String take(int upTo) {
        int room = maxChars - emitted;
        int end = Text.safeCut(received, Math.max(emitted, upTo));
        String out = received.substring(emitted, Math.max(emitted, end));
        if (out.length() > room) {
            out = out.substring(0, Text.safeCut(out, Math.max(0, room)));
            status = new StreamStatus.Capped(maxChars);
        }
        emitted += out.length();
        return out;
    }

    /** Configuration of a {@link StreamGuard}; validated on {@link #build()}. */
    public static final class Builder {

        private final List<String> markers = new ArrayList<>();
        private int maxChars = -1;

        private Builder() {}

        /**
         * Texts that must never reach the user: start of the system prompt, data delimiters, a
         * confidentiality line. Required, at least one, none empty.
         */
        public Builder leakMarkers(Collection<String> leakMarkers) {
            Text.required(leakMarkers, "leakMarkers");
            markers.clear();
            markers.addAll(leakMarkers);
            return this;
        }

        /** Most characters the user may see. Required, greater than zero. */
        public Builder maxChars(int maxChars) {
            this.maxChars = maxChars;
            return this;
        }

        /**
         * Validates the configuration and creates a guard for one answer.
         *
         * @throws IllegalArgumentException when no marker was given, a marker is empty, or the limit is
         *     missing or not positive
         */
        public StreamGuard build() {
            if (markers.isEmpty()) {
                throw new IllegalArgumentException("at least one leak marker is required");
            }
            for (String marker : markers) {
                if (marker == null || marker.isEmpty()) {
                    throw new IllegalArgumentException("leak markers must not be null or empty");
                }
            }
            if (maxChars <= 0) {
                throw new IllegalArgumentException("maxChars must be greater than zero, got " + maxChars);
            }
            return new StreamGuard(List.copyOf(markers), maxChars);
        }
    }
}
