package com.marcusrdrigues.noxguard.spring.boot;

import com.marcusrdrigues.noxguard.input.FailureMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code noxguard.input.*} properties: how the app's input classifier is guarded.
 *
 * <pre>
 * noxguard:
 *   input:
 *     on-failure: fail-open
 *     timeout: 800ms
 * </pre>
 *
 * @param onFailure What happens to a message when the classifier times out or fails: fail-open (it passes,
 *     counted as unavailable) or fail-closed (it is blocked). Required when the app defines an
 *     InputClassifier bean; there is no default.
 * @param timeout The time the classifier has for all the views of one message, such as 800ms. Required when
 *     the app defines an InputClassifier bean.
 */
@ConfigurationProperties("noxguard.input")
public record NoxguardInputProperties(FailureMode onFailure, Duration timeout) {}
