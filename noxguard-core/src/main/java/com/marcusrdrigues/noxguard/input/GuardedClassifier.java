package com.marcusrdrigues.noxguard.input;

import com.marcusrdrigues.noxguard.input.ClassifierOutcome.Failure;
import com.marcusrdrigues.noxguard.internal.Text;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An {@link InputClassifier} with a timeout, the decoded views of the message, and an explicit answer
 * for when the classifier is down.
 *
 * <pre>{@code
 * GuardedClassifier guarded = GuardedClassifier.of(classifier)
 *         .timeout(Duration.ofMillis(800))
 *         .onFailure(FailureMode.FAIL_OPEN)   // required: no default
 *         .build();
 *
 * ClassifierOutcome outcome = guarded.classify(message);
 * }</pre>
 *
 * <p>The classifier reads the normalized message and each view {@link InputViews} decodes (base64,
 * ROT13, leetspeak). Any flagged view blocks, and the outcome carries the flagged verdict with the
 * highest score; otherwise it carries the highest score seen. One timeout covers all the views. The
 * classifier runs on a virtual thread, interrupted when the time is up.
 *
 * <p>Immutable and thread-safe when the classifier is.
 */
public final class GuardedClassifier {

    private final InputClassifier classifier;
    private final Duration timeout;
    private final FailureMode failureMode;
    private final InputViews views;

    private GuardedClassifier(Builder builder) {
        this.classifier = builder.classifier;
        this.timeout = builder.timeout;
        this.failureMode = builder.failureMode;
        this.views = builder.views;
    }

    /** Starts a guarded classifier around this one. */
    public static Builder of(InputClassifier classifier) {
        return new Builder(Text.required(classifier, "classifier"));
    }

    /** The time the classifier has for all the views of one message. */
    public Duration timeout() {
        return timeout;
    }

    /** What happens when the classifier times out or fails. */
    public FailureMode failureMode() {
        return failureMode;
    }

    /**
     * Classifies a message and its decoded views.
     *
     * @param message the user's message, as received
     * @return allowed, blocked, or unavailable (this last one only under {@link FailureMode#FAIL_OPEN})
     */
    public ClassifierOutcome classify(String message) {
        Text.required(message, "message");
        String clean = InputViews.normalize(message);
        List<String> texts = new ArrayList<>();
        texts.add(clean);
        texts.addAll(views.decode(clean));
        FutureTask<List<Verdict>> task = new FutureTask<>(() -> classifyAll(texts));
        Thread.ofVirtual().name("noxguard-classifier").start(task);
        try {
            return decide(task.get(timeout.toNanos(), TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            task.cancel(true);
            return failed(new Failure(Failure.Kind.TIMEOUT, "no answer in " + timeout.toMillis() + " ms"));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String detail = cause.getMessage() == null ? "" : ": " + cause.getMessage();
            return failed(new Failure(Failure.Kind.ERROR, cause.getClass().getSimpleName() + detail));
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return failed(new Failure(Failure.Kind.ERROR, "interrupted"));
        }
    }

    private List<Verdict> classifyAll(List<String> texts) throws Exception {
        List<Verdict> verdicts = new ArrayList<>(texts.size());
        for (String text : texts) {
            Verdict verdict = classifier.classify(text);
            if (verdict == null) {
                throw new IllegalStateException("the classifier returned null");
            }
            verdicts.add(verdict);
        }
        return verdicts;
    }

    /** Any flagged view blocks; the first view with the highest score speaks for the rest. */
    private static ClassifierOutcome decide(List<Verdict> verdicts) {
        int flagged = -1;
        int highest = 0;
        for (int i = 0; i < verdicts.size(); i++) {
            Verdict v = verdicts.get(i);
            if (v.flagged() && (flagged < 0 || v.score() > verdicts.get(flagged).score())) {
                flagged = i;
            }
            if (v.score() > verdicts.get(highest).score()) {
                highest = i;
            }
        }
        if (flagged >= 0) {
            return ClassifierOutcome.Blocked.flagged(verdicts.get(flagged), flagged > 0);
        }
        return new ClassifierOutcome.Allowed(verdicts.get(highest));
    }

    private ClassifierOutcome failed(Failure failure) {
        return failureMode == FailureMode.FAIL_OPEN
                ? new ClassifierOutcome.Unavailable(failure)
                : ClassifierOutcome.Blocked.failedClosed(failure);
    }

    /** Builds a {@link GuardedClassifier}. The timeout and the failure mode have no default. */
    public static final class Builder {
        private final InputClassifier classifier;
        private Duration timeout;
        private FailureMode failureMode;
        private InputViews views = InputViews.withDefaults();

        private Builder(InputClassifier classifier) {
            this.classifier = classifier;
        }

        /**
         * The time the classifier has for all the views of one message.
         *
         * @throws IllegalArgumentException when it is zero or negative
         */
        public Builder timeout(Duration timeout) {
            Text.required(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive, was " + timeout);
            }
            this.timeout = timeout;
            return this;
        }

        /** What happens when the classifier times out or fails. Required. */
        public Builder onFailure(FailureMode failureMode) {
            this.failureMode = Text.required(failureMode, "failureMode");
            return this;
        }

        /** The decoder for the views, when your users' languages need other common words. Defaults to Portuguese and English. */
        public Builder views(InputViews views) {
            this.views = Text.required(views, "views");
            return this;
        }

        /**
         * The guarded classifier.
         *
         * @throws IllegalStateException when the failure mode or the timeout was not chosen
         */
        public GuardedClassifier build() {
            if (failureMode == null) {
                throw new IllegalStateException(
                        "choose what happens when the classifier fails: onFailure(FailureMode.FAIL_OPEN) or onFailure(FailureMode.FAIL_CLOSED)");
            }
            if (timeout == null) {
                throw new IllegalStateException("choose how long the classifier may take: timeout(Duration)");
            }
            return new GuardedClassifier(this);
        }
    }
}
