package com.storycreator.ai.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;

/**
 * Provides a linear-backoff retry specification for AI provider stream connections.
 * Retries only on connection-level errors (reset, refused, timeout, premature close).
 */
public final class AiRetrySpec {

    private static final Logger log = LoggerFactory.getLogger(AiRetrySpec.class);

    private static final int MAX_RETRIES = 5;
    private static final Duration BASE_DELAY = Duration.ofSeconds(2);

    private AiRetrySpec() {}

    /**
     * Returns a Retry with linear backoff: 2s, 4s, 6s, 8s, 10s.
     * Only retries on connection-level errors.
     */
    public static Retry linearBackoffRetry(String context) {
        return Retry.from(signals -> signals.flatMap(retrySignal -> {
            long attempt = retrySignal.totalRetries() + 1;
            Throwable failure = retrySignal.failure();

            if (attempt > MAX_RETRIES || !isRetryableError(failure)) {
                return reactor.core.publisher.Mono.error(failure);
            }

            long delaySeconds = BASE_DELAY.getSeconds() * attempt;
            log.warn("[{}] Retry attempt {} after {}s due to: {}",
                    context, attempt, delaySeconds, failure.getMessage());
            return reactor.core.publisher.Mono.delay(Duration.ofSeconds(delaySeconds))
                    .then(reactor.core.publisher.Mono.empty());
        }));
    }

    /**
     * Determines if an error is a transient connection issue worth retrying.
     */
    public static boolean isRetryableError(Throwable e) {
        if (e instanceof IOException) return true;
        String msg = e.getMessage();
        if (msg == null) {
            return e.getCause() instanceof IOException;
        }
        return msg.contains("Connection reset")
                || msg.contains("connection reset")
                || msg.contains("Connection refused")
                || msg.contains("Connection timed out")
                || msg.contains("Connection prematurely closed")
                || msg.contains("premature close")
                || msg.contains("GOAWAY")
                || msg.contains("connection was aborted");
    }
}
