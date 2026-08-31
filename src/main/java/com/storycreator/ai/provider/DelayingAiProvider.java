package com.storycreator.ai.provider;

import com.storycreator.core.port.ai.AiProvider;
import com.storycreator.core.port.ai.AiRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Wraps an AiProvider to sleep a configurable number of seconds before each
 * upstream call, to ease load on the AI service. delaySeconds <= 0 is a no-op.
 */
public class DelayingAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(DelayingAiProvider.class);

    private final AiProvider delegate;
    private final int delaySeconds;

    public DelayingAiProvider(AiProvider delegate, int delaySeconds) {
        this.delegate = delegate;
        this.delaySeconds = delaySeconds;
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    @Override
    public String generateText(AiRequest request) {
        sleepIfNeeded(request);
        return delegate.generateText(request);
    }

    @Override
    public Flux<String> streamText(AiRequest request) {
        return Flux.defer(() -> {
            sleepIfNeeded(request);
            return delegate.streamText(request);
        });
    }

    private void sleepIfNeeded(AiRequest request) {
        if (delaySeconds <= 0) return;
        log.info("Pre-call delay: sleeping {}s before {} (model={})",
                delaySeconds, delegate.getProviderName(), request.getModel());
        try {
            Thread.sleep(delaySeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Pre-call delay interrupted");
        }
    }
}
