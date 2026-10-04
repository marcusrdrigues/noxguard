package com.marcusrdrigues.noxguard.output;

import com.marcusrdrigues.noxguard.internal.Text;

/**
 * A link outside the allow list, found by {@link LinkPolicy}.
 *
 * @param kind a plain link or a Markdown image
 * @param value the link as found: lowercased, without the scheme and trailing punctuation; for an
 *     image, the start of the Markdown image syntax
 */
public record ForeignLink(Kind kind, String value) {

    /** What was found. */
    public enum Kind {
        /** An explicit link: {@code http://}, {@code https://} or {@code www.}. */
        URL,
        /** A Markdown image, reported whatever its address, since a client may load it on its own. */
        MARKDOWN_IMAGE
    }

    /** Validates that both parts are present. */
    public ForeignLink {
        Text.required(kind, "kind");
        Text.required(value, "value");
    }
}
