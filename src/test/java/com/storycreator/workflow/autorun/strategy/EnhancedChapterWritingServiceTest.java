package com.storycreator.workflow.autorun.strategy;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ChapterOutlineEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.ChapterOutlineRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.workflow.autorun.AutoRunContext;
import com.storycreator.workflow.autorun.AutoRunObservation;
import com.storycreator.workflow.engine.ContextSummaryService;
import com.storycreator.workflow.engine.WorkflowContext;
import com.storycreator.workflow.engine.WorkflowContextBuilder;
import com.storycreator.workflow.engine.WorkflowEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnhancedChapterWritingServiceTest {

    private EnhancedChapterWritingService service;

    @Mock private EnhancedSubStepExecutor executor;
    @Mock private ChapterRepository chapterRepository;
    @Mock private ChapterOutlineRepository chapterOutlineRepository;
    @Mock private WorkflowEngine workflowEngine;
    @Mock private WorkflowContextBuilder contextBuilder;
    @Mock private ContextSummaryService contextSummaryService;
    @Mock private ProjectRepository projectRepository;
    @Mock private GlobalSettingService globalSettingService;

    private ConcurrentHashMap<Long, Boolean> stopSignals;
    private ConcurrentHashMap<Long, AutoRunObservation> observations;
    private static final Long PROJECT_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new EnhancedChapterWritingService(executor, chapterRepository,
                chapterOutlineRepository, workflowEngine, contextBuilder, contextSummaryService);
        stopSignals = new ConcurrentHashMap<>();
        observations = new ConcurrentHashMap<>();
    }

    private AutoRunContext buildCtx() {
        return new AutoRunContext(
                PROJECT_ID, projectRepository, chapterRepository,
                chapterOutlineRepository,
                mock(com.storycreator.persistence.repository.CharacterRepository.class),
                mock(com.storycreator.persistence.repository.WorldSettingRepository.class),
                mock(com.storycreator.persistence.repository.StoryOutlineRepository.class),
                workflowEngine, globalSettingService,
                mock(com.storycreator.persistence.repository.AutoRunStepConfigRepository.class),
                stopSignals, observations
        );
    }

    private ChapterEntity makeChapter(int num, String cycleStatus) {
        ChapterEntity ch = new ChapterEntity();
        ch.setChapterNumber(num);
        ch.setWritingCycleStatus(cycleStatus);
        ch.setContent("章节内容");
        ch.setWordCount(100);
        return ch;
    }

    private void setupWorkflowContext() {
        WorkflowContext wfCtx = mock(WorkflowContext.class);
        when(wfCtx.getGenre()).thenReturn(Genre.XUANHUAN);
        when(wfCtx.getChapterSummary()).thenReturn("摘要");
        when(wfCtx.getCharacterCards()).thenReturn("角色卡");
        when(wfCtx.getStepGuidance()).thenReturn("");
        when(wfCtx.getTitle()).thenReturn("测试标题");
        when(wfCtx.getPreviousChapterContent()).thenReturn("前文内容");
        when(contextBuilder.build(eq(PROJECT_ID), anyInt())).thenReturn(wfCtx);
    }

    private void setupChapterOutline(int chapterNumber) {
        ChapterOutlineEntity outline = new ChapterOutlineEntity();
        outline.setEventPlan("事件计划");
        lenient().when(chapterOutlineRepository.findByProjectIdAndChapterNumber(PROJECT_ID, chapterNumber))
                .thenReturn(Optional.of(outline));
    }

    @Test
    void writeChapterEnhanced_fullCycleFromNotStarted() {
        ChapterEntity chapter = makeChapter(1, "NOT_STARTED");
        when(chapterRepository.findByProjectIdAndChapterNumber(PROJECT_ID, 1))
                .thenReturn(Optional.of(chapter));
        setupWorkflowContext();
        setupChapterOutline(1);

        // Mock all executor calls
        when(executor.generateContextBriefing(eq(PROJECT_ID), any(), any())).thenReturn("前文梳理");
        when(executor.generatePlotReasoning(eq(PROJECT_ID), any(), any())).thenReturn("推演结果");
        when(executor.runInstantReview(eq(PROJECT_ID), any(), any())).thenReturn("审查通过");
        when(executor.updateStoryline(eq(PROJECT_ID), any(), any())).thenReturn("故事线");

        // Mock projectRepository for ctx.generateAndSave (it calls findById)
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        lenient().when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        lenient().when(globalSettingService.getAiTimeoutSeconds()).thenReturn(300);

        // For step 3 (content generation), mock the ctx.generateAndSave flow
        when(workflowEngine.generate(eq(PROJECT_ID), eq(WorkflowStep.CHAPTER_WRITING), eq(1)))
                .thenReturn(reactor.core.publisher.Flux.just("生成内容" + "x".repeat(200)));

        AutoRunContext ctx = buildCtx();
        service.writeChapterEnhanced(ctx, 1, "写作规则", "风格指纹", 5);

        // Verify all steps ran
        verify(executor).generateContextBriefing(eq(PROJECT_ID), any(), any());
        verify(executor).generatePlotReasoning(eq(PROJECT_ID), any(), any());
        verify(workflowEngine).generate(PROJECT_ID, WorkflowStep.CHAPTER_WRITING, 1);
        verify(executor).runInstantReview(eq(PROJECT_ID), any(), any());
        verify(executor).updateStoryline(eq(PROJECT_ID), any(), any());
        // Final status saved as DONE
        verify(chapterRepository, atLeast(5)).save(argThat(ch ->
                ch.getChapterNumber() == 1));
    }

    @Test
    void writeChapterEnhanced_resumesFromBriefingDone() {
        ChapterEntity chapter = makeChapter(1, "BRIEFING_DONE");
        chapter.setWritingBriefing("已有前文梳理");
        when(chapterRepository.findByProjectIdAndChapterNumber(PROJECT_ID, 1))
                .thenReturn(Optional.of(chapter));
        setupWorkflowContext();
        setupChapterOutline(1);

        when(executor.generatePlotReasoning(eq(PROJECT_ID), any(), any())).thenReturn("推演");
        when(executor.runInstantReview(eq(PROJECT_ID), any(), any())).thenReturn("通过");
        when(executor.updateStoryline(eq(PROJECT_ID), any(), any())).thenReturn("故事线");

        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        lenient().when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        lenient().when(globalSettingService.getAiTimeoutSeconds()).thenReturn(300);
        when(workflowEngine.generate(eq(PROJECT_ID), eq(WorkflowStep.CHAPTER_WRITING), eq(1)))
                .thenReturn(reactor.core.publisher.Flux.just("内容" + "x".repeat(200)));

        AutoRunContext ctx = buildCtx();
        service.writeChapterEnhanced(ctx, 1, "规则", "指纹", 5);

        // Step 1 (context briefing) should NOT be called
        verify(executor, never()).generateContextBriefing(any(), any(), any());
        // Step 2 (plot reasoning) should be called
        verify(executor).generatePlotReasoning(eq(PROJECT_ID), any(), any());
    }

    @Test
    void writeChapterEnhanced_skipsOptimizationWhenReviewPasses() {
        ChapterEntity chapter = makeChapter(1, "CONTENT_DONE");
        chapter.setContent("已有内容");
        when(chapterRepository.findByProjectIdAndChapterNumber(PROJECT_ID, 1))
                .thenReturn(Optional.of(chapter));
        setupWorkflowContext();
        setupChapterOutline(1);

        // Review passes (no "需优化")
        when(executor.runInstantReview(eq(PROJECT_ID), any(), any())).thenReturn("审查通过，质量良好");
        when(executor.updateStoryline(eq(PROJECT_ID), any(), any())).thenReturn("故事线");

        AutoRunContext ctx = buildCtx();
        service.writeChapterEnhanced(ctx, 1, "规则", "指纹", 5);

        // Optimization should NOT be called
        verify(executor, never()).runContentOptimization(any(), any(), any());
        verify(executor).updateStoryline(eq(PROJECT_ID), any(), any());
    }

    @Test
    void writeChapterEnhanced_runsOptimizationWhenReviewFails() {
        ChapterEntity chapter = makeChapter(1, "CONTENT_DONE");
        chapter.setContent("需要优化的内容");
        when(chapterRepository.findByProjectIdAndChapterNumber(PROJECT_ID, 1))
                .thenReturn(Optional.of(chapter));
        setupWorkflowContext();
        setupChapterOutline(1);

        // Review fails (contains "需优化")
        when(executor.runInstantReview(eq(PROJECT_ID), any(), any())).thenReturn("需优化：节奏较慢");
        when(executor.runContentOptimization(eq(PROJECT_ID), any(), any())).thenReturn("优化后的内容");
        when(executor.updateStoryline(eq(PROJECT_ID), any(), any())).thenReturn("故事线");

        AutoRunContext ctx = buildCtx();
        service.writeChapterEnhanced(ctx, 1, "规则", "指纹", 5);

        verify(executor).runContentOptimization(eq(PROJECT_ID), any(), any());
    }

    @Test
    void writeChapterEnhanced_runsDeepReviewAtInterval() {
        // chapterNumber=5, interval=5 → deep review should run
        ChapterEntity chapter = makeChapter(5, "OPTIMIZED");
        chapter.setContent("已优化内容");
        when(chapterRepository.findByProjectIdAndChapterNumber(PROJECT_ID, 5))
                .thenReturn(Optional.of(chapter));
        setupWorkflowContext();
        setupChapterOutline(5);

        when(executor.updateStoryline(eq(PROJECT_ID), any(), any())).thenReturn("故事线");
        when(executor.runDeepReview(eq(PROJECT_ID), any(), any())).thenReturn("深度审查结果");

        AutoRunContext ctx = buildCtx();
        service.writeChapterEnhanced(ctx, 5, "规则", "指纹", 5);

        verify(executor).updateStoryline(eq(PROJECT_ID), any(), any());
        verify(executor).runDeepReview(eq(PROJECT_ID), any(), any());
    }

    @Test
    void writeChapterEnhanced_skipsDeepReviewNotAtInterval() {
        // chapterNumber=3, interval=5 → deep review should NOT run
        ChapterEntity chapter = makeChapter(3, "OPTIMIZED");
        chapter.setContent("已优化内容");
        when(chapterRepository.findByProjectIdAndChapterNumber(PROJECT_ID, 3))
                .thenReturn(Optional.of(chapter));
        setupWorkflowContext();
        setupChapterOutline(3);

        when(executor.updateStoryline(eq(PROJECT_ID), any(), any())).thenReturn("故事线");

        AutoRunContext ctx = buildCtx();
        service.writeChapterEnhanced(ctx, 3, "规则", "指纹", 5);

        verify(executor).updateStoryline(eq(PROJECT_ID), any(), any());
        verify(executor, never()).runDeepReview(any(), any(), any());
    }

    @Test
    void writeChapterEnhanced_skipsIfAlreadyDone() {
        ChapterEntity chapter = makeChapter(1, "DONE");
        when(chapterRepository.findByProjectIdAndChapterNumber(PROJECT_ID, 1))
                .thenReturn(Optional.of(chapter));

        // contextBuilder.build() is called before the DONE check
        WorkflowContext wfCtx = mock(WorkflowContext.class);
        lenient().when(wfCtx.getGenre()).thenReturn(Genre.XUANHUAN);
        lenient().when(wfCtx.getChapterSummary()).thenReturn("");
        lenient().when(wfCtx.getCharacterCards()).thenReturn("");
        lenient().when(wfCtx.getStepGuidance()).thenReturn("");
        lenient().when(wfCtx.getTitle()).thenReturn("");
        lenient().when(wfCtx.getPreviousChapterContent()).thenReturn("");
        when(contextBuilder.build(eq(PROJECT_ID), eq(1))).thenReturn(wfCtx);

        AutoRunContext ctx = buildCtx();
        service.writeChapterEnhanced(ctx, 1, "规则", "指纹", 5);

        // Nothing should run
        verify(executor, never()).generateContextBriefing(any(), any(), any());
        verify(executor, never()).generatePlotReasoning(any(), any(), any());
        verify(executor, never()).runInstantReview(any(), any(), any());
    }

    @Test
    void writeChapterEnhanced_stopsOnShouldStop() {
        ChapterEntity chapter = makeChapter(1, "NOT_STARTED");
        when(chapterRepository.findByProjectIdAndChapterNumber(PROJECT_ID, 1))
                .thenReturn(Optional.of(chapter));
        setupWorkflowContext();
        setupChapterOutline(1);

        // Set stop signal before execution
        stopSignals.put(PROJECT_ID, true);

        AutoRunContext ctx = buildCtx();
        service.writeChapterEnhanced(ctx, 1, "规则", "指纹", 5);

        // No AI calls should be made
        verify(executor, never()).generateContextBriefing(any(), any(), any());
    }

    @Test
    void writeChapterEnhanced_regeneratesContentSummaryAfterOptimization() {
        ChapterEntity chapter = makeChapter(1, "CONTENT_DONE");
        chapter.setContent("需要优化的内容");
        when(chapterRepository.findByProjectIdAndChapterNumber(PROJECT_ID, 1))
                .thenReturn(Optional.of(chapter));
        setupWorkflowContext();
        setupChapterOutline(1);

        // Review fails → optimization runs
        when(executor.runInstantReview(eq(PROJECT_ID), any(), any())).thenReturn("需优化：节奏较慢");
        when(executor.runContentOptimization(eq(PROJECT_ID), any(), any())).thenReturn("优化后内容");
        when(contextSummaryService.summarizeChapterContent(eq(PROJECT_ID), eq(1), eq("优化后内容")))
                .thenReturn("新摘要");
        when(executor.updateStoryline(eq(PROJECT_ID), any(), any())).thenReturn("故事线");

        AutoRunContext ctx = buildCtx();
        service.writeChapterEnhanced(ctx, 1, "规则", "指纹", 5);

        // Verify content summary was regenerated
        verify(contextSummaryService).summarizeChapterContent(PROJECT_ID, 1, "优化后内容");
        // Verify saved chapter has new content summary
        verify(chapterRepository, atLeastOnce()).save(argThat(ch ->
                ch.getChapterNumber() == 1 && "新摘要".equals(ch.getContentSummary())));
    }

    @Test
    void writeChapterEnhanced_doesNotResummarizeWhenOptimizationSkipped() {
        ChapterEntity chapter = makeChapter(1, "CONTENT_DONE");
        chapter.setContent("已有内容");
        when(chapterRepository.findByProjectIdAndChapterNumber(PROJECT_ID, 1))
                .thenReturn(Optional.of(chapter));
        setupWorkflowContext();
        setupChapterOutline(1);

        // Review passes (no "需优化")
        when(executor.runInstantReview(eq(PROJECT_ID), any(), any())).thenReturn("审查通过");
        when(executor.updateStoryline(eq(PROJECT_ID), any(), any())).thenReturn("故事线");

        AutoRunContext ctx = buildCtx();
        service.writeChapterEnhanced(ctx, 1, "规则", "指纹", 5);

        // Content summary should NOT be regenerated
        verify(contextSummaryService, never()).summarizeChapterContent(any(), anyInt(), any());
    }
}
