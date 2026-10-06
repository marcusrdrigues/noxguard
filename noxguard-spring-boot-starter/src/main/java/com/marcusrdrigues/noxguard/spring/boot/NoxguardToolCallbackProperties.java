package com.marcusrdrigues.noxguard.spring.boot;

import com.marcusrdrigues.noxguard.springai.ConfirmMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code noxguard.tools.*} property for Spring AI tools, read only when {@code noxguard-spring-ai} is on the
 * class path.
 *
 * <pre>
 * noxguard:
 *   tools:
 *     on-confirm: hold
 * </pre>
 *
 * @param onConfirm What happens to a call to a tool declared with confirm: true: deny (it never runs; right
 *     without a screen to ask on) or hold (it waits for the person's confirmation). Required when such a tool is
 *     given to the model; there is no default.
 */
@ConfigurationProperties("noxguard.tools")
public record NoxguardToolCallbackProperties(ConfirmMode onConfirm) {}
