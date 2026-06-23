package com.storycreator.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storycreator.core.port.tts.TtsProvider;
import com.storycreator.core.port.tts.TtsRequest;
import com.storycreator.core.service.GlobalSettingService;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Component
public class OpenAiTtsProvider implements TtsProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTtsProvider.class);
    private final ObjectMapper objectMapper;
    private final GlobalSettingService globalSettingService;

    public OpenAiTtsProvider(ObjectMapper objectMapper, GlobalSettingService globalSettingService) {
        this.objectMapper = objectMapper;
        this.globalSettingService = globalSettingService;
    }

    @Override
    public String getProviderName() {
        return "openai-tts";
    }

    @Override
    public byte[] generateAudio(TtsRequest request) {
        WebClient client = buildWebClient(request);
        String body = buildRequestBody(request);

        log.info("TTS request: model={} voice={} speed={} inputLen={}",
                request.getModel(), request.getVoice(), request.getSpeed(),
                request.getInput() != null ? request.getInput().length() : 0);

        byte[] audio = client.post()
                .uri("/v1/audio/speech")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(errBody -> {
                                    log.error("TTS API error: status={} body={}", response.statusCode(), errBody);
                                    return reactor.core.publisher.Mono.error(
                                            new RuntimeException("TTS API error " + response.statusCode() + ": " + errBody));
                                }))
                .bodyToMono(byte[].class)
                .block();

        if (audio == null || audio.length == 0) {
            throw new RuntimeException("TTS API returned empty audio");
        }

        log.info("TTS response: {} bytes", audio.length);
        return audio;
    }

    private WebClient buildWebClient(TtsRequest request) {
        String baseUrl = request.getBaseUrl();
        String apiKey = request.getApiKey();

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
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .defaultHeader("Content-Type", "application/json");

        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }

        return builder.build();
    }

    private String buildRequestBody(TtsRequest request) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", request.getModel());
            root.put("input", request.getInput());
            root.put("voice", request.getVoice());
            root.put("response_format", request.getResponseFormat());
            root.put("speed", request.getSpeed());
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build TTS request body", e);
        }
    }
}
