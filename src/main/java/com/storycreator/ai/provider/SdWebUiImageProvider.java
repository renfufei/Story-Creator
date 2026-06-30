package com.storycreator.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storycreator.core.port.image.ImageProvider;
import com.storycreator.core.port.image.ImageRequest;
import com.storycreator.core.port.image.ImageResult;
import com.storycreator.core.service.GlobalSettingService;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Base64;

@Component
public class SdWebUiImageProvider implements ImageProvider {

    private static final Logger log = LoggerFactory.getLogger(SdWebUiImageProvider.class);
    private final ObjectMapper objectMapper;
    private final GlobalSettingService globalSettingService;

    public SdWebUiImageProvider(ObjectMapper objectMapper, GlobalSettingService globalSettingService) {
        this.objectMapper = objectMapper;
        this.globalSettingService = globalSettingService;
    }

    @Override
    public String getProviderName() {
        return "sd-webui";
    }

    @Override
    public ImageResult generateImage(ImageRequest request) {
        WebClient client = buildWebClient(request);
        String body = buildRequestBody(request);

        log.info("SD WebUI request: size={}x{}", request.getWidth(), request.getHeight());

        String responseBody = client.post()
                .uri("/sdapi/v1/txt2img")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(errBody -> {
                                    log.error("SD WebUI error: status={} body={}", response.statusCode(), errBody);
                                    return reactor.core.publisher.Mono.error(
                                            new RuntimeException("SD WebUI error " + response.statusCode() + ": " + errBody));
                                }))
                .bodyToMono(String.class)
                .block();

        if (responseBody == null || responseBody.isBlank()) {
            throw new RuntimeException("SD WebUI returned empty response");
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode images = root.get("images");
            if (images == null || !images.isArray() || images.isEmpty()) {
                throw new RuntimeException("SD WebUI returned no images");
            }

            String b64 = images.get(0).asText();
            byte[] imageBytes = Base64.getDecoder().decode(b64);
            log.info("SD WebUI generated image: {} bytes", imageBytes.length);
            return new ImageResult(imageBytes, "image/png", null);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse SD WebUI response", e);
        }
    }

    private WebClient buildWebClient(ImageRequest request) {
        String baseUrl = request.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://127.0.0.1:7860";
        }

        int timeoutSeconds = globalSettingService.getAiTimeoutSeconds();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)
                .responseTimeout(Duration.ofSeconds(timeoutSeconds));

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .defaultHeader("Content-Type", "application/json");

        String apiKey = request.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }

        return builder.build();
    }

    private String buildRequestBody(ImageRequest request) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("prompt", request.getPrompt());
            root.put("width", request.getWidth());
            root.put("height", request.getHeight());

            if (request.getNegativePrompt() != null && !request.getNegativePrompt().isBlank()) {
                root.put("negative_prompt", request.getNegativePrompt());
            }

            // Default SD settings
            int steps = 20;
            double cfgScale = 7.0;
            String samplerName = "Euler a";

            // Override from extraParams
            if (request.getExtraParams() != null && !request.getExtraParams().isBlank()) {
                JsonNode extra = objectMapper.readTree(request.getExtraParams());
                if (extra.has("steps")) steps = extra.get("steps").asInt();
                if (extra.has("cfg_scale")) cfgScale = extra.get("cfg_scale").asDouble();
                if (extra.has("sampler_name")) samplerName = extra.get("sampler_name").asText();
                if (extra.has("negative_prompt") && (request.getNegativePrompt() == null || request.getNegativePrompt().isBlank())) {
                    root.put("negative_prompt", extra.get("negative_prompt").asText());
                }
                // Pass through any other SD-specific params
                extra.fields().forEachRemaining(entry -> {
                    String key = entry.getKey();
                    if (!key.equals("steps") && !key.equals("cfg_scale") && !key.equals("sampler_name") && !key.equals("negative_prompt")) {
                        root.set(key, entry.getValue());
                    }
                });
            }

            root.put("steps", steps);
            root.put("cfg_scale", cfgScale);
            root.put("sampler_name", samplerName);

            if (request.getModel() != null && !request.getModel().isBlank()) {
                ObjectNode overrideSettings = objectMapper.createObjectNode();
                overrideSettings.put("sd_model_checkpoint", request.getModel());
                root.set("override_settings", overrideSettings);
            }

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build SD WebUI request body", e);
        }
    }
}
