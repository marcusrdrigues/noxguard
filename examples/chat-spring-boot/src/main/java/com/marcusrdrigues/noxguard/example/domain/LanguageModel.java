package com.marcusrdrigues.noxguard.example.domain;

import reactor.core.publisher.Flux;

/** Port: a language model that streams its reply. */
public interface LanguageModel {

    /** The reply as it is generated. Cancelling the subscription stops the generation. */
    Flux<ModelChunk> stream(ModelRequest request);
}
