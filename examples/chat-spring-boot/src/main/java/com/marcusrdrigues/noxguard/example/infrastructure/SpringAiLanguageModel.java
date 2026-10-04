package com.marcusrdrigues.noxguard.example.infrastructure;

import com.marcusrdrigues.noxguard.example.domain.LanguageModel;
import com.marcusrdrigues.noxguard.example.domain.ModelChunk;
import com.marcusrdrigues.noxguard.example.domain.ModelRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * A real model through Spring AI (profile "openai"). It streams text only: tool calling through Spring
 * AI is left out of this example, so the proposal gate is shown with the scripted model.
 */
@Component
@Profile("openai")
public class SpringAiLanguageModel implements LanguageModel {

    private final ChatClient client;

    public SpringAiLanguageModel(ChatClient.Builder builder) {
        this.client = builder.build();
    }

    @Override
    public Flux<ModelChunk> stream(ModelRequest request) {
        return client.prompt()
                .system(request.system())
                .user(request.user())
                .stream()
                .content()
                .map(ModelChunk.Text::new);
    }
}
