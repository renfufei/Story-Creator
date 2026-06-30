package com.storycreator.workflow.autorun;

import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.AutoRunStepConfigRepository;
import com.storycreator.persistence.repository.CharacterRepository;
import com.storycreator.persistence.repository.ChapterOutlineRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.StoryOutlineRepository;
import com.storycreator.persistence.repository.WorldSettingRepository;
import com.storycreator.workflow.autorun.strategy.AutoRunStrategy;
import com.storycreator.workflow.autorun.strategy.AutoRunStrategyRegistry;
import com.storycreator.workflow.engine.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class AutoRunService {

    private static final Logger log = LoggerFactory.getLogger(AutoRunService.class);

    private final ProjectRepository projectRepository;
    private final ChapterRepository chapterRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final CharacterRepository characterRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final StoryOutlineRepository storyOutlineRepository;
    private final WorkflowEngine workflowEngine;
    private final GlobalSettingService globalSettingService;
    private final AutoRunStepConfigRepository autoRunStepConfigRepository;
    private final AutoRunStrategyRegistry strategyRegistry;

    private final ConcurrentHashMap<Long, Boolean> stopSignals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> runningProjects = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AutoRunObservation> observations = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService observationCleanupScheduler = Executors.newSingleThreadScheduledExecutor();

    public AutoRunService(ProjectRepository projectRepository,
                          ChapterRepository chapterRepository,
                          ChapterOutlineRepository chapterOutlineRepository,
                          CharacterRepository characterRepository,
                          WorldSettingRepository worldSettingRepository,
                          StoryOutlineRepository storyOutlineRepository,
                          WorkflowEngine workflowEngine,
                          GlobalSettingService globalSettingService,
                          AutoRunStepConfigRepository autoRunStepConfigRepository,
                          AutoRunStrategyRegistry strategyRegistry) {
        this.projectRepository = projectRepository;
        this.chapterRepository = chapterRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.characterRepository = characterRepository;
        this.worldSettingRepository = worldSettingRepository;
        this.storyOutlineRepository = storyOutlineRepository;
        this.workflowEngine = workflowEngine;
        this.globalSettingService = globalSettingService;
        this.autoRunStepConfigRepository = autoRunStepConfigRepository;
        this.strategyRegistry = strategyRegistry;
    }

    public void startAutoRun(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        if (project.getStatus() == com.storycreator.core.domain.ProjectStatus.COMPLETED) {
            throw new IllegalStateException("项目状态为「已完本」，无法启动自动创作");
        }
        if (project.getStatus() == com.storycreator.core.domain.ProjectStatus.ABANDONED) {
            throw new IllegalStateException("项目状态为「已废弃」，无法启动自动创作");
        }

        // Atomic check-and-set to prevent double-start race condition
        if (runningProjects.putIfAbsent(projectId, true) != null) {
            throw new IllegalStateException("自动创作已在运行中");
        }

        project.setAutoRunStatus(AutoRunStatus.RUNNING);
        project.setAutoRunError(null);
        project.setAutoRunProgress("准备开始...");
        projectRepository.save(project);

        stopSignals.remove(projectId);

        // Create observation for real-time streaming
        AutoRunObservation obs = new AutoRunObservation();
        obs.setActive(true);
        observations.put(projectId, obs);

        // Resolve strategy
        String strategyName = project.getAutoRunStrategy();
        AutoRunStrategy strategy = strategyRegistry.resolve(strategyName);

        executor.submit(() -> executeAutoRun(projectId, strategy));
    }

    public void stopAutoRun(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        if (project.getAutoRunStatus() != AutoRunStatus.RUNNING) {
            return;
        }

        stopSignals.put(projectId, true);
        project.setAutoRunStatus(AutoRunStatus.STOPPING);
        project.setAutoRunProgress("正在停止...");
        projectRepository.save(project);
    }

    public AutoRunObservation getObservation(Long projectId) {
        return observations.get(projectId);
    }

    public Map<String, Object> getStatus(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        return Map.of(
                "status", project.getAutoRunStatus().name(),
                "step", project.getAutoRunStep() != null ? project.getAutoRunStep() : "",
                "chapter", project.getAutoRunChapter(),
                "progress", project.getAutoRunProgress() != null ? project.getAutoRunProgress() : "",
                "error", project.getAutoRunError() != null ? project.getAutoRunError() : ""
        );
    }

    private void executeAutoRun(Long projectId, AutoRunStrategy strategy) {
        try {
            AutoRunContext ctx = buildContext(projectId);
            strategy.execute(ctx);

            // If strategy returned normally but shouldStop is set, mark as stopped
            if (Boolean.TRUE.equals(stopSignals.get(projectId))) {
                markStopped(projectId);
            }
        } catch (Exception e) {
            log.error("Auto run failed for project {}", projectId, e);
            markFailed(projectId, e.getMessage());
        } finally {
            runningProjects.remove(projectId);
            stopSignals.remove(projectId);
            completeObservation(projectId);
        }
    }

    private AutoRunContext buildContext(Long projectId) {
        return new AutoRunContext(
                projectId,
                projectRepository,
                chapterRepository,
                chapterOutlineRepository,
                characterRepository,
                worldSettingRepository,
                storyOutlineRepository,
                workflowEngine,
                globalSettingService,
                autoRunStepConfigRepository,
                stopSignals,
                observations
        );
    }

    private void completeObservation(Long projectId) {
        AutoRunObservation obs = observations.get(projectId);
        if (obs != null) {
            obs.setActive(false);
            obs.getSink().tryEmitComplete();
            observationCleanupScheduler.schedule(() -> observations.remove(projectId, obs), 30, TimeUnit.SECONDS);
        }
    }

    private void markStopped(Long projectId) {
        stopSignals.remove(projectId);
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        project.setAutoRunStatus(AutoRunStatus.IDLE);
        project.setAutoRunProgress("已停止");
        project.setAutoRunStep(null);
        projectRepository.save(project);
        log.info("Auto run stopped for project {}", projectId);
    }

    private void markFailed(Long projectId, String errorMessage) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        project.setAutoRunStatus(AutoRunStatus.FAILED);
        String error = errorMessage != null ? errorMessage : "未知错误";
        if (error.length() > 490) error = error.substring(0, 490);
        project.setAutoRunError(error);
        projectRepository.save(project);
        log.error("Auto run failed for project {}: {}", projectId, error);
    }
}
