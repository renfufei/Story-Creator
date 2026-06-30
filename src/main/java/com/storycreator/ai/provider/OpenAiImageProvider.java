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
public class OpenAiImageProvider implements ImageProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageProvider.class);
    private final ObjectMapper objectMapper;
    private final GlobalSettingService globalSettingService;

    public OpenAiImageProvider(ObjectMapper objectMapper, GlobalSettingService globalSettingService) {
        this.objectMapper = objectMapper;
        this.globalSettingService = globalSettingService;
    }

    @Override
    public String getProviderName() {
        return "openai-image";
    }

    @Override
    public ImageResult generateImage(ImageRequest request) {
        WebClient client = buildWebClient(request);
        String body = buildRequestBody(request);

        log.info("Image generation request: model={} size={}x{}", request.getModel(), request.getWidth(), request.getHeight());

        String responseBody = client.post()
                .uri("/v1/images/generations")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(errBody -> {
                                    log.error("Image API error: status={} body={}", response.statusCode(), errBody);
                                    return reactor.core.publisher.Mono.error(
                                            new RuntimeException("Image API error " + response.statusCode() + ": " + errBody));
                                }))
                .bodyToMono(String.class)
                .block();

        if (responseBody == null || responseBody.isBlank()) {
            throw new RuntimeException("Image API returned empty response");
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                throw new RuntimeException("Image API returned no data: " + responseBody);
            }

            JsonNode firstImage = data.get(0);
            String b64 = firstImage.has("b64_json") ? firstImage.get("b64_json").asText() : null;
            String revisedPrompt = firstImage.has("revised_prompt") ? firstImage.get("revised_prompt").asText() : null;

            if (b64 == null || b64.isBlank() || "null".equals(b64)) {
                // Try URL fallback
                String url = firstImage.has("url") ? firstImage.get("url").asText() : null;
                if (url != null && !url.isBlank() && !"null".equals(url)) {
                    log.info("No b64_json in response, downloading from URL: {}", url);
                    int timeoutSeconds = globalSettingService.getAiTimeoutSeconds();
                    HttpClient downloadHttpClient = HttpClient.create()
                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)
                            .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                            .followRedirect(true);
                    byte[] downloaded = WebClient.builder()
                            .clientConnector(new ReactorClientHttpConnector(downloadHttpClient))
                            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                            .build()
                            .get().uri(url)
                            .retrieve()
                            .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                    response -> response.bodyToMono(String.class)
                                            .flatMap(errBody -> {
                                                log.error("Image download error: status={} body={}", response.statusCode(), errBody);
                                                return reactor.core.publisher.Mono.error(
                                                        new RuntimeException("Image download error " + response.statusCode() + ": " + errBody));
                                            }))
                            .bodyToMono(byte[].class)
                            .block();
                    if (downloaded == null || downloaded.length < 100) {
                        throw new RuntimeException("Image download returned invalid data: " + (downloaded != null ? downloaded.length : 0) + " bytes");
                    }
                    log.info("Image downloaded from URL: {} bytes", downloaded.length);
                    return new ImageResult(downloaded, "image/png", revisedPrompt);
                }
                throw new RuntimeException("Image API returned neither b64_json nor url");
            }

            byte[] imageBytes = Base64.getDecoder().decode(b64);
            log.info("Image generated: {} bytes, revisedPrompt={}", imageBytes.length,
                    revisedPrompt != null ? revisedPrompt.substring(0, Math.min(50, revisedPrompt.length())) + "..." : "null");
            return new ImageResult(imageBytes, "image/png", revisedPrompt);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse image response", e);
        }
    }

    private WebClient buildWebClient(ImageRequest request) {
        String baseUrl = request.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com";
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
            root.put("model", request.getModel());
            root.put("prompt", request.getPrompt());
            root.put("n", 1);

            String size = request.getWidth() + "x" + request.getHeight();
            root.put("size", size);

            // Parse extra params first to check for output format overrides
            boolean hasResponseFormat = false;
            boolean hasReturnBase64 = false;
            if (request.getExtraParams() != null && !request.getExtraParams().isBlank()) {
                JsonNode extra = objectMapper.readTree(request.getExtraParams());
                hasResponseFormat = extra.has("response_format");
                hasReturnBase64 = extra.has("return_base64");
                extra.fields().forEachRemaining(entry -> {
                    String key = entry.getKey();
                    // Skip SD-specific params
                    if (key.equals("negative_prompt") || key.equals("steps")
                            || key.equals("sampler_name") || key.equals("cfg_scale")) {
                        return;
                    }
                    root.set(key, entry.getValue());
                });
            }

            // Only set default response_format if user didn't specify any output format preference
            if (!hasResponseFormat && !hasReturnBase64) {
                root.put("response_format", "b64_json");
            }

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build image request body", e);
        }
    }
}
