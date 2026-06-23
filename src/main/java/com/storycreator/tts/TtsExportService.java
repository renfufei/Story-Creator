package com.storycreator.tts;

import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.TtsExportChapterEntity;
import com.storycreator.persistence.entity.TtsExportTaskEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.TtsExportChapterRepository;
import com.storycreator.persistence.repository.TtsExportTaskRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class TtsExportService {

    private static final Logger log = LoggerFactory.getLogger(TtsExportService.class);

    private final TtsExportTaskRepository taskRepository;
    private final TtsExportChapterRepository chapterRepository;
    private final ChapterRepository storyChapterRepository;
    private final TtsService ttsService;
    private final Mp3ProcessingService mp3ProcessingService;

    private final ConcurrentHashMap<Long, Boolean> stopSignals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> pauseSignals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> runningTasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final Path storageBaseDir;

    public TtsExportService(TtsExportTaskRepository taskRepository,
                            TtsExportChapterRepository chapterRepository,
                            ChapterRepository storyChapterRepository,
                            TtsService ttsService,
                            Mp3ProcessingService mp3ProcessingService,
                            @Value("${STORY_DB_PATH:./data}") String dbPath) {
        this.taskRepository = taskRepository;
        this.chapterRepository = chapterRepository;
        this.storyChapterRepository = storyChapterRepository;
        this.ttsService = ttsService;
        this.mp3ProcessingService = mp3ProcessingService;
        this.storageBaseDir = Paths.get(dbPath, "tts-export");
    }

    @PostConstruct
    public void init() {
        // Reset any RUNNING tasks from previous JVM to PAUSED
        int reset = taskRepository.updateStatusByStatus(TtsExportStatus.RUNNING, TtsExportStatus.PAUSED);
        if (reset > 0) {
            log.info("Reset {} stuck RUNNING TTS export tasks to PAUSED", reset);
        }
    }

    public record CreateTaskRequest(
            Long projectId,
            Long configId,
            String voice,
            double speed,
            int minLen,
            int maxLen,
            boolean useFfmpeg,
            String bitrate,
            List<Integer> chapterNumbers
    ) {}

    public TtsExportTaskEntity createTask(CreateTaskRequest request) {
        TtsExportTaskEntity task = new TtsExportTaskEntity();
        task.setProjectId(request.projectId());
        task.setConfigId(request.configId());
        task.setVoice(request.voice() != null ? request.voice() : "alloy");
        task.setSpeed(request.speed() > 0 ? request.speed() : 1.0);
        task.setMinLen(request.minLen() > 0 ? request.minLen() : 30);
        task.setMaxLen(request.maxLen() > 0 ? request.maxLen() : 200);
        task.setUseFfmpeg(request.useFfmpeg());
        task.setBitrate(request.bitrate() != null ? request.bitrate() : "128k");
        task.setProgressTotalChapters(request.chapterNumbers().size());
        task = taskRepository.save(task);

        // Create chapter records
        for (int chapterNum : request.chapterNumbers()) {
            TtsExportChapterEntity chapterEntity = new TtsExportChapterEntity();
            chapterEntity.setTaskId(task.getId());
            chapterEntity.setProjectId(request.projectId());
            chapterEntity.setChapterNumber(chapterNum);
            chapterRepository.save(chapterEntity);
        }

        return task;
    }

    public void startTask(Long taskId) {
        if (runningTasks.putIfAbsent(taskId, true) != null) {
            throw new IllegalStateException("任务已在运行中");
        }

        TtsExportTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        stopSignals.remove(taskId);
        pauseSignals.remove(taskId);

        task.setStatus(TtsExportStatus.RUNNING);
        task.setErrorMessage(null);
        taskRepository.save(task);

        executor.submit(() -> {
            try {
                executeTask(taskId);
            } catch (Exception e) {
                log.error("TTS export task {} failed", taskId, e);
                markFailed(taskId, e.getMessage());
            } finally {
                runningTasks.remove(taskId);
            }
        });
    }

    public void pauseTask(Long taskId) {
        pauseSignals.put(taskId, true);
    }

    public void resumeTask(Long taskId) {
        pauseSignals.remove(taskId);
        stopSignals.remove(taskId);
        startTask(taskId);
    }

    public void stopTask(Long taskId) {
        stopSignals.put(taskId, true);
        pauseSignals.remove(taskId);
    }

    public void deleteTask(Long taskId) {
        // Stop if running
        stopSignals.put(taskId, true);
        // Wait briefly for task to notice
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        TtsExportTaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return;

        // Delete files
        Path taskDir = storageBaseDir.resolve(String.valueOf(task.getProjectId()))
                .resolve(String.valueOf(taskId));
        deleteDirectoryRecursively(taskDir);

        // Delete DB records
        chapterRepository.deleteByTaskId(taskId);
        taskRepository.delete(task);

        stopSignals.remove(taskId);
        runningTasks.remove(taskId);
    }

    public TtsExportTaskEntity getTask(Long taskId) {
        return taskRepository.findById(taskId).orElse(null);
    }

    public List<TtsExportChapterEntity> getTaskChapters(Long taskId) {
        return chapterRepository.findByTaskIdOrderByChapterNumber(taskId);
    }

    public List<TtsExportTaskEntity> getAllTasks() {
        return taskRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<TtsExportTaskEntity> getTasksByProject(Long projectId) {
        return taskRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    /**
     * Returns a map of chapterNumber -> download URL for completed chapters of the latest task.
     */
    public Map<Integer, String> getCompletedFiles(Long projectId) {
        List<TtsExportTaskEntity> tasks = taskRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        Map<Integer, String> result = new HashMap<>();
        for (TtsExportTaskEntity task : tasks) {
            List<TtsExportChapterEntity> chapters = chapterRepository.findByTaskIdOrderByChapterNumber(task.getId());
            for (TtsExportChapterEntity ch : chapters) {
                if (ch.getStatus() == TtsExportChapterStatus.COMPLETED && ch.getFilePath() != null) {
                    // Only use the first (newest) completed file per chapter
                    result.putIfAbsent(ch.getChapterNumber(),
                            "/api/tts-export/tasks/" + task.getId() + "/chapters/" + ch.getChapterNumber() + "/file");
                }
            }
        }
        return result;
    }

    public Path getChapterFilePath(Long taskId, int chapterNumber) {
        TtsExportChapterEntity chapter = chapterRepository.findByTaskIdAndChapterNumber(taskId, chapterNumber)
                .orElse(null);
        if (chapter == null || chapter.getFilePath() == null) return null;
        return storageBaseDir.resolve(chapter.getFilePath());
    }

    private void executeTask(Long taskId) {
        List<TtsExportChapterEntity> chapters = chapterRepository.findByTaskIdOrderByChapterNumber(taskId);
        TtsExportTaskEntity task = taskRepository.findById(taskId).orElseThrow();

        Path taskDir = storageBaseDir.resolve(String.valueOf(task.getProjectId()))
                .resolve(String.valueOf(taskId));
        try {
            Files.createDirectories(taskDir);
        } catch (IOException e) {
            markFailed(taskId, "无法创建存储目录: " + e.getMessage());
            return;
        }

        int completed = 0;
        for (TtsExportChapterEntity chapter : chapters) {
            // Check signals
            if (Boolean.TRUE.equals(stopSignals.get(taskId))) {
                markStopped(taskId);
                return;
            }
            if (Boolean.TRUE.equals(pauseSignals.get(taskId))) {
                markPaused(taskId);
                return;
            }

            // Skip already completed chapters
            if (chapter.getStatus() == TtsExportChapterStatus.COMPLETED) {
                completed++;
                continue;
            }

            // Process this chapter
            chapter.setStatus(TtsExportChapterStatus.RUNNING);
            chapterRepository.save(chapter);

            try {
                Path chapterFile = generateChapterAudio(task, chapter, taskDir);
                chapter.setStatus(TtsExportChapterStatus.COMPLETED);
                chapter.setFilePath(storageBaseDir.relativize(chapterFile).toString());
                chapter.setFileSize(Files.size(chapterFile));
                chapter.setErrorMessage(null);
                chapterRepository.save(chapter);
                completed++;
            } catch (Exception e) {
                log.error("TTS export chapter {} failed for task {}", chapter.getChapterNumber(), taskId, e);
                chapter.setStatus(TtsExportChapterStatus.FAILED);
                chapter.setErrorMessage(truncate(e.getMessage(), 290));
                chapterRepository.save(chapter);
                // Continue to next chapter instead of failing the whole task
            }

            // Update progress
            task = taskRepository.findById(taskId).orElseThrow();
            task.setProgressChapter(completed);
            taskRepository.save(task);
        }

        // Final status
        task = taskRepository.findById(taskId).orElseThrow();
        boolean anyFailed = chapters.stream()
                .map(ch -> chapterRepository.findById(ch.getId()).orElse(ch))
                .anyMatch(ch -> ch.getStatus() == TtsExportChapterStatus.FAILED);

        if (anyFailed) {
            task.setStatus(TtsExportStatus.COMPLETED);
            task.setErrorMessage("部分章节生成失败");
        } else {
            task.setStatus(TtsExportStatus.COMPLETED);
        }
        task.setProgressChapter(completed);
        taskRepository.save(task);
    }

    private Path generateChapterAudio(TtsExportTaskEntity task, TtsExportChapterEntity chapter, Path taskDir) throws IOException {
        // Get text chunks
        List<String> chunks = ttsService.getChapterChunks(
                task.getProjectId(), chapter.getChapterNumber(), task.getMinLen(), task.getMaxLen());

        // Create chunk directory
        Path chunkDir = taskDir.resolve("chapter_" + chapter.getChapterNumber());
        Files.createDirectories(chunkDir);

        List<Path> chunkFiles = new ArrayList<>();
        try {
            // Generate audio for each chunk
            for (int i = 0; i < chunks.size(); i++) {
                // Check stop signal between chunks
                if (Boolean.TRUE.equals(stopSignals.get(task.getId()))) {
                    throw new IOException("任务被停止");
                }

                byte[] audio = ttsService.generateAudioForChunk(
                        task.getConfigId(), chunks.get(i), task.getVoice(), "mp3", task.getSpeed());

                Path chunkFile = chunkDir.resolve("chunk_" + i + ".mp3");
                Files.write(chunkFile, audio);
                chunkFiles.add(chunkFile);
            }

            // Concatenate chunks into final chapter file
            Path outputFile = taskDir.resolve("chapter_" + chapter.getChapterNumber() + ".mp3");
            if (task.isUseFfmpeg() && mp3ProcessingService.isFfmpegAvailable()) {
                mp3ProcessingService.concatenateAndCompress(chunkFiles, outputFile, task.getBitrate());
            } else {
                mp3ProcessingService.concatenateMp3(chunkFiles, outputFile);
            }

            return outputFile;
        } finally {
            // Clean up chunk files
            deleteDirectoryRecursively(chunkDir);
        }
    }

    private void markFailed(Long taskId, String message) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(TtsExportStatus.FAILED);
            task.setErrorMessage(truncate(message, 490));
            taskRepository.save(task);
        });
    }

    private void markStopped(Long taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(TtsExportStatus.STOPPED);
            taskRepository.save(task);
        });
        stopSignals.remove(taskId);
    }

    private void markPaused(Long taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(TtsExportStatus.PAUSED);
            taskRepository.save(task);
        });
    }

    private void deleteDirectoryRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); }
                        catch (IOException e) { log.warn("Failed to delete: {}", path); }
                    });
        } catch (IOException e) {
            log.warn("Failed to walk directory for deletion: {}", dir);
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
