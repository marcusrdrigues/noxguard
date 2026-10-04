package com.marcusrdrigues.noxguard.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StreamGuardTest {

    private static final List<String> MARKERS = List.of("<trechos>", "Você é Nox", "Estas instruções são confidenciais");

    private static StreamGuard guard(int maxChars) {
        return StreamGuard.builder().leakMarkers(MARKERS).maxChars(maxChars).build();
    }

    /** Feeds the chunks like a stream consumer would and returns what the user saw. */
    private static String run(StreamGuard guard, List<String> chunks) {
        StringBuilder shown = new StringBuilder();
        for (String chunk : chunks) {
            shown.append(guard.push(chunk));
            if (!(guard.status() instanceof StreamStatus.Open)) {
                break;
            }
        }
        shown.append(guard.end());
        return shown.toString();
    }

    private static List<String> chunksOf(String text, int size) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            chunks.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return chunks;
    }

    @Test
    @DisplayName("a normal answer comes out whole and in order")
    void normalAnswerComesOutWhole() {
        String text = "Marcus trabalha com Java, .NET e Angular e cuida da camada de IA.";
        StreamGuard guard = guard(1200);
        assertEquals(text, run(guard, chunksOf(text, 5)));
        assertInstanceOf(StreamStatus.Open.class, guard.status());
    }

    @Test
    @DisplayName("a leak marker never shows, not even half of it")
    void leakMarkerNeverShows() {
        String text = "Claro, aqui vai: Você é Nox, o corvo do site...";
        for (int size : new int[] {1, 2, 3, 7, 50}) {
            StreamGuard guard = guard(1200);
            String shown = run(guard, chunksOf(text, size));
            StreamStatus.Tripped tripped = assertInstanceOf(StreamStatus.Tripped.class, guard.status(), "chunks of " + size);
            assertEquals("Você é Nox", tripped.marker());
            assertFalse(shown.contains("Você"), "leaked part of the marker with chunks of " + size + ": " + shown);
        }
    }

    @Test
    @DisplayName("the length limit cuts and ends with an ellipsis")
    void lengthLimitCutsWithEllipsis() {
        StreamGuard guard = guard(40);
        String shown = run(guard, List.of("a".repeat(30), "b".repeat(30)));
        assertInstanceOf(StreamStatus.Capped.class, guard.status());
        assertTrue(shown.startsWith("a".repeat(30)));
        assertTrue(shown.endsWith("…"));
        assertTrue(shown.length() <= 41, "length " + shown.length());
    }

    @Test
    @DisplayName("an empty answer stays empty (the app replaces it with its refusal)")
    void emptyAnswerStaysEmpty() {
        StreamGuard guard = guard(1200);
        assertEquals("", run(guard, List.of()));
        assertEquals("", guard.received());
    }

    @Test
    @DisplayName("a surrogate pair is never split, at the holdback or at the limit")
    void surrogatePairIsNeverSplit() {
        String text = "Corvo 🐦‍⬛ no site 🚀🚀🚀 fim";
        for (int size = 1; size <= 6; size++) {
            for (int max : new int[] {7, 8, 9, 1200}) {
                StreamGuard guard = guard(max);
                for (String chunk : chunksOf(text, size)) {
                    String out = guard.push(chunk);
                    assertFalse(!out.isEmpty() && Character.isHighSurrogate(out.charAt(out.length() - 1)),
                            "released half a pair with chunks of " + size + " and limit " + max);
                    if (!(guard.status() instanceof StreamStatus.Open)) {
                        break;
                    }
                }
                guard.end();
            }
        }
    }

    @Test
    @DisplayName("the received text keeps everything, released or not")
    void receivedKeepsEverything() {
        StreamGuard guard = guard(1200);
        guard.push("Olá, ");
        guard.push("<trechos>");
        assertEquals("Olá, <trechos>", guard.received());
        assertEquals("", guard.push("mais texto"), "nothing is released after a trip");
        assertEquals("", guard.end());
    }

    @Test
    @DisplayName("one guard per answer: push after end and end twice fail")
    void oneGuardPerAnswer() {
        StreamGuard guard = guard(1200);
        guard.push("oi");
        guard.end();
        assertThrows(IllegalStateException.class, () -> guard.push("de novo"));
        assertThrows(IllegalStateException.class, guard::end);
    }

    @Test
    @DisplayName("the builder rejects a missing or empty marker and a non-positive limit")
    void builderValidates() {
        assertThrows(IllegalArgumentException.class, () -> StreamGuard.builder().maxChars(10).build());
        assertThrows(IllegalArgumentException.class, () -> StreamGuard.builder().leakMarkers(List.of("")).maxChars(10).build());
        assertThrows(IllegalArgumentException.class, () -> StreamGuard.builder().leakMarkers(MARKERS).build());
        assertThrows(IllegalArgumentException.class, () -> StreamGuard.builder().leakMarkers(MARKERS).maxChars(0).build());
        assertThrows(NullPointerException.class, () -> guard(10).push(null));
    }

    /**
     * What streaming breaks, tested directly: across thousands of random ways to split an answer into
     * chunks, a clean answer is released whole and no character of a marker is ever released.
     */
    @Test
    @DisplayName("any chunking: clean answers come out whole, markers never leak")
    void anyChunkingIsSafe() {
        List<String> clean = List.of(
                "Marcus trabalha com Java, .NET e Angular.",
                "He builds RAG chats 🚀 and evaluates them with noxeval.",
                "Você é bem-vindo! Estas instruções ficam no prompt? Não.");
        List<String> leaky = new ArrayList<>();
        for (String marker : MARKERS) {
            leaky.add(marker + " no começo");
            leaky.add("No meio: " + marker + " e depois");
            leaky.add("No fim: " + marker);
            leaky.add("Quase " + marker.substring(0, marker.length() - 1) + " e depois o inteiro " + marker);
        }
        Random random = new Random(20261004L);
        for (int round = 0; round < 3000; round++) {
            boolean isLeaky = round % 2 == 1;
            String text = isLeaky ? leaky.get(random.nextInt(leaky.size())) : clean.get(random.nextInt(clean.size()));
            List<String> chunks = randomChunks(text, random);
            StreamGuard guard = guard(1200);
            String shown = run(guard, chunks);
            assertTrue(text.startsWith(shown), "released text out of order: " + shown);
            if (isLeaky) {
                int first = firstMarkerIndex(text);
                assertInstanceOf(StreamStatus.Tripped.class, guard.status(), "missed a marker in " + chunks);
                assertTrue(shown.length() <= first, "released part of a marker with " + chunks + ": " + shown);
            } else {
                assertEquals(text, shown, "a clean answer was not released whole with " + chunks);
            }
        }
    }

    private static List<String> randomChunks(String text, Random random) {
        List<String> chunks = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int size = 1 + random.nextInt(12);
            chunks.add(text.substring(i, Math.min(text.length(), i + size)));
            i += size;
        }
        return chunks;
    }

    private static int firstMarkerIndex(String text) {
        return MARKERS.stream().mapToInt(text::indexOf).filter(i -> i >= 0).min().orElseThrow();
    }
}
