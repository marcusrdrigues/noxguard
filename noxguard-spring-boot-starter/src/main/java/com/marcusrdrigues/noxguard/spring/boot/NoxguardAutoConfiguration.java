package com.marcusrdrigues.noxguard.spring.boot;

import com.marcusrdrigues.noxguard.agent.ArgRule;
import com.marcusrdrigues.noxguard.agent.ToolPolicy;
import com.marcusrdrigues.noxguard.data.DataEnvelope;
import com.marcusrdrigues.noxguard.history.HistorySigner;
import com.marcusrdrigues.noxguard.output.LinkPolicy;
import com.marcusrdrigues.noxguard.output.StreamGuard;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * The noxguard beans, from {@link NoxguardProperties}. Each one backs off when the app defines its own
 * bean of the same type.
 *
 * <ul>
 *   <li>{@link LinkPolicy}: always, from {@code noxguard.links.allow} (empty: no link allowed);
 *   <li>{@link DataEnvelope}: when {@code noxguard.data.reserved-tags} is set;
 *   <li>{@link HistorySigner}: when {@code noxguard.history.secret} is set (or
 *       {@code random-secret-for-development}, with a warning);
 *   <li>{@link StreamGuards}: when {@code noxguard.stream.leak-markers} is set;
 *   <li>{@link ToolPolicy}: when {@code noxguard.tools.allow} declares a tool.
 * </ul>
 *
 * <p>Unsafe or meaningless settings stop the app at startup, with the property named: a history secret
 * under 32 bytes, an invalid regular expression, leak markers without {@code max-chars}, tools without
 * {@code noxguard.tools.max-calls}, a tool with {@code max-calls: 0}.
 */
@AutoConfiguration
@EnableConfigurationProperties(NoxguardProperties.class)
public class NoxguardAutoConfiguration {

    private static final System.Logger LOG = System.getLogger(NoxguardAutoConfiguration.class.getName());
    private static final int MIN_SECRET_BYTES = 32;

    /** Created by Spring Boot. */
    public NoxguardAutoConfiguration() {}

    /** The links an answer may contain; with no pattern, every link is foreign. */
    @Bean
    @ConditionalOnMissingBean
    public LinkPolicy noxguardLinkPolicy(NoxguardProperties properties) {
        List<String> allow = properties.links().allow();
        List<Pattern> patterns = new ArrayList<>();
        for (int i = 0; i < allow.size(); i++) {
            patterns.add(compile(allow.get(i), "noxguard.links.allow[" + i + "]"));
        }
        return LinkPolicy.allow(patterns);
    }

    /** Delimits data in the prompt with the reserved tags. */
    @Bean
    @ConditionalOnMissingBean
    @Conditional(NoxguardConditions.OnReservedTags.class)
    public DataEnvelope noxguardDataEnvelope(NoxguardProperties properties) {
        try {
            return DataEnvelope.withReservedTags(properties.data().reservedTags());
        } catch (IllegalArgumentException e) {
            throw invalid("noxguard.data.reserved-tags:", e.getMessage(), e);
        }
    }

    /** Signs answers, so forged assistant turns leave the history. */
    @Bean
    @ConditionalOnMissingBean
    @Conditional(NoxguardConditions.OnHistory.class)
    public HistorySigner noxguardHistorySigner(NoxguardProperties properties) {
        NoxguardProperties.History history = properties.history();
        String secret = history.secret();
        if (secret != null && !secret.isBlank()) {
            int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
            if (bytes < MIN_SECRET_BYTES) {
                throw invalid("noxguard.history.secret", "must have at least " + MIN_SECRET_BYTES + " bytes, got " + bytes
                        + " (a short secret can be guessed, and every signed turn with it)", null);
            }
            return HistorySigner.hmacSha256(secret);
        }
        LOG.log(System.Logger.Level.WARNING, "noxguard.history.random-secret-for-development is on: using a random history secret. "
                + "Signed history will not survive a restart or reach another instance; set noxguard.history.secret in production.");
        byte[] random = new byte[MIN_SECRET_BYTES];
        new SecureRandom().nextBytes(random);
        return HistorySigner.hmacSha256(random);
    }

