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
public class OllamaAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaAiProvider.class);
    private final ObjectMapper objectMapper;
    private final AiModelConfigRepository configRepository;
    private final GlobalSettingService globalSettingService;
    private final String fallbackBaseUrl;
    private final String fallbackModel;

    public OllamaAiProvider(
            @Value("${story-creator.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${story-creator.ai.ollama.default-model:llama3}") String defaultModel,
            ObjectMapper objectMapper,
            AiModelConfigRepository configRepository,
            GlobalSettingService globalSettingService) {
        this.fallbackBaseUrl = baseUrl;
        this.fallbackModel = defaultModel;
        this.objectMapper = objectMapper;
        this.configRepository = configRepository;
        this.globalSettingService = globalSettingService;
    }

    @Override
    public String getProviderName() {
        return "ollama";
    }

    @Override
    public String generateText(AiRequest request) {
        String body = buildRequestBody(request, false);
        WebClient client = buildWebClient(request);

        String response = client.post()
                .uri("/api/chat")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode message = root.get("message");
            if (message != null && message.has("content")) {
                return message.get("content").asText();
            }
            return "";
        } catch (Exception e) {
            log.error("Failed to parse Ollama response", e);
            throw new RuntimeException("Failed to parse Ollama response", e);
        }
    }

    @Override
    public Flux<String> streamText(AiRequest request) {
        String body = buildRequestBody(request, true);
        WebClient client = buildWebClient(request);

        return client.post()
                .uri("/api/chat")
                .bodyValue(body)
                .accept(MediaType.APPLICATION_NDJSON)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> line != null && !line.isEmpty())
                .mapNotNull(this::extractDelta);
    }

    private WebClient buildWebClient(AiRequest request) {
        // Priority: request-level > DB config > fallback
        String baseUrl = (request != null && request.getBaseUrl() != null && !request.getBaseUrl().isBlank())
                ? request.getBaseUrl() : null;

        if (baseUrl == null) {
            AiModelConfigEntity config = getActiveConfig();
            baseUrl = (config != null && config.getBaseUrl() != null && !config.getBaseUrl().isBlank())
                    ? config.getBaseUrl() : fallbackBaseUrl;
        }

        int timeoutSeconds = globalSettingService.getAiTimeoutSeconds();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)
                .responseTimeout(Duration.ofSeconds(timeoutSeconds));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    private AiModelConfigEntity getActiveConfig() {
        List<AiModelConfigEntity> configs = configRepository.findByProvider("ollama");
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

    private String extractDelta(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            JsonNode message = node.get("message");
            if (message != null && message.has("content")) {
                String content = message.get("content").asText();
                return content.isEmpty() ? null : content;
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
            root.put("stream", stream);

            ObjectNode options = root.putObject("options");
            options.put("temperature", request.getTemperature());
            options.put("num_predict", request.getMaxTokens());

            ArrayNode messages = root.putArray("messages");

            if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
                ObjectNode sysMsg = messages.addObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", request.getSystemPrompt());
            }

            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", request.getUserPrompt());

            // Merge extra params
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
