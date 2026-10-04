package com.marcusrdrigues.noxguard.internal;

/** Text helpers shared by the guards. Not part of the API. */
public final class Text {

    private Text() {}

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
