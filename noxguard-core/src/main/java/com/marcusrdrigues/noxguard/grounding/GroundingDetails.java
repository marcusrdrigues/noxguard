package com.marcusrdrigues.noxguard.grounding;

import com.marcusrdrigues.noxguard.internal.Text;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the checkable details of an answer (numbers, acronyms, proper names) and the ones that are in none of the
 * sources the model received.
 *
 * <p>A pure rule, no model: cheap, explainable and tested. Ported from Nox, where it ran before anything else; the
 * rules are the ones that survived its runs, and the same rules ship in noxeval 0.5:
 *
 * <ul>
 *   <li>"10 mil", "10.000", "10,000" and "dez mil" are the same number; "BM25" and "v1.2" are identifiers, not numbers.
 *   <li>A detail that came from the question passes only in a sentence that negates it ("I didn't find a prize in
 *       2024"): that is how a question's bait becomes a fact.
 *   <li>Date arithmetic in plain sight is not invention: "3 years later" passes when it is the difference between two
 *       grounded years of the answer. "3 projects" does not.
 *   <li>Allowed names (the assistant's own, the person or company the app is about) are never details, and they split
 *       neighboring names: "Python Marcus" is "Python".
 * </ul>
 *
 * <p>Number words and connectors cover Portuguese and English together. Immutable and thread-safe.
 */
public final class GroundingDetails {

    private static final String SPACE = "[" + Text.SPACE_CLASS + "]";

    /** Words that join the parts of a name ("Rio de Janeiro"). "e" and "and" are left out: they separate names. */
    private static final Set<String> CONNECTORS = Set.of("de", "da", "do", "das", "dos", "of", "the");

    /** Negation marks: a detail from the question repeated in a denial. */
    private static final Pattern NEGATION = Pattern.compile(
            "(?:^|[^\\p{L}])(?:não|nao|nenhum|nenhuma|nunca|sem|not|no|never|none)(?=$|[^\\p{L}])|n['’]t(?=$|[^\\p{L}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?:])" + SPACE + "+|\\n+");

    /**
     * Loose numbers with their multiplier word. A number glued to a letter, hyphen or dot is part of an identifier
     * ("BM25", "text-embedding-3-small", "v1.2"); after a slash it counts ("mar/2023").
     */
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\p{L}\\p{N}\\-._])\\d+(?:[.,]\\d+)*(?:" + SPACE + "?(?:mil|thousand|milhões|milhoes|million|millions|mi|k)(?![\\p{L}]))?(?![\\p{L}\\p{N}\\-_]|[.,]\\d)");

    private static final Pattern CANONICAL = Pattern.compile("^(\\d+(?:[.,]\\d+)*)" + SPACE + "*([a-zçõ]+)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern THOUSANDS = Pattern.compile("^\\d{1,3}(?:[.,]\\d{3})+$");
    private static final Pattern YEAR = Pattern.compile("^(?:19|20)\\d{2}$");
    private static final Pattern COMBINING = Pattern.compile("[\\u0300-\\u036f]");
    private static final Pattern NOT_WORD = Pattern.compile("[^a-z0-9]+");

    private static final Map<String, Double> MULTIPLIER = Map.of(
            "mil", 1e3, "thousand", 1e3, "k", 1e3, "milhao", 1e6, "milhoes", 1e6, "million", 1e6, "millions", 1e6, "mi", 1e6);

    private static final Map<String, Integer> WORD_NUMBERS = wordNumbers();

    /** Punctuation around a word that isn't part of it. */
    private static final String EDGE = "\"“”'‘’()[]{},;:!?…";

    private final Set<String> allowed;

    private GroundingDetails(Set<String> allowed) {
        this.allowed = allowed;
    }

    /** Details with no allowed names. */
    public static GroundingDetails create() {
        return new GroundingDetails(Set.of());
    }

    /**
     * Details that never count these names: the assistant's name, the person or company the app is about, its own
     * channels. Each entry and each of its words is allowed ("Marcus Rodrigues" allows "Marcus" too).
     */
    public static GroundingDetails withAllowedNames(Collection<String> names) {
        Text.required(names, "names");
        Set<String> keys = new HashSet<>();
        for (String name : names) {
            String key = normalize(Text.required(name, "name")).strip();
            if (key.isEmpty()) {
                continue;
            }
            keys.add(key);
            for (String word : key.split(" ")) {
                if (!CONNECTORS.contains(word)) {
                    keys.add(word);
                }
            }
        }
        return new GroundingDetails(Set.copyOf(keys));
    }

    /** The checkable details of a text, sentence by sentence. */
    public List<Detail> extract(String text) {
        return extract(Text.required(text, "text"), false);
    }

    /**
     * Details of the answer that are in none of the sources.
     *
     * @param answer the answer, without citation marks
     * @param sources everything the model received to answer (passages, tool results)
     * @param question the user's question, for the premise bait; may be empty
     */
    public List<Detail> ungrounded(String answer, List<String> sources, String question) {
        return ungrounded(answer, sources, question, List.of());
    }

    /**
     * Same as {@link #ungrounded(String, List, String)}, with grounded years from elsewhere in the answer, for a check
     * that goes one sentence at a time.
     */
    public List<Detail> ungrounded(String answer, List<String> sources, String question, Collection<Integer> contextYears) {
        Text.required(answer, "answer");
        Text.required(sources, "sources");
        Text.required(question, "question");
        Text.required(contextYears, "contextYears");
        Index index = index(sources);
        Set<String> fromQuestion = new HashSet<>();
        for (Detail d : extract(question, true)) {
            fromQuestion.add(d.kind() + ":" + d.key());
        }
        List<String> sentences = splitSentences(answer);
        List<Detail> details = extract(answer, false);
        Set<Integer> years = new LinkedHashSet<>(groundedYears(details, index));
        years.addAll(contextYears);
        List<Integer> yearList = List.copyOf(years);
        // Whether a sentence negates is asked once per sentence: one long sentence can hold thousands of details.
        Map<Integer, Boolean> negates = new HashMap<>();
        List<Detail> out = new ArrayList<>();
        for (Detail d : details) {
            String sentence = d.sentence() < sentences.size() ? sentences.get(d.sentence()) : "";
            if (isGrounded(d, index) || isYearDifference(d, sentence, yearList)) {
                continue;
            }
            if (fromQuestion.contains(d.kind() + ":" + d.key())
                    && negates.computeIfAbsent(d.sentence(), i -> NEGATION.matcher(sentence).find())) {
                continue;
            }
            out.add(d);
        }
        return List.copyOf(out);
    }

    /** Years of the answer that are in the sources: the base of date arithmetic. */
    public List<Integer> groundedYears(String answer, List<String> sources) {
        return groundedYears(extract(Text.required(answer, "answer"), false), index(Text.required(sources, "sources")));
    }

    /** Whether one detail is in the sources: a number by value; an acronym or name as whole words, plural "s" allowed. */
    public boolean isGrounded(Detail detail, List<String> sources) {
        return isGrounded(Text.required(detail, "detail"), index(Text.required(sources, "sources")));
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Sources ready to check against: the normalized text and its set of numbers. */
    record Index(String text, Set<String> numbers) {}

    static Index index(List<String> sources) {
        String joined = String.join("\n", sources);
        return new Index(normalize(joined), numberSet(joined));
    }

    static boolean isGrounded(Detail detail, Index index) {
        if (detail.kind() == Detail.Kind.NUMBER) {
            return index.numbers().contains(detail.key());
        }
        if (has(index, detail.key())) {
            return true;
        }
        if (detail.key().endsWith("s") && has(index, detail.key().substring(0, detail.key().length() - 1))) {
            return true;
        }
        // A composed name passes when each meaningful word is in the sources ("Spring Boot" with "Boot" after a break).
        if (detail.kind() == Detail.Kind.NAME && detail.key().contains(" ")) {
            for (String word : detail.key().split(" ")) {
                if (!CONNECTORS.contains(word) && !has(index, word)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean has(Index index, String key) {
        return index.text().contains(" " + key + " ");
    }

    private static List<Integer> groundedYears(List<Detail> details, Index index) {
        Set<Integer> years = new LinkedHashSet<>();
        for (Detail d : details) {
            if (d.kind() == Detail.Kind.NUMBER && YEAR.matcher(d.key()).matches() && isGrounded(d, index)) {
                years.add(Integer.parseInt(d.key()));
            }
        }
        return List.copyOf(years);
    }

    /** A number of years equal to the difference between two grounded years of the answer, written as years. */
    private static boolean isYearDifference(Detail detail, String sentence, List<Integer> years) {
        if (detail.kind() != Detail.Kind.NUMBER || YEAR.matcher(detail.key()).matches()) {
            return false;
        }
        double value;
        try {
            value = Double.parseDouble(detail.key());
        } catch (NumberFormatException e) {
            return false;
        }
        if (value != Math.rint(value) || value < 1 || value > 100) {
            return false;
        }
        int n = (int) value;
        Pattern span = Pattern.compile("(?<![\\p{N}.,])" + Pattern.quote(detail.text()) + SPACE + "+(?:anos?|years?)(?![\\p{L}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        if (!span.matcher(sentence).find()) {
            return false;
        }
        for (int i = 0; i < years.size(); i++) {
            for (int k = i + 1; k < years.size(); k++) {
                if (Math.abs(years.get(i) - years.get(k)) == n) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<Detail> extract(String text, boolean firstWordCounts) {
        List<Detail> details = new ArrayList<>();
        List<String> sentences = splitSentences(text);
        for (int si = 0; si < sentences.size(); si++) {
            String sentence = sentences.get(si);
            Matcher m = NUMBER.matcher(sentence);
            while (m.find()) {
                String key = canonicalNumber(m.group());
                if (key != null) {
                    details.add(new Detail(Detail.Kind.NUMBER, m.group(), key, si));
                }
            }
            List<String> phrase = new ArrayList<>();
            List<Token> tokens = tokens(sentence);
            for (int ti = 0; ti < tokens.size(); ti++) {
                Token tok = tokens.get(ti);
                String t = tok.text();
                if (hasAsciiDigit(t) && !hasLetter(t)) {
                    flush(phrase, details, si);
                    continue;
                }
                // Right after a quote, a capital may only start the quotation.
                boolean startsSentence = (ti == 0 && !firstWordCounts) || tok.quoted();
                if (isAcronym(t)) {
                    flush(phrase, details, si);
                    details.add(new Detail(Detail.Kind.ACRONYM, t, normalize(t).strip(), si));
                } else if (allowed.contains(normalize(t).strip())) {
                    // An allowed name ends the previous one: "Python Marcus" are two names.
                    flush(phrase, details, si);
                } else if (startsWithUppercase(t) && !startsSentence) {
                    phrase.add(t);
                } else if (!phrase.isEmpty() && CONNECTORS.contains(t.toLowerCase(Locale.ROOT))) {
                    phrase.add(t);
                } else {
                    flush(phrase, details, si);
                }
            }
            flush(phrase, details, si);
        }
        List<Detail> out = new ArrayList<>();
        for (Detail d : details) {
            if (!d.key().isEmpty() && !allowed.contains(d.key())) {
                out.add(d);
            }
        }
        return List.copyOf(out);
    }

    private static void flush(List<String> phrase, List<Detail> details, int sentence) {
        // A trailing connector isn't part of the name ("Vibetex e" is "Vibetex").
        while (!phrase.isEmpty() && CONNECTORS.contains(phrase.get(phrase.size() - 1).toLowerCase(Locale.ROOT))) {
            phrase.remove(phrase.size() - 1);
        }
        if (!phrase.isEmpty()) {
            String name = String.join(" ", phrase);
            details.add(new Detail(Detail.Kind.NAME, name, normalize(name).strip(), sentence));
        }
        phrase.clear();
    }

    private record Token(String text, boolean quoted) {}

    /** Words of a sentence without edge punctuation (a plain loop, no backtracking regex). */
    private static List<Token> tokens(String sentence) {
        List<Token> out = new ArrayList<>();
        for (String raw : sentence.split(SPACE, -1)) {
            int start = 0;
            int end = raw.length();
            while (start < end && EDGE.indexOf(raw.charAt(start)) >= 0) {
                start++;
            }
            while (end > start && (EDGE.indexOf(raw.charAt(end - 1)) >= 0 || raw.charAt(end - 1) == '.')) {
                end--;
            }
            if (end > start) {
                String lead = raw.substring(0, start);
                boolean quoted = lead.indexOf('"') >= 0 || lead.indexOf('“') >= 0 || lead.indexOf('\'') >= 0
                        || lead.indexOf('‘') >= 0 || lead.indexOf('(') >= 0;
                out.add(new Token(raw.substring(start, end), quoted));
            }
        }
        return out;
    }

    /** Sentences: split after ".", "!", "?" or ":" followed by a space, and at line breaks. */
    static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : SENTENCE_BREAK.split(text, -1)) {
            String trimmed = Text.trim(s);
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** Lowercase, without accents, only letters and digits separated by one space, padded for whole-word search. */
    static String normalize(String text) {
        String flat = COMBINING.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
        return " " + NOT_WORD.matcher(flat).replaceAll(" ").strip() + " ";
    }

    /**
     * Canonical value of a written number: "10 mil", "10.000" and "10,000" become "10000"; "0,61" becomes "0.61". A
     * separator followed by exactly three digits is a thousands separator; any other is decimal.
     *
     * @return the canonical value, or {@code null} when the text is not a number
     */
    public static String canonicalNumber(String raw) {
        String flat = COMBINING.matcher(Normalizer.normalize(Text.required(raw, "raw"), Normalizer.Form.NFD)).replaceAll("");
        Matcher m = CANONICAL.matcher(Text.trim(flat));
        if (!m.matches()) {
            return null;
        }
        String digits = m.group(1);
        String word = m.group(2);
        double value;
        try {
            value = THOUSANDS.matcher(digits).matches()
                    ? Double.parseDouble(digits.replace(".", "").replace(",", ""))
                    : Double.parseDouble(digits.replaceFirst(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
        if (!Double.isFinite(value)) {
            return null;
        }
        Double mult = word == null ? Double.valueOf(1) : MULTIPLIER.get(word.toLowerCase(Locale.ROOT));
        if (word != null && mult == null) {
            return jsNumber(value);
        }
        return jsNumber(Math.round(value * mult * 1000) / 1000.0);
    }

    /** A number as JavaScript prints it ("10000", "0.61"), so keys match the TypeScript original. */
    private static String jsNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e21) {
            return Long.toString((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** Canonical values of the numbers of a text, words included ("cinco trechos", "ten thousand rows"). */
    static Set<String> numberSet(String text) {
        Set<String> set = new HashSet<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String c = canonicalNumber(m.group());
            if (c != null) {
                set.add(c);
            }
        }
        String[] words = normalize(text).strip().split(" ");
        for (int i = 0; i < words.length; i++) {
            Integer v = WORD_NUMBERS.get(words[i]);
            if (v == null) {
                continue;
            }
            set.add(Integer.toString(v));
            String next = i + 1 < words.length ? words[i + 1] : "";
            if (next.equals("mil") || next.equals("thousand")) {
                set.add(Integer.toString(v * 1000));
            }
        }
        return set;
    }

    private static boolean isAcronym(String token) {
        if (token.startsWith(".")) {
            return true;
        }
        int upper = 0;
        for (int i = 0; i < token.length(); ) {
            int cp = token.codePointAt(i);
            if (Character.getType(cp) == Character.UPPERCASE_LETTER) {
                upper++;
            }
            i += Character.charCount(cp);
        }
        return upper >= 2 || (startsWithUppercase(token) && hasAsciiDigit(token));
    }

    private static boolean startsWithUppercase(String token) {
        return !token.isEmpty() && Character.getType(token.codePointAt(0)) == Character.UPPERCASE_LETTER;
    }

    private static boolean hasAsciiDigit(String token) {
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (ch >= '0' && ch <= '9') {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLetter(String token) {
        return token.codePoints().anyMatch(Character::isLetter);
    }

    private static Map<String, Integer> wordNumbers() {
        Map<String, Integer> m = new HashMap<>();
        Object[] pairs = {
            "um", 1, "uma", 1, "one", 1, "dois", 2, "duas", 2, "two", 2, "tres", 3, "three", 3, "quatro", 4, "four", 4,
            "cinco", 5, "five", 5, "seis", 6, "six", 6, "sete", 7, "seven", 7, "oito", 8, "eight", 8, "nove", 9, "nine", 9,
            "dez", 10, "ten", 10, "onze", 11, "eleven", 11, "doze", 12, "twelve", 12, "vinte", 20, "twenty", 20, "cem", 100,
            "hundred", 100,
        };
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return Map.copyOf(m);
    }
}
