package com.marcusrdrigues.noxguard.grounding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marcusrdrigues.noxguard.grounding.SentenceCheck.Outcome;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The citation guard (docs/specs/0.3-citations-and-input-classifier.md), with the Nox cases ported. */
class CitationGuardTest {

    static final List<String> ALLOW = List.of("Marcus Rodrigues", "Nox", "LinkedIn", "GitHub");
    private static final CitationGuard GUARD = CitationGuard.builder().allowNames(ALLOW).build();
    private static final List<String> SOURCES = List.of(
            "Hoje sou desenvolvedor fullstack na Vibetex (mar/2026), com Java, Spring Boot e IA.",
            "Projeto da ACME: mais de 10 mil pedidos processados.");

    private static List<Outcome> outcomes(CitationResult r) {
        return r.sentences().stream().map(SentenceCheck::outcome).toList();
    }

    @Test
    @DisplayName("cited numbers: [2], [1][3] and [1, 4], in order, without repeats")
    void citedNumbers() {
        assertEquals(List.of(1, 2, 3, 4), CitationGuard.citedNumbers("a [1] b [2][3] c [1, 4] d [2]"));
        assertEquals(List.of(), CitationGuard.citedNumbers("no citation, not [x] nor [123]"));
    }

    @Test
    @DisplayName("stripping citations leaves no space before the punctuation")
    void stripCitations() {
        assertEquals("Marcus trabalha na Vibetex. Usa Java.", CitationGuard.stripCitations("Marcus trabalha na Vibetex [1]. Usa Java [2][3]."));
        assertEquals("Na ACME, sim.", CitationGuard.stripCitations("Na ACME [1, 2], sim."));
    }

    @Test
    @DisplayName("numbered sources: the numbering the model sees is the one the guard checks")
    void numbered() {
        assertEquals("[1] a\n\n[2] b", CitationGuard.numbered(List.of("a", "b")));
    }

    @Test
    @DisplayName("everything supported by the cited source: the answer passes whole")
    void allSupported() {
        String answer = "Marcus trabalha na Vibetex desde 2026 [1]. Na ACME, cuidou de mais de 10.000 pedidos [2].";
        CitationResult r = GUARD.check(answer, SOURCES);
        assertEquals(0, r.removed());
        assertEquals(answer, r.text());
        assertEquals(List.of(1, 2), r.cites());
        assertFalse(r.empty());
        assertFalse(r.changed());
    }

    @Test
    @DisplayName("a detail in another received source: the sentence stays and the citation points to it")
    void recited() {
        CitationResult r = GUARD.check("Na ACME, cuidou de 10 mil pedidos [1].", SOURCES);
        assertEquals(1, r.recited());
        assertTrue(r.changed());
        assertEquals("Na ACME, cuidou de 10 mil pedidos [1][2].", r.text());
        assertEquals(List.of(1, 2), r.cites());

        CitationResult invented = GUARD.check("Marcus trabalha na Vibetex [7].", SOURCES);
        assertEquals("Marcus trabalha na Vibetex [1].", invented.text(), "a citation the model never received is replaced");
        assertEquals(List.of(Outcome.RECITED), outcomes(invented));
        assertTrue(invented.changed());
    }

    @Test
    @DisplayName("a detail in no received source: the sentence is removed, and an empty answer is flagged")
    void removed() {
        CitationResult r = GUARD.check("Marcus trabalhou na Microsoft [1].", SOURCES);
        assertEquals(1, r.removed());
        assertEquals(List.of(Outcome.REMOVED), outcomes(r));
        assertEquals("Microsoft", r.sentences().get(0).ungrounded().get(0).text());
        assertTrue(r.empty());

        CitationResult partial = GUARD.check("Marcus trabalha na Vibetex [1]. Na ACME, ele usou Python [2].", SOURCES);
        assertEquals("Marcus trabalha na Vibetex [1].", partial.text(), "only the unsupported sentence goes");
        assertFalse(partial.empty());
        assertTrue(partial.changed());
    }

    @Test
    @DisplayName("a colon does not end a sentence, and a citation after the full stop belongs to the previous one")
    void sentenceBoundaries() {
        CitationResult colon = GUARD.check("Hoje ele trabalha na Vibetex: cuida da camada de IA [1].", SOURCES);
        assertEquals(0, colon.removed());
        assertEquals(1, colon.sentences().size());

        CitationResult after = GUARD.check("Trabalha na Vibetex. [1] Usa Java [1].", SOURCES);
        assertEquals(2, after.sentences().size());
        assertEquals(List.of(1), after.sentences().get(0).cites());
    }

    @Test
    @DisplayName("an uncited sentence with a detail: removed if ungrounded, cited if grounded")
    void uncited() {
        assertEquals("Marcus trabalha na Vibetex [1].", GUARD.check("Marcus trabalha na Vibetex [1]. Também usa Kotlin.", SOURCES).text());
        assertEquals("Marcus trabalha na Vibetex [1]. Também usa Spring Boot [1].",
                GUARD.check("Marcus trabalha na Vibetex [1]. Também usa Spring Boot.", SOURCES).text());
    }

