package com.storycreator.sidestory;

import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.SideStoryChapterEntity;
import com.storycreator.persistence.entity.SideStoryEntity;
import com.storycreator.persistence.repository.SideStoryChapterRepository;
import com.storycreator.persistence.repository.SideStoryRepository;
import com.storycreator.workflow.autorun.AutoRunObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
public class SideStoryAutoRunService {

    private static final Logger log = LoggerFactory.getLogger(SideStoryAutoRunService.class);

    private final SideStoryWorkflowService workflowService;
    private final SideStoryRepository sideStoryRepository;
    private final SideStoryChapterRepository sideStoryChapterRepository;
    private final GlobalSettingService globalSettingService;

    private final ConcurrentHashMap<Long, Boolean> stopSignals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> runningSideStories = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AutoRunObservation> observations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Map<String, Object>> statusMap = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();

    public SideStoryAutoRunService(SideStoryWorkflowService workflowService,
                                    SideStoryRepository sideStoryRepository,
                                    SideStoryChapterRepository sideStoryChapterRepository,
                                    GlobalSettingService globalSettingService) {
        this.workflowService = workflowService;
        this.sideStoryRepository = sideStoryRepository;
        this.sideStoryChapterRepository = sideStoryChapterRepository;
        this.globalSettingService = globalSettingService;
    }

    public void startAutoRun(Long projectId, Long sideStoryId) {
        SideStoryEntity sideStory = sideStoryRepository.findById(sideStoryId)
                .orElseThrow(() -> new IllegalArgumentException("Side story not found: " + sideStoryId));

        if ("COMPLETED".equals(sideStory.getStatus())) {
            // Reset to IN_PROGRESS to allow re-run (user may want to re-polish or content was partial)
            sideStory.setStatus("IN_PROGRESS");
            sideStoryRepository.save(sideStory);
        }

        if (runningSideStories.putIfAbsent(sideStoryId, true) != null) {
            throw new IllegalStateException("自动创作已在运行中");
        }

        stopSignals.remove(sideStoryId);

        AutoRunObservation obs = new AutoRunObservation();
        obs.setActive(true);
        observations.put(sideStoryId, obs);

        updateStatus(sideStoryId, "RUNNING", "准备开始", "", 0, "");

        executor.submit(() -> executeAutoRun(projectId, sideStoryId));
    }

    public void stopAutoRun(Long sideStoryId) {
        if (!runningSideStories.containsKey(sideStoryId)) return;
        stopSignals.put(sideStoryId, true);
        updateStatus(sideStoryId, "STOPPING", "正在停止...", "", 0, "");
    }

    public Map<String, Object> getStatus(Long sideStoryId) {
        Map<String, Object> status = statusMap.get(sideStoryId);
        if (status == null) {
            return Map.of("status", "IDLE", "step", "", "progress", "", "chapter", 0, "error", "");
        }
        return status;
    }

    public AutoRunObservation getObservation(Long sideStoryId) {
        return observations.get(sideStoryId);
    }

