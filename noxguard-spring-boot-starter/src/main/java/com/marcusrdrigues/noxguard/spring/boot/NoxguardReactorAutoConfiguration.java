package com.marcusrdrigues.noxguard.spring.boot;

import com.marcusrdrigues.noxguard.output.LinkPolicy;
import com.marcusrdrigues.noxguard.reactor.ReactorGuard;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * A {@link ReactorGuard} for {@code Flux<String>} model streams, when {@code noxguard-reactor} is on the
 * class path, a {@link StreamGuards} bean exists and {@code noxguard.refusal} is set.
 */
@AutoConfiguration(after = NoxguardAutoConfiguration.class)
@ConditionalOnClass(ReactorGuard.class)
public class NoxguardReactorAutoConfiguration {

    /** Created by Spring Boot. */
    public NoxguardReactorAutoConfiguration() {}

    /** Guards a model stream: leaks, length and foreign links, with the refusal as the replacement. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({StreamGuards.class, LinkPolicy.class})
    @ConditionalOnProperty("noxguard.refusal")
    public ReactorGuard noxguardReactorGuard(StreamGuards streamGuards, LinkPolicy links, NoxguardProperties properties) {
        try {
            return ReactorGuard.builder().streamGuard(streamGuards).links(links).refusal(properties.refusal()).build();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("noxguard.refusal: " + e.getMessage(), e);
        }
    }
}
