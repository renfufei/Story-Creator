package com.storycreator.chat;

import com.storycreator.persistence.entity.ChatMessageEntity;
import reactor.core.Disposable;
import com.storycreator.persistence.entity.ChatSessionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * 文本消息流式接口（SSE）。
     *
     * <p><b>为什么是 POST 而不是 GET</b>：这个接口有副作用——落库「用户消息」、调模型、
     * 再把「助手回复」落库，语义上不是幂等读操作（浏览器刷新/预取/历史前进重放都不该重发）；
     * 而且聊天原文放 query 里会撞 URL 长度上限（Tomcat 默认请求头 8KB，中文经
     * {@code encodeURIComponent} 后一个汉字变 9 个字符，两三千字就超），
     * 原文还会进浏览器历史、服务端访问日志和 Referer。
     *
     * <p>响应仍是 {@code text/event-stream}，前端用 fetch 的 ReadableStream 读（不用 EventSource，
     * 它只支持 GET），因此改 POST 前端不需要换流式方案。
     */
    @PostMapping(value = "/sessions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTextMessage(@PathVariable Long id,
                                        @RequestBody(required = false) StreamRequest body) {
        if (body == null || body.input() == null || body.input().isBlank()) {
            // 400 比"开一条流再往里推一个 error 事件"更准确：这是请求本身不合法，不是生成失败
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "input 不能为空");
        }
        Long configId = body.configId();
        String input = body.input();

        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout

        // 客户端刷新/切页/超时后要能停掉上游（否则模型继续生成、白烧 token）
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        emitter.onTimeout(() -> disposeQuietly(subscriptionRef.get()));
        emitter.onError(e -> disposeQuietly(subscriptionRef.get()));
        emitter.onCompletion(() -> disposeQuietly(subscriptionRef.get()));

        executor.submit(() -> {
            try {
                Disposable subscription = chatService.sendTextMessage(id, configId, input)
                        .subscribe(
                                token -> sendEvent(emitter, "token", token),
                                error -> {
                                    log.error("Chat stream error for session {}: {}", id, error.getMessage());
                                    sendEvent(emitter, "error", error.getMessage() != null ? error.getMessage() : "未知错误");
                                    emitter.complete();
                                },
                                () -> {
                                    sendEvent(emitter, "done", "");
                                    emitter.complete();
                                });
                subscriptionRef.set(subscription);
            } catch (Exception e) {
                log.error("Chat stream failed for session {}: {}", id, e.getMessage());
                sendEvent(emitter, "error", e.getMessage() != null ? e.getMessage() : "未知错误");
                emitter.complete();
            }
        });

        return emitter;
    }

    /** 流式请求体：{@code {"configId": 12, "input": "你好"}} */
    public record StreamRequest(Long configId, String input) {
    }

    /**
     * 推一条 SSE 事件。客户端已断开（刷新/切页）时 send 会抛 {@code IOException}/{@code IllegalStateException}，
     * 这里只记日志不做二次抛出——收尾交给 emitter 的 onError/onCompletion。
     */
    private void sendEvent(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data == null ? "" : data));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE send failed for event {}: {}", name, e.getMessage());
        }
    }

    private static void disposeQuietly(Disposable subscription) {
        if (subscription != null && !subscription.isDisposed()) {
            try {
                subscription.dispose();
            } catch (Exception e) {
                log.debug("Dispose chat stream failed: {}", e.getMessage());
            }
        }
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
