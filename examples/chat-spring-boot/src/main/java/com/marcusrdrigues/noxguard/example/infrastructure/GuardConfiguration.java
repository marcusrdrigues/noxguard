package com.marcusrdrigues.noxguard.example.infrastructure;

import com.marcusrdrigues.noxguard.data.DataEnvelope;
import com.marcusrdrigues.noxguard.example.application.AnswerQuestion;
import com.marcusrdrigues.noxguard.example.application.ChatPolicy;
import com.marcusrdrigues.noxguard.example.domain.KnowledgeBase;
import com.marcusrdrigues.noxguard.example.domain.LanguageModel;
import com.marcusrdrigues.noxguard.history.HistorySigner;
import com.marcusrdrigues.noxguard.output.LinkPolicy;
import com.marcusrdrigues.noxguard.output.StreamGuard;
import com.marcusrdrigues.noxguard.reactor.ReactorGuard;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the guards. The configuration objects are immutable and shared; the per-answer guards
 * ({@code StreamGuard}, {@code ProposalGate}, {@code ToolBudget}) are created for each answer.
 */
@Configuration
public class GuardConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GuardConfiguration.class);

    @Bean
    public ChatPolicy chatPolicy() {
        return ChatPolicy.exampleBooks();
    }

    @Bean
    public LinkPolicy linkPolicy() {
        return LinkPolicy.allow(Pattern.compile("^([a-z0-9-]+\\.)*example\\.com$"));
    }

    @Bean
    public DataEnvelope dataEnvelope() {
        return DataEnvelope.withReservedTags(List.of("context", "question", "history"));
    }

    @Bean
    public HistorySigner historySigner(@Value("${noxguard.example.history-secret:}") String secret) {
        if (secret.getBytes(StandardCharsets.UTF_8).length >= 32) {
            return HistorySigner.hmacSha256(secret);
        }
        log.warn("NOXGUARD_HISTORY_SECRET is not set (or under 32 bytes): using a random secret; signed history will not survive a restart.");
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return HistorySigner.hmacSha256(random);
    }

    @Bean
    public ReactorGuard reactorGuard(ChatPolicy policy, LinkPolicy links) {
        return ReactorGuard.builder()
                .streamGuard(() -> StreamGuard.builder().leakMarkers(policy.leakMarkers()).maxChars(policy.maxAnswerChars()).build())
                .links(links)
                .refusal(policy.refusal())
                .build();
    }

    @Bean
    public AnswerQuestion answerQuestion(ChatPolicy policy, KnowledgeBase knowledge, LanguageModel model, ReactorGuard guard, DataEnvelope envelope, HistorySigner signer) {
        return new AnswerQuestion(policy, knowledge, model, guard, envelope, signer);
    }
}