    @Test
    @DisplayName("coherence: without the first sentence, a second one that depended on it goes too")
    void coherence() {
        CitationResult r = GUARD.check("Na ACME, ele usou Python [2]. Lá, cuidou de 10 mil pedidos [2].", SOURCES);
        assertEquals(List.of(Outcome.REMOVED, Outcome.REMOVED_WITH_PREVIOUS), outcomes(r));
        assertTrue(r.empty());
        assertEquals("Marcus trabalha na Vibetex [1].", GUARD.check("Na ACME, ele usou Python [2]. Marcus trabalha na Vibetex [1].", SOURCES).text());
    }

    @Test
    @DisplayName("date arithmetic in plain sight passes; a loose number of years does not")
    void dateArithmetic() {
        List<String> sources = List.of(
                "Oficial Administrativo na OAB/RJ, de mar/2023 a mar/2026. Desenvolvedor fullstack na Vibetex desde mar/2026.",
                "Análise e Desenvolvimento de Sistemas, conclusão prevista em 2027.");
        assertEquals(0, GUARD.check("Marcus entrou na OAB/RJ em 2023 [1]. Foi para a Vibetex em 2026, 3 anos depois [1].", sources).removed());
        assertEquals(0, GUARD.check("De 2023, quando entrou na OAB/RJ, a 2027, ano previsto da formatura, serão 4 anos [1][2].", sources).removed());
        assertEquals(1, GUARD.check("Marcus entrou na OAB/RJ em 2023 [1]. Foi para a Vibetex 3 anos depois [1].", sources).removed());
        assertEquals(1, GUARD.check("Entre 2023 e 2026 ele fez 3 projetos [1].", sources).removed());
        assertEquals(1, GUARD.check("De 2023 a 2026 foram 5 anos [1].", sources).removed());
    }

    @Test
    @DisplayName("refusals, 'not found' and the denied bait pass without citations")
    void refusalsAndBait() {
        for (String answer : List.of(
                "Esse voo não é comigo: eu só respondo sobre o Marcus. Quer saber da trajetória, dos projetos ou da stack dele?",
                "Não encontrei essa informação no site. Escreva para contato@marcusrdrigues.com.")) {
            assertEquals(0, GUARD.check(answer, SOURCES).removed(), answer);
        }
        assertEquals(0, GUARD.check("Não encontrei passagem pelo Google no site.", SOURCES, "Ele trabalhou no Google?").removed());
        // "no" is also a Portuguese preposition, so a sentence with "no Google" counts as negating (the same in Nox).
        assertEquals(1, GUARD.check("Sim, ele trabalhou na Microsoft em 2024 [1].", SOURCES, "Ele trabalhou na Microsoft em 2024?")
                .removed());
    }

    /**
     * The differential run: 1,500 answers made from the site's real content, checked by the TypeScript original in Nox
     * (tools/parity/citations-fixture.mjs) and replayed here. Text, counts, citations and each sentence's outcome must
     * match, and so must the ungrounded details of the plain answer.
     */
    @Test
    @DisplayName("same results as Nox's TypeScript on 1,500 answers from the site's content")
    void parityWithNox() throws IOException {
        Base64.Decoder b64 = Base64.getDecoder();
        GroundingDetails details = GroundingDetails.withAllowedNames(ALLOW);
        List<String> mismatches = new ArrayList<>();
        int cases = 0;
        try (InputStream in = getClass().getResourceAsStream("/citations-parity.tsv")) {
            assertNotNull(in, "fixture missing");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> table = Arrays.stream(reader.readLine().split(",")).map(s -> new String(b64.decode(s), StandardCharsets.UTF_8)).toList();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isEmpty()) {
                    continue;
                }
                cases++;
                String[] f = line.split("\t", -1);
                String answer = new String(b64.decode(f[0]), StandardCharsets.UTF_8);
                String question = new String(b64.decode(f[1]), StandardCharsets.UTF_8);
                List<String> sources = Arrays.stream(f[2].split(",")).map(i -> table.get(Integer.parseInt(i))).toList();
                CitationResult r = GUARD.check(answer, sources, question);
                String got = String.join("\u0001", r.text(), Integer.toString(r.removed()), Integer.toString(r.recited()), Boolean.toString(r.empty()),
                        String.join(",", r.cites().stream().map(String::valueOf).toList()),
                        String.join(",", r.sentences().stream().map(s -> s.outcome().name()).toList()));
                String want = String.join("\u0001", new String(b64.decode(f[3]), StandardCharsets.UTF_8), f[4], f[5], f[6], f[7], f[8]);
                String plainGot = String.join("|", details.ungrounded(CitationGuard.stripCitations(answer), sources, question).stream()
                        .map(d -> d.kind().name() + ":" + d.key()).toList());
                String plainWant = new String(b64.decode(f[9]), StandardCharsets.UTF_8);
                if (!got.equals(want) || !plainGot.equals(plainWant)) {
                    mismatches.add("case " + cases + ": " + answer + "\n  want " + want + " / " + plainWant + "\n  got  " + got + " / " + plainGot);
                }
            }
        }
        assertEquals(1500, cases);
        assertTrue(mismatches.isEmpty(), mismatches.size() + " mismatches, first:\n" + String.join("\n", mismatches.subList(0, Math.min(3, mismatches.size()))));
    }
}
