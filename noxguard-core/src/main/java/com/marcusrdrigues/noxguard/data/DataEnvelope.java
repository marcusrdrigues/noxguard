package com.marcusrdrigues.noxguard.data;

import com.marcusrdrigues.noxguard.internal.Text;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Wraps untrusted text (retrieved passages, tool results) as data before it goes into the prompt.
 *
 * <p>The prompt tells the model that whatever sits inside these tags is data, never instructions. That
 * only holds if the content cannot close the block early or open a fake one, so every opening or
 * closing reserved tag inside the text is removed, in any case and with any attributes, including
 * tags that only form once another is removed ({@code <to<tool>ol>}). The text is then cut at the
 * limit with {@code \n[...]}.
 *
 * <pre>{@code
 * DataEnvelope envelope = DataEnvelope.withReservedTags(Set.of("context", "question", "tool"));
 * String block = envelope.wrap("tool", Map.of("name", "get_case_study"), resultText, 4000);
 * // <tool name="get_case_study">
 * // ...
 * // </tool>
 * }</pre>
 *
 * <p>Wrapping only works if the prompt says what the tags mean; the envelope does the part that has to
 * be code. Immutable and thread-safe.
 */
public final class DataEnvelope {

    private static final Pattern TAG_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final String CUT = "\n[...]";

    private final Set<String> reserved;
    private final Pattern reservedTag;

    private DataEnvelope(Set<String> reserved) {
        this.reserved = reserved;
        String names = reserved.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        this.reservedTag = Pattern.compile("</?\\s*(?:" + names + ")\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    }

    /**
     * Envelope for these tag names: every tag the prompt uses to delimit data, so none of them can be
     * forged from inside any block.
     *
     * @throws IllegalArgumentException when the set is empty or a name is not a plain tag name
     */
    public static DataEnvelope withReservedTags(Collection<String> tagNames) {
        Text.required(tagNames, "tagNames");
        if (tagNames.isEmpty()) {
            throw new IllegalArgumentException("at least one reserved tag is required");
        }
        for (String name : tagNames) {
            if (name == null || !TAG_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("not a plain tag name: " + name);
            }
        }
        return new DataEnvelope(Set.copyOf(tagNames));
    }

    /** Same as {@link #wrap(String, Map, String, int)} with no attributes. */
    public String wrap(String tag, String text, int maxChars) {
        return wrap(tag, Map.of(), text, maxChars);
    }

    /**
     * Wraps the text in {@code <tag attr="value">...</tag>}.
     *
     * @param tag one of the reserved tags, so the content can't close it
     * @param attributes attributes set by the app (never by the model); names must be plain names,
     *     values are escaped. Use a map with a stable order (such as {@code LinkedHashMap}) for more
     *     than one.
     * @param text the untrusted text
     * @param maxChars most characters of text kept; longer text is cut and ends with {@code [...]}
     * @throws IllegalArgumentException when the tag is not reserved, an attribute name is not a plain
     *     name, or the limit is not positive
     */
    public String wrap(String tag, Map<String, String> attributes, String text, int maxChars) {
        Text.required(tag, "tag");
        Text.required(attributes, "attributes");
        Text.required(text, "text");
        if (!reserved.contains(tag)) {
            throw new IllegalArgumentException("tag must be one of the reserved tags " + reserved + ", got " + tag);
        }
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be greater than zero, got " + maxChars);
        }
        StringBuilder out = new StringBuilder().append('<').append(tag);
        attributes.forEach((name, value) -> {
            if (name == null || !TAG_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("not a plain attribute name: " + name);
            }
            out.append(' ').append(name).append("=\"").append(escape(Text.required(value, "attribute value"))).append('"');
        });
        return out.append(">\n").append(clean(text, maxChars)).append("\n</").append(tag).append('>').toString();
    }

    /** The text without reserved tags, cut at the limit. Exposed for apps that build blocks themselves. */
    public String clean(String text, int maxChars) {
        Text.required(text, "text");
        // Removing a tag can join its neighbours into a new one ("<to<tool>ol>"), so repeat until none is
        // left. Each pass removes at least one character, so the loop ends.
        String clean = text;
        Matcher tags = reservedTag.matcher(clean);
        while (tags.find()) {
            clean = tags.replaceAll("");
            tags = reservedTag.matcher(clean);
        }
        return clean.length() > maxChars ? clean.substring(0, Text.safeCut(clean, maxChars)) + CUT : clean;
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
