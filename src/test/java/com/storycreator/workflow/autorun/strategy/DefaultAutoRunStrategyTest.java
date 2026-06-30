package com.storycreator.workflow.autorun.strategy;

import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.CharacterEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.CharacterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.workflow.autorun.AutoRunContext;
import com.storycreator.workflow.autorun.AutoRunObservation;
import com.storycreator.workflow.autorun.AutoRunStatus;
import com.storycreator.workflow.engine.WorkflowEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultAutoRunStrategyTest {

    private DefaultAutoRunStrategy strategy;

    @Mock private ProjectRepository projectRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private CharacterRepository characterRepository;
    @Mock private WorkflowEngine workflowEngine;
    @Mock private GlobalSettingService globalSettingService;

    private ConcurrentHashMap<Long, Boolean> stopSignals;
    private ConcurrentHashMap<Long, AutoRunObservation> observations;
    private static final Long PROJECT_ID = 1L;

    @BeforeEach
    void setUp() {
        strategy = new DefaultAutoRunStrategy();
        stopSignals = new ConcurrentHashMap<>();
        observations = new ConcurrentHashMap<>();
    }

    private AutoRunContext buildCtx() {
        return new AutoRunContext(
                PROJECT_ID,
                projectRepository,
                chapterRepository,
                mock(com.storycreator.persistence.repository.ChapterOutlineRepository.class),
                characterRepository,
                mock(com.storycreator.persistence.repository.WorldSettingRepository.class),
                mock(com.storycreator.persistence.repository.StoryOutlineRepository.class),
                workflowEngine,
                globalSettingService,
                mock(com.storycreator.persistence.repository.AutoRunStepConfigRepository.class),
                stopSignals,
                observations
        );
    }

    private ProjectEntity makeProject(WorkflowStep currentStep, int totalChapters) {
        ProjectEntity p = new ProjectEntity();
        p.setId(PROJECT_ID);
        p.setCurrentStep(currentStep);
        p.setTotalChapters(totalChapters);
        p.setCharacterCount(5);
        p.setAutoRunStatus(AutoRunStatus.RUNNING);
        return p;
    }

    @Test
    void execute_worldBuildingStep_callsGenerateAndSave() throws Exception {
        // Project at WORLD_BUILDING, content not complete
        ProjectEntity project = makeProject(WorkflowStep.WORLD_BUILDING, 2);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(globalSettingService.getAiTimeoutSeconds()).thenReturn(300);

        // Make generate return a valid flux that completes
        when(workflowEngine.generate(eq(PROJECT_ID), eq(WorkflowStep.WORLD_BUILDING), eq(0)))
                .thenReturn(Flux.just("世界观内容" + "x".repeat(200)));

        // After generate, the strategy saves + confirms + moves to next step.
        // For simplicity, set stop signal after first step so we don't loop forever
        doAnswer(inv -> {
            stopSignals.put(PROJECT_ID, true);
            return null;
        }).when(workflowEngine).saveGeneratedContent(eq(PROJECT_ID), eq(WorkflowStep.WORLD_BUILDING), anyString(), eq(0));

        AutoRunContext ctx = buildCtx();
        strategy.execute(ctx);

        verify(workflowEngine).generate(PROJECT_ID, WorkflowStep.WORLD_BUILDING, 0);
        verify(workflowEngine).saveGeneratedContent(eq(PROJECT_ID), eq(WorkflowStep.WORLD_BUILDING), anyString(), eq(0));
    }

    @Test
    void execute_skipsDisabledStep() throws Exception {
        ProjectEntity project = makeProject(WorkflowStep.WORLD_BUILDING, 2);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        // Build a context where WORLD_BUILDING is disabled
        var stepConfigRepo = mock(com.storycreator.persistence.repository.AutoRunStepConfigRepository.class);
        var disabledConfig = mock(com.storycreator.persistence.entity.AutoRunStepConfigEntity.class);
        when(disabledConfig.isEnabled()).thenReturn(false);
        when(stepConfigRepo.findByProjectIdAndStep(PROJECT_ID, WorkflowStep.WORLD_BUILDING))
                .thenReturn(Optional.of(disabledConfig));
        // All other steps default to enabled (return empty = enabled)
        lenient().when(stepConfigRepo.findByProjectIdAndStep(eq(PROJECT_ID), eq(WorkflowStep.CHARACTER_DESIGN)))
                .thenReturn(Optional.empty());
        lenient().when(stepConfigRepo.findByProjectIdAndStep(eq(PROJECT_ID), eq(WorkflowStep.OUTLINE_GENERATION)))
                .thenReturn(Optional.empty());
        lenient().when(stepConfigRepo.findByProjectIdAndStep(eq(PROJECT_ID), eq(WorkflowStep.CHAPTER_WRITING)))
                .thenReturn(Optional.empty());
        lenient().when(stepConfigRepo.findByProjectIdAndStep(eq(PROJECT_ID), eq(WorkflowStep.POLISHING)))
                .thenReturn(Optional.empty());
        lenient().when(stepConfigRepo.findByProjectIdAndStep(eq(PROJECT_ID), eq(WorkflowStep.PROOFREADING)))
                .thenReturn(Optional.empty());

        AutoRunContext ctx = new AutoRunContext(
                PROJECT_ID, projectRepository, chapterRepository,
                mock(com.storycreator.persistence.repository.ChapterOutlineRepository.class),
                characterRepository,
                mock(com.storycreator.persistence.repository.WorldSettingRepository.class),
                mock(com.storycreator.persistence.repository.StoryOutlineRepository.class),
                workflowEngine, globalSettingService, stepConfigRepo, stopSignals, observations
        );

        // Stop after skipping first step
        doAnswer(inv -> {
            stopSignals.put(PROJECT_ID, true);
            return null;
        }).when(projectRepository).save(any());

        strategy.execute(ctx);

        // generateAndSave should NOT be called for WORLD_BUILDING
        verify(workflowEngine, never()).generate(eq(PROJECT_ID), eq(WorkflowStep.WORLD_BUILDING), anyInt());
    }

    @Test
    void execute_skipsAlreadyCompleteStep() throws Exception {
        ProjectEntity project = makeProject(WorkflowStep.WORLD_BUILDING, 2);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        // World setting already has content (simulating complete)
        var worldSettingRepo = mock(com.storycreator.persistence.repository.WorldSettingRepository.class);
        var worldSetting = mock(com.storycreator.persistence.entity.WorldSettingEntity.class);
        when(worldSetting.getContent()).thenReturn("x".repeat(100));
        when(worldSettingRepo.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(worldSetting));

        AutoRunContext ctx = new AutoRunContext(
                PROJECT_ID, projectRepository, chapterRepository,
                mock(com.storycreator.persistence.repository.ChapterOutlineRepository.class),
                characterRepository, worldSettingRepo,
                mock(com.storycreator.persistence.repository.StoryOutlineRepository.class),
                workflowEngine, globalSettingService,
                mock(com.storycreator.persistence.repository.AutoRunStepConfigRepository.class),
                stopSignals, observations
        );

        // Stop after first step is confirmed
        doAnswer(inv -> {
            stopSignals.put(PROJECT_ID, true);
            return null;
        }).when(workflowEngine).confirmStep(eq(PROJECT_ID), eq(WorkflowStep.WORLD_BUILDING));

        strategy.execute(ctx);

        // Should call confirmStep but NOT generate
        verify(workflowEngine, never()).generate(eq(PROJECT_ID), eq(WorkflowStep.WORLD_BUILDING), anyInt());
        verify(workflowEngine).confirmStep(PROJECT_ID, WorkflowStep.WORLD_BUILDING);
    }

    @Test
    void execute_stopsOnShouldStop() throws Exception {
        ProjectEntity project = makeProject(WorkflowStep.WORLD_BUILDING, 2);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        // Set stop before execution starts
        stopSignals.put(PROJECT_ID, true);

        AutoRunContext ctx = buildCtx();
        strategy.execute(ctx);

        // Nothing should happen
        verify(workflowEngine, never()).generate(any(), any(), anyInt());
        verify(workflowEngine, never()).confirmStep(any(), any());
    }

    @Test
    void execute_chapterWritingResumesFromIncomplete() throws Exception {
        // Project at CHAPTER_WRITING with 3 total chapters
        ProjectEntity project = makeProject(WorkflowStep.CHAPTER_WRITING, 3);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(globalSettingService.getAiTimeoutSeconds()).thenReturn(300);

        // Make earlier steps content-complete so rewind doesn't trigger
        var worldSettingRepo = mock(com.storycreator.persistence.repository.WorldSettingRepository.class);
        var worldSetting = mock(com.storycreator.persistence.entity.WorldSettingEntity.class);
        when(worldSetting.getContent()).thenReturn("x".repeat(100));
        when(worldSettingRepo.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(worldSetting));

        List<CharacterEntity> chars = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            CharacterEntity c = new CharacterEntity();
            c.setContent("x".repeat(100));
            c.setStatus("REFINED");
            chars.add(c);
        }
        when(characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(PROJECT_ID, 0))
                .thenReturn(chars);

        var storyOutlineRepo = mock(com.storycreator.persistence.repository.StoryOutlineRepository.class);
        var storyOutline = mock(com.storycreator.persistence.entity.StoryOutlineEntity.class);
        when(storyOutline.getContent()).thenReturn("x".repeat(100));
        when(storyOutlineRepo.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(storyOutline));

        var chapterOutlineRepo = mock(com.storycreator.persistence.repository.ChapterOutlineRepository.class);
        var outline1 = mock(com.storycreator.persistence.entity.ChapterOutlineEntity.class);
        when(outline1.getStatus()).thenReturn("REFINED");
        var outline2 = mock(com.storycreator.persistence.entity.ChapterOutlineEntity.class);
        when(outline2.getStatus()).thenReturn("REFINED");
        var outline3 = mock(com.storycreator.persistence.entity.ChapterOutlineEntity.class);
        when(outline3.getStatus()).thenReturn("REFINED");
        when(chapterOutlineRepo.findByProjectIdOrderByChapterNumber(PROJECT_ID))
                .thenReturn(List.of(outline1, outline2, outline3));

        // First 2 chapters have content, 3rd does not
        ChapterEntity ch1 = new ChapterEntity();
        ch1.setChapterNumber(1);
        ch1.setContent("content1");
        ch1.setWordCount(100);
        ch1.setStatus(StepStatus.CONFIRMED);
        ch1.setPolishStatus(StepStatus.NOT_STARTED);

        ChapterEntity ch2 = new ChapterEntity();
        ch2.setChapterNumber(2);
        ch2.setContent("content2");
        ch2.setWordCount(100);
        ch2.setStatus(StepStatus.CONFIRMED);
        ch2.setPolishStatus(StepStatus.NOT_STARTED);

        ChapterEntity ch3 = new ChapterEntity();
        ch3.setChapterNumber(3);
        ch3.setContent(null);
        ch3.setWordCount(0);
        ch3.setStatus(StepStatus.NOT_STARTED);
        ch3.setPolishStatus(StepStatus.NOT_STARTED);

        when(chapterRepository.findByProjectIdOrderByChapterNumber(PROJECT_ID))
                .thenReturn(List.of(ch1, ch2, ch3));

        // Mock: generate chapter 3
        when(workflowEngine.generate(eq(PROJECT_ID), eq(WorkflowStep.CHAPTER_WRITING), eq(3)))
                .thenReturn(Flux.just("chapter 3 content " + "x".repeat(500)));

        // Stop after chapter 3 is saved
        doAnswer(inv -> {
            stopSignals.put(PROJECT_ID, true);
            return null;
        }).when(workflowEngine).saveGeneratedContent(eq(PROJECT_ID), eq(WorkflowStep.CHAPTER_WRITING), anyString(), eq(3));

        AutoRunContext ctx = new AutoRunContext(
                PROJECT_ID, projectRepository, chapterRepository,
                chapterOutlineRepo, characterRepository, worldSettingRepo, storyOutlineRepo,
                workflowEngine, globalSettingService,
                mock(com.storycreator.persistence.repository.AutoRunStepConfigRepository.class),
                stopSignals, observations
        );
        strategy.execute(ctx);

        // Should only generate chapter 3
        verify(workflowEngine, never()).generate(PROJECT_ID, WorkflowStep.CHAPTER_WRITING, 1);
        verify(workflowEngine, never()).generate(PROJECT_ID, WorkflowStep.CHAPTER_WRITING, 2);
        verify(workflowEngine).generate(PROJECT_ID, WorkflowStep.CHAPTER_WRITING, 3);
    }

    @Test
    void execute_marksCompletedWhenAllDone() throws Exception {
        // Project with all steps already complete — should loop through and confirm all
        ProjectEntity project = makeProject(WorkflowStep.WORLD_BUILDING, 2);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        // Mock all steps as content-complete
        var worldSettingRepo = mock(com.storycreator.persistence.repository.WorldSettingRepository.class);
        var worldSetting = mock(com.storycreator.persistence.entity.WorldSettingEntity.class);
        when(worldSetting.getContent()).thenReturn("x".repeat(100));
        when(worldSettingRepo.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(worldSetting));

        // Characters: 5 refined cards
        List<CharacterEntity> chars = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            CharacterEntity c = new CharacterEntity();
            c.setContent("x".repeat(100));
            c.setStatus("REFINED");
            chars.add(c);
        }
        when(characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(PROJECT_ID, 0))
                .thenReturn(chars);

        // Outline complete
        var storyOutlineRepo = mock(com.storycreator.persistence.repository.StoryOutlineRepository.class);
        var storyOutline = mock(com.storycreator.persistence.entity.StoryOutlineEntity.class);
        when(storyOutline.getContent()).thenReturn("x".repeat(100));
        when(storyOutlineRepo.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(storyOutline));

        var chapterOutlineRepo = mock(com.storycreator.persistence.repository.ChapterOutlineRepository.class);
        var outline1 = mock(com.storycreator.persistence.entity.ChapterOutlineEntity.class);
        when(outline1.getStatus()).thenReturn("REFINED");
        var outline2 = mock(com.storycreator.persistence.entity.ChapterOutlineEntity.class);
        when(outline2.getStatus()).thenReturn("REFINED");
        when(chapterOutlineRepo.findByProjectIdOrderByChapterNumber(PROJECT_ID))
                .thenReturn(List.of(outline1, outline2));

        // Chapters: 2 chapters with content and polishing confirmed and proofreading confirmed
        ChapterEntity ch1 = new ChapterEntity();
        ch1.setChapterNumber(1);
        ch1.setContent("x".repeat(100));
        ch1.setWordCount(100);
        ch1.setPolishStatus(StepStatus.CONFIRMED);
        ch1.setProofreadStatus(StepStatus.CONFIRMED);
        ch1.setProofreadFixStatus(StepStatus.CONFIRMED);

        ChapterEntity ch2 = new ChapterEntity();
        ch2.setChapterNumber(2);
        ch2.setContent("x".repeat(100));
        ch2.setWordCount(100);
        ch2.setPolishStatus(StepStatus.CONFIRMED);
        ch2.setProofreadStatus(StepStatus.CONFIRMED);
        ch2.setProofreadFixStatus(StepStatus.CONFIRMED);

        when(chapterRepository.findByProjectIdOrderByChapterNumber(PROJECT_ID))
                .thenReturn(List.of(ch1, ch2));

        AutoRunContext ctx = new AutoRunContext(
                PROJECT_ID, projectRepository, chapterRepository,
                chapterOutlineRepo, characterRepository, worldSettingRepo, storyOutlineRepo,
                workflowEngine, globalSettingService,
                mock(com.storycreator.persistence.repository.AutoRunStepConfigRepository.class),
                stopSignals, observations
        );

        strategy.execute(ctx);

        // Verify COMPLETED status was set
        verify(projectRepository, atLeastOnce()).save(argThat(p ->
                p.getAutoRunStatus() == AutoRunStatus.COMPLETED));
    }

    @Test
    void execute_rewindsToEarliestIncomplete() throws Exception {
        // currentStep = CHAPTER_WRITING but WORLD_BUILDING content is incomplete
        ProjectEntity project = makeProject(WorkflowStep.CHAPTER_WRITING, 2);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(globalSettingService.getAiTimeoutSeconds()).thenReturn(300);

        // World setting is empty → incomplete
        var worldSettingRepo = mock(com.storycreator.persistence.repository.WorldSettingRepository.class);
        when(worldSettingRepo.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());

        // Make generate return content
        when(workflowEngine.generate(eq(PROJECT_ID), eq(WorkflowStep.WORLD_BUILDING), eq(0)))
                .thenReturn(Flux.just("world content " + "x".repeat(200)));

        // Stop after WORLD_BUILDING save
        doAnswer(inv -> {
            stopSignals.put(PROJECT_ID, true);
            return null;
        }).when(workflowEngine).saveGeneratedContent(eq(PROJECT_ID), eq(WorkflowStep.WORLD_BUILDING), anyString(), eq(0));

        AutoRunContext ctx = new AutoRunContext(
                PROJECT_ID, projectRepository, chapterRepository,
                mock(com.storycreator.persistence.repository.ChapterOutlineRepository.class),
                characterRepository, worldSettingRepo,
                mock(com.storycreator.persistence.repository.StoryOutlineRepository.class),
                workflowEngine, globalSettingService,
                mock(com.storycreator.persistence.repository.AutoRunStepConfigRepository.class),
                stopSignals, observations
        );

        strategy.execute(ctx);

        // Should rewind and generate WORLD_BUILDING (not CHAPTER_WRITING)
        verify(workflowEngine).generate(PROJECT_ID, WorkflowStep.WORLD_BUILDING, 0);
        // Verify project was saved with rewound step
        verify(projectRepository, atLeastOnce()).save(argThat(p ->
                p.getCurrentStep() == WorkflowStep.WORLD_BUILDING));
    }
}
