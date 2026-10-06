package com.marcusrdrigues.noxguard.grounding;

import com.marcusrdrigues.noxguard.internal.Text;

/**
 * One checkable detail of an answer: a number, an acronym or a proper name.
 *
 * @param kind what it is
 * @param text the detail as written in the answer, such as {@code "10 mil"} or {@code "Spring Boot"}
 * @param key the value compared with the sources: a canonical number ({@code "10000"}) or the normalized words
 * @param sentence index of the sentence it came from, starting at 0
 */
public record Detail(Kind kind, String text, String key, int sentence) {

    /** What a detail is. */
    public enum Kind {
        /** A number, with its multiplier word ("10 mil", "3 million"). */
        NUMBER,
        /** Two or more capitals, a capital with a digit, or a leading dot (".NET"). */
        ACRONYM,
        /** Capitalized words outside the start of a sentence, joined by connectors ("Rio de Janeiro"). */
        NAME
    }

    /** A detail; every component is required. */
    public Detail {
        Text.required(kind, "kind");
        Text.required(text, "text");
        Text.required(key, "key");
    }
}
