package com.marcusrdrigues.noxguard.grounding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The detail extractor, with the cases Nox met. */
class GroundingDetailsTest {

    private static final GroundingDetails DETAILS = GroundingDetails.withAllowedNames(CitationGuardTest.ALLOW);

    private static List<String> brief(List<Detail> details) {
        return details.stream().map(d -> d.kind().name().toLowerCase() + ":" + d.text()).toList();
    }

    @Test
    @DisplayName("canonical numbers: thousands, decimals and multiplier words")
    void canonicalNumbers() {
        assertEquals("10000", GroundingDetails.canonicalNumber("10 mil"));
        assertEquals("10000", GroundingDetails.canonicalNumber("10.000"));
        assertEquals("10000", GroundingDetails.canonicalNumber("10,000"));
        assertEquals("1200", GroundingDetails.canonicalNumber("1.200"));
        assertEquals("0.61", GroundingDetails.canonicalNumber("0,61"));
        assertEquals("0.78", GroundingDetails.canonicalNumber("0.78"));
        assertEquals("2000000", GroundingDetails.canonicalNumber("2 milhões"));
        assertEquals("49", GroundingDetails.canonicalNumber("49"));
        assertNull(GroundingDetails.canonicalNumber("abc"));
    }

    @Test
    @DisplayName("numbers, acronyms and names, not the first word of a sentence; identifiers are not numbers")
    void extract() {
        assertEquals(List.of("number:2026", "name:Vibetex", "name:Java", "name:Spring Boot", "acronym:IA", "acronym:ACME"),
                brief(DETAILS.extract("Marcus trabalha na Vibetex desde 2026 com Java e Spring Boot, e cuidou da IA do projeto da ACME.")));
        assertEquals(List.of("acronym:OAB/RJ", "name:Rio de Janeiro"), brief(DETAILS.extract("Ele passou três anos na OAB/RJ, no Rio de Janeiro.")));
        assertEquals(List.of("number:0,78", "acronym:.NET", "name:Next.js", "acronym:MRR"), brief(DETAILS.extract("Usa .NET e Next.js; o MRR subiu para 0,78.")));
        assertEquals(List.of("acronym:BM25"), brief(DETAILS.extract("Usa BM25 com text-embedding-3-small.")));
    }

    @Test
    @DisplayName("allowed names are never details, and they split neighboring names")
    void allowedNames() {
        assertEquals(List.of(), DETAILS.extract("Fale com Marcus Rodrigues pelo LinkedIn ou pelo GitHub; o Nox responde aqui."));
        assertEquals(List.of("name:Python"), brief(DETAILS.extract("Não encontrei quantos anos de Python Marcus tem.")));
        assertEquals(List.of("name:Python Marcus"), brief(GroundingDetails.create().extract("Não encontrei quantos anos de Python Marcus tem.")));
    }

    @Test
    @DisplayName("ungrounded: spellings of numbers, the question's bait, date arithmetic")
    void ungrounded() {
        List<String> sources = List.of("Hoje sou desenvolvedor fullstack na Vibetex (mar/2026), com Java, Spring Boot e IA.",
                "Projeto da ACME: mais de 10 mil pedidos processados, com LLM e busca semântica.");
        assertEquals(List.of(), DETAILS.ungrounded("Na ACME, ele cuidou de mais de 10.000 pedidos com LLMs.", sources, ""));
        assertEquals(List.of("number:12 mil", "number:2025", "name:Microsoft"),
                brief(DETAILS.ungrounded("Na ACME, ele cuidou de 12 mil pedidos em 2025 com a Microsoft.", sources, "")));
        String q = "Ele ganhou o prêmio Nobel em 2024?";
        assertEquals(List.of(), DETAILS.ungrounded("Não encontrei um prêmio Nobel em 2024 no site.", sources, q));
        assertEquals(List.of("number:2024", "name:Nobel"), brief(DETAILS.ungrounded("Sim, ele ganhou o prêmio Nobel em 2024.", sources, q)));
        assertEquals(List.of(), DETAILS.ungrounded("I couldn't find a Nobel prize in 2024 on the site.", sources, "Did he win the Nobel prize in 2024?"));
    }

    @Test
    @DisplayName("long input runs in linear time")
    void linear() {
        String longText = "Na ACME ".repeat(5000) + "1".repeat(5000);
        long t0 = System.nanoTime();
        DETAILS.ungrounded(longText, List.of("x"), longText);
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L);
    }
}
