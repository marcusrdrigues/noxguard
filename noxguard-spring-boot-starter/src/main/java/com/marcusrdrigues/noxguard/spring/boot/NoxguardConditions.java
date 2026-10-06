package com.marcusrdrigues.noxguard.spring.boot;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Conditions on {@code noxguard.*} properties that are lists or need more than one property, which
 * {@code @ConditionalOnProperty} cannot express.
 */
final class NoxguardConditions {

    private NoxguardConditions() {}

    private static boolean hasStrings(ConditionContext context, String name) {
        return Binder.get(context.getEnvironment())
                .bind(name, Bindable.listOf(String.class))
                .map(list -> list.stream().anyMatch(s -> s != null && !s.isBlank()))
                .orElse(false);
    }

    /** Whether any property sits under this name, such as {@code noxguard.tools.allow[0].name}. */
    private static boolean hasEntries(ConditionContext context, String name) {
        ConfigurationPropertyName prefix = ConfigurationPropertyName.of(name);
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(context.getEnvironment())) {
            if (source.containsDescendantOf(prefix) == ConfigurationPropertyState.PRESENT) {
                return true;
            }
        }
        return false;
    }

    private static ConditionOutcome outcome(boolean match, String what) {
        return match ? ConditionOutcome.match(what + " is set") : ConditionOutcome.noMatch(what + " is not set");
    }

    /** {@code noxguard.stream.leak-markers} has at least one marker. */
    static final class OnLeakMarkers extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return outcome(hasStrings(context, "noxguard.stream.leak-markers"), "noxguard.stream.leak-markers");
        }
    }

    /** {@code noxguard.data.reserved-tags} has at least one tag. */
    static final class OnReservedTags extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return outcome(hasStrings(context, "noxguard.data.reserved-tags"), "noxguard.data.reserved-tags");
        }
    }

    /** {@code noxguard.tools.allow} declares at least one tool. */
    static final class OnTools extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return outcome(hasEntries(context, "noxguard.tools.allow"), "noxguard.tools.allow");
        }
    }

    /** {@code noxguard.citations.allow-names} has at least one name. */
    static final class OnCitationNames extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return outcome(hasStrings(context, "noxguard.citations.allow-names"), "noxguard.citations.allow-names");
        }
    }

    /** {@code noxguard.history.secret} is set, or a random secret was asked for development. */
    static final class OnHistory extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Binder binder = Binder.get(context.getEnvironment());
            boolean secret = binder.bind("noxguard.history.secret", String.class).map(s -> !s.isBlank()).orElse(false);
            boolean random = binder.bind("noxguard.history.random-secret-for-development", Boolean.class).orElse(false);
            return outcome(secret || random, "noxguard.history.secret (or random-secret-for-development)");
        }
    }
}
