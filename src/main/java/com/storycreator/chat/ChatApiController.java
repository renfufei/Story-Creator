package com.storycreator.chat;

import com.storycreator.persistence.entity.ChatMessageEntity;
import com.storycreator.persistence.entity.ChatSessionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/chat")
public class ChatApiController {

    private static final Logger log = LoggerFactory.getLogger(ChatApiController.class);
    private final ChatService chatService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatApiController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/sessions")
    public ResponseEntity<Map<String, Object>> createSession() {
        ChatSessionEntity session = chatService.createSession();
        Map<String, Object> result = new HashMap<>();
        result.put("id", session.getId());
        result.put("title", session.getTitle());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> listSessions() {
        List<ChatSessionEntity> sessions = chatService.listSessions();
        List<Map<String, Object>> result = sessions.stream().map(s -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("title", s.getTitle());
            m.put("updatedAt", s.getUpdatedAt().toString());
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable Long id) {
        chatService.deleteSession(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<List<Map<String, Object>>> getMessages(@PathVariable Long id) {
        List<ChatMessageEntity> messages = chatService.getMessages(id);
        List<Map<String, Object>> result = messages.stream().map(m -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", m.getId());
            map.put("role", m.getRole());
            map.put("content", m.getContent());
            map.put("modelType", m.getModelType());
            map.put("mediaFilePath", m.getMediaFilePath());
            map.put("createdAt", m.getCreatedAt().toString());
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/sessions/{id}/stream")
    public SseEmitter streamTextMessage(@PathVariable Long id,
                                        @RequestParam Long configId,
                                        @RequestParam String input) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout

        executor.submit(() -> {
            try {
                chatService.sendTextMessage(id, configId, input)
                        .doOnNext(token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data(""));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnError(e -> {
                            try {
                                emitter.send(SseEmitter.event().name("error").data(e.getMessage() != null ? e.getMessage() : "未知错误"));
                                emitter.complete();
                            } catch (IOException ex) {
                                emitter.completeWithError(ex);
                            }
                        })
                        .blockLast();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage() != null ? e.getMessage() : "未知错误"));
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            }
        });

        return emitter;
    }

    @PostMapping("/sessions/{id}/send")
    public ResponseEntity<Map<String, Object>> sendMessage(@PathVariable Long id,
                                                           @RequestBody Map<String, Object> body) {
        Long configId = body.get("configId") != null ? Long.parseLong(body.get("configId").toString()) : null;
        String input = (String) body.get("input");
        String type = (String) body.get("type"); // IMAGE or TTS

        if ("IMAGE".equals(type)) {
            Map<String, Object> result = chatService.sendImageMessage(id, configId, input);
            return ResponseEntity.ok(result);
        } else if ("TTS".equals(type)) {
            String voice = (String) body.get("voice");
            double speed = body.get("speed") != null ? Double.parseDouble(body.get("speed").toString()) : 1.0;
            Map<String, Object> result = chatService.sendTtsMessage(id, configId, input, voice, speed);
            return ResponseEntity.ok(result);
        }

        return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Unknown type: " + type));
    }

    @PutMapping("/sessions/{id}/title")
    public ResponseEntity<Map<String, Object>> updateTitle(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title != null && !title.isBlank()) {
            chatService.updateSessionTitle(id, title);
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/media/images/{sessionId}/{filename}")
    public ResponseEntity<byte[]> serveImage(@PathVariable Long sessionId, @PathVariable String filename) {
        try {
            Path filePath = Paths.get("data/chat/images/" + sessionId + "/" + filename);
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }
            byte[] data = Files.readAllBytes(filePath);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "image/png")
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .body(data);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/media/audio/{sessionId}/{filename}")
    public ResponseEntity<byte[]> serveAudio(@PathVariable Long sessionId, @PathVariable String filename) {
        try {
            Path filePath = Paths.get("data/chat/audio/" + sessionId + "/" + filename);
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }
            byte[] data = Files.readAllBytes(filePath);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .body(data);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
