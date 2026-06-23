package com.storycreator.web;

import com.storycreator.ai.router.TtsProviderRegistry;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.TtsExportChapterEntity;
import com.storycreator.persistence.entity.TtsExportTaskEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.tts.Mp3ProcessingService;
import com.storycreator.tts.TtsExportService;
import com.storycreator.tts.TtsExportStatus;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class TtsExportController {

    private final TtsExportService ttsExportService;
    private final ProjectRepository projectRepository;
    private final ChapterRepository chapterRepository;
    private final TtsProviderRegistry ttsProviderRegistry;
    private final AiModelConfigRepository configRepository;
    private final Mp3ProcessingService mp3ProcessingService;

    public TtsExportController(TtsExportService ttsExportService,
                               ProjectRepository projectRepository,
                               ChapterRepository chapterRepository,
                               TtsProviderRegistry ttsProviderRegistry,
                               AiModelConfigRepository configRepository,
                               Mp3ProcessingService mp3ProcessingService) {
        this.ttsExportService = ttsExportService;
        this.projectRepository = projectRepository;
        this.chapterRepository = chapterRepository;
        this.ttsProviderRegistry = ttsProviderRegistry;
        this.configRepository = configRepository;
        this.mp3ProcessingService = mp3ProcessingService;
    }

    // ==================== Page ====================

    @GetMapping("/tts-export")
    public String ttsExportPage(@RequestParam(required = false) Long projectId, Model model) {
        List<ProjectEntity> projects = projectRepository.findAllByOrderByUpdatedAtDesc();
        // Build chapter count and word count map per project
        Map<Long, long[]> chapterStats = new HashMap<>();
        for (Object[] row : chapterRepository.countAndWordCountByProject()) {
            Long pid = (Long) row[0];
            long count = (Long) row[1];
            long wordCount = (Long) row[2];
            chapterStats.put(pid, new long[]{count, wordCount});
        }
        List<Map<String, Object>> projectList = projects.stream().map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.getId());
            m.put("title", p.getTitle());
            long[] stats = chapterStats.getOrDefault(p.getId(), new long[]{0, 0});
            m.put("chapterCount", stats[0]);
            m.put("wordCount", stats[1]);
            return m;
        }).toList();
        model.addAttribute("projects", projectList);
        model.addAttribute("preselectedProjectId", projectId);
        return "tts-export";
    }

    // ==================== REST API ====================

    @GetMapping("/api/tts-export/tasks")
    @ResponseBody
    public List<Map<String, Object>> listTasks() {
        return ttsExportService.getAllTasks().stream().map(this::taskToMap).toList();
    }

    @PostMapping("/api/tts-export/tasks")
    @ResponseBody
    public Map<String, Object> createTask(@RequestBody TtsExportService.CreateTaskRequest request) {
        TtsExportTaskEntity task = ttsExportService.createTask(request);
        return taskToMap(task);
    }

    @GetMapping("/api/tts-export/tasks/{id}")
    @ResponseBody
    public Map<String, Object> getTask(@PathVariable Long id) {
        TtsExportTaskEntity task = ttsExportService.getTask(id);
        if (task == null) return Map.of("error", "任务不存在");
        Map<String, Object> result = taskToMap(task);
        List<TtsExportChapterEntity> chapters = ttsExportService.getTaskChapters(id);
        result.put("chapters", chapters.stream().map(this::chapterToMap).toList());
        return result;
    }

    @PostMapping("/api/tts-export/tasks/{id}/start")
    @ResponseBody
    public Map<String, Object> startTask(@PathVariable Long id) {
        try {
            TtsExportTaskEntity task = ttsExportService.getTask(id);
            if (task == null) return Map.of("error", "任务不存在");
            if (task.getStatus() == TtsExportStatus.PAUSED) {
                ttsExportService.resumeTask(id);
            } else {
                ttsExportService.startTask(id);
            }
            return Map.of("success", true);
        } catch (IllegalStateException e) {
            return Map.of("error", e.getMessage());
        }
    }

    @PostMapping("/api/tts-export/tasks/{id}/pause")
    @ResponseBody
    public Map<String, Object> pauseTask(@PathVariable Long id) {
        ttsExportService.pauseTask(id);
        return Map.of("success", true);
    }

    @PostMapping("/api/tts-export/tasks/{id}/stop")
    @ResponseBody
    public Map<String, Object> stopTask(@PathVariable Long id) {
        ttsExportService.stopTask(id);
        return Map.of("success", true);
    }

    @DeleteMapping("/api/tts-export/tasks/{id}")
    @ResponseBody
    public Map<String, Object> deleteTask(@PathVariable Long id) {
        ttsExportService.deleteTask(id);
        return Map.of("success", true);
    }

    @GetMapping("/api/tts-export/tasks/{id}/chapters/{num}/file")
    public ResponseEntity<Resource> downloadChapterFile(@PathVariable Long id, @PathVariable int num) {
        Path file = ttsExportService.getChapterFilePath(id, num);
        if (file == null || !Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"chapter_" + num + ".mp3\"")
                .body(resource);
    }

    @GetMapping("/api/tts-export/projects/{projectId}/chapters")
    @ResponseBody
    public List<Map<String, Object>> getProjectChapters(@PathVariable Long projectId) {
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        return chapters.stream()
                .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                .map(ch -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("chapterNumber", ch.getChapterNumber());
                    m.put("title", ch.getTitle());
                    return m;
                })
                .toList();
    }

    @GetMapping("/api/tts-export/configs/{configId}/voices")
    @ResponseBody
    public Map<String, Object> getConfigVoices(@PathVariable Long configId) {
        var config = configRepository.findById(configId).orElse(null);
        if (config == null) return Map.of("voices", List.of());
        String extraParams = config.getExtraParams();
        List<String> voices = List.of("alloy", "echo", "fable", "onyx", "nova", "shimmer");
        if (extraParams != null && !extraParams.isBlank()) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var node = mapper.readTree(extraParams);
                if (node.has("voices")) {
                    String voicesStr = node.get("voices").asText();
                    voices = List.of(voicesStr.split(",")).stream()
                            .map(String::trim).filter(s -> !s.isEmpty()).toList();
                }
            } catch (Exception ignored) {}
        }
        return Map.of("voices", voices);
    }

    @GetMapping("/api/tts-export/ffmpeg-status")
    @ResponseBody
    public Map<String, Object> ffmpegStatus() {
        return Map.of("available", mp3ProcessingService.isFfmpegAvailable());
    }

    @GetMapping("/api/tts-export/projects/{projectId}/completed-files")
    @ResponseBody
    public Map<Integer, String> getCompletedFiles(@PathVariable Long projectId) {
        return ttsExportService.getCompletedFiles(projectId);
    }

    // ==================== Helpers ====================

    private Map<String, Object> taskToMap(TtsExportTaskEntity task) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", task.getId());
        m.put("projectId", task.getProjectId());
        m.put("configId", task.getConfigId());
        m.put("voice", task.getVoice());
        m.put("speed", task.getSpeed());
        m.put("minLen", task.getMinLen());
        m.put("maxLen", task.getMaxLen());
        m.put("useFfmpeg", task.isUseFfmpeg());
        m.put("bitrate", task.getBitrate());
        m.put("status", task.getStatus().name());
        m.put("progressChapter", task.getProgressChapter());
        m.put("progressTotalChapters", task.getProgressTotalChapters());
        m.put("errorMessage", task.getErrorMessage());
        m.put("createdAt", task.getCreatedAt() != null ? task.getCreatedAt().toString() : null);
        // Add project name
        projectRepository.findById(task.getProjectId())
                .ifPresent(p -> m.put("projectName", p.getTitle()));
        return m;
    }

    private Map<String, Object> chapterToMap(TtsExportChapterEntity ch) {
        Map<String, Object> m = new HashMap<>();
        m.put("chapterNumber", ch.getChapterNumber());
        m.put("status", ch.getStatus().name());
        m.put("fileSize", ch.getFileSize());
        m.put("errorMessage", ch.getErrorMessage());
        return m;
    }
}
