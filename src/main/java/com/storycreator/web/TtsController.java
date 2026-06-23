package com.storycreator.web;

import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.ai.router.TtsProviderRegistry;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.tts.TtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/projects/{projectId}/tts")
public class TtsController {

    private static final Logger log = LoggerFactory.getLogger(TtsController.class);

    private final TtsService ttsService;
    private final TtsProviderRegistry ttsProviderRegistry;
    private final AiProviderRouter providerRouter;

    public TtsController(TtsService ttsService, TtsProviderRegistry ttsProviderRegistry, AiProviderRouter providerRouter) {
        this.ttsService = ttsService;
        this.ttsProviderRegistry = ttsProviderRegistry;
        this.providerRouter = providerRouter;
    }

    @GetMapping("/configs")
    public ResponseEntity<List<Map<String, Object>>> getConfigs() {
        List<AiModelConfigEntity> configs = ttsProviderRegistry.getActiveTtsConfigs();
        Long defaultTtsId = providerRouter.getGlobalDefaultTtsConfigId();
        List<Map<String, Object>> result = configs.stream()
                .map(c -> {
                    java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
                    map.put("id", c.getId());
                    map.put("displayName", c.getDisplayName() != null ? c.getDisplayName() : c.getModelId());
                    map.put("modelId", c.getModelId());
                    map.put("isDefault", c.getId().equals(defaultTtsId));
                    // Extract voices and format from extraParams if available
                    String voices = extractVoicesFromExtraParams(c.getExtraParams());
                    if (voices != null) map.put("voices", voices);
                    String format = extractFormatFromExtraParams(c.getExtraParams());
                    if (format != null) map.put("format", format);
                    return map;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    private String extractVoicesFromExtraParams(String extraParams) {
        if (extraParams == null || extraParams.isBlank()) return null;
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(extraParams);
            if (node.has("voices")) {
                return node.get("voices").asText();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String extractFormatFromExtraParams(String extraParams) {
        if (extraParams == null || extraParams.isBlank()) return null;
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(extraParams);
            if (node.has("format")) {
                return node.get("format").asText();
            }
        } catch (Exception ignored) {}
        return null;
    }

    @GetMapping("/chapter/{chapterNumber}/chunks")
    public ResponseEntity<List<String>> getChapterChunks(
            @PathVariable Long projectId,
            @PathVariable int chapterNumber,
            @RequestParam(defaultValue = "10") int minLen,
            @RequestParam(defaultValue = "200") int maxLen) {
        try {
            List<String> chunks = ttsService.getChapterChunks(projectId, chapterNumber, minLen, maxLen);
            return ResponseEntity.ok(chunks);
        } catch (Exception e) {
            log.error("Failed to get chapter chunks: project={} chapter={}", projectId, chapterNumber, e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/chunk")
    public ResponseEntity<byte[]> generateChunk(
            @PathVariable Long projectId,
            @RequestBody ChunkRequest request) {
        try {
            byte[] audio = ttsService.generateAudioForChunk(
                    request.configId(),
                    request.text(),
                    request.voice(),
                    request.format() != null ? request.format() : "mp3",
                    request.speed() > 0 ? request.speed() : 1.0
            );
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, getContentType(request.format() != null ? request.format() : "mp3"))
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .body(audio);
        } catch (Exception e) {
            log.error("TTS chunk generation failed: project={}", projectId, e);
            return ResponseEntity.badRequest().build();
        }
    }

    public record ChunkRequest(Long configId, String text, String voice, String format, double speed) {}

    @GetMapping("/chapter/{chapterNumber}")
    public ResponseEntity<byte[]> playChapter(
            @PathVariable Long projectId,
            @PathVariable int chapterNumber,
            @RequestParam Long configId,
            @RequestParam(defaultValue = "alloy") String voice,
            @RequestParam(defaultValue = "mp3") String format,
            @RequestParam(defaultValue = "1.0") double speed) {

        try {
            byte[] audio = ttsService.generateChapterAudio(projectId, configId, chapterNumber, voice, format, speed);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, getContentType(format))
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .body(audio);
        } catch (Exception e) {
            log.error("TTS chapter generation failed: project={} chapter={}", projectId, chapterNumber, e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportAudio(
            @PathVariable Long projectId,
            @RequestParam Long configId,
            @RequestParam(defaultValue = "all") String chapters,
            @RequestParam(defaultValue = "alloy") String voice,
            @RequestParam(defaultValue = "mp3") String format,
            @RequestParam(defaultValue = "1.0") double speed) {

        try {
            List<Integer> chapterNumbers = null;
            if (!"all".equalsIgnoreCase(chapters)) {
                chapterNumbers = Arrays.stream(chapters.split(","))
                        .map(String::trim)
                        .map(Integer::parseInt)
                        .collect(Collectors.toList());
            }

            byte[] audio = ttsService.generateMultiChapterAudio(projectId, configId, chapterNumbers, voice, format, speed);

            String filename = "novel_export." + format;
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, getContentType(format))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(audio);
        } catch (Exception e) {
            log.error("TTS export failed: project={}", projectId, e);
            return ResponseEntity.badRequest().build();
        }
    }

    private String getContentType(String format) {
        return switch (format) {
            case "opus" -> "audio/opus";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "wav" -> "audio/wav";
            default -> "audio/mpeg";
        };
    }
}
