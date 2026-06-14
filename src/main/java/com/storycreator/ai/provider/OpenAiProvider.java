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
public class OpenAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);
    private final ObjectMapper objectMapper;
    private final AiModelConfigRepository configRepository;
    private final GlobalSettingService globalSettingService;
    private final String fallbackApiKey;
    private final String fallbackBaseUrl;
    private final String fallbackModel;


    public OpenAiProvider(
            @Value("${story-creator.ai.openai.api-key:}") String apiKey,
            @Value("${story-creator.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${story-creator.ai.openai.default-model:gpt-4o}") String defaultModel,
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
        return "openai";
    }

    @Override
    public String generateText(AiRequest request) {
        String body = buildRequestBody(request, false);
        WebClient client = buildWebClient(request);

        String response = client.post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                String content = choices.get(0).get("message").get("content").asText();
                return stripThinkBlocks(content);
            }
            return "";
        } catch (Exception e) {
            log.error("Failed to parse OpenAI response", e);
            throw new RuntimeException("Failed to parse OpenAI response", e);
        }
    }

    @Override
    public Flux<String> streamText(AiRequest request) {
        String body = buildRequestBody(request, true);
        WebClient client = buildWebClient(request);
        log.info("OpenAI streamText: model={} baseUrl={} promptLen={}",
                request.getModel(), request.getBaseUrl(),
                request.getUserPrompt() != null ? request.getUserPrompt().length() : 0);

        // Per-stream state for tracking <think> blocks
        java.util.concurrent.atomic.AtomicBoolean insideThink = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger rawCount = new java.util.concurrent.atomic.AtomicInteger(0);

        return client.post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(errBody -> {
                                    log.error("OpenAI API error: status={} body={}", response.statusCode(), errBody);
                                    return reactor.core.publisher.Mono.error(
                                            new RuntimeException("OpenAI API error " + response.statusCode() + ": " + errBody));
                                }))
                .bodyToFlux(String.class)
                .doOnNext(raw -> {
                    rawCount.incrementAndGet();
                })
                .doOnComplete(() -> log.info("OpenAI stream completed, total raw events: {}", rawCount.get()))
                .doOnError(e -> log.error("OpenAI stream error: {}", e.getMessage()))
                .filter(line -> line != null && !line.isEmpty() && !"[DONE]".equals(line))
                .mapNotNull(this::extractDelta)
                .mapNotNull(content -> filterThinkBlocks(content, insideThink));
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
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    private AiModelConfigEntity getActiveConfig() {
        List<AiModelConfigEntity> configs = configRepository.findByProvider("openai");
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

            // Check for error response in SSE stream (e.g. vLLM OOM errors)
            if (node.has("error")) {
                JsonNode error = node.get("error");
                String msg = error.has("message") ? error.get("message").asText() : error.toString();
                throw new RuntimeException("AI服务错误: " + msg);
            }

            JsonNode choices = node.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta == null) return null;

                // Skip reasoning_content field (some providers put thinking here)
                if (delta.has("reasoning_content") && !delta.has("content")) {
                    return null;
                }

                if (delta.has("content")) {
                    return delta.get("content").asText();
                }
            }
            return null;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Strip <think>...</think> blocks from complete (non-streaming) response.
     */
    private String stripThinkBlocks(String content) {
        if (content == null) return "";
        return content.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    /**
     * Filter out <think>...</think> blocks from streaming content.
     * Handles multi-chunk scenarios where the tags span across multiple SSE events.
     */
    private String filterThinkBlocks(String content, java.util.concurrent.atomic.AtomicBoolean insideThink) {
        if (content == null || content.isEmpty()) return null;

        boolean inside = insideThink.get();
        StringBuilder result = new StringBuilder();
        int i = 0;

        while (i < content.length()) {
            if (inside) {
                int closeIdx = content.indexOf("</think>", i);
                if (closeIdx >= 0) {
                    inside = false;
                    i = closeIdx + "</think>".length();
                } else {
                    // Still inside think block, discard rest
                    break;
                }
            } else {
                int openIdx = content.indexOf("<think>", i);
                if (openIdx >= 0) {
                    result.append(content, i, openIdx);
                    inside = true;
                    i = openIdx + "<think>".length();
                } else {
                    result.append(content, i, content.length());
                    break;
                }
            }
        }

        insideThink.set(inside);
        String filtered = result.toString();
        return filtered.isEmpty() ? null : filtered;
    }

    private String buildRequestBody(AiRequest request, boolean stream) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", request.getModel() != null ? request.getModel() : getDefaultModel());
            root.put("max_tokens", request.getMaxTokens());
            root.put("temperature", request.getTemperature());
            root.put("stream", stream);

            // Disable thinking mode by default (for models like Qwen that support it)
            ObjectNode chatTemplateKwargs = objectMapper.createObjectNode();
            chatTemplateKwargs.put("enable_thinking", false);
            root.set("chat_template_kwargs", chatTemplateKwargs);

            ArrayNode messages = root.putArray("messages");

            if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
                ObjectNode sysMsg = messages.addObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", request.getSystemPrompt());
            }

            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", request.getUserPrompt());

            // Merge extra params (e.g. {"enable_thinking": false, "reasoning_effort": "none"})
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
