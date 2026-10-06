package com.marcusrdrigues.noxguard.input;

import com.marcusrdrigues.noxguard.internal.Text;
import java.util.Optional;

/**
 * What a {@link GuardedClassifier} decided about a message: allowed, blocked, or passed while the
 * classifier was down.
 *
 * <pre>{@code
 * switch (guarded.classify(message)) {
 *     case ClassifierOutcome.Allowed a -> answer(message);
 *     case ClassifierOutcome.Blocked b -> refuse();
 *     case ClassifierOutcome.Unavailable u -> { metrics.count("classifier.down"); answer(message); }
 * }
 * }</pre>
 */
public sealed interface ClassifierOutcome {

    /** Whether the message may go on to the model. */
    boolean passes();

    /**
     * The classifier answered and no view was flagged.
     *
     * @param verdict the verdict with the highest score among the views
     */
    record Allowed(Verdict verdict) implements ClassifierOutcome {
        /** An allowed outcome. */
        public Allowed {
            Text.required(verdict, "verdict");
            if (verdict.flagged()) {
                throw new IllegalArgumentException("an allowed outcome cannot carry a flagged verdict");
            }
        }

        @Override
        public boolean passes() {
            return true;
        }
    }

    /**
     * The message is blocked: a view was flagged, or the classifier failed under {@link FailureMode#FAIL_CLOSED}.
     * Exactly one of {@code verdict} and {@code failure} is present.
     *
     * @param verdict the flagged verdict with the highest score, when the classifier answered
     * @param decoded whether that verdict came from a decoded view (base64, ROT13, leetspeak) and not the message itself
     * @param failure why the classifier didn't answer, when the block comes from failing closed
     */
    record Blocked(Optional<Verdict> verdict, boolean decoded, Optional<Failure> failure) implements ClassifierOutcome {
        /** A blocked outcome. */
        public Blocked {
            Text.required(verdict, "verdict");
            Text.required(failure, "failure");
            if (verdict.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException("a blocked outcome has either a verdict or a failure");
            }
            if (verdict.isPresent() && !verdict.get().flagged()) {
                throw new IllegalArgumentException("a blocked outcome needs a flagged verdict");
            }
            if (failure.isPresent() && decoded) {
                throw new IllegalArgumentException("a block from a failure has no view");
            }
        }

        /** Blocked because a view was flagged. */
        public static Blocked flagged(Verdict verdict, boolean decoded) {
            return new Blocked(Optional.of(verdict), decoded, Optional.empty());
        }

        /** Blocked because the classifier failed and the app fails closed. */
        public static Blocked failedClosed(Failure failure) {
            return new Blocked(Optional.empty(), false, Optional.of(failure));
        }

        @Override
        public boolean passes() {
            return false;
        }
    }

    /**
     * The classifier failed and the app fails open: the message passes, and this case lets you count it.
     *
     * @param failure why the classifier didn't answer
     */
    record Unavailable(Failure failure) implements ClassifierOutcome {
        /** An unavailable outcome. */
        public Unavailable {
            Text.required(failure, "failure");
        }

        @Override
        public boolean passes() {
            return true;
        }
    }

    /**
     * Why the classifier didn't answer.
     *
     * @param kind a timeout or an error
     * @param message a short description for the log, such as {@code "no answer in 800 ms"}
     */
    record Failure(Kind kind, String message) {
        /** A failure. */
        public Failure {
            Text.required(kind, "kind");
            Text.required(message, "message");
        }

        /** How the classifier failed. */
        public enum Kind {
            /** No answer within the timeout. */
            TIMEOUT,
            /** It threw, returned null, or was interrupted. */
            ERROR
        }
    }
}
