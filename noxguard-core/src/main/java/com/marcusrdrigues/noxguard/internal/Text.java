package com.marcusrdrigues.noxguard.internal;

/** Text helpers shared by the guards. Not part of the API. */
public final class Text {

    /**
     * Whitespace as JavaScript's {@code \s} sees it, as a regex character class body: ASCII spaces,
     * no-break space, every Unicode space separator, line and paragraph separators and U+FEFF. The
     * guards were written against JavaScript, and Java's {@code \s} is ASCII-only, so a no-break
     * space would otherwise join two links into one.
     */
    public static final String SPACE_CLASS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";

    private Text() {}

    /** The text without leading and trailing whitespace, as JavaScript's {@code trim()} does. */
    public static String trim(String text) {
        return text.replaceAll("^[" + SPACE_CLASS + "]+|[" + SPACE_CLASS + "]+$", "");
    }

    /**
     * Largest cut position at or below {@code index} that does not split a surrogate pair, so an
     * emoji or any character outside the Basic Multilingual Plane is never broken in two.
     */
    public static int safeCut(CharSequence text, int index) {
        if (index <= 0) {
            return 0;
        }
        if (index >= text.length()) {
            return text.length();
        }
        return Character.isHighSurrogate(text.charAt(index - 1)) ? index - 1 : index;
    }

    /** Fails fast with a clear message when a required argument is null. */
    public static <T> T required(T value, String name) {
        if (value == null) {
            throw new NullPointerException(name + " must not be null");
        }
        return value;
    }
}
