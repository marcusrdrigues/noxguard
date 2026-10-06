package com.marcusrdrigues.noxguard.spring.boot;

import com.marcusrdrigues.noxguard.agent.ToolPolicy;
import com.marcusrdrigues.noxguard.springai.GuardedToolCallbacks;
import com.marcusrdrigues.noxguard.springai.ToolDecisionListener;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@link GuardedToolCallbacks} for Spring AI, when {@code noxguard-spring-ai} is on the class path, a
 * {@link ToolPolicy} bean exists and the app defines {@link ToolCallback} or {@link ToolCallbackProvider} beans.
 *
 * <p>Every one of those tools must be declared in the policy, or the app does not start: a tool never reaches the
 * model without a rule. A tool declared with {@code confirm: true} needs {@code noxguard.tools.on-confirm}. An app
 * {@link ToolDecisionListener} bean, if any, hears every decision.
 */
@AutoConfiguration(after = NoxguardAutoConfiguration.class)
@ConditionalOnClass({GuardedToolCallbacks.class, ToolCallback.class})
@EnableConfigurationProperties(NoxguardToolCallbackProperties.class)
public class NoxguardSpringAiAutoConfiguration {

    /** Created by Spring Boot. */
    public NoxguardSpringAiAutoConfiguration() {}

    /** The app's tools under the policy; call {@code forNewAnswer()} on every request. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ToolPolicy.class)
    @Conditional(OnToolCallbacks.class)
    public GuardedToolCallbacks noxguardGuardedToolCallbacks(ToolPolicy policy, ObjectProvider<ToolCallback> callbacks,
            ObjectProvider<ToolCallbackProvider> providers, ObjectProvider<ToolDecisionListener> listener,
            NoxguardToolCallbackProperties properties) {
        List<ToolCallback> tools = new ArrayList<>(callbacks.orderedStream().toList());
        providers.orderedStream().forEach(provider -> tools.addAll(List.of(provider.getToolCallbacks())));
        List<String> confirming = tools.stream()
                .map(tool -> tool.getToolDefinition().name())
                .filter(policy::requiresConfirmation)
                .toList();
        if (!confirming.isEmpty() && properties.onConfirm() == null) {
            throw new IllegalStateException("noxguard.tools.on-confirm is required: " + String.join(", ", confirming)
                    + " needs the person's confirmation; set deny (it never runs) or hold (it waits for the person)");
        }
        GuardedToolCallbacks.Builder builder = GuardedToolCallbacks.builder(policy).tools(tools);
        if (properties.onConfirm() != null) {
            builder.onConfirm(properties.onConfirm());
        }
        listener.ifAvailable(builder::listener);
        try {
            return builder.build();
        } catch (IllegalStateException e) {
            throw new IllegalStateException("noxguard.tools: " + e.getMessage(), e);
        }
    }

    /** The app defines at least one {@link ToolCallback} or {@link ToolCallbackProvider} bean. */
    static final class OnToolCallbacks extends SpringBootCondition implements ConfigurationCondition {

        @Override
        public ConfigurationPhase getConfigurationPhase() {
            return ConfigurationPhase.REGISTER_BEAN;
        }

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            ConfigurableListableBeanFactory beans = context.getBeanFactory();
            boolean any = beans != null
                    && (beans.getBeanNamesForType(ToolCallback.class, true, false).length > 0
                            || beans.getBeanNamesForType(ToolCallbackProvider.class, true, false).length > 0);
            return any ? ConditionOutcome.match("Spring AI tool beans found") : ConditionOutcome.noMatch("no Spring AI tool beans");
        }
    }
}