    /** One new stream guard per answer, with the leak markers and the length limit. */
    @Bean
    @ConditionalOnMissingBean
    @Conditional(NoxguardConditions.OnLeakMarkers.class)
    public StreamGuards noxguardStreamGuards(NoxguardProperties properties) {
        NoxguardProperties.Stream stream = properties.stream();
        if (stream.maxChars() == null) {
            throw invalid("noxguard.stream.max-chars", "is required with noxguard.stream.leak-markers (the longest answer shown)", null);
        }
        List<String> markers = stream.leakMarkers();
        int maxChars = stream.maxChars();
        StreamGuards guards = () -> StreamGuard.builder().leakMarkers(markers).maxChars(maxChars).build();
        try {
            guards.get(); // validates now, so a bad setting stops the app instead of the first answer
        } catch (IllegalArgumentException e) {
            throw invalid("noxguard.stream:", e.getMessage(), e);
        }
        return guards;
    }

    /** The tools an agent may call, deny by default. */
    @Bean
    @ConditionalOnMissingBean
    @Conditional(NoxguardConditions.OnTools.class)
    public ToolPolicy noxguardToolPolicy(NoxguardProperties properties) {
        NoxguardProperties.Tools tools = properties.tools();
        if (tools.maxCalls() == null) {
            throw invalid("noxguard.tools.max-calls", "is required when tools are declared, so an agent loop always ends", null);
        }
        ToolPolicy.Builder builder = ToolPolicy.builder();
        try {
            builder.maxCalls(tools.maxCalls());
        } catch (IllegalArgumentException e) {
            throw invalid("noxguard.tools.max-calls:", e.getMessage(), e);
        }
        for (int i = 0; i < tools.allow().size(); i++) {
            NoxguardProperties.Tool tool = tools.allow().get(i);
            String path = "noxguard.tools.allow[" + i + "]";
            if (tool.name() == null || tool.name().isBlank()) {
                throw invalid(path + ".name", "is required", null);
            }
            List<ArgRuleSpec> args = new ArrayList<>();
            for (int j = 0; j < tool.args().size(); j++) {
                NoxguardProperties.Arg arg = tool.args().get(j);
                String argPath = path + ".args[" + j + "]";
                if (arg.name() == null || arg.name().isBlank()) {
                    throw invalid(argPath + ".name", "is required", null);
                }
                String pattern = arg.pattern();
                if (pattern == null || pattern.isEmpty()) {
                    args.add(new ArgRuleSpec(arg.name(), null));
                } else {
                    compile(pattern, argPath + ".pattern");
                    args.add(new ArgRuleSpec(arg.name(), ArgRule.matches(pattern)));
                }
            }
            try {
                builder.tool(tool.name(), t -> {
                    for (ArgRuleSpec arg : args) {
                        if (arg.rule() == null) {
                            t.arg(arg.name());
                        } else {
                            t.arg(arg.name(), arg.rule());
                        }
                    }
                    t.logArgs(tool.logArgs().toArray(String[]::new));
                    if (tool.confirm()) {
                        t.confirm();
                    }
                    if (tool.maxCalls() != null) {
                        t.maxCalls(tool.maxCalls());
                    }
                });
            } catch (IllegalArgumentException e) {
                throw invalid(path + " (" + tool.name() + "):", e.getMessage(), e);
            }
        }
        return builder.build();
    }

    private record ArgRuleSpec(String name, ArgRule rule) {}

    private static Pattern compile(String regex, String property) {
        if (regex == null) {
            throw invalid(property, "must not be empty", null);
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw invalid(property, "is not a valid regular expression: " + e.getDescription(), e);
        }
    }

    private static IllegalStateException invalid(String property, String problem, Exception cause) {
        return new IllegalStateException(property + " " + problem, cause);
    }
}
