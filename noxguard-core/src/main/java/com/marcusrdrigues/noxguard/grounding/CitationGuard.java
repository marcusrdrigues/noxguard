package com.marcusrdrigues.noxguard.grounding;

import com.marcusrdrigues.noxguard.internal.Text;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a RAG answer sentence by sentence against the sources it cites, before the user sees it.
 *
 * <p>The prompt numbers the sources ({@link #numbered(List)}) and asks the model to end each sentence with the number
 * of its source, like {@code [2]} or {@code [1][3]}. A prompt rule lowers how often the model invents a detail; this
 * guard makes sure an invented one never reaches the user.
 *
 * <pre>{@code
 * CitationGuard guard = CitationGuard.builder().allowNames(List.of("Ava", "Example Books")).build();
 * CitationResult result = guard.check(answer, sources, question);
 * String shown = result.empty() ? NOT_CONFIRMED : result.text();
 * }</pre>
 *
 * <p>For each sentence (it ends at {@code .}, {@code !}, {@code ?} or a line break; a colon does not end it):
 *
 * <ul>
 *   <li>every detail (number, acronym, proper name, see {@link GroundingDetails}) is in a source the sentence cites:
 *       {@link SentenceCheck.Outcome#KEPT};
 *   <li>a detail is in another source the model received, not the cited one: the sentence stays and the citation is
 *       corrected to the fewest sources that cover it ({@link SentenceCheck.Outcome#RECITED});
 *   <li>a detail is in no source received: the sentence is removed ({@link SentenceCheck.Outcome#REMOVED});
 *   <li>when the first sentence is removed, a second one that depends on it ("He...", "That...", "Ele...", "Isso...")
 *       goes too ({@link SentenceCheck.Outcome#REMOVED_WITH_PREVIOUS}).
 * </ul>
 *
 * <p>A citation to a number the model never received is dropped. Date arithmetic in plain sight passes: "3 years later"
 * between two grounded years of the answer is not an invented detail. When no cited sentence is left,
 * {@link CitationResult#empty()} is {@code true}.
 *
 * <p>The guard runs on the complete answer: a citation can only be checked once its sentence ends. A user watching
 * the stream may see a sentence that is then removed; render the answer as plain text until it ends.
 *
 * <p>Ported from Nox, where it runs on every answer. Immutable and thread-safe.
 */
public final class CitationGuard {

    /** A citation group: [2], [1][3] (two groups) or [1, 3]. */
    private static final Pattern CITE_GROUP = Pattern.compile("\\[(\\d{1,2}(?:, ?\\d{1,2})*)\\]");
    private static final Pattern WHOLE_GROUP = Pattern.compile("^\\[\\d{1,2}(?:, ?\\d{1,2})*\\]$");
    private static final String SPACE = "[" + Text.SPACE_CLASS + "]";
    /** Sentences end at . ! ? and line breaks. Not at a colon: "works at Vibetex: runs the AI layer [1]" is one. */
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])" + SPACE + "+|\\n+");
    private static final Pattern TRAILING_SPACE = Pattern.compile(SPACE + "+$");
    private static final Pattern LEADING_SPACE = Pattern.compile("^" + SPACE + "+");
    private static final Pattern DOUBLE_SPACE = Pattern.compile(" {2,}");

    /** A sentence that only makes sense with the previous one: it starts with a pronoun or a reference to it. */
    private static final Pattern DEPENDENT_START = Pattern.compile(
            "^(?:ele|ela|eles|elas|isso|isto|lá|ali|nesse|nessa|neste|nesta|esse|essa|este|esta|também|he|she|they|it|that|this|there|also|in that)(?=$|[^\\p{L}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final GroundingDetails details;

    private CitationGuard(GroundingDetails details) {
        this.details = details;
    }

    /** Starts a guard. */
    public static Builder builder() {
        return new Builder();
    }

    /** Same as {@link #check(String, List, String)} without a question. */
    public CitationResult check(String answer, List<String> sources) {
        return check(answer, sources, "");
    }

    /**
     * Checks the answer.
     *
     * @param answer the complete answer, with its {@code [n]} citations
     * @param sources what the model received, in the numbering it saw: index 0 is {@code [1]}
     * @param question the user's question (a detail from it passes only in a sentence that denies it)
     */
    public CitationResult check(String answer, List<String> sources, String question) {
        Text.required(answer, "answer");
        List<String> src = List.copyOf(Text.required(sources, "sources"));
        Text.required(question, "question");
        List<Cited> original = citedSentences(answer);
        // Date arithmetic: grounded years of any sentence support the difference written in another.
        List<Integer> years = details.groundedYears(stripCitations(answer), src);
        List<SentenceCheck> checked = new ArrayList<>();
        for (Cited s : original) {
            String plain = stripCitations(s.text());
            List<Integer> valid = new ArrayList<>();
            for (int n : s.cites()) {
                if (n >= 1 && n <= src.size()) {
                    valid.add(n);
                }
            }
            List<String> cited = new ArrayList<>();
            for (int n : valid) {
                cited.add(src.get(n - 1));
            }
            List<Detail> missing = details.ungrounded(plain, cited, question, years);
            if (missing.isEmpty()) {
                // A citation to a source the model never received is dropped; the sentence has nothing to check.
                checked.add(valid.size() == s.cites().size()
                        ? new SentenceCheck(s.text(), s.cites(), SentenceCheck.Outcome.KEPT, List.of())
                        : new SentenceCheck(withCitations(s.text(), valid), valid, SentenceCheck.Outcome.KEPT, List.of()));
                continue;
            }
            List<Integer> cover = coveringSources(missing, src);
            if (cover == null) {
                checked.add(new SentenceCheck(s.text(), s.cites(), SentenceCheck.Outcome.REMOVED, missing));
                continue;
            }
            Set<Integer> cites = new TreeSet<>(valid);
            cites.addAll(cover);
            List<Integer> list = List.copyOf(cites);
            checked.add(new SentenceCheck(withCitations(s.text(), list), list, SentenceCheck.Outcome.RECITED, List.of()));
        }
        // Coherence: without the first sentence, a second one that depended on it goes too.
        if (checked.size() > 1 && !checked.get(0).kept() && checked.get(1).kept()
                && DEPENDENT_START.matcher(stripCitations(checked.get(1).text())).find()) {
            SentenceCheck second = checked.get(1);
            checked.set(1, new SentenceCheck(second.text(), second.cites(), SentenceCheck.Outcome.REMOVED_WITH_PREVIOUS, List.of()));
        }
        List<SentenceCheck> kept = checked.stream().filter(SentenceCheck::kept).toList();
        int removed = checked.size() - kept.size();
        int recited = (int) kept.stream().filter(s -> s.outcome() == SentenceCheck.Outcome.RECITED).count();
        boolean changed = false;
        for (int i = 0; i < checked.size(); i++) {
            if (!checked.get(i).kept() || !checked.get(i).text().equals(original.get(i).text())) {
                changed = true;
                break;
            }
        }
        String text = changed ? String.join(" ", kept.stream().map(SentenceCheck::text).toList()) : Text.trim(answer);
        boolean empty = removed > 0 && kept.stream().noneMatch(s -> !s.cites().isEmpty());
        return new CitationResult(text, checked, removed, recited, changed, empty, citedNumbers(text));
    }

    /**
     * The sources numbered as the model should see them, {@code [1] text}, separated by blank lines. Use it to build
     * the prompt, so the numbers the model cites and the ones this guard checks can't drift.
     */
    public static String numbered(List<String> sources) {
        Text.required(sources, "sources");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            out.add("[" + (i + 1) + "] " + Text.required(sources.get(i), "source"));
        }
        return String.join("\n\n", out);
    }

    /** The source numbers cited in a text, in order, without repeats. */
    public static List<Integer> citedNumbers(String text) {
        Text.required(text, "text");
        Set<Integer> out = new LinkedHashSet<>();
        Matcher m = CITE_GROUP.matcher(text);
        while (m.find()) {
            for (String n : m.group(1).split(",")) {
                out.add(Integer.parseInt(Text.trim(n)));
            }
        }
        return List.copyOf(out);
    }

    /**
     * The text without citations and without the space before them ("Vibetex [1]." becomes "Vibetex."). For the
     * history sent back to the model (the numbers belonged to the previous question) and for plain displays.
     */
    public static String stripCitations(String text) {
        Text.required(text, "text");
        String[] parts = CITE_GROUP.matcher(text).replaceAll("\u0000").split("\u0000", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            out.append(i < parts.length - 1 ? TRAILING_SPACE.matcher(parts[i]).replaceAll("") : parts[i]);
        }
        return Text.trim(DOUBLE_SPACE.matcher(out.toString()).replaceAll(" "));
    }

    private record Cited(String text, List<Integer> cites) {}

    /** Sentences with their citations. A citation right after the full stop belongs to the previous sentence. */
    static List<Cited> citedSentences(String answer) {
        List<Cited> out = new ArrayList<>();
        for (String piece : SENTENCE_BREAK.split(answer, -1)) {
            String sentence = Text.trim(piece);
            if (sentence.isEmpty()) {
                continue;
            }
            StringBuilder lead = new StringBuilder();
            while (sentence.startsWith("[")) {
                int end = sentence.indexOf(']');
                if (end < 0 || !WHOLE_GROUP.matcher(sentence.substring(0, end + 1)).matches()) {
                    break;
                }
                lead.append(sentence, 0, end + 1);
                sentence = LEADING_SPACE.matcher(sentence.substring(end + 1)).replaceAll("");
            }
            if (!lead.isEmpty() && !out.isEmpty()) {
                Cited prev = out.remove(out.size() - 1);
                String joined = prev.text() + " " + lead;
                out.add(new Cited(joined, citedNumbers(joined)));
            } else if (!lead.isEmpty()) {
                sentence = Text.trim(lead + " " + sentence);
            }
            if (!sentence.isEmpty()) {
                out.add(new Cited(sentence, citedNumbers(sentence)));
            }
        }
        return out;
    }

    /** Replaces the sentence's citations with new ones, before the final punctuation ("Vibetex [3]."). */
    private static String withCitations(String sentence, List<Integer> cites) {
        String plain = stripCitations(sentence);
        StringBuilder marks = new StringBuilder();
        for (int n : cites) {
            marks.append('[').append(n).append(']');
        }
        String end = plain.isEmpty() ? "" : plain.substring(plain.length() - 1);
        boolean punct = end.equals(".") || end.equals("!") || end.equals("?");
        return (punct ? plain.substring(0, plain.length() - 1) : plain) + " " + marks + (punct ? end : "");
    }

    /**
     * The fewest sources that cover the details, greedily: at each step, the one that covers most of the details still
     * open. {@code null} when a detail is in no source.
     */
    private static List<Integer> coveringSources(List<Detail> missing, List<String> sources) {
        List<GroundingDetails.Index> indexes = new ArrayList<>();
        for (String s : sources) {
            indexes.add(GroundingDetails.index(List.of(s)));
        }
        List<Detail> open = new ArrayList<>(missing);
        List<Integer> chosen = new ArrayList<>();
        while (!open.isEmpty()) {
            int best = -1;
            List<Detail> bestCovered = List.of();
            for (int i = 0; i < indexes.size(); i++) {
                GroundingDetails.Index idx = indexes.get(i);
                List<Detail> covered = open.stream().filter(d -> GroundingDetails.isGrounded(d, idx)).toList();
                if (covered.size() > bestCovered.size()) {
                    best = i;
                    bestCovered = covered;
                }
            }
            if (best < 0) {
                return null;
            }
            chosen.add(best + 1);
            open.removeAll(bestCovered);
        }
        chosen.sort(null);
        return chosen;
    }

    /** Builds a {@link CitationGuard}. */
    public static final class Builder {

        private Collection<String> allowNames = List.of();

        private Builder() {}

        /** Names the answer may always say: the assistant's name, the person or company the app is about. */
        public Builder allowNames(Collection<String> names) {
            this.allowNames = List.copyOf(Text.required(names, "names"));
            return this;
        }

        /** The guard. */
        public CitationGuard build() {
            return new CitationGuard(GroundingDetails.withAllowedNames(allowNames));
        }
    }
}
