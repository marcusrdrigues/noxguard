package com.marcusrdrigues.noxguard.example.application;

import com.marcusrdrigues.noxguard.agent.ProposalGate;
import com.marcusrdrigues.noxguard.agent.ToolCall;
import com.marcusrdrigues.noxguard.agent.ToolChoice;
import com.marcusrdrigues.noxguard.agent.ToolDecision;
import com.marcusrdrigues.noxguard.agent.ToolPolicy;
import com.marcusrdrigues.noxguard.agent.ToolSession;
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
import com.marcusrdrigues.noxguard.example.domain.StoreTools;
import com.marcusrdrigues.noxguard.example.domain.ToolResult;
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
 *   <li>The model streams. Every tool it asks for goes through the {@link ToolPolicy} first: a declared
 *       read-only tool runs, the message tool is only held as a draft ({@link ProposalGate}), anything
 *       else is denied with a message the model reads. The results go back to the model until it
 *       answers or the answer uses its calls.
 *   <li>The text is guarded while it streams ({@link ReactorGuard}): leaks, length, foreign links.
 *   <li>At the end, the draft is released only if the answer is not a refusal, and the answer is
 *       signed for the next request's history.
 * </ol>
 */
public final class AnswerQuestion {

    /** Signatures are bound to this app and language. */
    public static final String SCOPE = "answer:en";

    private static final String DRAFT_HELD = "The draft will be shown to the visitor to review and confirm. Nothing was sent.";
    private static final String DRAFT_REPEATED = "A draft was already proposed in this answer.";

    private final ChatPolicy policy;
    private final KnowledgeBase knowledge;
    private final LanguageModel model;
    private final ReactorGuard guard;
    private final DataEnvelope envelope;
    private final HistorySigner signer;
    private final ToolPolicy tools;
    private final StoreTools storeTools;

    public AnswerQuestion(ChatPolicy policy, KnowledgeBase knowledge, LanguageModel model, ReactorGuard guard, DataEnvelope envelope,
            HistorySigner signer, ToolPolicy tools, StoreTools storeTools) {
        this.policy = policy;
        this.knowledge = knowledge;
        this.model = model;
        this.guard = guard;
        this.envelope = envelope;
        this.signer = signer;
        this.tools = tools;
        this.storeTools = storeTools;
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
        ToolSession session = tools.session();
        List<String> called = new ArrayList<>();
        StringBuilder shown = new StringBuilder();
        AtomicReference<String> replacement = new AtomicReference<>();

        Flux<String> modelText = modelText(request, session, gate, called);

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
                ChatEvent.Done end = new ChatEvent.Done(answer, signer.sign(SCOPE, answer), List.copyOf(called), passages);
                yield draft.isPresent() ? Flux.just(new ChatEvent.Draft(draft.get()), end) : Flux.just(end);
            }
        });
    }

    /**
     * One model pass, then another with the tool results while the model calls tools. The loop ends: each
     * pass with calls uses the session's budget, and once it is spent the next request forbids tools and
     * any call the model still makes is ignored.
     */
    private Flux<String> modelText(ModelRequest request, ToolSession session, ProposalGate<MessageDraft> gate, List<String> called) {
        List<ToolResult> results = new ArrayList<>();
        Flux<String> pass = model.stream(request).handle((chunk, sink) -> {
            switch (chunk) {
                case ModelChunk.Text t -> sink.next(t.text());
                case ModelChunk.ToolUse use -> {
                    called.add(use.call().name());
                    if (request.toolsAllowed()) {
                        results.add(new ToolResult(use.call(), decide(session, gate, use.call())));
                    }
                }
            }
        });
        return pass.concatWith(Flux.defer(() -> results.isEmpty()
                ? Flux.empty()
                : modelText(request.withResults(results, session.nextChoice() == ToolChoice.AUTO), session, gate, called)));
    }

    /** The policy decides; only a declared read-only tool runs here. */
    private String decide(ToolSession session, ProposalGate<MessageDraft> gate, ToolCall call) {
        return switch (session.decide(call)) {
            case ToolDecision.Run run -> storeTools.run(run.call());
            case ToolDecision.Confirm confirm -> gate.hold(draft(confirm.call())) ? DRAFT_HELD : DRAFT_REPEATED;
            case ToolDecision.Deny deny -> deny.messageForModel();
        };
    }

    /** The policy already checked both arguments are present strings within their patterns. */
    private static MessageDraft draft(ToolCall call) {
        return new MessageDraft((String) call.args().get("subject"), (String) call.args().get("message"));
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
