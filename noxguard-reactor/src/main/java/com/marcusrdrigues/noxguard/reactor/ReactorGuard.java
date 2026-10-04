package com.marcusrdrigues.noxguard.reactor;

import com.marcusrdrigues.noxguard.output.LinkPolicy;
import com.marcusrdrigues.noxguard.output.StreamGuard;
import com.marcusrdrigues.noxguard.output.StreamStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;

/**
 * Guards a model stream ({@code Flux<String>}) and turns it into {@link GuardEvent}s.
 *
 * <pre>{@code
 * ReactorGuard guard = ReactorGuard.builder()
 *         .streamGuard(() -> StreamGuard.builder().leakMarkers(markers).maxChars(1200).build())
 *         .links(LinkPolicy.allow(Pattern.compile("^([a-z0-9-]+\\.)*example\\.com$")))
 *         .refusal("I can only talk about Acme's products.")
 *         .build();
 *
 * Flux<GuardEvent> events = guard.guard(chatClient.prompt(question).stream().content());
 * }</pre>
 *
 * <ul>
 *   <li>Each subscription gets its own {@link StreamGuard} from the supplier: two answers never share
 *       state.
 *   <li>Safe text goes out as {@link GuardEvent.Delta} while it streams, without buffering the answer.
 *   <li>When a leak marker appears, the model stream is cancelled (it stops spending tokens) and a
 *       {@link GuardEvent.Replace} with the refusal goes out. When the length limit is reached, the
 *       model stream is cancelled and the answer ends with {@code "…"}.
 *   <li>At the end, the answer is replaced by the refusal when it was empty or, with a
 *       {@link LinkPolicy}, when it has a foreign link. Then {@link GuardEvent.Done} closes the stream.
 *   <li>An error from the model stream is passed on unchanged: retrying or showing an error is the
 *       app's decision.
 * </ul>
 *
 * <p>Depends only on Reactor, not on Spring AI: it works with any {@code Flux<String>}. Immutable and
 * thread-safe; the per-answer state lives inside each subscription.
 */
public final class ReactorGuard {

    private final Supplier<StreamGuard> streamGuards;
    private final Optional<LinkPolicy> links;
    private final String refusal;

    private ReactorGuard(Supplier<StreamGuard> streamGuards, Optional<LinkPolicy> links, String refusal) {
        this.streamGuards = streamGuards;
        this.links = links;
        this.refusal = refusal;
    }

    /** Starts the configuration. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The guarded version of a model stream. Nothing runs until it is subscribed, and each
     * subscription subscribes to the model stream once.
     */
    public Flux<GuardEvent> guard(Flux<String> modelStream) {
        Objects.requireNonNull(modelStream, "modelStream");
        return Flux.defer(() -> {
            StreamGuard guard = Objects.requireNonNull(streamGuards.get(), "the StreamGuard supplier returned null");
            Flux<GuardEvent> body = modelStream.handle((delta, sink) -> {
                String safe = guard.push(delta);
                if (guard.status() instanceof StreamStatus.Tripped) {
                    sink.next(new GuardEvent.Replace(refusal, GuardEvent.Reason.LEAK));
                    sink.complete(); // cancels the model stream
                    return;
                }
                if (!safe.isEmpty()) {
                    sink.next(new GuardEvent.Delta(safe));
                }
                if (guard.status() instanceof StreamStatus.Capped) {
                    sink.complete(); // cancels the model stream
                }
            });
            return body.concatWith(Flux.defer(() -> Flux.fromIterable(ending(guard))));
        });
    }

    /** Events after the model stream ended or was cancelled by the guard. */
    private List<GuardEvent> ending(StreamGuard guard) {
        String rest = guard.end();
        if (guard.status() instanceof StreamStatus.Tripped) {
            return List.of(new GuardEvent.Done(guard.status()));
        }
        String received = guard.received();
        if (received.isBlank()) {
            return List.of(new GuardEvent.Replace(refusal, GuardEvent.Reason.EMPTY), new GuardEvent.Done(guard.status()));
        }
        if (links.isPresent() && !links.get().foreignLinks(received).isEmpty()) {
            return List.of(new GuardEvent.Replace(refusal, GuardEvent.Reason.FOREIGN_LINK), new GuardEvent.Done(guard.status()));
        }
        return rest.isEmpty()
                ? List.of(new GuardEvent.Done(guard.status()))
                : List.of(new GuardEvent.Delta(rest), new GuardEvent.Done(guard.status()));
    }

    /** Configuration of a {@link ReactorGuard}; validated on {@link #build()}. */
    public static final class Builder {

        private Supplier<StreamGuard> streamGuards;
        private LinkPolicy links;
        private String refusal;

        private Builder() {}

        /**
         * Creates the {@link StreamGuard} for each answer. Required. Return a new guard on every call;
         * a guard is single-use.
         */
        public Builder streamGuard(Supplier<StreamGuard> streamGuards) {
            this.streamGuards = Objects.requireNonNull(streamGuards, "streamGuards");
            return this;
        }

        /** Replaces an answer with a link outside this policy. Optional. */
        public Builder links(LinkPolicy links) {
            this.links = Objects.requireNonNull(links, "links");
            return this;
        }

        /** The text that replaces a blocked or empty answer. Required, not blank. */
        public Builder refusal(String refusal) {
            this.refusal = Objects.requireNonNull(refusal, "refusal");
            return this;
        }

        /**
         * Validates the configuration.
         *
         * @throws IllegalArgumentException when the supplier or the refusal is missing, or the refusal
         *     is blank
         */
        public ReactorGuard build() {
            if (streamGuards == null) {
                throw new IllegalArgumentException("streamGuard supplier is required");
            }
            if (refusal == null || refusal.isBlank()) {
                throw new IllegalArgumentException("refusal is required and must not be blank");
            }
            return new ReactorGuard(streamGuards, Optional.ofNullable(links), refusal);
        }
    }
}
