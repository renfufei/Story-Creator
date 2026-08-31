package com.storycreator.chat;

import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.ai.router.ImageProviderRegistry;
import com.storycreator.ai.router.TtsProviderRegistry;
import com.storycreator.core.domain.ImageType;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.core.port.image.ImageRequest;
import com.storycreator.core.port.image.ImageResult;
import com.storycreator.core.port.tts.TtsRequest;
import com.storycreator.persistence.entity.ChatMessageEntity;
import com.storycreator.persistence.entity.ChatSessionEntity;
import com.storycreator.persistence.repository.ChatMessageRepository;
import com.storycreator.persistence.repository.ChatSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int CONTEXT_FETCH_LIMIT = 40;
    private static final int CONTEXT_MESSAGE_LIMIT = 20;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final AiProviderRouter aiProviderRouter;
    private final ImageProviderRegistry imageProviderRegistry;
    private final TtsProviderRegistry ttsProviderRegistry;

    public ChatService(ChatSessionRepository sessionRepository,
                       ChatMessageRepository messageRepository,
                       AiProviderRouter aiProviderRouter,
                       ImageProviderRegistry imageProviderRegistry,
                       TtsProviderRegistry ttsProviderRegistry) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.aiProviderRouter = aiProviderRouter;
        this.imageProviderRegistry = imageProviderRegistry;
        this.ttsProviderRegistry = ttsProviderRegistry;
    }

    // --- Session CRUD ---

    public ChatSessionEntity createSession() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setTitle(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + " 新对话");
        return sessionRepository.save(session);
    }

    public List<ChatSessionEntity> listSessions() {
        return sessionRepository.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional
    public void deleteSession(Long sessionId) {
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
    }

    public List<ChatMessageEntity> getMessages(Long sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    public void updateSessionTitle(Long sessionId, String title) {
        ChatSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null) {
            session.setTitle(title);
            sessionRepository.save(session);
        }
    }

    // --- Text Message (Streaming) ---

    public Flux<String> sendTextMessage(Long sessionId, Long configId, String userInput) {
        // Save user message
        saveMessage(sessionId, "user", userInput, configId, "TEXT", null);

        // Touch session updated_at
        sessionRepository.findById(sessionId).ifPresent(sessionRepository::save);

        // Build context
        List<Map<String, String>> messages = buildContextMessages(sessionId);

        // Resolve model
        AiProviderRouter.ResolvedModel resolved;
        if (configId != null) {
            resolved = aiProviderRouter.resolveModelByConfigId(configId);
        } else {
            resolved = aiProviderRouter.resolveModel(null, null);
        }
        if (resolved == null) {
            resolved = aiProviderRouter.resolveModel(null, null);
        }

        AiRequest aiRequest = AiRequest.builder()
                .model(resolved.modelId())
                .baseUrl(resolved.baseUrl())
                .apiKey(resolved.apiKey())
                .extraParams(resolved.extraParams())
                .messages(messages)
                .maxTokens(4096)
                .temperature(0.8)
                .build();

        StringBuilder fullResponse = new StringBuilder();
        AiProviderRouter.ResolvedModel finalResolved = resolved;

        return finalResolved.provider().streamText(aiRequest)
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    saveMessage(sessionId, "assistant", fullResponse.toString(), configId, "TEXT", null);
                })
                .doOnError(e -> {
                    log.error("Chat stream error for session {}: {}", sessionId, e.getMessage());
                    saveMessage(sessionId, "assistant", "错误: " + e.getMessage(), configId, "TEXT", null);
                });
    }

    // --- Image Message (Blocking) ---

    public Map<String, Object> sendImageMessage(Long sessionId, Long configId, String userInput) {
        saveMessage(sessionId, "user", userInput, configId, "IMAGE", null);
        sessionRepository.findById(sessionId).ifPresent(sessionRepository::save);

        var resolved = imageProviderRegistry.resolve(configId);
        if (resolved == null) {
            resolved = imageProviderRegistry.resolveGlobalDefault();
        }
        if (resolved == null) {
            return Map.of("success", false, "message", "没有可用的图像模型配置");
        }

        try {
            ImageRequest imageRequest = ImageRequest.builder()
                    .model(resolved.modelId())
                    .prompt(userInput)
                    .imageType(ImageType.PORTRAIT)
                    .baseUrl(resolved.baseUrl())
                    .apiKey(resolved.apiKey())
                    .extraParams(resolved.extraParams())
                    .build();

            ImageResult result = resolved.provider().generateImage(imageRequest);

            // Save file
            String filename = UUID.randomUUID() + ".png";
            Path dir = Paths.get("data/chat/images/" + sessionId);
            Files.createDirectories(dir);
            Path filePath = dir.resolve(filename);
            Files.write(filePath, result.imageBytes());

            String mediaPath = "images/" + sessionId + "/" + filename;
            saveMessage(sessionId, "assistant", result.revisedPrompt(), configId, "IMAGE", mediaPath);

            return Map.of("success", true, "mediaUrl", "/api/chat/media/" + mediaPath, "revisedPrompt", result.revisedPrompt() != null ? result.revisedPrompt() : "");
        } catch (Exception e) {
            log.error("Chat image generation error: {}", e.getMessage());
            saveMessage(sessionId, "assistant", "图像生成失败: " + e.getMessage(), configId, "IMAGE", null);
            return Map.of("success", false, "message", e.getMessage() != null ? e.getMessage() : "未知错误");
        }
    }

    // --- TTS Message (Blocking) ---

    public Map<String, Object> sendTtsMessage(Long sessionId, Long configId, String userInput, String voice, double speed) {
        saveMessage(sessionId, "user", userInput, configId, "TTS", null);
        sessionRepository.findById(sessionId).ifPresent(sessionRepository::save);

        var resolved = ttsProviderRegistry.resolve(configId);
        if (resolved == null) {
            return Map.of("success", false, "message", "没有可用的TTS模型配置");
        }

        try {
            TtsRequest ttsRequest = TtsRequest.builder()
                    .model(resolved.modelId())
                    .input(userInput)
                    .voice(voice != null ? voice : "alloy")
                    .speed(speed > 0 ? speed : 1.0)
                    .baseUrl(resolved.baseUrl())
                    .apiKey(resolved.apiKey())
                    .build();

            byte[] audioBytes = resolved.provider().generateAudio(ttsRequest);

            String filename = UUID.randomUUID() + ".mp3";
            Path dir = Paths.get("data/chat/audio/" + sessionId);
            Files.createDirectories(dir);
            Path filePath = dir.resolve(filename);
            Files.write(filePath, audioBytes);

            String mediaPath = "audio/" + sessionId + "/" + filename;
            saveMessage(sessionId, "assistant", userInput, configId, "TTS", mediaPath);

            return Map.of("success", true, "mediaUrl", "/api/chat/media/" + mediaPath);
        } catch (Exception e) {
            log.error("Chat TTS error: {}", e.getMessage());
            saveMessage(sessionId, "assistant", "语音生成失败: " + e.getMessage(), configId, "TTS", null);
            return Map.of("success", false, "message", e.getMessage() != null ? e.getMessage() : "未知错误");
        }
    }

    // --- Context building ---

    private List<Map<String, String>> buildContextMessages(Long sessionId) {
        List<ChatMessageEntity> recent = messageRepository.findTop40BySessionIdOrderByCreatedAtDesc(sessionId);
        // Reverse to chronological order
        Collections.reverse(recent);

        // Filter to text messages only, take last N
        List<Map<String, String>> messages = new ArrayList<>();
        for (ChatMessageEntity msg : recent) {
            if (!"TEXT".equals(msg.getModelType()) && msg.getModelType() != null) continue;
            if (msg.getContent() == null || msg.getContent().isBlank()) continue;
            messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
            if (messages.size() >= CONTEXT_MESSAGE_LIMIT) break;
        }
        return messages;
    }

    private void saveMessage(Long sessionId, String role, String content, Long configId, String modelType, String mediaFilePath) {
        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setModelConfigId(configId);
        msg.setModelType(modelType);
        msg.setMediaFilePath(mediaFilePath);
        messageRepository.save(msg);
    }
}
