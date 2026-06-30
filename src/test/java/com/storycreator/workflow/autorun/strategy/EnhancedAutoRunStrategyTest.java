package com.storycreator.workflow.autorun.strategy;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import com.storycreator.workflow.autorun.AutoRunContext;
import com.storycreator.workflow.autorun.AutoRunObservation;
import com.storycreator.workflow.autorun.AutoRunStatus;
import com.storycreator.workflow.engine.WorkflowEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnhancedAutoRunStrategyTest {

    private EnhancedAutoRunStrategy strategy;

    @Mock private EnhancedSubStepExecutor executor;
    @Mock private EnhancedChapterWritingService enhancedChapterWritingService;
    @Mock private WritingRulesRepository writingRulesRepository;
    @Mock private StyleFingerprintRepository styleFingerprintRepository;
    @Mock private GlobalSettingService globalSettingService;

    @Mock private ProjectRepository projectRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private ChapterOutlineRepository chapterOutlineRepository;
    @Mock private CharacterRepository characterRepository;
    @Mock private WorldSettingRepository worldSettingRepository;
    @Mock private StoryOutlineRepository storyOutlineRepository;
    @Mock private WorkflowEngine workflowEngine;
    @Mock private AutoRunStepConfigRepository autoRunStepConfigRepository;

    private ConcurrentHashMap<Long, Boolean> stopSignals;
    private ConcurrentHashMap<Long, AutoRunObservation> observations;
    private static final Long PROJECT_ID = 1L;

    @BeforeEach
    void setUp() {
        strategy = new EnhancedAutoRunStrategy(executor, enhancedChapterWritingService,
                writingRulesRepository, styleFingerprintRepository, globalSettingService);
        stopSignals = new ConcurrentHashMap<>();
        observations = new ConcurrentHashMap<>();
    }

    private AutoRunContext buildCtx() {
        return new AutoRunContext(
                PROJECT_ID, projectRepository, chapterRepository, chapterOutlineRepository,
                characterRepository, worldSettingRepository, storyOutlineRepository,
                workflowEngine, globalSettingService, autoRunStepConfigRepository,
                stopSignals, observations
        );
    }

    private ProjectEntity makeProject(WorkflowStep currentStep, int totalChapters) {
        ProjectEntity p = new ProjectEntity();
        p.setId(PROJECT_ID);
        p.setCurrentStep(currentStep);
        p.setTotalChapters(totalChapters);
        p.setCharacterCount(2);
        p.setAutoRunStatus(AutoRunStatus.RUNNING);
        p.setTitle("测试小说");
        p.setGenre(Genre.XUANHUAN);
        p.setDescription("测试描述");
        return p;
    }

    @Test
    void execute_runsPreparationAfterWorldBuilding() throws Exception {
        // Project starts at WORLD_BUILDING; world setting empty initially
        ProjectEntity project = makeProject(WorkflowStep.WORLD_BUILDING, 2);
        project.setCharacterCount(2);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        // WORLD_BUILDING: no world setting yet → not complete, so generate step
        when(worldSettingRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(globalSettingService.getAiTimeoutSeconds()).thenReturn(300);

        // Generate world building content
        when(workflowEngine.generate(eq(PROJECT_ID), eq(WorkflowStep.WORLD_BUILDING), eq(0)))
                .thenReturn(Flux.just("世界观内容" + "x".repeat(200)));

        // After world building saved, make world setting available for preparation
        doAnswer(inv -> {
            WorldSettingEntity ws = new WorldSettingEntity();
            ws.setContent("世界观内容" + "x".repeat(200));
            when(worldSettingRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(ws));
            return null;
        }).when(workflowEngine).saveGeneratedContent(eq(PROJECT_ID), eq(WorkflowStep.WORLD_BUILDING), anyString(), eq(0));

        // CHARACTER_DESIGN: one character not refined → isStepContentComplete = false → enters step
        CharacterEntity c1 = new CharacterEntity();
        c1.setContent("x".repeat(100));
        c1.setStatus("REFINED");
        c1.setName("角色1");
        CharacterEntity c2 = new CharacterEntity();
        c2.setContent("x".repeat(100));
        c2.setStatus("GENERATED"); // Not refined → step incomplete
        c2.setName("角色2");
        when(characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(PROJECT_ID, 0))
                .thenReturn(List.of(c1, c2));

        // Refine will be called (c2 not refined)
        when(workflowEngine.refineAllCharacters(PROJECT_ID)).thenReturn(Flux.empty());

        // Writing rules don't exist yet → should be generated
        when(writingRulesRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(styleFingerprintRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(executor.generateWritingRules(eq(PROJECT_ID), any(), any())).thenReturn("写作规则");
        when(executor.generateStyleFingerprint(eq(PROJECT_ID), any(), any())).thenReturn("风格指纹");

        // Stop after CHARACTER_DESIGN is confirmed
        doAnswer(inv -> {
            stopSignals.put(PROJECT_ID, true);
            return null;
        }).when(workflowEngine).confirmStep(eq(PROJECT_ID), eq(WorkflowStep.CHARACTER_DESIGN));

        AutoRunContext ctx = buildCtx();
        strategy.execute(ctx);

        // Verify preparation ran with worldSetting available
        ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(executor).generateWritingRules(eq(PROJECT_ID), varsCaptor.capture(), eq(Genre.XUANHUAN));
        assertThat(varsCaptor.getValue().get("worldSetting")).contains("世界观内容");
    }

    @Test
    void execute_skipsPreparationIfAlreadyExists() throws Exception {
        ProjectEntity project = makeProject(WorkflowStep.CHARACTER_DESIGN, 2);
        project.setCharacterCount(2);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        // World setting exists
        WorldSettingEntity ws = new WorldSettingEntity();
        ws.setContent("x".repeat(100));
        when(worldSettingRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(ws));

        // Characters: 2 cards with content, but one not REFINED → step not complete
        CharacterEntity c1 = new CharacterEntity();
        c1.setContent("x".repeat(100));
        c1.setStatus("REFINED");
        c1.setName("角色1");
        c1.setBehaviorBoundaries("已有边界");
        CharacterEntity c2 = new CharacterEntity();
        c2.setContent("x".repeat(100));
        c2.setStatus("GENERATED"); // Not refined → isStepContentComplete = false
        c2.setName("角色2");
        c2.setBehaviorBoundaries("已有边界");

        // After refine, both are REFINED
        CharacterEntity c2Refined = new CharacterEntity();
        c2Refined.setContent("x".repeat(100));
        c2Refined.setStatus("REFINED");
        c2Refined.setName("角色2");
        c2Refined.setBehaviorBoundaries("已有边界");

        when(characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(PROJECT_ID, 0))
                .thenReturn(List.of(c1, c2))           // isStepContentComplete check
                .thenReturn(List.of(c1, c2))           // inside runCharacterDesign (validCards check)
                .thenReturn(List.of(c1, c2Refined));   // after refine (boundary check)

        // Writing rules and fingerprint already exist
        WritingRulesEntity rulesEntity = new WritingRulesEntity();
        rulesEntity.setContent("已有规则");
        when(writingRulesRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(rulesEntity));
        StyleFingerprintEntity fpEntity = new StyleFingerprintEntity();
        fpEntity.setContent("已有指纹");
        when(styleFingerprintRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(fpEntity));

        // Refine needed (c2 is not REFINED)
        when(globalSettingService.getAiTimeoutSeconds()).thenReturn(300);
        when(workflowEngine.refineAllCharacters(PROJECT_ID)).thenReturn(Flux.empty());

        // Stop after CHARACTER_DESIGN
        doAnswer(inv -> {
            stopSignals.put(PROJECT_ID, true);
            return null;
        }).when(workflowEngine).confirmStep(eq(PROJECT_ID), eq(WorkflowStep.CHARACTER_DESIGN));

        AutoRunContext ctx = buildCtx();
        strategy.execute(ctx);

        // No AI calls for preparation (rules/fingerprint already exist)
        verify(executor, never()).generateWritingRules(any(), any(), any());
        verify(executor, never()).generateStyleFingerprint(any(), any(), any());
    }

    @Test
    void execute_stopsOnShouldStop() throws Exception {
        ProjectEntity project = makeProject(WorkflowStep.WORLD_BUILDING, 2);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        // Pre-set stop signal
        stopSignals.put(PROJECT_ID, true);

        AutoRunContext ctx = buildCtx();
        strategy.execute(ctx);

        // Nothing generated
        verify(workflowEngine, never()).generate(any(), any(), anyInt());
        verify(executor, never()).generateWritingRules(any(), any(), any());
    }

    @Test
    void execute_characterDesignGeneratesBehaviorBoundaries() throws Exception {
        ProjectEntity project = makeProject(WorkflowStep.CHARACTER_DESIGN, 2);
        project.setCharacterCount(2);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        // World setting exists
        WorldSettingEntity ws = new WorldSettingEntity();
        ws.setContent("x".repeat(100));
        when(worldSettingRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(ws));

        // Preparation already done
        WritingRulesEntity rulesEntity = new WritingRulesEntity();
        rulesEntity.setContent("规则");
        when(writingRulesRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(rulesEntity));
        StyleFingerprintEntity fpEntity = new StyleFingerprintEntity();
        fpEntity.setContent("指纹");
        when(styleFingerprintRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(fpEntity));

        // Characters: 2 cards, both REFINED, but one not yet "REFINED" on first isStepContentComplete call
        // to ensure the step is not skipped. Use sequential return values.
        CharacterEntity c1 = new CharacterEntity();
        c1.setContent("角色1内容" + "x".repeat(100));
        c1.setStatus("REFINED");
        c1.setName("李明");
        c1.setBehaviorBoundaries(null);
        CharacterEntity c2 = new CharacterEntity();
        c2.setContent("角色2内容" + "x".repeat(100));
        c2.setStatus("GENERATED"); // Not refined → isStepContentComplete = false
        c2.setName("王芳");
        c2.setBehaviorBoundaries(null);

        // First call (isStepContentComplete): c2 is GENERATED → not complete
        // Subsequent calls (inside runCharacterDesign after refine): both REFINED
        CharacterEntity c2Refined = new CharacterEntity();
        c2Refined.setContent("角色2内容" + "x".repeat(100));
        c2Refined.setStatus("REFINED");
        c2Refined.setName("王芳");
        c2Refined.setBehaviorBoundaries(null);

        when(characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(PROJECT_ID, 0))
                .thenReturn(List.of(c1, c2))      // 1st call: isStepContentComplete
                .thenReturn(List.of(c1, c2))      // 2nd call: inside runCharacterDesign (validCards check)
                .thenReturn(List.of(c1, c2Refined)); // 3rd call: after refine, boundary generation loop

        // validCards=2 >= characterCount=2 → skip generate step
        // But one character is not REFINED → needs refine
        when(globalSettingService.getAiTimeoutSeconds()).thenReturn(300);
        when(workflowEngine.refineAllCharacters(PROJECT_ID)).thenReturn(Flux.empty());

        when(executor.generateBehaviorBoundaries(eq(PROJECT_ID), any(), any())).thenReturn("边界内容");

        // Stop after CHARACTER_DESIGN confirm
        doAnswer(inv -> {
            stopSignals.put(PROJECT_ID, true);
            return null;
        }).when(workflowEngine).confirmStep(eq(PROJECT_ID), eq(WorkflowStep.CHARACTER_DESIGN));

        AutoRunContext ctx = buildCtx();
        strategy.execute(ctx);

        // Verify behavior boundaries generated for both refined characters
        verify(executor, times(2)).generateBehaviorBoundaries(eq(PROJECT_ID), any(), eq(Genre.XUANHUAN));
    }

    @Test
    void execute_outlineGeneratesEventPlans() throws Exception {
        ProjectEntity project = makeProject(WorkflowStep.OUTLINE_GENERATION, 2);
        project.setCharacterCount(1); // 1 character needed, 1 exists → CHARACTER_DESIGN complete
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        // Earlier steps complete
        WorldSettingEntity ws = new WorldSettingEntity();
        ws.setContent("x".repeat(100));
        when(worldSettingRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(ws));
        CharacterEntity c1 = new CharacterEntity();
        c1.setContent("x".repeat(100));
        c1.setStatus("REFINED");
        c1.setName("角色");
        when(characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(PROJECT_ID, 0))
                .thenReturn(List.of(c1));
        // Outline not yet complete (no story outline)
        when(storyOutlineRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());

        // Preparation already done
        WritingRulesEntity rulesEntity = new WritingRulesEntity();
        rulesEntity.setContent("规则");
        when(writingRulesRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(rulesEntity));
        StyleFingerprintEntity fpEntity = new StyleFingerprintEntity();
        fpEntity.setContent("指纹");
        when(styleFingerprintRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(fpEntity));

        // Generate outline content
        when(globalSettingService.getAiTimeoutSeconds()).thenReturn(300);
        when(workflowEngine.generate(eq(PROJECT_ID), eq(WorkflowStep.OUTLINE_GENERATION), eq(0)))
                .thenReturn(Flux.just("大纲" + "x".repeat(200)));

        // After outline generated, outlines exist without event plans
        ChapterOutlineEntity o1 = new ChapterOutlineEntity();
        o1.setChapterNumber(1);
        o1.setSummary("第1章摘要");
        o1.setEventPlan(null);
        o1.setStatus("REFINED");
        ChapterOutlineEntity o2 = new ChapterOutlineEntity();
        o2.setChapterNumber(2);
        o2.setSummary("第2章摘要");
        o2.setEventPlan(null);
        o2.setStatus("REFINED");
        when(chapterOutlineRepository.findByProjectIdOrderByChapterNumber(PROJECT_ID))
                .thenReturn(List.of(o1, o2));

        when(executor.generateEventPlan(eq(PROJECT_ID), any(), any())).thenReturn("事件计划");

        // Stop after OUTLINE confirm
        doAnswer(inv -> {
            stopSignals.put(PROJECT_ID, true);
            return null;
        }).when(workflowEngine).confirmStep(eq(PROJECT_ID), eq(WorkflowStep.OUTLINE_GENERATION));

        AutoRunContext ctx = buildCtx();
        strategy.execute(ctx);

        // Verify event plans generated for both outlines
        verify(executor, times(2)).generateEventPlan(eq(PROJECT_ID), any(), eq(Genre.XUANHUAN));
        verify(chapterOutlineRepository, times(2)).save(any());
    }

    @Test
    void execute_chapterWritingCallsEnhancedService() throws Exception {
        ProjectEntity project = makeProject(WorkflowStep.CHAPTER_WRITING, 2);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        // Earlier steps complete
        WorldSettingEntity ws = new WorldSettingEntity();
        ws.setContent("x".repeat(100));
        when(worldSettingRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(ws));
        CharacterEntity c1 = new CharacterEntity();
        c1.setContent("x".repeat(100));
        c1.setStatus("REFINED");
        when(characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(PROJECT_ID, 0))
                .thenReturn(List.of(c1));
        project.setCharacterCount(1);
        StoryOutlineEntity so = new StoryOutlineEntity();
        so.setContent("x".repeat(100));
        when(storyOutlineRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(so));
        ChapterOutlineEntity co1 = new ChapterOutlineEntity();
        co1.setStatus("REFINED");
        ChapterOutlineEntity co2 = new ChapterOutlineEntity();
        co2.setStatus("REFINED");
        when(chapterOutlineRepository.findByProjectIdOrderByChapterNumber(PROJECT_ID))
                .thenReturn(List.of(co1, co2));

        // Chapters: no content yet → not complete
        ChapterEntity ch1 = new ChapterEntity();
        ch1.setChapterNumber(1);
        ch1.setWritingCycleStatus("NOT_STARTED");
        ChapterEntity ch2 = new ChapterEntity();
        ch2.setChapterNumber(2);
        ch2.setWritingCycleStatus("NOT_STARTED");
        when(chapterRepository.findByProjectIdOrderByChapterNumber(PROJECT_ID))
                .thenReturn(List.of(ch1, ch2));

        // Preparation
        WritingRulesEntity rulesEntity = new WritingRulesEntity();
        rulesEntity.setContent("规则");
        when(writingRulesRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(rulesEntity));
        StyleFingerprintEntity fpEntity = new StyleFingerprintEntity();
        fpEntity.setContent("指纹");
        when(styleFingerprintRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(fpEntity));

        when(globalSettingService.getEnhancedDeepReviewInterval()).thenReturn(5);

        // Stop after chapter writing
        doAnswer(inv -> {
            stopSignals.put(PROJECT_ID, true);
            return null;
        }).when(workflowEngine).confirmStep(eq(PROJECT_ID), eq(WorkflowStep.CHAPTER_WRITING));

        AutoRunContext ctx = buildCtx();
        strategy.execute(ctx);

        // Verify enhanced chapter writing service called for each chapter
        verify(enhancedChapterWritingService).writeChapterEnhanced(eq(ctx), eq(1), eq("规则"), eq("指纹"), eq(5));
        verify(enhancedChapterWritingService).writeChapterEnhanced(eq(ctx), eq(2), eq("规则"), eq("指纹"), eq(5));
    }

    @Test
    void execute_marksCompletedWhenAllDone() throws Exception {
        // All steps already have content complete
        ProjectEntity project = makeProject(WorkflowStep.WORLD_BUILDING, 2);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        // World setting complete
        WorldSettingEntity ws = new WorldSettingEntity();
        ws.setContent("x".repeat(100));
        when(worldSettingRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(ws));

        // Characters complete
        project.setCharacterCount(1);
        CharacterEntity c1 = new CharacterEntity();
        c1.setContent("x".repeat(100));
        c1.setStatus("REFINED");
        when(characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(PROJECT_ID, 0))
                .thenReturn(List.of(c1));

        // Outline complete
        StoryOutlineEntity so = new StoryOutlineEntity();
        so.setContent("x".repeat(100));
        when(storyOutlineRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(so));
        ChapterOutlineEntity co1 = new ChapterOutlineEntity();
        co1.setStatus("REFINED");
        ChapterOutlineEntity co2 = new ChapterOutlineEntity();
        co2.setStatus("REFINED");
        when(chapterOutlineRepository.findByProjectIdOrderByChapterNumber(PROJECT_ID))
                .thenReturn(List.of(co1, co2));

        // Chapters complete with polish + proofread
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

        // Preparation already done
        WritingRulesEntity rulesEntity = new WritingRulesEntity();
        rulesEntity.setContent("规则");
        when(writingRulesRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(rulesEntity));
        StyleFingerprintEntity fpEntity = new StyleFingerprintEntity();
        fpEntity.setContent("指纹");
        when(styleFingerprintRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(fpEntity));

        AutoRunContext ctx = buildCtx();
        strategy.execute(ctx);

        // Verify COMPLETED status was set
        verify(projectRepository, atLeastOnce()).save(argThat(p ->
                p.getAutoRunStatus() == AutoRunStatus.COMPLETED));
    }
}
