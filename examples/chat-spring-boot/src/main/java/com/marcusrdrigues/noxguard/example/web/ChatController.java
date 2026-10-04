package com.marcusrdrigues.noxguard.example.web;

import com.marcusrdrigues.noxguard.example.application.AnswerQuestion;
import com.marcusrdrigues.noxguard.example.application.Question;
import com.marcusrdrigues.noxguard.example.domain.ChatEvent;
import com.marcusrdrigues.noxguard.example.domain.MessageDraft;
import com.marcusrdrigues.noxguard.example.domain.Passage;
import com.marcusrdrigues.noxguard.history.Turn;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Two ways to ask: {@code POST /api/chat} streams one JSON line per event (what a chat UI uses), and
 * {@code POST /api/chat/complete} returns the whole answer at once (what noxeval uses).
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AnswerQuestion answerQuestion;

    public ChatController(AnswerQuestion answerQuestion) {
        this.answerQuestion = answerQuestion;
    }

    /** Streams {"t":"d","v":...} deltas, then maybe "replace" and "draft", then "done". */
    @PostMapping(produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<Map<String, Object>> stream(@RequestBody ChatRequest request) {
        return answerQuestion.answer(toQuestion(request)).map(ChatController::toLine);
    }

    /** The whole answer as one JSON object. */
    @PostMapping(path = "/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ChatResponse> complete(@RequestBody ChatRequest request) {
        return answerQuestion.answer(toQuestion(request)).collectList().map(ChatController::toResponse);
    }

    private static Question toQuestion(ChatRequest request) {
        List<Turn> history = request.history() == null ? List.of() : request.history().stream()
                .filter(h -> h != null && h.content() != null)
                .map(h -> "assistant".equals(h.role())
                        ? Turn.assistant(h.content(), Optional.ofNullable(h.sig()))
                        : Turn.user(h.content()))
                .toList();
        return new Question(request.question(), history);
    }

    private static Map<String, Object> toLine(ChatEvent event) {
        Map<String, Object> line = new LinkedHashMap<>();
        switch (event) {
            case ChatEvent.Delta d -> {
                line.put("t", "d");
                line.put("v", d.text());
            }
            case ChatEvent.Replace r -> {
                line.put("t", "replace");
                line.put("v", r.text());
                line.put("reason", r.reason());
            }
            case ChatEvent.Draft d -> {
                line.put("t", "draft");
                line.put("subject", d.draft().subject());
                line.put("message", d.draft().message());
            }
            case ChatEvent.Done d -> {
                line.put("t", "done");
                line.put("sig", d.signature());
                line.put("tools", d.tools());
            }
        }
        return line;
    }

    private static ChatResponse toResponse(List<ChatEvent> events) {
        String replaced = null;
        MessageDraft draft = null;
        ChatEvent.Done done = null;
        for (ChatEvent event : events) {
            switch (event) {
                case ChatEvent.Replace r -> replaced = r.reason();
                case ChatEvent.Draft d -> draft = d.draft();
                case ChatEvent.Done d -> done = d;
                case ChatEvent.Delta _ -> { }
            }
        }
        if (done == null) {
            throw new IllegalStateException("the answer ended without a done event");
        }
        return new ChatResponse(
                done.answer(),
                done.signature(),
                replaced,
                draft,
                done.tools().stream().map(ChatResponse.ToolCall::new).toList(),
                done.passages().stream().map(Passage::text).toList());
    }
}
