package com.marcusrdrigues.noxguard.example.infrastructure;

import com.marcusrdrigues.noxguard.agent.ToolPolicy;
import com.marcusrdrigues.noxguard.data.DataEnvelope;
import com.marcusrdrigues.noxguard.example.application.AnswerQuestion;
import com.marcusrdrigues.noxguard.example.application.ChatPolicy;
import com.marcusrdrigues.noxguard.example.domain.KnowledgeBase;
import com.marcusrdrigues.noxguard.example.domain.LanguageModel;
import com.marcusrdrigues.noxguard.example.domain.StoreTools;
import com.marcusrdrigues.noxguard.history.HistorySigner;
import com.marcusrdrigues.noxguard.reactor.ReactorGuard;
import com.marcusrdrigues.noxguard.spring.boot.NoxguardProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the use case. The guards themselves come from noxguard-spring-boot-starter, built from
 * {@code noxguard.*} in {@code application.yml}; only what is really this app's stays here.
 */
@Configuration
public class GuardConfiguration {

    @Bean
    public ChatPolicy chatPolicy(NoxguardProperties noxguard) {
        return ChatPolicy.exampleBooks(noxguard.refusal());
    }

    @Bean
    public AnswerQuestion answerQuestion(ChatPolicy policy, KnowledgeBase knowledge, LanguageModel model, ReactorGuard guard,
            DataEnvelope envelope, HistorySigner signer, ToolPolicy tools, StoreTools storeTools) {
        return new AnswerQuestion(policy, knowledge, model, guard, envelope, signer, tools, storeTools);
    }
}
