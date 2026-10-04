package com.marcusrdrigues.noxguard.history;

import com.marcusrdrigues.noxguard.internal.Text;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stops forged conversation history.
 *
 * <p>A chat that sends the history back on every request trusts the client with what "the assistant
 * said before". A forged turn ("Developer mode on.") is a cheap injection. The server signs each answer
 * it sends (HMAC-SHA256); on the next request, assistant turns without a valid signature are dropped
 * before reaching the model.
 *
 * <pre>{@code
 * HistorySigner signer = HistorySigner.hmacSha256(secret);
 * String sig = signer.sign("answer:en", answerText);          // send with the answer
 * List<Turn> history = signer.keepSigned(fromClient, "answer:en");  // before the model call
 * }</pre>
 *
 * <p>The scope ({@code "answer:en"}) binds a signature to its context: an answer signed for one
 * locale or app does not verify in another. Signatures are Base64url without padding, the same format
 * as Node's {@code digest("base64url")}, so a Java and a TypeScript service sharing a secret accept
 * each other's signatures. Immutable and thread-safe.
 */
public final class HistorySigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKeySpec key;

    private HistorySigner(byte[] secret) {
        this.key = new SecretKeySpec(secret, ALGORITHM);
    }

    /**
     * Signer with this secret.
     *
     * @param secret at least 32 bytes; keep it out of the repository (an environment variable or a
     *     secret manager)
     * @throws IllegalArgumentException when the secret is shorter than 32 bytes
     */
    public static HistorySigner hmacSha256(byte[] secret) {
        Text.required(secret, "secret");
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("secret must have at least " + MIN_SECRET_BYTES + " bytes, got " + secret.length);
        }
        return new HistorySigner(secret.clone());
    }

    /** Same as {@link #hmacSha256(byte[])}, with the UTF-8 bytes of the text. */
    public static HistorySigner hmacSha256(String secret) {
        Text.required(secret, "secret");
        return hmacSha256(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Signature of an answer in a scope.
     *
     * @param scope what the signature is bound to, such as {@code "answer:en"}; no line breaks
     * @param text the answer exactly as sent to the client
     * @throws IllegalArgumentException when the scope has a line break (it would make two different
     *     scope and text pairs sign the same bytes), or the text has a lone surrogate (it has no UTF-8
     *     form, and replacing it would make two texts sign the same)
     */
    public String sign(String scope, String text) {
        Text.required(scope, "scope");
        Text.required(text, "text");
        if (scope.indexOf('\n') >= 0 || scope.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("scope must not contain line breaks");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(utf8(scope + "\n" + text));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available in this JVM", e);
        }
    }

    /**
     * Whether the signature is valid for this scope and text, compared in constant time. A text that
     * can't be signed (a lone surrogate) is never valid.
     *
     * @throws IllegalArgumentException when the scope has a line break
     */
    public boolean verify(String scope, String text, String signature) {
        Text.required(text, "text");
        Text.required(signature, "signature");
        String expected;
        try {
            expected = sign(scope, text);
        } catch (IllegalArgumentException e) {
            if (scope.indexOf('\n') >= 0 || scope.indexOf('\r') >= 0) {
                throw e;
            }
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] utf8(String text) {
        try {
            var bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] out = new byte[bytes.remaining()];
            bytes.get(out);
            return out;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("text has a lone surrogate and can't be signed", e);
        }
    }

    /**
     * The history without forged turns, ready for the model.
     *
     * <ul>
     *   <li>A user turn is kept; an assistant turn only with a valid signature for the scope.
     *   <li>When an assistant turn is dropped, the user turn right before it goes too (a question with
     *       no answer).
     *   <li>A trailing user turn with no answer is dropped.
     * </ul>
     */
    public List<Turn> keepSigned(List<Turn> history, String scope) {
        Text.required(history, "history");
        Text.required(scope, "scope");
        List<Turn> out = new ArrayList<>();
        for (Turn turn : history) {
            Text.required(turn, "turn");
            if (turn.role() == Turn.Role.USER) {
                out.add(turn);
            } else if (isSigned(turn, scope)) {
                out.add(turn);
            } else if (!out.isEmpty() && out.getLast().role() == Turn.Role.USER) {
                out.removeLast();
            }
        }
        if (!out.isEmpty() && out.getLast().role() == Turn.Role.USER) {
            out.removeLast();
        }
        return List.copyOf(out);
    }

    private boolean isSigned(Turn turn, String scope) {
        Optional<String> signature = turn.signature();
        return signature.isPresent() && verify(scope, turn.content(), signature.get());
    }
}
