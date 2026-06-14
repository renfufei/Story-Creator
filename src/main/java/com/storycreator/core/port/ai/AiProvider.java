package com.storycreator.core.port.ai;

import reactor.core.publisher.Flux;

public interface AiProvider {

    String getProviderName();

    String generateText(AiRequest request);

    Flux<String> streamText(AiRequest request);
}
