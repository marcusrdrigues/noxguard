package com.marcusrdrigues.noxguard.output;

import com.marcusrdrigues.noxguard.internal.Text;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds links outside an allow list: the classic exfiltration route in RAG chats, where an injected
 * instruction makes the model write a link or image with data in the URL.
 *
 * <p>Only explicit links count ({@code http://}, {@code https://}, {@code www.}), so names like
 * ASP.NET or Node.js are not taken for addresses. A link ends at any Unicode whitespace, so a no-break
 * space can't join a foreign link to an allowed one. Trailing punctuation is dropped and the value is
 * lowercased. A link passes when an allowed pattern is found in the full value (host and path) or in
 * the host alone. Any Markdown image is reported, allowed or not.
 *
 * <pre>{@code
 * LinkPolicy links = LinkPolicy.allow(
 *         Pattern.compile("^([a-z0-9-]+\\.)*example\\.com$"),
 *         Pattern.compile("^(www\\.)?github\\.com/acme"));
 * if (!links.foreignLinks(answer).isEmpty()) replaceWithRefusal();
 * }</pre>
 *
 * <p>Run it on the full answer at the end of the stream; a client should render the answer as plain
 * text until then. Immutable and thread-safe.
 */
public final class LinkPolicy {

    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\(");
    // A link starts where no letter or digit comes before it ("_https://..." is still a link) and ends at
    // any whitespace JavaScript and Markdown renderers see, including the no-break space.
    private static final Pattern LINK = Pattern.compile(
            "(?<![A-Za-z0-9])(?:https?://|www\\.)[^" + Text.SPACE_CLASS + "<>\"'`)\\]]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCHEME = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:!?]+$");
    private static final Pattern HOST_END = Pattern.compile("[/?#]");

    private final List<Pattern> allowed;

    private LinkPolicy(List<Pattern> allowed) {
        this.allowed = allowed;
    }

    /**
     * Policy that accepts links matching any of these patterns. Patterns see lowercase text without
     * the scheme, such as {@code www.example.com/path}. With no pattern, every link is foreign.
     */
    public static LinkPolicy allow(Pattern... allowed) {
        Text.required(allowed, "allowed");
        return allow(List.of(allowed));
    }

    /** Same as {@link #allow(Pattern...)}, from a collection. */
    public static LinkPolicy allow(Collection<Pattern> allowed) {
        Text.required(allowed, "allowed");
        return new LinkPolicy(List.copyOf(allowed));
    }

    /**
     * Links in the text that the policy does not allow, in order of appearance (Markdown image first).
     *
     * @return an empty list when the text has no foreign link
     */
    public List<ForeignLink> foreignLinks(String text) {
        Text.required(text, "text");
        List<ForeignLink> found = new ArrayList<>();
        Matcher image = MARKDOWN_IMAGE.matcher(text);
        if (image.find()) {
            found.add(new ForeignLink(ForeignLink.Kind.MARKDOWN_IMAGE, image.group()));
        }
        Matcher link = LINK.matcher(text);
        while (link.find()) {
            String withoutScheme = SCHEME.matcher(link.group()).replaceFirst("");
            String value = TRAILING_PUNCTUATION.matcher(withoutScheme).replaceFirst("").toLowerCase(Locale.ROOT);
            String host = HOST_END.split(value, 2)[0];
            if (!isAllowed(value, host)) {
                found.add(new ForeignLink(ForeignLink.Kind.URL, value));
            }
        }
        return List.copyOf(found);
    }

    private boolean isAllowed(String value, String host) {
        for (Pattern pattern : allowed) {
            if (pattern.matcher(value).find() || pattern.matcher(host).find()) {
                return true;
            }
        }
        return false;
    }
}
