package com.marcusrdrigues.noxguard.output;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LinkPolicyTest {

    /** Nox's allow list: the site, its LinkedIn and GitHub profiles, and WhatsApp links. */
    private static final LinkPolicy NOX = LinkPolicy.allow(
            Pattern.compile("^([a-z0-9-]+\\.)*marcusrdrigues\\.com$"),
            Pattern.compile("^(www\\.)?linkedin\\.com/in/marcusrdrigues"),
            Pattern.compile("^(www\\.)?github\\.com/marcusrdrigues"),
            Pattern.compile("^wa\\.me/"));

    private static List<String> values(String text) {
        return NOX.foreignLinks(text).stream().map(ForeignLink::value).toList();
    }

    @Test
    @DisplayName("allowed links and technology names are not reported")
    void allowedLinksAndNamesPass() {
        assertEquals(List.of(), values("Veja https://marcusrdrigues.com/pt e github.com/marcusrdrigues. Usa ASP.NET e Node.js."));
        assertEquals(List.of(), values("LinkedIn: https://www.linkedin.com/in/marcusrdrigues/ e https://wa.me/55"));
    }

    @Test
    @DisplayName("a foreign link is reported without scheme and trailing punctuation")
    void foreignLinkIsReported() {
        assertEquals(List.of("evil.example/x?q=segredo"), values("clique em https://evil.example/x?q=segredo."));
        assertEquals(List.of("www.evil.com"), values("www.evil.com"));
        assertEquals(List.of("evil.example/up"), values("HTTPS://EVIL.EXAMPLE/UP!"));
    }

    @Test
    @DisplayName("a Markdown image is reported, and so is its address when foreign")
    void markdownImageIsReported() {
        List<ForeignLink> found = NOX.foreignLinks("![img](https://evil.example/p.png)");
        assertEquals(2, found.size());
        assertEquals(ForeignLink.Kind.MARKDOWN_IMAGE, found.get(0).kind());
        assertEquals(new ForeignLink(ForeignLink.Kind.URL, "evil.example/p.png"), found.get(1));
        assertEquals(1, NOX.foreignLinks("![logo](https://marcusrdrigues.com/logo.png)").size(), "an image is reported even when allowed");
    }

    @Test
    @DisplayName("a no-break or other Unicode space can't join a foreign link to an allowed one")
    void unicodeSpacesEndALink() {
        for (String space : List.of("\u00A0", "\u2028", "\u3000", "\uFEFF", "\u202F")) {
            assertEquals(List.of("evil.com/steal?d=secret"),
                    values("https://marcusrdrigues.com/" + space + "https://evil.com/steal?d=SECRET"), "space U+" + Integer.toHexString(space.charAt(0)));
        }
    }

    @Test
    @DisplayName("a link right after an underscore or other punctuation is still a link")
    void linkAfterPunctuation() {
        assertEquals(List.of("evil.com/x_"), values("_https://evil.com/x_"));
        assertEquals(List.of(), values("xhttps://evil.com"), "glued to a word, it is not a link");
    }

    @Test
    @DisplayName("with no allowed pattern, every link is foreign")
    void emptyPolicyRejectsAll() {
        assertEquals(1, LinkPolicy.allow().foreignLinks("https://marcusrdrigues.com").size());
    }
}
