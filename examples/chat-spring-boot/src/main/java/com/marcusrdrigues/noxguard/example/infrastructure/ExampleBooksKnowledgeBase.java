package com.marcusrdrigues.noxguard.example.infrastructure;

import com.marcusrdrigues.noxguard.example.domain.KnowledgeBase;
import com.marcusrdrigues.noxguard.example.domain.Passage;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * The store's published content, searched by shared words. A real app would use embeddings; the
 * guards don't care how the passages were found.
 */
@Component
public class ExampleBooksKnowledgeBase implements KnowledgeBase {

    /** Each passage with the words that find it besides its own text (a stand-in for embeddings). */
    private static final List<Entry> ENTRIES = List.of(
            new Entry(new Passage("hours", "Example Books is open from 9am to 6pm, Monday to Saturday."), "when time hour open close"),
            new Entry(new Passage("address", "Example Books is at 12 Sample Street. More at example.com."), "where address location street find"),
            new Entry(new Passage("orders", "Books not in stock can be ordered and arrive in 3 to 5 business days."), "order stock delivery arrive"),
            new Entry(new Passage("contact", "To leave a message for the store, ask Ava to draft it; you review it before it is sent."), "message contact"));

    private static final Set<String> STOP = Set.of("the", "a", "an", "is", "are", "do", "does", "you", "your", "to", "of", "and", "what", "how", "can", "i", "store", "example", "books");

    @Override
    public List<Passage> search(String question, int limit) {
        Set<String> words = words(question);
        return ENTRIES.stream()
                .map(e -> new Scored(e.passage(), (int) words(e.passage().text() + " " + e.keywords()).stream().filter(words::contains).count()))
                .filter(s -> s.score > 0)
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(limit)
                .map(Scored::passage)
                .toList();
    }

    private static Set<String> words(String text) {
        return Stream.of(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() > 1 && !STOP.contains(w))
                .map(w -> w.endsWith("s") ? w.substring(0, w.length() - 1) : w)
                .collect(Collectors.toSet());
    }

    private record Entry(Passage passage, String keywords) {}

    private record Scored(Passage passage, int score) {}
}
