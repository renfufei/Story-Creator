package com.storycreator.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClaudeAiProviderTest {

    private MockWebServer mockWebServer;
    private ClaudeAiProvider provider;

    @Mock AiModelConfigRepository configRepository;
    @Mock GlobalSettingService globalSettingService;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");

        when(globalSettingService.getAiTimeoutSeconds()).thenReturn(30);
        when(configRepository.findByProvider("claude")).thenReturn(List.of());

        provider = new ClaudeAiProvider(
                "test-api-key",
                baseUrl,
                "claude-sonnet-test",
                new ObjectMapper(),
                configRepository,
                globalSettingService
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ── generateText ─────────────────────────────────────────────────────────

    @Test
    void generateText_successResponse_returnsContent() throws InterruptedException {
        String responseBody = """
                {
                  "content": [{"type": "text", "text": "这是生成的内容"}],
                  "stop_reason": "end_turn"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(responseBody)
                .addHeader("Content-Type", "application/json"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("test-key")
                .userPrompt("写一个故事")
                .model("claude-sonnet-test")
                .build();

        String result = provider.generateText(request);

        assertThat(result).isEqualTo("这是生成的内容");

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/messages");
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getHeader("x-api-key")).isEqualTo("test-key");
        assertThat(recorded.getHeader("anthropic-version")).isEqualTo("2023-06-01");
    }

    @Test
    void generateText_emptyContent_returnsEmptyString() {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"content\": []}")
                .addHeader("Content-Type", "application/json"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("test-key")
                .userPrompt("prompt")
                .build();

        assertThat(provider.generateText(request)).isEmpty();
    }

    @Test
    void generateText_withSystemPrompt_includedInBody() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"content\": [{\"type\": \"text\", \"text\": \"ok\"}]}")
                .addHeader("Content-Type", "application/json"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("key")
                .systemPrompt("你是一个写作助手")
                .userPrompt("写故事")
                .build();

        provider.generateText(request);

        RecordedRequest recorded = mockWebServer.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"system\"");
        assertThat(body).contains("你是一个写作助手");
    }

    @Test
    void generateText_withMultiTurnMessages_usesMessagesArray() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"content\": [{\"type\": \"text\", \"text\": \"回复\"}]}")
                .addHeader("Content-Type", "application/json"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("key")
                .messages(List.of(
                        Map.of("role", "user", "content", "第一条消息"),
                        Map.of("role", "assistant", "content", "助手回复"),
                        Map.of("role", "user", "content", "继续")
                ))
                .build();

        provider.generateText(request);

        RecordedRequest recorded = mockWebServer.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("第一条消息");
        assertThat(body).contains("助手回复");
        assertThat(body).contains("继续");
    }

    @Test
    void generateText_withExtraParams_mergedIntoBody() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"content\": [{\"type\": \"text\", \"text\": \"ok\"}]}")
                .addHeader("Content-Type", "application/json"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("key")
                .userPrompt("prompt")
                .extraParams("{\"top_p\": 0.9, \"custom_flag\": true}")
                .build();

        provider.generateText(request);

        RecordedRequest recorded = mockWebServer.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"top_p\"");
        assertThat(body).contains("0.9");
        assertThat(body).contains("\"custom_flag\"");
    }

    @Test
    void generateText_serverError_throwsException() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\": {\"message\": \"Internal Server Error\"}}"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("key")
                .userPrompt("prompt")
                .build();

        assertThatThrownBy(() -> provider.generateText(request))
                .isInstanceOf(Exception.class);
    }

    // ── streamText ────────────────────────────────────────────────────────────

    @Test
    void streamText_collectsDeltas() {
        // WebClient SSE decoder strips "data: " prefix and delivers the raw JSON
        String sseBody = "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}\n\n" +
                         "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"，世界\"}}\n\n" +
                         "data: {\"type\":\"message_stop\"}\n\n";

        mockWebServer.enqueue(new MockResponse()
                .setBody(sseBody)
                .addHeader("Content-Type", "text/event-stream"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("key")
                .userPrompt("prompt")
                .model("claude-sonnet-test")
                .build();

        List<String> tokens = provider.streamText(request).collectList().block();

        assertThat(tokens).isNotNull();
        assertThat(String.join("", tokens)).contains("你好");
        assertThat(String.join("", tokens)).contains("，世界");
    }

    @Test
    void getProviderName_returnsClaude() {
        assertThat(provider.getProviderName()).isEqualTo("claude");
    }
}
