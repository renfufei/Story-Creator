package com.storycreator.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storycreator.ai.provider.MockAiProvider;
import com.storycreator.core.port.ai.AiRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/mock")
public class MockApiController {

    private final MockAiProvider mockAiProvider;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public MockApiController(MockAiProvider mockAiProvider, ObjectMapper objectMapper) {
        this.mockAiProvider = mockAiProvider;
        this.objectMapper = objectMapper;
    }

    // --- OpenAI-compatible endpoints ---

    @PostMapping(value = "/v1/chat/completions")
    public Object chatCompletions(@RequestBody Map<String, Object> requestBody) {
        // Extract messages
        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) requestBody.get("messages");
        String model = (String) requestBody.getOrDefault("model", "mock-model");
        Boolean stream = (Boolean) requestBody.get("stream");

        // Combine messages into prompt
        StringBuilder systemPrompt = new StringBuilder();
        StringBuilder userPrompt = new StringBuilder();
        if (messages != null) {
            for (Map<String, String> msg : messages) {
                String role = msg.get("role");
                String content = msg.get("content");
                if (content == null) continue;
                if ("system".equals(role)) {
                    systemPrompt.append(content).append("\n");
                } else {
                    userPrompt.append(content).append("\n");
                }
            }
        }

        String combinedPrompt = systemPrompt.toString() + " " + userPrompt.toString();
        String generatedContent = mockAiProvider.generateForPrompt(combinedPrompt);

        if (Boolean.TRUE.equals(stream)) {
            return streamResponse(generatedContent, model);
        } else {
            return nonStreamResponse(generatedContent, model);
        }
    }

    @GetMapping(value = "/v1/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listModels() {
        return ResponseEntity.ok(Map.of(
                "object", "list",
                "data", List.of(Map.of("id", "mock-model", "object", "model"))
        ));
    }

    // --- Non-streaming response ---

    private ResponseEntity<Map<String, Object>> nonStreamResponse(String content, String model) {
        String id = "mock-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> response = Map.of(
                "id", id,
                "object", "chat.completion",
                "model", model,
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", Map.of("role", "assistant", "content", content),
                        "finish_reason", "stop"
                )),
                "usage", Map.of(
                        "prompt_tokens", 0,
                        "completion_tokens", 0,
                        "total_tokens", 0
                )
        );
        return ResponseEntity.ok(response);
    }

    // --- Streaming response ---

    private SseEmitter streamResponse(String content, String model) {
        SseEmitter emitter = new SseEmitter(300_000L);
        String id = "mock-" + UUID.randomUUID().toString().substring(0, 8);

        executor.submit(() -> {
            try {
                // Split into 5-8 char chunks
                int chunkSize = 6;
                int chunkCount = (content.length() + chunkSize - 1) / chunkSize;
                long totalDelayMs = Math.min((long) content.length() * 15, 10000L);
                long chunkDelayMs = Math.max(10, Math.min(100, chunkCount > 0 ? totalDelayMs / chunkCount : 50));

                for (int i = 0; i < content.length(); i += chunkSize) {
                    String chunk = content.substring(i, Math.min(i + chunkSize, content.length()));
                    Map<String, Object> choice = new LinkedHashMap<>();
                    choice.put("index", 0);
                    choice.put("delta", Map.of("content", chunk));
                    choice.put("finish_reason", null);
                    String chunkJson = objectMapper.writeValueAsString(Map.of(
                            "id", id,
                            "object", "chat.completion.chunk",
                            "model", model,
                            "choices", List.of(choice)
                    ));
                    emitter.send(SseEmitter.event().data(chunkJson));
                    Thread.sleep(chunkDelayMs);
                }

                // Send final chunk with finish_reason
                String finalJson = objectMapper.writeValueAsString(Map.of(
                        "id", id,
                        "object", "chat.completion.chunk",
                        "model", model,
                        "choices", List.of(Map.of(
                                "index", 0,
                                "delta", Map.of(),
                                "finish_reason", "stop"
                        ))
                ));
                emitter.send(SseEmitter.event().data(finalJson));

                // Send [DONE]
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {}
            }
        });

        return emitter;
    }

    // --- Legacy endpoints (kept for backward compatibility) ---

    @PostMapping("/api")
    public ResponseEntity<Map<String, String>> legacyGenerate(@RequestBody LegacyRequest req) {
        AiRequest aiRequest = AiRequest.builder()
                .systemPrompt(req.systemPrompt())
                .userPrompt(req.userPrompt())
                .build();
        String content = mockAiProvider.generateText(aiRequest);
        return ResponseEntity.ok(Map.of("content", content));
    }

    @GetMapping(value = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter legacyStream(@RequestParam String prompt) {
        SseEmitter emitter = new SseEmitter(60_000L);
        AiRequest aiRequest = AiRequest.builder()
                .userPrompt(prompt)
                .build();

        executor.submit(() -> {
            try {
                mockAiProvider.streamText(aiRequest)
                        .doOnNext(token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (Exception ignored) {}
                        })
                        .doOnComplete(() -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data(""));
                                emitter.complete();
                            } catch (Exception ignored) {}
                        })
                        .doOnError(e -> emitter.completeWithError(e))
                        .blockLast();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    record LegacyRequest(String systemPrompt, String userPrompt, Boolean stream) {}
}
