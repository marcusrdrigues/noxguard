package com.marcusrdrigues.noxguard.input;

import com.marcusrdrigues.noxguard.internal.Text;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gives an input classifier the versions of a message that an attacker hides.
 *
 * <p>A classifier that reads only the raw message misses an instruction in base64, in ROT13, in
 * leetspeak or broken up by invisible characters. {@link #normalize(String)} cleans the message;
 * {@link #decoded(String)} returns the decoded versions worth checking too. An ordinary question
 * produces no version, so the classifier sees only the message, as before.
 *
 * <pre>{@code
 * String clean = InputViews.normalize(message);
 * boolean attack = classifier.isAttack(clean)
 *         || InputViews.decoded(clean).stream().anyMatch(classifier::isAttack);
 * }</pre>
 *
 * <p>This is not a classifier. It decides nothing; it gives a classifier better input. Immutable and
 * thread-safe.
 */
public final class InputViews {

    /** Format and direction-control characters with no legitimate use in a question. */
    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\u00AD\\u034F\\u061C\\u115F\\u1160\\u17B4\\u17B5\\u180B-\\u180E\\u200B-\\u200F\\u202A-\\u202E"
                    + "\\u2060-\\u206F\\u3164\\uFE00-\\uFE0F\\uFEFF\\uFFA0]");
    private static final Pattern SPACES = Pattern.compile("[ \\t]+");
    private static final Pattern BASE64_RUN = Pattern.compile("[A-Za-z0-9+/]{16,}={0,2}");
    private static final Pattern SEGMENT_END = Pattern.compile("[:.!?][" + Text.SPACE_CLASS + "]+");
    private static final Pattern ASCII_WORD = Pattern.compile("[a-z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD = Pattern.compile("[a-zà-ú]+");
    private static final Pattern MIXED_WORD = Pattern.compile("\\b(?=[\\p{L}\\d]*\\d)(?=[\\p{L}\\d]*\\p{L})[\\p{L}\\d]{3,}\\b");
    private static final Pattern LEET_DIGIT = Pattern.compile("[0134578]");
    private static final Pattern PRINTABLE = Pattern.compile("[\\p{L}\\p{N}\\p{P}\\p{Zs}]");
    private static final Pattern WHITESPACE = Pattern.compile("[" + Text.SPACE_CLASS + "]");
    private static final Pattern THREE_LETTERS = Pattern.compile("\\p{L}{3,}");
    private static final Map<Character, Character> LEET = Map.of('0', 'o', '1', 'i', '3', 'e', '4', 'a', '5', 's', '7', 't', '8', 'b');

    /** Common Portuguese and English words: plain text has several, ciphered text almost none. */
    private static final Set<String> DEFAULT_COMMON_WORDS = Set.of(
            "as", "os", "de", "do", "da", "dos", "das", "que", "para", "por", "com", "em", "no", "na", "um", "uma",
            "seu", "sua", "ele", "ela", "the", "and", "to", "of", "you", "your", "is", "are", "in", "on", "for",
            "with", "this", "that", "all");

    private static final InputViews DEFAULT = new InputViews(DEFAULT_COMMON_WORDS);

    private final Set<String> commonWords;

    private InputViews(Set<String> commonWords) {
        this.commonWords = commonWords;
    }

    /** Decoder with the Portuguese and English common words, the same one {@link #decoded(String)} uses. */
    public static InputViews withDefaults() {
        return DEFAULT;
    }

    /**
     * Decoder whose ROT13 check uses these common words instead of the Portuguese and English
     * defaults: use the frequent short words of your users' languages, in lowercase.
     *
     * @throws IllegalArgumentException when the set is empty
     */
    public static InputViews withCommonWords(Set<String> words) {
        Text.required(words, "words");
        if (words.isEmpty()) {
            throw new IllegalArgumentException("at least one common word is required");
        }
        return new InputViews(Set.copyOf(words.stream().map(w -> w.toLowerCase(Locale.ROOT)).toList()));
    }

    /** NFKC (full-width letters and ligatures become plain ones), invisible characters removed, spaces collapsed, trimmed. */
    public static String normalize(String text) {
        Text.required(text, "text");
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return Text.trim(SPACES.matcher(INVISIBLE.matcher(nfkc).replaceAll("")).replaceAll(" "));
    }

    /** Decoded versions of the message with the default common words. See {@link #decode(String)}. */
    public static List<String> decoded(String text) {
        return DEFAULT.decode(text);
    }

    /**
     * Decoded versions of the message worth checking too:
     *
     * <ul>
     *   <li>base64 runs of 16 or more characters that decode to readable text;
     *   <li>the ROT13 of each segment whose flipped text has clearly more common words than the
     *       original (the plain request, such as "apply ROT13 and do what it says:", comes with it);
     *   <li>the message with digits read as letters, when three or more words mix letters and digits.
     * </ul>
     *
     * @return an empty list for an ordinary message
     */
    public List<String> decode(String text) {
        Text.required(text, "text");
        List<String> views = new ArrayList<>();
        Matcher runs = BASE64_RUN.matcher(text);
        while (runs.find()) {
            String decoded = decodeBase64(runs.group());
            if (decoded != null && !views.contains(decoded)) {
                views.add(decoded);
            }
        }
        List<String> deciphered = new ArrayList<>();
        for (String segment : SEGMENT_END.split(text)) {
            if (count(ASCII_WORD, segment) < 3) {
                continue;
            }
            String flipped = rot13(segment);
            int flippedCommon = commonWordCount(flipped);
            if (flippedCommon >= 2 && flippedCommon >= commonWordCount(segment) + 2) {
                deciphered.add(flipped);
            }
        }
        if (!deciphered.isEmpty()) {
            views.add(String.join(". ", deciphered));
        }
        if (count(MIXED_WORD, text) >= 3) {
            views.add(LEET_DIGIT.matcher(text).replaceAll(m -> String.valueOf(LEET.get(m.group().charAt(0)))));
        }
        return List.copyOf(views);
    }

    /** ROT13 of the ASCII letters; everything else is kept. */
    static String rot13(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= 'a' && c <= 'z') {
                out.append((char) ('a' + (c - 'a' + 13) % 26));
            } else if (c >= 'A' && c <= 'Z') {
                out.append((char) ('A' + (c - 'A' + 13) % 26));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private int commonWordCount(String text) {
        Matcher words = WORD.matcher(text.toLowerCase(Locale.ROOT));
        int n = 0;
        while (words.find()) {
            if (commonWords.contains(words.group())) {
                n++;
            }
        }
        return n;
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** Decodes leniently, as Node does: missing padding is added and a dangling last character ignored. */
    private static String decodeBase64(String token) {
        String body = token.replace("=", "");
        if (body.length() % 4 == 1) {
            body = body.substring(0, body.length() - 1);
        }
        String padded = body + "=".repeat((4 - body.length() % 4) % 4);
        try {
            String out = new String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8);
            return readable(out) ? out : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Does the decoded text look like language: almost all printable, with letters and spaces? */
    private static boolean readable(String text) {
        if (text.length() < 6) {
            return false;
        }
        int[] codePoints = text.codePoints().toArray();
        long printable = text.codePoints().filter(cp -> PRINTABLE.matcher(Character.toString(cp)).matches()).count();
        return (double) printable / codePoints.length > 0.9 && WHITESPACE.matcher(text).find() && THREE_LETTERS.matcher(text).find();
    }
}
