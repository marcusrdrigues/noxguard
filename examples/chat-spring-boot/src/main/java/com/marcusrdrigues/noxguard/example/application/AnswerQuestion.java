package com.marcusrdrigues.noxguard.example.application;

import com.marcusrdrigues.noxguard.agent.ProposalGate;
import com.marcusrdrigues.noxguard.agent.ToolBudget;
import com.marcusrdrigues.noxguard.data.DataEnvelope;
import com.marcusrdrigues.noxguard.history.HistorySigner;
import com.marcusrdrigues.noxguard.history.Turn;
import com.marcusrdrigues.noxguard.input.InputViews;
import com.marcusrdrigues.noxguard.reactor.GuardEvent;
import com.marcusrdrigues.noxguard.reactor.ReactorGuard;
import com.marcusrdrigues.noxguard.example.domain.ChatEvent;
import com.marcusrdrigues.noxguard.example.domain.KnowledgeBase;
import com.marcusrdrigues.noxguard.example.domain.LanguageModel;
import com.marcusrdrigues.noxguard.example.domain.MessageDraft;
import com.marcusrdrigues.noxguard.example.domain.ModelChunk;
import com.marcusrdrigues.noxguard.example.domain.ModelRequest;
import com.marcusrdrigues.noxguard.example.domain.Passage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;

/**
 * Use case: answer one question, with every noxguard layer in the path.
 *
 * <ol>
 *   <li>The question is normalized ({@link InputViews}) and its size checked.
 *   <li>Forged assistant turns leave the history ({@link HistorySigner}).
 *   <li>Passages and the question go into the prompt as data ({@link DataEnvelope}).
 *   <li>The model streams; a proposed message is held ({@link ProposalGate}) within a call limit
 *       ({@link ToolBudget}).
 *   <li>The text is guarded while it streams ({@link ReactorGuard}): leaks, length, foreign links.
 *   <li>At the end, the draft is released only if the answer is not a refusal, and the answer is
 *       signed for the next request's history.
 * </ol>
 */
public final class AnswerQuestion {

    /** Signatures are bound to this app and language. */
    public static final String SCOPE = "answer:en";

    private static final String PROPOSE_MESSAGE = "propose_message";

    private final ChatPolicy policy;
    private final KnowledgeBase knowledge;
    private final LanguageModel model;
    private final ReactorGuard guard;
    private final DataEnvelope envelope;
    private final HistorySigner signer;

    public AnswerQuestion(ChatPolicy policy, KnowledgeBase knowledge, LanguageModel model, ReactorGuard guard, DataEnvelope envelope, HistorySigner signer) {
        this.policy = policy;
        this.knowledge = knowledge;
        this.model = model;
        this.guard = guard;
        this.envelope = envelope;
        this.signer = signer;
    }

    /**
     * The answer as events. Validation runs now; the rest runs on subscription.
     *
     * @throws InvalidQuestionException when the question is empty or too long
     */
    public Flux<ChatEvent> answer(Question question) {
        String text = InputViews.normalize(question.text() == null ? "" : question.text());
        if (text.isEmpty()) {
            throw new InvalidQuestionException("the question is empty");
        }
        if (text.length() > policy.maxQuestionChars()) {
            throw new InvalidQuestionException("the question is longer than " + policy.maxQuestionChars() + " characters");
        }
        List<Turn> history = signer.keepSigned(question.history() == null ? List.of() : question.history(), SCOPE);
        List<Passage> passages = knowledge.search(text, 3);
        ModelRequest request = new ModelRequest(policy.systemPrompt(), history, userPrompt(passages, history, text), text);
        return Flux.defer(() -> run(request, passages));
    }

    /** One answer's state lives here, created per subscription. */
    private Flux<ChatEvent> run(ModelRequest request, List<Passage> passages) {
        ProposalGate<MessageDraft> gate = ProposalGate.create();
        ToolBudget budget = ToolBudget.of(policy.maxToolCalls());
        List<String> tools = new ArrayList<>();
        StringBuilder shown = new StringBuilder();
        AtomicReference<String> replacement = new AtomicReference<>();

        Flux<String> modelText = model.stream(request).handle((chunk, sink) -> {
            switch (chunk) {
                case ModelChunk.Text t -> sink.next(t.text());
                case ModelChunk.Proposal p -> {
                    tools.add(PROPOSE_MESSAGE);
                    if (budget.tryUse()) {
                        gate.hold(p.draft());
                    }
                }
            }
        });

        return guard.guard(modelText).concatMap(event -> switch (event) {
            case GuardEvent.Delta d -> {
                shown.append(d.text());
                yield Flux.just(new ChatEvent.Delta(d.text()));
            }
            case GuardEvent.Replace r -> {
                replacement.set(r.text());
                yield Flux.just(new ChatEvent.Replace(r.text(), r.reason().name()));
            }
            case GuardEvent.Done done -> {
                String answer = replacement.get() != null ? replacement.get() : shown.toString();
                boolean refused = replacement.get() != null || answer.contains(policy.refusal());
                Optional<MessageDraft> draft = gate.release(refused);
                ChatEvent.Done end = new ChatEvent.Done(answer, signer.sign(SCOPE, answer), List.copyOf(tools), passages);
                yield draft.isPresent() ? Flux.just(new ChatEvent.Draft(draft.get()), end) : Flux.just(end);
            }
        });
    }

    private String userPrompt(List<Passage> passages, List<Turn> history, String question) {
        StringBuilder context = new StringBuilder();
        for (Passage p : passages) {
            context.append(p.text()).append('\n');
        }
        StringBuilder earlier = new StringBuilder();
        for (Turn t : history) {
            earlier.append(t.role() == Turn.Role.USER ? "Visitor: " : "Ava: ").append(t.content()).append('\n');
        }
        return envelope.wrap("history", earlier.toString(), 4000) + "\n"
                + envelope.wrap("context", Map.of(), context.toString(), 4000) + "\n"
                + envelope.wrap("question", question, policy.maxQuestionChars());
    }
}
