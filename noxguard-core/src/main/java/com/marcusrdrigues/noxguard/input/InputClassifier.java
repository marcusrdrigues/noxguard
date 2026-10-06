package com.marcusrdrigues.noxguard.input;

/**
 * A classifier that reads one text and says whether it is an attack: usually a call to a hosted model.
 *
 * <p>Implement it with your own client; wrap it in a {@link GuardedClassifier} to get a timeout, the
 * decoded views of the message and an explicit answer for when the classifier is down.
 *
 * <pre>{@code
 * InputClassifier classifier = text -> {
 *     double score = client.injectionScore(text);   // your HTTP call
 *     return score >= 0.9 ? Verdict.flagged(score, "injection") : Verdict.clean(score);
 * };
 * }</pre>
 */
@FunctionalInterface
public interface InputClassifier {

    /**
     * Classifies one text: the message or one of its decoded views.
     *
     * @param text the text to read, never null
     * @return the verdict, never null
     * @throws Exception when the classifier can't answer; the {@link GuardedClassifier} turns it into a failure
     */
    Verdict classify(String text) throws Exception;
}
