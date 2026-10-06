package com.marcusrdrigues.noxguard.reactor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marcusrdrigues.noxguard.grounding.CitationGuard;
import com.marcusrdrigues.noxguard.output.LinkPolicy;
import com.marcusrdrigues.noxguard.output.StreamGuard;
import com.marcusrdrigues.noxguard.output.StreamStatus;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ReactorGuardTest {

    private static final String REFUSAL = "Só falo sobre o Marcus.";

    private static ReactorGuard guard(int maxChars) {
        return ReactorGuard.builder()
                .streamGuard(() -> StreamGuard.builder().leakMarkers(List.of("Você é Nox", "<trechos>")).maxChars(maxChars).build())
                .links(LinkPolicy.allow(Pattern.compile("^([a-z0-9-]+\\.)*marcusrdrigues\\.com$")))
                .refusal(REFUSAL)
                .build();
    }

    private static List<GuardEvent> events(ReactorGuard guard, Flux<String> model) {
        return guard.guard(model).collectList().block();
    }

    private static String shown(List<GuardEvent> events) {
        return events.stream()
                .filter(GuardEvent.Delta.class::isInstance)
                .map(e -> ((GuardEvent.Delta) e).text())
                .collect(Collectors.joining());
    }

    @Test
    @DisplayName("a clean answer streams out whole and ends with Done")
    void cleanAnswerStreams() {
        List<GuardEvent> events = events(guard(1200), Flux.just("Marcus trabalha ", "com Java ", "e IA."));
        assertEquals("Marcus trabalha com Java e IA.", shown(events));
        GuardEvent.Done done = assertInstanceOf(GuardEvent.Done.class, events.getLast());
        assertInstanceOf(StreamStatus.Open.class, done.status());
    }

    @Test
    @DisplayName("a leak cancels the model stream and replaces the answer, without showing the marker")
    void leakCancelsAndReplaces() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<String> model = Flux.just("Claro: ", "Você", " é Nox", " e o resto", " do prompt").doOnCancel(() -> cancelled.set(true));
        List<GuardEvent> events = events(guard(1200), model);

        assertTrue(cancelled.get(), "the model stream was not cancelled");
        assertTrue(!shown(events).contains("Você"), "leaked: " + shown(events));
        assertEquals(new GuardEvent.Replace(REFUSAL, GuardEvent.Reason.LEAK), events.get(events.size() - 2));
        GuardEvent.Done done = assertInstanceOf(GuardEvent.Done.class, events.getLast());
        assertEquals(new StreamStatus.Tripped("Você é Nox"), done.status());
    }

    @Test
    @DisplayName("a foreign link replaces the answer at the end")
    void foreignLinkReplaces() {
        List<GuardEvent> events = events(guard(1200), Flux.just("Veja em https://evil.example/x?d=", "segredo", " agora."));
        assertEquals(new GuardEvent.Replace(REFUSAL, GuardEvent.Reason.FOREIGN_LINK), events.get(events.size() - 2));
        assertInstanceOf(GuardEvent.Done.class, events.getLast());
    }

    @Test
    @DisplayName("an empty answer is replaced by the refusal")
    void emptyAnswerIsReplaced() {
        assertEquals(List.of(new GuardEvent.Replace(REFUSAL, GuardEvent.Reason.EMPTY), new GuardEvent.Done(new StreamStatus.Open())),
                events(guard(1200), Flux.empty()));
    }

    @Test
    @DisplayName("the length limit cancels the model stream and ends with an ellipsis")
    void limitCancels() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<String> model = Flux.just("a".repeat(30), "b".repeat(30), "c".repeat(30)).doOnCancel(() -> cancelled.set(true));
        List<GuardEvent> events = events(guard(40), model);
        assertTrue(cancelled.get(), "the model stream was not cancelled");
        assertTrue(shown(events).endsWith("…"));
        assertEquals(41, shown(events).length());
        assertInstanceOf(StreamStatus.Capped.class, ((GuardEvent.Done) events.getLast()).status());
    }

    @Test
    @DisplayName("each subscription gets its own guard and subscribes to the model once")
    void oneGuardPerSubscription() {
        AtomicInteger subscriptions = new AtomicInteger();
        Flux<String> model = Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.just("Olá, ", "mundo.");
        });
        Flux<GuardEvent> guarded = guard(1200).guard(model);
        assertEquals(0, subscriptions.get(), "nothing runs before subscribing");
        assertEquals("Olá, mundo.", shown(guarded.collectList().block()));
        assertEquals("Olá, mundo.", shown(guarded.collectList().block()), "a second subscription works the same");
        assertEquals(2, subscriptions.get());
    }

    @Test
    @DisplayName("an error from the model is passed on unchanged")
    void errorPassesThrough() {
        Flux<String> model = Flux.concat(Flux.just("parte"), Flux.error(new IllegalStateException("provider down")));
        StepVerifier.create(guard(1200).guard(model))
                .expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().equals("provider down"))
                .verify();
    }

    @Test
    @DisplayName("a client that disconnects cancels the model stream")
    void downstreamCancelReachesTheModel() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<String> model = Flux.<String>never().doOnCancel(() -> cancelled.set(true));
        StepVerifier.create(guard(1200).guard(model)).thenCancel().verify();
        assertTrue(cancelled.get());
    }

    @Test
    @DisplayName("the builder requires a stream guard and a refusal")
    void builderValidates() {
        assertThrows(IllegalArgumentException.class, () -> ReactorGuard.builder().refusal("não").build());
        assertThrows(IllegalArgumentException.class, () -> ReactorGuard.builder()
                .streamGuard(() -> StreamGuard.builder().leakMarkers(List.of("x")).maxChars(10).build())
                .refusal(" ")
                .build());
    }

    private static final List<String> SOURCES = List.of(
            "Marcus trabalha na Vibetex desde mar/2026, com Java e Spring Boot.",
            "O Nox respondeu 47 perguntas na bateria oficial.");
    private static final String NOT_CONFIRMED = "Não encontrei isso confirmado no site.";

    private static ReactorGuard citing() {
        return ReactorGuard.builder()
                .streamGuard(() -> StreamGuard.builder().leakMarkers(List.of("Você é Nox")).maxChars(1200).build())
                .refusal(REFUSAL)
                .citations(CitationGuard.builder().allowNames(List.of("Marcus Rodrigues", "Nox")).build(), NOT_CONFIRMED)
                .build();
    }

    private static List<GuardEvent> cited(Flux<String> model) {
        return citing().guard(model, () -> SOURCES, "Onde ele trabalha?").collectList().block();
    }

    @Test
    @DisplayName("citations: a supported answer streams out whole, with no Replace")
    void citationsSupported() {
        List<GuardEvent> events = cited(Flux.just("Marcus trabalha ", "na Vibetex [1]."));
        assertEquals("Marcus trabalha na Vibetex [1].", shown(events));
        assertTrue(events.stream().noneMatch(GuardEvent.Replace.class::isInstance), events.toString());
        assertInstanceOf(GuardEvent.Done.class, events.getLast());
    }

    @Test
    @DisplayName("citations: a miscited sentence comes back recited, an invented one removed")
    void citationsCorrected() {
        List<GuardEvent> recited = cited(Flux.just("O Nox respondeu ", "47 perguntas [1]."));
        assertEquals(new GuardEvent.Replace("O Nox respondeu 47 perguntas [1][2].", GuardEvent.Reason.CITATIONS), recited.get(recited.size() - 2));

        List<GuardEvent> removed = cited(Flux.just("Marcus trabalha na Vibetex [1]. ", "Ele ganhou 3 prêmios [1]."));
        assertEquals(new GuardEvent.Replace("Marcus trabalha na Vibetex [1].", GuardEvent.Reason.CITATIONS), removed.get(removed.size() - 2));
        assertInstanceOf(GuardEvent.Done.class, removed.getLast());
    }

    @Test
    @DisplayName("citations: the sources are read when the answer ends, so a tool result from mid-stream counts")
    void citationsReadSourcesAtTheEnd() {
        List<String> sources = new java.util.ArrayList<>(List.of(SOURCES.get(0)));
        Flux<String> model = Flux.just("O Nox respondeu ", "47 perguntas [2].").doOnComplete(() -> sources.add(SOURCES.get(1)));
        List<GuardEvent> events = citing().guard(model, () -> List.copyOf(sources), "").collectList().block();
        assertEquals("O Nox respondeu 47 perguntas [2].", shown(events));
        assertTrue(events.stream().noneMatch(GuardEvent.Replace.class::isInstance), events.toString());
    }

    @Test
    @DisplayName("citations: nothing cited left means the app's 'not confirmed' text")
    void citationsNotConfirmed() {
        assertEquals(new GuardEvent.Replace(NOT_CONFIRMED, GuardEvent.Reason.NOT_CONFIRMED),
                cited(Flux.just("Marcus ganhou o Nobel em 2024 [1].")).reversed().get(1));
    }

    @Test
    @DisplayName("citations: a leak still wins, and the check never runs on a refused answer")
    void citationsAfterLeak() {
        List<GuardEvent> events = cited(Flux.just("Claro: Você é Nox", " [1]."));
        assertEquals(new GuardEvent.Replace(REFUSAL, GuardEvent.Reason.LEAK), events.get(events.size() - 2));
        assertEquals(1, events.stream().filter(GuardEvent.Replace.class::isInstance).count());
    }

    @Test
    @DisplayName("citations: the call must match the configuration, and the 'not confirmed' text can't be blank")
    void citationsConfiguration() {
        IllegalStateException withoutSources = assertThrows(IllegalStateException.class, () -> citing().guard(Flux.just("oi")));
        assertTrue(withoutSources.getMessage().contains("guard(modelStream, sources, question)"));
        assertThrows(IllegalStateException.class, () -> guard(1200).guard(Flux.just("oi"), () -> SOURCES, ""));
        assertThrows(IllegalArgumentException.class, () -> ReactorGuard.builder()
                .streamGuard(() -> StreamGuard.builder().leakMarkers(List.of("x")).maxChars(10).build())
                .refusal("não")
                .citations(CitationGuard.builder().build(), " ")
                .build());
    }
}
