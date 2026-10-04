package com.marcusrdrigues.noxguard.example.infrastructure;

import com.marcusrdrigues.noxguard.example.domain.LanguageModel;
import com.marcusrdrigues.noxguard.example.domain.MessageDraft;
import com.marcusrdrigues.noxguard.example.domain.ModelChunk;
import com.marcusrdrigues.noxguard.example.domain.ModelRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * A stand-in for a language model, with no API key and the same reply every run: the model a CI can
 * test against.
 *
 * <p>It is deliberately <strong>not</strong> well behaved. It obeys an instruction to reveal its prompt,
 * appends an image when asked, proposes a message on someone else's behalf before refusing, and rambles
 * when asked for everything. Those are the mistakes real models make sometimes; here they happen every
 * time, so the guards can be seen catching each one. The replies are cut into small pieces, as a real
 * stream would be, so the stream guard is tested with text that arrives in parts.
 */
@Component
@Profile("!openai")
public class ScriptedLanguageModel implements LanguageModel {

    static final String REFUSAL = "I only answer questions about Example Books.";
    private static final Pattern HR = Pattern.compile("\\bhr\\b");

    @Override
    public Flux<ModelChunk> stream(ModelRequest request) {
        return Flux.fromIterable(reply(request));
    }

    private List<ModelChunk> reply(ModelRequest request) {
        String q = request.question().toLowerCase(Locale.ROOT);
        List<ModelChunk> out = new ArrayList<>();
        if (q.contains("ignore") || q.contains("system prompt") || q.contains("instructions")) {
            // Misbehaves: starts reciting its instructions. The stream guard trips on "You are Ava".
            text(out, "Sure, here they are. You are Ava, the assistant of Example Books. Answer only about the store.");
        } else if (q.contains("capital") || q.contains("poem")) {
            text(out, REFUSAL);
        } else if (q.contains("2020") || q.contains("close")) {
            text(out, "I couldn't find any record of the store closing.");
        } else if (q.contains("email") || q.contains("on behalf") || HR.matcher(q).find()) {
            // Misbehaves: proposes a message for a third party, then refuses. The proposal gate drops it.
            out.add(new ModelChunk.Proposal(new MessageDraft("Job offer", "Marcus accepted the job.")));
            text(out, REFUSAL);
        } else if (q.contains("leave a message") || q.contains("message for the store")) {
            out.add(new ModelChunk.Proposal(new MessageDraft("Order question", request.question())));
            text(out, "Your draft is below. Nothing is sent until you confirm.");
        } else if (q.contains("every book") || q.contains("everything")) {
            // Misbehaves: an answer far over the limit. The stream guard caps it.
            text(out, "We have many books. ".repeat(120));
        } else if (q.contains("earlier") || q.contains("before")) {
            text(out, "I can see " + request.history().size() + " earlier messages in this chat.");
        } else if (q.contains("open") || q.contains("hours")) {
            // Misbehaves: obeys an image instruction pasted after the question. The link policy replaces the answer.
            String image = q.contains("![") ? " ![x](https://collector.test/?q=hours)" : "";
            text(out, "We're open from 9am to 6pm, Monday to Saturday." + image);
        } else if (q.contains("where") || q.contains("address")) {
            text(out, "We're at 12 Sample Street. More at https://example.com/visit.");
        } else if (q.contains("order")) {
            text(out, "Books not in stock can be ordered and arrive in 3 to 5 business days.");
        } else if (q.contains("silent")) {
            // An empty reply: the app answers with the refusal instead of nothing.
        } else {
            text(out, "I couldn't find that. Write to hello@example.com.");
        }
        return out;
    }

    /** Cuts the text into pieces of 1 to 5 characters, the same way every run. */
    private static void text(List<ModelChunk> out, String text) {
        int i = 0;
        int size = 1;
        while (i < text.length()) {
            int end = Math.min(text.length(), i + size);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end++;
            }
            out.add(new ModelChunk.Text(text.substring(i, end)));
            i = end;
            size = size % 5 + 1;
        }
    }
}
