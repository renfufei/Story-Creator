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
class OpenAiProviderTest {

    private MockWebServer mockWebServer;
    private OpenAiProvider provider;

    @Mock AiModelConfigRepository configRepository;
    @Mock GlobalSettingService globalSettingService;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");

        when(globalSettingService.getAiTimeoutSeconds()).thenReturn(30);
        when(configRepository.findByProvider("openai")).thenReturn(List.of());

        provider = new OpenAiProvider(
                "test-api-key",
                baseUrl,
                "gpt-4o-test",
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
                  "choices": [
                    {"message": {"role": "assistant", "content": "这是生成的内容"}}
                  ]
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(responseBody)
                .addHeader("Content-Type", "application/json"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("test-key")
                .userPrompt("写一个故事")
                .model("gpt-4o-test")
                .build();

        String result = provider.generateText(request);

        assertThat(result).isEqualTo("这是生成的内容");

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-key");
    }

    @Test
    void generateText_emptyChoices_returnsEmptyString() {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"choices\": []}")
                .addHeader("Content-Type", "application/json"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("key")
                .userPrompt("prompt")
                .build();

        assertThat(provider.generateText(request)).isEmpty();
    }

    @Test
    void generateText_stripsThinkBlocks() {
        String responseBody = """
                {
                  "choices": [
                    {"message": {"role": "assistant", "content": "<think>内部思考内容</think>实际回答"}}
                  ]
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(responseBody)
                .addHeader("Content-Type", "application/json"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("key")
                .userPrompt("prompt")
                .build();

        String result = provider.generateText(request);
        assertThat(result).isEqualTo("实际回答");
        assertThat(result).doesNotContain("<think>");
        assertThat(result).doesNotContain("内部思考内容");
    }

    @Test
    void generateText_multipleThinkBlocks_allStripped() {
        String content = "<think>思考1</think>正文A<think>思考2</think>正文B";
        String responseBody = String.format(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"%s\"}}]}", content);
        mockWebServer.enqueue(new MockResponse()
                .setBody(responseBody)
                .addHeader("Content-Type", "application/json"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("key")
                .userPrompt("prompt")
                .build();

        String result = provider.generateText(request);
        assertThat(result).isEqualTo("正文A正文B");
    }

    @Test
    void generateText_withSystemPrompt_addsSystemMessage() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}")
                .addHeader("Content-Type", "application/json"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("key")
                .systemPrompt("你是写作助手")
                .userPrompt("写故事")
                .build();

        provider.generateText(request);

        RecordedRequest recorded = mockWebServer.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"system\"");
        assertThat(body).contains("你是写作助手");
    }

    @Test
    void generateText_withMultiTurnMessages_systemMessageFirst() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"回复\"}}]}")
                .addHeader("Content-Type", "application/json"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("key")
                .systemPrompt("系统提示")
                .messages(List.of(
                        Map.of("role", "user", "content", "消息1"),
                        Map.of("role", "assistant", "content", "回复1")
                ))
                .build();

        provider.generateText(request);

        RecordedRequest recorded = mockWebServer.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("系统提示");
        assertThat(body).contains("消息1");
    }

    @Test
    void generateText_serverError_throwsException() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody("{\"error\":{\"message\":\"Rate limit exceeded\"}}"));

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
        String sseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n" +
                         "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}\n\n" +
                         "data: [DONE]\n\n";

        mockWebServer.enqueue(new MockResponse()
                .setBody(sseBody)
                .addHeader("Content-Type", "text/event-stream"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("key")
                .userPrompt("prompt")
                .model("gpt-4o-test")
                .build();

        List<String> tokens = provider.streamText(request).collectList().block();

        assertThat(tokens).isNotNull();
        String combined = String.join("", tokens);
        assertThat(combined).contains("你好");
        assertThat(combined).contains("，世界");
    }

    @Test
    void streamText_filterThinkBlocks_inStreaming() {
        String sseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"<think>\"}}]}\n\n" +
                         "data: {\"choices\":[{\"delta\":{\"content\":\"思考内容\"}}]}\n\n" +
                         "data: {\"choices\":[{\"delta\":{\"content\":\"</think>\"}}]}\n\n" +
                         "data: {\"choices\":[{\"delta\":{\"content\":\"实际回答\"}}]}\n\n" +
                         "data: [DONE]\n\n";

        mockWebServer.enqueue(new MockResponse()
                .setBody(sseBody)
                .addHeader("Content-Type", "text/event-stream"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("key")
                .userPrompt("prompt")
                .model("gpt-4o-test")
                .build();

        List<String> tokens = provider.streamText(request).collectList().block();

        assertThat(tokens).isNotNull();
        String combined = String.join("", tokens);
        assertThat(combined).doesNotContain("思考内容");
        assertThat(combined).contains("实际回答");
    }

    @Test
    void streamText_skipsDoneToken() {
        mockWebServer.enqueue(new MockResponse()
                .setBody("data: [DONE]\n\n")
                .addHeader("Content-Type", "text/event-stream"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("key")
                .userPrompt("prompt")
                .build();

        List<String> tokens = provider.streamText(request).collectList().block();
        assertThat(tokens).isNotNull().isEmpty();
    }

    @Test
    void streamText_errorInSseStream_throwsException() {
        String sseBody = "data: {\"error\":{\"message\":\"模型过载，请稍后重试\"}}\n\n";
        mockWebServer.enqueue(new MockResponse()
                .setBody(sseBody)
                .addHeader("Content-Type", "text/event-stream"));

        AiRequest request = AiRequest.builder()
                .baseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""))
                .apiKey("key")
                .userPrompt("prompt")
                .build();

        assertThatThrownBy(() -> provider.streamText(request).collectList().block())
                .isInstanceOf(Exception.class)
                .hasMessageContaining("AI服务错误");
    }

    @Test
    void getProviderName_returnsOpenai() {
        assertThat(provider.getProviderName()).isEqualTo("openai");
    }
}
