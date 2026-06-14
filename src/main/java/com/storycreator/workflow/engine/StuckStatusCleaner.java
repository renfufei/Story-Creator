package com.storycreator.workflow.engine;

import com.storycreator.core.domain.StepStatus;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.WorkflowStateRepository;
import com.storycreator.workflow.autorun.AutoRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resets any GENERATING statuses back to a safe state on application startup.
 * This handles the case where the app was interrupted mid-generation.
 */
@Component
public class StuckStatusCleaner {

    private static final Logger log = LoggerFactory.getLogger(StuckStatusCleaner.class);

    private final WorkflowStateRepository workflowStateRepository;
    private final ChapterRepository chapterRepository;
    private final ProjectRepository projectRepository;

    public StuckStatusCleaner(WorkflowStateRepository workflowStateRepository,
                              ChapterRepository chapterRepository,
                              ProjectRepository projectRepository) {
        this.workflowStateRepository = workflowStateRepository;
        this.chapterRepository = chapterRepository;
        this.projectRepository = projectRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void resetStuckStatuses() {
        int stepsReset = workflowStateRepository.updateStatusByStatus(
                StepStatus.GENERATING, StepStatus.NOT_STARTED);
        int chaptersReset = chapterRepository.updateStatusByStatus(
                StepStatus.GENERATING, StepStatus.NOT_STARTED);
        int fixReset = chapterRepository.updateProofreadFixStatusByStatus(
                StepStatus.GENERATING, StepStatus.NOT_STARTED);

        if (stepsReset > 0 || chaptersReset > 0 || fixReset > 0) {
            log.info("Reset stuck GENERATING statuses: {} workflow steps, {} chapters, {} proofread fix", stepsReset, chaptersReset, fixReset);
        }

        // Reset stuck auto-run statuses
        projectRepository.findAll().stream()
                .filter(p -> p.getAutoRunStatus() == AutoRunStatus.RUNNING
                        || p.getAutoRunStatus() == AutoRunStatus.STOPPING)
                .forEach(p -> {
                    p.setAutoRunStatus(AutoRunStatus.FAILED);
                    p.setAutoRunError("应用重启，自动创作中断");
                    projectRepository.save(p);
                    log.info("Reset stuck auto-run status for project {}", p.getId());
                });
    }
}
