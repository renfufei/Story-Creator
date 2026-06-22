package com.storycreator.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storycreator.core.port.ai.AiProvider;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;

@Component
public class ClaudeAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(ClaudeAiProvider.class);
    private final ObjectMapper objectMapper;
    private final AiModelConfigRepository configRepository;
    private final GlobalSettingService globalSettingService;
    private final String fallbackApiKey;
    private final String fallbackBaseUrl;
    private final String fallbackModel;

    public ClaudeAiProvider(
            @Value("${story-creator.ai.claude.api-key:}") String apiKey,
            @Value("${story-creator.ai.claude.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${story-creator.ai.claude.default-model:claude-sonnet-4-5-20250514}") String defaultModel,
            ObjectMapper objectMapper,
            AiModelConfigRepository configRepository,
            GlobalSettingService globalSettingService) {
        this.fallbackApiKey = apiKey;
        this.fallbackBaseUrl = baseUrl;
        this.fallbackModel = defaultModel;
        this.objectMapper = objectMapper;
        this.configRepository = configRepository;
        this.globalSettingService = globalSettingService;
    }

    @Override
    public String getProviderName() {
        return "claude";
    }

    @Override
    public String generateText(AiRequest request) {
        String body = buildRequestBody(request, false);
        WebClient client = buildWebClient(request);

        String response = client.post()
                .uri("/v1/messages")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.get("content");
            if (content != null && content.isArray() && !content.isEmpty()) {
                return content.get(0).get("text").asText();
            }
            return "";
        } catch (Exception e) {
            log.error("Failed to parse Claude response", e);
            throw new RuntimeException("Failed to parse Claude response", e);
        }
    }

    @Override
    public Flux<String> streamText(AiRequest request) {
        String body = buildRequestBody(request, true);
        WebClient client = buildWebClient(request);
        log.info("Claude streamText: model={} baseUrl={} promptLen={}",
                request.getModel(), request.getBaseUrl(),
                request.getUserPrompt() != null ? request.getUserPrompt().length() : 0);

        java.util.concurrent.atomic.AtomicInteger rawCount = new java.util.concurrent.atomic.AtomicInteger(0);

        return client.post()
                .uri("/v1/messages")
                .bodyValue(body)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(errBody -> {
                                    log.error("Claude API error: status={} body={}", response.statusCode(), errBody);
                                    return reactor.core.publisher.Mono.error(
                                            new RuntimeException("Claude API error " + response.statusCode() + ": " + errBody));
                                }))
                .bodyToFlux(String.class)
                .doOnNext(raw -> {
                    int cnt = rawCount.incrementAndGet();
                    if (cnt <= 3) {
                        log.debug("Claude raw SSE event [{}]: {}", cnt,
                                raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);
                    }
                })
                .doOnComplete(() -> log.info("Claude stream completed, total raw events: {}", rawCount.get()))
                .doOnError(e -> log.error("Claude stream error: {}", e.getMessage()))
                .filter(line -> line != null && !line.isEmpty())
                .mapNotNull(this::extractDelta);
    }

    private WebClient buildWebClient(AiRequest request) {
        // Priority: request-level > DB config > fallback
        String apiKey = null;
        String baseUrl = null;

        if (request != null) {
            apiKey = request.getApiKey();
            baseUrl = request.getBaseUrl();
        }

        if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            AiModelConfigEntity config = getActiveConfig();
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = (config != null && config.getApiKey() != null && !config.getApiKey().isBlank())
                        ? config.getApiKey() : fallbackApiKey;
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = (config != null && config.getBaseUrl() != null && !config.getBaseUrl().isBlank())
                        ? config.getBaseUrl() : fallbackBaseUrl;
            }
        }

        int timeoutSeconds = globalSettingService.getAiTimeoutSeconds();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)
                .responseTimeout(Duration.ofSeconds(timeoutSeconds));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    private AiModelConfigEntity getActiveConfig() {
        List<AiModelConfigEntity> configs = configRepository.findByProvider("claude");
        return configs.stream()
                .filter(AiModelConfigEntity::isActive)
                .findFirst()
                .orElse(null);
    }

    private String getDefaultModel() {
        AiModelConfigEntity config = getActiveConfig();
        if (config != null && config.getModelId() != null && !config.getModelId().isBlank()) {
            return config.getModelId();
        }
        return fallbackModel;
    }

    private String extractDelta(String eventData) {
        try {
            JsonNode node = objectMapper.readTree(eventData);
            String type = node.has("type") ? node.get("type").asText() : "";
            if ("content_block_delta".equals(type)) {
                JsonNode delta = node.get("delta");
                if (delta != null && delta.has("text")) {
                    return delta.get("text").asText();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String buildRequestBody(AiRequest request, boolean stream) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", request.getModel() != null ? request.getModel() : getDefaultModel());
            root.put("max_tokens", request.getMaxTokens());
            root.put("temperature", request.getTemperature());
            root.put("stream", stream);

            if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
                root.put("system", request.getSystemPrompt());
            }

            ArrayNode messages = root.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", request.getUserPrompt());

            // Merge extra params (e.g. custom API parameters)
            mergeExtraParams(root, request.getExtraParams());

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build request body", e);
        }
    }

    private void mergeExtraParams(ObjectNode root, String extraParamsJson) {
        if (extraParamsJson == null || extraParamsJson.isBlank()) return;
        try {
            JsonNode extra = objectMapper.readTree(extraParamsJson);
            if (extra.isObject()) {
                extra.fields().forEachRemaining(entry -> root.set(entry.getKey(), entry.getValue()));
            }
        } catch (Exception e) {
            log.warn("Failed to parse extra params: {}", extraParamsJson, e);
        }
    }
}
