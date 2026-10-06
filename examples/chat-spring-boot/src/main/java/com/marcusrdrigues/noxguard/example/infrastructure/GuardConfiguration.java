package com.marcusrdrigues.noxguard.example.infrastructure;

import com.marcusrdrigues.noxguard.agent.ToolPolicy;
import com.marcusrdrigues.noxguard.data.DataEnvelope;
import com.marcusrdrigues.noxguard.example.application.AnswerQuestion;
import com.marcusrdrigues.noxguard.example.application.ChatPolicy;
import com.marcusrdrigues.noxguard.example.domain.KnowledgeBase;
import com.marcusrdrigues.noxguard.example.domain.LanguageModel;
import com.marcusrdrigues.noxguard.example.domain.StoreTools;
import com.marcusrdrigues.noxguard.grounding.CitationGuard;
import com.marcusrdrigues.noxguard.history.HistorySigner;
import com.marcusrdrigues.noxguard.output.LinkPolicy;
import com.marcusrdrigues.noxguard.reactor.ReactorGuard;
import com.marcusrdrigues.noxguard.spring.boot.NoxguardProperties;
import com.marcusrdrigues.noxguard.spring.boot.StreamGuards;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the use case. The guards themselves come from noxguard-spring-boot-starter, built from
 * {@code noxguard.*} in {@code application.yml}; only what is really this app's stays here. The one
 * guard built here is the ReactorGuard, because it adds the citation check, which needs the app's
 * "not confirmed" text; the starter's own ReactorGuard then backs off.
 */
@Configuration
public class GuardConfiguration {

    @Bean
    public ChatPolicy chatPolicy(NoxguardProperties noxguard) {
        return ChatPolicy.exampleBooks(noxguard.refusal());
    }

    @Bean
    public ReactorGuard reactorGuard(StreamGuards streamGuards, LinkPolicy links, CitationGuard citations, ChatPolicy policy) {
        return ReactorGuard.builder()
                .streamGuard(streamGuards)
                .links(links)
                .refusal(policy.refusal())
                .citations(citations, policy.notConfirmed())
                .build();
    }

    @Bean
    public AnswerQuestion answerQuestion(ChatPolicy policy, KnowledgeBase knowledge, LanguageModel model, ReactorGuard guard,
            DataEnvelope envelope, HistorySigner signer, ToolPolicy tools, StoreTools storeTools) {
        return new AnswerQuestion(policy, knowledge, model, guard, envelope, signer, tools, storeTools);
    }
}