    private void executeAutoRun(Long projectId, Long sideStoryId) {
        try {
            int timeoutSeconds = globalSettingService.getAiTimeoutSeconds();
            List<SideStoryChapterEntity> chapters = sideStoryChapterRepository
                    .findBySideStoryIdOrderByChapterNumber(sideStoryId);
            int totalSteps = 1 + chapters.size() * 3; // outline + (chapterOutline + writing + polish) * N
            int completedSteps = 0;

            AutoRunObservation obs = observations.get(sideStoryId);

            // Step 1: Generate outline if blank
            SideStoryEntity sideStory = sideStoryRepository.findById(sideStoryId).orElseThrow();
            if (sideStory.getOutline() == null || sideStory.getOutline().isBlank()) {
                if (shouldStop(sideStoryId)) return;
                updateStatus(sideStoryId, "RUNNING", "生成番外故事线", "故事线生成中...", 0, "");
                obs.reset("生成番外故事线", 0);

                Flux<String> flux = workflowService.generateOutline(projectId, sideStoryId);
                String content = generateAndSaveSync(flux, obs, timeoutSeconds);
                workflowService.saveOutline(sideStoryId, content);
            }
            completedSteps++;
            updateProgress(sideStoryId, completedSteps, totalSteps);

            // Step 2: Generate chapter outlines for all chapters
            chapters = sideStoryChapterRepository.findBySideStoryIdOrderByChapterNumber(sideStoryId);
            for (SideStoryChapterEntity ch : chapters) {
                if (shouldStop(sideStoryId)) return;
                if (ch.getOutlineSummary() != null && !ch.getOutlineSummary().isBlank()) {
                    completedSteps++;
                    updateProgress(sideStoryId, completedSteps, totalSteps);
                    continue;
                }

                int chNum = ch.getChapterNumber();
                updateStatus(sideStoryId, "RUNNING", "章节大纲", "第" + chNum + "章大纲生成中...", chNum, "");
                obs.reset("章节大纲 第" + chNum + "章", chNum);

                Flux<String> flux = workflowService.generateChapterOutline(projectId, sideStoryId, chNum);
                String content = generateAndSaveSync(flux, obs, timeoutSeconds);
                workflowService.saveChapterOutline(sideStoryId, chNum, content);

                completedSteps++;
                updateProgress(sideStoryId, completedSteps, totalSteps);
            }

            // Step 3: Generate writing for all chapters
            chapters = sideStoryChapterRepository.findBySideStoryIdOrderByChapterNumber(sideStoryId);
            for (SideStoryChapterEntity ch : chapters) {
                if (shouldStop(sideStoryId)) return;
                if (ch.getContent() != null && !ch.getContent().isBlank()) {
                    completedSteps++;
                    updateProgress(sideStoryId, completedSteps, totalSteps);
                    continue;
                }

                int chNum = ch.getChapterNumber();
                updateStatus(sideStoryId, "RUNNING", "章节写作", "第" + chNum + "章写作中...", chNum, "");
                obs.reset("章节写作 第" + chNum + "章", chNum);

                Flux<String> flux = workflowService.generateChapterContent(projectId, sideStoryId, chNum);
                String content = generateAndSaveSync(flux, obs, timeoutSeconds);
                workflowService.saveChapterContent(sideStoryId, chNum, content);

                completedSteps++;
                updateProgress(sideStoryId, completedSteps, totalSteps);
            }

            // Step 4: Polish all chapters
            chapters = sideStoryChapterRepository.findBySideStoryIdOrderByChapterNumber(sideStoryId);
            for (SideStoryChapterEntity ch : chapters) {
                if (shouldStop(sideStoryId)) return;
                if ("POLISHED".equals(ch.getStatus())) {
                    completedSteps++;
                    updateProgress(sideStoryId, completedSteps, totalSteps);
                    continue;
                }
                if (ch.getContent() == null || ch.getContent().isBlank()) {
                    completedSteps++;
                    updateProgress(sideStoryId, completedSteps, totalSteps);
                    continue;
                }

                int chNum = ch.getChapterNumber();
                updateStatus(sideStoryId, "RUNNING", "润色", "第" + chNum + "章润色中...", chNum, "");
                obs.reset("润色 第" + chNum + "章", chNum);

                Flux<String> flux = workflowService.polishChapter(projectId, sideStoryId, chNum);
                String content = generateAndSaveSync(flux, obs, timeoutSeconds);
                workflowService.savePolishedContent(sideStoryId, chNum, content);

                completedSteps++;
                updateProgress(sideStoryId, completedSteps, totalSteps);
            }

            // Mark side story as completed
            sideStory = sideStoryRepository.findById(sideStoryId).orElseThrow();
            sideStory.setStatus("COMPLETED");
            sideStoryRepository.save(sideStory);

            if (shouldStop(sideStoryId)) {
                updateStatus(sideStoryId, "STOPPED", "已停止", "", 0, "");
            } else {
                updateStatus(sideStoryId, "COMPLETED", "自动创作完成", "", 0, "");
            }
        } catch (Exception e) {
            log.error("Side story auto run failed: sideStoryId={}", sideStoryId, e);
            String error = e.getMessage() != null ? e.getMessage() : "未知错误";
            if (error.length() > 200) error = error.substring(0, 200);
            updateStatus(sideStoryId, "FAILED", "失败", "", 0, error);
        } finally {
            runningSideStories.remove(sideStoryId);
            stopSignals.remove(sideStoryId);
            completeObservation(sideStoryId);
        }
    }

    private String generateAndSaveSync(Flux<String> flux, AutoRunObservation obs, int timeoutSeconds) {
        StringBuilder buffer = new StringBuilder();
        flux.doOnNext(token -> {
            buffer.append(token);
            obs.appendToken(token);
        }).blockLast(Duration.ofSeconds(timeoutSeconds));
        return buffer.toString();
    }

    private boolean shouldStop(Long sideStoryId) {
        return stopSignals.containsKey(sideStoryId);
    }

    private void updateStatus(Long sideStoryId, String status, String step, String progress, int chapter, String error) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        map.put("step", step);
        map.put("progress", progress);
        map.put("chapter", chapter);
        map.put("error", error);
        statusMap.put(sideStoryId, map);
    }

    private void updateProgress(Long sideStoryId, int completed, int total) {
        Map<String, Object> current = statusMap.get(sideStoryId);
        if (current != null) {
            current.put("progress", completed + "/" + total);
        }
    }

    private void completeObservation(Long sideStoryId) {
        AutoRunObservation obs = observations.get(sideStoryId);
        if (obs != null) {
            obs.setActive(false);
            obs.getNotifySink().tryEmitComplete();
            cleanupScheduler.schedule(() -> {
                observations.remove(sideStoryId, obs);
                statusMap.remove(sideStoryId);
            }, 30, TimeUnit.SECONDS);
        }
    }
}
