package com.marcusrdrigues.noxguard.reactor;

import com.marcusrdrigues.noxguard.grounding.CitationGuard;
import com.marcusrdrigues.noxguard.grounding.CitationResult;
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
 *       {@link LinkPolicy}, when it has a foreign link. With a {@link CitationGuard}, the answer shown is
 *       checked against the sources: a {@link GuardEvent.Replace} carries the checked text when a sentence
 *       was removed or a citation corrected, or the "not confirmed" text when no cited sentence is left.
 *       Then {@link GuardEvent.Done} closes the stream.
 *   <li>An error from the model stream is passed on unchanged: retrying or showing an error is the
 *       app's decision.
 * </ul>
 *
 * <p>Citations are only checkable once the answer ends, so the user may see a sentence that is then
 * removed. Render the deltas as plain text and the final text after {@link GuardEvent.Done}.
 *
 * <p>Depends only on Reactor, not on Spring AI: it works with any {@code Flux<String>}. Immutable and
 * thread-safe; the per-answer state lives inside each subscription.
 */
public final class ReactorGuard {

    private final Supplier<StreamGuard> streamGuards;
    private final Optional<LinkPolicy> links;
    private final String refusal;
    private final Optional<CitationGuard> citations;
    private final String notConfirmed;

    private ReactorGuard(Builder builder) {
        this.streamGuards = builder.streamGuards;
        this.links = Optional.ofNullable(builder.links);
        this.refusal = builder.refusal;
        this.citations = Optional.ofNullable(builder.citations);
        this.notConfirmed = builder.notConfirmed;
    }

    /** The sources and question of one answer, for the citation check. */
    private record CitationInput(Supplier<List<String>> sources, String question) {}

    /** Starts the configuration. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The guarded version of a model stream. Nothing runs until it is subscribed, and each
     * subscription subscribes to the model stream once.
     *
     * @throws IllegalStateException when this guard checks citations: use {@link #guard(Flux, Supplier, String)}
     */
    public Flux<GuardEvent> guard(Flux<String> modelStream) {
        Objects.requireNonNull(modelStream, "modelStream");
        if (citations.isPresent()) {
            throw new IllegalStateException("this guard checks citations: call guard(modelStream, sources, question)");
        }
        return guarded(modelStream, Optional.empty());
    }

    /**
     * The guarded version of a model stream whose answer cites sources.
     *
     * <pre>{@code
     * guard.guard(stream, () -> sources, question);               // passages known up front
     * guard.guard(stream, () -> concat(passages, toolResults), q); // tool results that arrive during the stream
     * }</pre>
     *
     * @param modelStream the model's text
     * @param sources what the model received, in the numbering it saw (index 0 is {@code [1]}); read once, when the
     *     answer ends, so tool results that arrived during the stream count
     * @param question the user's question
     * @throws IllegalStateException when this guard was built without {@link Builder#citations(CitationGuard, String)}
     */
    public Flux<GuardEvent> guard(Flux<String> modelStream, Supplier<List<String>> sources, String question) {
        Objects.requireNonNull(modelStream, "modelStream");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(question, "question");
        if (citations.isEmpty()) {
            throw new IllegalStateException("this guard has no citation check: build it with citations(guard, notConfirmed)");
        }
        return guarded(modelStream, Optional.of(new CitationInput(sources, question)));
    }

    private Flux<GuardEvent> guarded(Flux<String> modelStream, Optional<CitationInput> input) {
        return Flux.defer(() -> {
            StreamGuard guard = Objects.requireNonNull(streamGuards.get(), "the StreamGuard supplier returned null");
            StringBuilder shown = new StringBuilder();
            Flux<GuardEvent> body = modelStream.handle((delta, sink) -> {
                String safe = guard.push(delta);
                shown.append(safe);
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
            return body.concatWith(Flux.defer(() -> Flux.fromIterable(ending(guard, shown, input))));
        });
    }

    /** Events after the model stream ended or was cancelled by the guard. */
    private List<GuardEvent> ending(StreamGuard guard, StringBuilder shown, Optional<CitationInput> input) {
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
        if (input.isPresent()) {
            // The text checked is the one the user saw: after a cap, the cut answer with its "…".
            List<String> sources = Objects.requireNonNull(input.get().sources().get(), "the sources supplier returned null");
            CitationResult checked = citations.orElseThrow().check(shown.append(rest).toString(), sources, input.get().question());
            if (checked.empty()) {
                return List.of(new GuardEvent.Replace(notConfirmed, GuardEvent.Reason.NOT_CONFIRMED), new GuardEvent.Done(guard.status()));
            }
            if (checked.changed()) {
                return List.of(new GuardEvent.Replace(checked.text(), GuardEvent.Reason.CITATIONS), new GuardEvent.Done(guard.status()));
            }
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
        private CitationGuard citations;
        private String notConfirmed;

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
         * Checks each answer's citations at the end. Optional. With it, call
         * {@link ReactorGuard#guard(Flux, Supplier, String)} with the sources of each answer.
         *
         * @param citations the citation guard
         * @param notConfirmed the text shown when no cited sentence is left, such as "I couldn't find that confirmed
         *     on the site."; not blank
         */
        public Builder citations(CitationGuard citations, String notConfirmed) {
            this.citations = Objects.requireNonNull(citations, "citations");
            this.notConfirmed = Objects.requireNonNull(notConfirmed, "notConfirmed");
            return this;
        }

        /**
         * Validates the configuration.
         *
         * @throws IllegalArgumentException when the supplier or the refusal is missing, or the refusal
         *     or the "not confirmed" text is blank
         */
        public ReactorGuard build() {
            if (streamGuards == null) {
                throw new IllegalArgumentException("streamGuard supplier is required");
            }
            if (refusal == null || refusal.isBlank()) {
                throw new IllegalArgumentException("refusal is required and must not be blank");
            }
            if (notConfirmed != null && notConfirmed.isBlank()) {
                throw new IllegalArgumentException("notConfirmed must not be blank");
            }
            return new ReactorGuard(this);
        }
    }
}
