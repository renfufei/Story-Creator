package com.storycreator.web;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.ImageType;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.image.CharacterImageService;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import com.storycreator.workflow.engine.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromptExploreServiceTest {

    @Mock private PromptTemplateRegistry promptRegistry;
    @Mock private WorkflowContextBuilder contextBuilder;
    @Mock private CharacterGenerationService characterGenerationService;
    @Mock private OutlineGenerationService outlineGenerationService;
    @Mock private ProofreadingService proofreadingService;
    @Mock private TitleGenerationService titleGenerationService;
    @Mock private CharacterStateService characterStateService;
    @Mock private CharacterImageService characterImageService;
    @Mock private ProjectRepository projectRepository;
    @Mock private WritingRulesRepository writingRulesRepository;
    @Mock private StyleFingerprintRepository styleFingerprintRepository;
    @Mock private WorldSettingRepository worldSettingRepository;
    @Mock private CharacterRepository characterRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private ChapterOutlineRepository chapterOutlineRepository;
    @Mock private StepGuidanceRepository stepGuidanceRepository;

    private PromptExploreService service;

    @BeforeEach
    void setUp() {
        service = new PromptExploreService(promptRegistry, contextBuilder,
                characterGenerationService, outlineGenerationService,
                proofreadingService, titleGenerationService,
                characterStateService, characterImageService,
                projectRepository, writingRulesRepository,
                styleFingerprintRepository, worldSettingRepository,
                characterRepository, chapterRepository,
                chapterOutlineRepository, stepGuidanceRepository);
    }

    private ProjectEntity mockProject(Genre genre) {
        ProjectEntity project = new ProjectEntity();
        project.setTitle("测试小说");
        project.setGenre(genre);
        project.setDescription("测试描述");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        return project;
    }

    private StepGuidanceEntity mockStepGuidance(WorkflowStep step, String content) {
        StepGuidanceEntity sg = new StepGuidanceEntity();
        sg.setGuidance(content);
        when(stepGuidanceRepository.findByProjectIdAndStep(1L, step)).thenReturn(Optional.of(sg));
        return sg;
    }

    private WorldSettingEntity mockWorldSetting(String content) {
        WorldSettingEntity ws = new WorldSettingEntity();
        ws.setContent(content);
        when(worldSettingRepository.findByProjectId(1L)).thenReturn(Optional.of(ws));
        return ws;
    }

    private void stubTemplateResolution() {
        when(promptRegistry.getSubStepTemplate(any(), any(), any())).thenReturn("tmpl");
        when(promptRegistry.getSubStepSystemPrompt(any(), any(), any())).thenReturn("sys");
        when(promptRegistry.resolveTemplate(anyString(), anyMap())).thenReturn("rendered");
    }

    // ═══════ Existing tests (fixed compilation) ═══════

    @Test
    void resolve_subStep_delegatesToCharacterCardVariables() {
        mockProject(Genre.XUANHUAN);
        Map<String, String> vars = Map.of("cardNumber", "2");
        when(characterGenerationService.buildCharacterCardVariables(1L, 2, 5)).thenReturn(vars);
        stubTemplateResolution();

        service.resolve(WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD,
                1L, null, null, 2, 5, null);

        verify(characterGenerationService).buildCharacterCardVariables(1L, 2, 5);
    }

    @Test
    void resolve_subStep_delegatesToChapterOutlineVariables() {
        mockProject(Genre.DUSHI);
        Map<String, String> vars = Map.of("chapterNumber", "3");
        when(outlineGenerationService.buildChapterOutlineVariables(1L, 3)).thenReturn(vars);
        stubTemplateResolution();

        service.resolve(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.CHAPTER_OUTLINE,
                1L, 3, null, null, null, null);

        verify(outlineGenerationService).buildChapterOutlineVariables(1L, 3);
    }

    @Test
    void resolve_subStep_delegatesToProofreadFixVariables() {
        mockProject(Genre.XUANYI);
        Map<String, String> vars = Map.of("reportSummary", "report");
        when(proofreadingService.buildProofreadFixVariables(1L, 5)).thenReturn(vars);
        stubTemplateResolution();

        service.resolve(WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_FIX,
                1L, 5, null, null, null, null);

        verify(proofreadingService).buildProofreadFixVariables(1L, 5);
    }

    @Test
    void resolve_subStep_delegatesToChapterTitleVariables() {
        mockProject(Genre.LISHI);
        Map<String, String> vars = Map.of("contentPreview", "preview");
        when(titleGenerationService.buildChapterTitleVariables(1L, 2)).thenReturn(vars);
        stubTemplateResolution();

        service.resolve(WorkflowStep.POLISHING, PromptSubStep.CHAPTER_TITLE,
                1L, 2, null, null, null, null);

        verify(titleGenerationService).buildChapterTitleVariables(1L, 2);
    }

    @Test
    void resolve_subStep_delegatesToCharacterStateVariables() {
        mockProject(Genre.KEHUAN);
        Map<String, String> vars = Map.of("dimList", "dims");
        when(characterStateService.buildCharacterStateVariables(1L, 4)).thenReturn(vars);
        stubTemplateResolution();

        service.resolve(WorkflowStep.POLISHING, PromptSubStep.CHARACTER_STATES,
                1L, 4, null, null, null, null);

        verify(characterStateService).buildCharacterStateVariables(1L, 4);
    }

    @Test
    void resolve_subStep_delegatesToImagePromptVariables() {
        mockProject(Genre.QIHUAN);
        Map<String, String> vars = Map.of("gender", "女");
        when(characterImageService.buildImagePromptVariables(1L, 10L, ImageType.AVATAR)).thenReturn(vars);
        stubTemplateResolution();

        service.resolve(WorkflowStep.CHARACTER_DESIGN, PromptSubStep.IMAGE_PROMPT_AVATAR,
                1L, null, 10L, null, null, null);

        verify(characterImageService).buildImagePromptVariables(1L, 10L, ImageType.AVATAR);
    }

    @Test
    void resolve_mainStep_buildsWorkflowContext() {
        mockProject(Genre.XUANHUAN);
        WorkflowContext ctx = mock(WorkflowContext.class);
        Map<String, String> vars = new HashMap<>(Map.of("title", "测试", "stepGuidance", ""));
        when(ctx.toTemplateVariables()).thenReturn(vars);
        when(contextBuilder.build(1L, 0)).thenReturn(ctx);
        when(promptRegistry.getTemplate(WorkflowStep.WORLD_BUILDING, Genre.XUANHUAN)).thenReturn("tmpl");
        when(promptRegistry.getSystemPrompt(WorkflowStep.WORLD_BUILDING, Genre.XUANHUAN)).thenReturn("sys");
        when(promptRegistry.resolveTemplate(anyString(), anyMap())).thenReturn("rendered");

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.WORLD_BUILDING, null, 1L, null, null, null, null, null);

        verify(contextBuilder).build(1L, 0);
        assertThat(result.templateContent()).isEqualTo("tmpl");
        assertThat(result.systemPrompt()).isEqualTo("sys");
    }

    @Test
    void resolve_projectNotFound_throwsException() {
        when(projectRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(
                WorkflowStep.WORLD_BUILDING, null, 999L, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Project not found");
    }

    @Test
    void resolve_nullChapterNumberDefaultsToZero() {
        mockProject(Genre.DUSHI);
        WorkflowContext ctx = mock(WorkflowContext.class);
        when(ctx.toTemplateVariables()).thenReturn(new HashMap<>(Map.of("stepGuidance", "")));
        when(contextBuilder.build(1L, 0)).thenReturn(ctx);
        when(promptRegistry.getTemplate(any(), any())).thenReturn("tmpl");
        when(promptRegistry.getSystemPrompt(any(), any())).thenReturn(null);
        when(promptRegistry.resolveTemplate(anyString(), anyMap())).thenReturn("rendered");

        service.resolve(WorkflowStep.CHAPTER_WRITING, null, 1L, null, null, null, null, null);

        verify(contextBuilder).build(1L, 0);
    }

    @Test
    void resolve_nullCardNumberDefaultsToOne() {
        mockProject(Genre.YANQING);
        when(characterGenerationService.buildCharacterCardVariables(1L, 1, 5)).thenReturn(Map.of());
        stubTemplateResolution();

        service.resolve(WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD,
                1L, null, null, null, 5, null);

        verify(characterGenerationService).buildCharacterCardVariables(1L, 1, 5);
    }

    @Test
    void resolve_returnsCompleteExploreResult() {
        mockProject(Genre.WUXIA);
        Map<String, String> vars = new HashMap<>(Map.of("title", "武侠小说", "stepGuidance", ""));
        WorkflowContext ctx = mock(WorkflowContext.class);
        when(ctx.toTemplateVariables()).thenReturn(vars);
        when(contextBuilder.build(1L, 3)).thenReturn(ctx);
        when(promptRegistry.getTemplate(WorkflowStep.CHAPTER_WRITING, Genre.WUXIA)).thenReturn("写第{{title}}章");
        when(promptRegistry.getSystemPrompt(WorkflowStep.CHAPTER_WRITING, Genre.WUXIA)).thenReturn("你是写手");
        when(promptRegistry.resolveTemplate("写第{{title}}章", vars)).thenReturn("写第武侠小说章");

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.CHAPTER_WRITING, null, 1L, 3, null, null, null, null);

        assertThat(result.templateContent()).isEqualTo("写第{{title}}章");
        assertThat(result.systemPrompt()).isEqualTo("你是写手");
        assertThat(result.renderedPrompt()).isEqualTo("写第武侠小说章");
    }

    // ═══════ Enhanced Sub-Steps: WRITING_RULES ═══════

    @Test
    void resolve_writingRules_loadsProjectAndWorldSetting() {
        mockProject(Genre.XUANHUAN);
        mockWorldSetting("一个魔法世界");
        mockStepGuidance(WorkflowStep.WORLD_BUILDING, "注意世界观一致性");
        stubTemplateResolution();

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.WORLD_BUILDING, PromptSubStep.WRITING_RULES,
                1L, null, null, null, null, null);

        assertThat(result.variables()).containsEntry("title", "测试小说");
        assertThat(result.variables()).containsEntry("genre", "玄幻");
        assertThat(result.variables()).containsEntry("description", "测试描述");
        assertThat(result.variables()).containsEntry("worldSetting", "一个魔法世界");
        assertThat(result.variables().get("stepGuidance")).contains("注意世界观一致性");
    }

    @Test
    void resolve_writingRules_emptyWorldSetting() {
        mockProject(Genre.DUSHI);
        // No world setting exists
        when(worldSettingRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        stubTemplateResolution();

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.WORLD_BUILDING, PromptSubStep.WRITING_RULES,
                1L, null, null, null, null, null);

        assertThat(result.variables()).containsEntry("worldSetting", "");
    }

    // ═══════ Enhanced Sub-Steps: STYLE_FINGERPRINT ═══════

    @Test
    void resolve_styleFingerprint_loadsProjectAndWorldSetting() {
        mockProject(Genre.KEHUAN);
        mockWorldSetting("未来科技世界");
        mockStepGuidance(WorkflowStep.WORLD_BUILDING, "科幻感要强");
        stubTemplateResolution();

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.WORLD_BUILDING, PromptSubStep.STYLE_FINGERPRINT,
                1L, null, null, null, null, null);

        assertThat(result.variables()).containsEntry("worldSetting", "未来科技世界");
        assertThat(result.variables().get("stepGuidance")).contains("科幻感要强");
    }

    // ═══════ Enhanced Sub-Steps: CHARACTER_BEHAVIOR_BOUNDARIES ═══════

    @Test
    void resolve_characterBehaviorBoundaries_loadsCharacterById() {
        mockProject(Genre.WUXIA);
        mockWorldSetting("江湖世界");
        mockStepGuidance(WorkflowStep.CHARACTER_DESIGN, "角色性格鲜明");
        stubTemplateResolution();

        CharacterEntity charEntity = new CharacterEntity();
        charEntity.setName("张三");
        charEntity.setContent("武功高强的侠客");
        when(characterRepository.findById(5L)).thenReturn(Optional.of(charEntity));

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_BEHAVIOR_BOUNDARIES,
                1L, null, 5L, null, null, null);

        assertThat(result.variables()).containsEntry("characterName", "张三");
        assertThat(result.variables()).containsEntry("cardContent", "武功高强的侠客");
        assertThat(result.variables()).containsEntry("worldSetting", "江湖世界");
        assertThat(result.variables().get("stepGuidance")).contains("角色性格鲜明");
    }

    @Test
    void resolve_characterBehaviorBoundaries_fallsBackToFirstChar() {
        mockProject(Genre.XUANYI);
        mockWorldSetting("悬疑世界");
        stubTemplateResolution();

        CharacterEntity firstChar = new CharacterEntity();
        firstChar.setName("李四");
        firstChar.setContent("神秘侦探");
        firstChar.setSortOrder(1);
        when(characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(1L, 0))
                .thenReturn(List.of(firstChar));

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_BEHAVIOR_BOUNDARIES,
                1L, null, null, null, null, null);

        assertThat(result.variables()).containsEntry("characterName", "李四");
        assertThat(result.variables()).containsEntry("cardContent", "神秘侦探");
    }

    // ═══════ Enhanced Sub-Steps: CHAPTER_EVENT_PLAN ═══════

    @Test
    void resolve_chapterEventPlan_loadsOutlineAndCharacters() {
        mockProject(Genre.XUANHUAN);
        mockWorldSetting("仙侠世界");
        stubTemplateResolution();

        WritingRulesEntity rules = new WritingRulesEntity();
        rules.setContent("写作规则内容");
        when(writingRulesRepository.findByProjectId(1L)).thenReturn(Optional.of(rules));

        StyleFingerprintEntity fp = new StyleFingerprintEntity();
        fp.setContent("风格指纹内容");
        when(styleFingerprintRepository.findByProjectId(1L)).thenReturn(Optional.of(fp));

        ChapterOutlineEntity outline = new ChapterOutlineEntity();
        outline.setSummary("第三章摘要");
        when(chapterOutlineRepository.findByProjectIdAndChapterNumber(1L, 3)).thenReturn(Optional.of(outline));

        CharacterEntity char1 = new CharacterEntity();
        char1.setName("主角");
        char1.setContent("强大的修仙者");
        char1.setSortOrder(1);
        when(characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(1L, 0))
                .thenReturn(List.of(char1));

        mockStepGuidance(WorkflowStep.OUTLINE_GENERATION, "大纲指导");

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.OUTLINE_GENERATION, PromptSubStep.CHAPTER_EVENT_PLAN,
                1L, 3, null, null, null, null);

        assertThat(result.variables()).containsEntry("chapterNumber", "3");
        assertThat(result.variables()).containsEntry("chapterSummary", "第三章摘要");
        assertThat(result.variables()).containsEntry("worldSetting", "仙侠世界");
        assertThat(result.variables()).containsEntry("writingRules", "写作规则内容");
        assertThat(result.variables()).containsEntry("styleFingerprint", "风格指纹内容");
        assertThat(result.variables().get("characters")).contains("主角");
        assertThat(result.variables().get("stepGuidance")).contains("大纲指导");
    }

    // ═══════ Enhanced Sub-Steps: CHAPTER_CONTEXT_BRIEFING ═══════

    @Test
    void resolve_chapterContextBriefing_usesContextBuilder() {
        mockProject(Genre.DUSHI);
        stubTemplateResolution();

        WorkflowContext wfCtx = mock(WorkflowContext.class);
        when(wfCtx.getTitle()).thenReturn("都市小说");
        when(wfCtx.getGenre()).thenReturn(Genre.DUSHI);
        when(wfCtx.getChapterSummary()).thenReturn("章节摘要");
        when(wfCtx.getCharacterCards()).thenReturn("角色卡片");
        when(wfCtx.getPreviousChapterContent()).thenReturn("上一章内容");
        when(contextBuilder.build(1L, 2)).thenReturn(wfCtx);

        WritingRulesEntity rules = new WritingRulesEntity();
        rules.setContent("写作规则");
        when(writingRulesRepository.findByProjectId(1L)).thenReturn(Optional.of(rules));

        mockStepGuidance(WorkflowStep.CHAPTER_WRITING, "写作指导");

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.CHAPTER_WRITING, PromptSubStep.CHAPTER_CONTEXT_BRIEFING,
                1L, 2, null, null, null, null);

        assertThat(result.variables()).containsEntry("title", "都市小说");
        assertThat(result.variables()).containsEntry("chapterNumber", "2");
        assertThat(result.variables()).containsEntry("previousChapterContent", "上一章内容");
        assertThat(result.variables()).containsEntry("chapterSummary", "章节摘要");
        assertThat(result.variables()).containsEntry("writingRules", "写作规则");
        assertThat(result.variables()).containsEntry("characterCards", "角色卡片");
        verify(contextBuilder).build(1L, 2);
    }

    // ═══════ Enhanced Sub-Steps: CHAPTER_PLOT_REASONING ═══════

    @Test
    void resolve_chapterPlotReasoning_loadsEventPlanAndBriefing() {
        mockProject(Genre.YANQING);
        stubTemplateResolution();

        WorkflowContext wfCtx = mock(WorkflowContext.class);
        when(wfCtx.getTitle()).thenReturn("言情小说");
        when(wfCtx.getGenre()).thenReturn(Genre.YANQING);
        when(wfCtx.getChapterSummary()).thenReturn("摘要");
        when(wfCtx.getCharacterCards()).thenReturn("角色");
        when(contextBuilder.build(1L, 3)).thenReturn(wfCtx);

        ChapterOutlineEntity outline = new ChapterOutlineEntity();
        outline.setEventPlan("事件计划内容");
        when(chapterOutlineRepository.findByProjectIdAndChapterNumber(1L, 3)).thenReturn(Optional.of(outline));

        ChapterEntity chapter = new ChapterEntity();
        chapter.setWritingBriefing("写作简报内容");
        when(chapterRepository.findByProjectIdAndChapterNumber(1L, 3)).thenReturn(Optional.of(chapter));

        mockStepGuidance(WorkflowStep.CHAPTER_WRITING, "");

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.CHAPTER_WRITING, PromptSubStep.CHAPTER_PLOT_REASONING,
                1L, 3, null, null, null, null);

        assertThat(result.variables()).containsEntry("eventPlan", "事件计划内容");
        assertThat(result.variables()).containsEntry("writingBriefing", "写作简报内容");
        assertThat(result.variables()).containsEntry("chapterNumber", "3");
    }

    // ═══════ Enhanced Sub-Steps: CHAPTER_INSTANT_REVIEW ═══════

    @Test
    void resolve_chapterInstantReview_loadsChapterData() {
        mockProject(Genre.XUANHUAN);
        stubTemplateResolution();

        ChapterEntity chapter = new ChapterEntity();
        chapter.setWritingReasoning("推理过程");
        chapter.setContent("初稿内容");
        when(chapterRepository.findByProjectIdAndChapterNumber(1L, 2)).thenReturn(Optional.of(chapter));

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.CHAPTER_WRITING, PromptSubStep.CHAPTER_INSTANT_REVIEW,
                1L, 2, null, null, null, null);

        assertThat(result.variables()).containsEntry("writingReasoning", "推理过程");
        assertThat(result.variables()).containsEntry("contentDraft", "初稿内容");
        assertThat(result.variables()).containsEntry("chapterNumber", "2");
    }

    @Test
    void resolve_chapterInstantReview_emptyWhenChapterMissing() {
        mockProject(Genre.DUSHI);
        stubTemplateResolution();
        when(chapterRepository.findByProjectIdAndChapterNumber(1L, 99)).thenReturn(Optional.empty());

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.CHAPTER_WRITING, PromptSubStep.CHAPTER_INSTANT_REVIEW,
                1L, 99, null, null, null, null);

        assertThat(result.variables()).containsEntry("writingReasoning", "");
        assertThat(result.variables()).containsEntry("contentDraft", "");
    }

    // ═══════ Enhanced Sub-Steps: CHAPTER_CONTENT_OPTIMIZATION ═══════

    @Test
    void resolve_chapterContentOptimization_loadsReviewAndRules() {
        mockProject(Genre.WUXIA);
        stubTemplateResolution();

        ChapterEntity chapter = new ChapterEntity();
        chapter.setContent("初稿");
        chapter.setInstantReview("即时审阅内容");
        when(chapterRepository.findByProjectIdAndChapterNumber(1L, 4)).thenReturn(Optional.of(chapter));

        WritingRulesEntity rules = new WritingRulesEntity();
        rules.setContent("写作规则");
        when(writingRulesRepository.findByProjectId(1L)).thenReturn(Optional.of(rules));

        StyleFingerprintEntity fp = new StyleFingerprintEntity();
        fp.setContent("风格指纹");
        when(styleFingerprintRepository.findByProjectId(1L)).thenReturn(Optional.of(fp));

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.CHAPTER_WRITING, PromptSubStep.CHAPTER_CONTENT_OPTIMIZATION,
                1L, 4, null, null, null, null);

        assertThat(result.variables()).containsEntry("contentDraft", "初稿");
        assertThat(result.variables()).containsEntry("instantReview", "即时审阅内容");
        assertThat(result.variables()).containsEntry("writingRules", "写作规则");
        assertThat(result.variables()).containsEntry("styleFingerprint", "风格指纹");
    }

    // ═══════ Enhanced Sub-Steps: CHAPTER_STORYLINE_UPDATE ═══════

    @Test
    void resolve_chapterStorylineUpdate_loadsPrevSnapshot() {
        mockProject(Genre.QIHUAN);
        stubTemplateResolution();

        ChapterEntity currentChapter = new ChapterEntity();
        currentChapter.setContent("当前章内容");
        when(chapterRepository.findByProjectIdAndChapterNumber(1L, 3)).thenReturn(Optional.of(currentChapter));

        ChapterEntity prevChapter = new ChapterEntity();
        prevChapter.setStorylineSnapshot("前一章快照");
        when(chapterRepository.findByProjectIdAndChapterNumber(1L, 2)).thenReturn(Optional.of(prevChapter));

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.CHAPTER_WRITING, PromptSubStep.CHAPTER_STORYLINE_UPDATE,
                1L, 3, null, null, null, null);

        assertThat(result.variables()).containsEntry("optimizedContent", "当前章内容");
        assertThat(result.variables()).containsEntry("previousStorylineSnapshot", "前一章快照");
        assertThat(result.variables()).containsEntry("chapterNumber", "3");
    }

    @Test
    void resolve_chapterStorylineUpdate_emptyPrevForChapter1() {
        mockProject(Genre.LISHI);
        stubTemplateResolution();

        ChapterEntity chapter = new ChapterEntity();
        chapter.setContent("第一章内容");
        when(chapterRepository.findByProjectIdAndChapterNumber(1L, 1)).thenReturn(Optional.of(chapter));

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.CHAPTER_WRITING, PromptSubStep.CHAPTER_STORYLINE_UPDATE,
                1L, 1, null, null, null, null);

        assertThat(result.variables()).containsEntry("previousStorylineSnapshot", "");
    }

    // ═══════ Enhanced Sub-Steps: CHAPTER_DEEP_REVIEW ═══════

    @Test
    void resolve_chapterDeepReview_loadsPrevPlotSummary() {
        mockProject(Genre.XUANHUAN);
        stubTemplateResolution();

        ChapterEntity currentChapter = new ChapterEntity();
        currentChapter.setContent("最终内容");
        when(chapterRepository.findByProjectIdAndChapterNumber(1L, 5)).thenReturn(Optional.of(currentChapter));

        ChapterEntity prevChapter = new ChapterEntity();
        prevChapter.setPlotSummary("前章情节摘要");
        when(chapterRepository.findByProjectIdAndChapterNumber(1L, 4)).thenReturn(Optional.of(prevChapter));

        WritingRulesEntity rules = new WritingRulesEntity();
        rules.setContent("规则");
        when(writingRulesRepository.findByProjectId(1L)).thenReturn(Optional.of(rules));

        StyleFingerprintEntity fp = new StyleFingerprintEntity();
        fp.setContent("指纹");
        when(styleFingerprintRepository.findByProjectId(1L)).thenReturn(Optional.of(fp));

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.CHAPTER_WRITING, PromptSubStep.CHAPTER_DEEP_REVIEW,
                1L, 5, null, null, null, null);

        assertThat(result.variables()).containsEntry("finalContent", "最终内容");
        assertThat(result.variables()).containsEntry("previousPlotSummary", "前章情节摘要");
        assertThat(result.variables()).containsEntry("writingRules", "规则");
        assertThat(result.variables()).containsEntry("styleFingerprint", "指纹");
    }

    // ═══════ stepGuidance fix tests ═══════

    @Test
    void resolve_mainStep_overridesStepGuidanceWithCorrectStep() {
        ProjectEntity project = mockProject(Genre.XUANHUAN);
        project.setCurrentStep(WorkflowStep.CHAPTER_WRITING); // project currently at CHAPTER_WRITING

        WorkflowContext ctx = mock(WorkflowContext.class);
        // contextBuilder returns stepGuidance from project.currentStep (CHAPTER_WRITING)
        Map<String, String> vars = new HashMap<>();
        vars.put("stepGuidance", "章写指导-不应出现");
        vars.put("title", "test");
        when(ctx.toTemplateVariables()).thenReturn(vars);
        when(contextBuilder.build(1L, 0)).thenReturn(ctx);
        when(promptRegistry.getTemplate(any(), any())).thenReturn("tmpl");
        when(promptRegistry.getSystemPrompt(any(), any())).thenReturn(null);
        when(promptRegistry.resolveTemplate(anyString(), anyMap())).thenReturn("rendered");

        // Set up guidance for WORLD_BUILDING (the step being explored)
        mockStepGuidance(WorkflowStep.WORLD_BUILDING, "世界观创作指导");

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.WORLD_BUILDING, null, 1L, null, null, null, null, null);

        // stepGuidance should be from WORLD_BUILDING, not CHAPTER_WRITING
        assertThat(result.variables().get("stepGuidance")).contains("世界观创作指导");
        assertThat(result.variables().get("stepGuidance")).doesNotContain("章写指导-不应出现");
    }

    @Test
    void resolve_mainStep_emptyGuidanceWhenNoneExists() {
        mockProject(Genre.DUSHI);
        WorkflowContext ctx = mock(WorkflowContext.class);
        Map<String, String> vars = new HashMap<>();
        vars.put("stepGuidance", "原始值");
        when(ctx.toTemplateVariables()).thenReturn(vars);
        when(contextBuilder.build(1L, 0)).thenReturn(ctx);
        when(promptRegistry.getTemplate(any(), any())).thenReturn("tmpl");
        when(promptRegistry.getSystemPrompt(any(), any())).thenReturn(null);
        when(promptRegistry.resolveTemplate(anyString(), anyMap())).thenReturn("rendered");

        // No guidance exists for this step
        when(stepGuidanceRepository.findByProjectIdAndStep(1L, WorkflowStep.POLISHING))
                .thenReturn(Optional.empty());

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.POLISHING, null, 1L, null, null, null, null, null);

        assertThat(result.variables().get("stepGuidance")).isEmpty();
    }

    @Test
    void resolve_subStep_stepGuidanceUsesParentStep() {
        mockProject(Genre.XUANHUAN);
        mockWorldSetting("世界设定");
        stubTemplateResolution();

        // WRITING_RULES parent step is WORLD_BUILDING
        mockStepGuidance(WorkflowStep.WORLD_BUILDING, "世界观指导内容");

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.WORLD_BUILDING, PromptSubStep.WRITING_RULES,
                1L, null, null, null, null, null);

        assertThat(result.variables().get("stepGuidance")).contains("世界观指导内容");

        // Verify it's NOT using CHARACTER_DESIGN guidance
        verify(stepGuidanceRepository).findByProjectIdAndStep(1L, WorkflowStep.WORLD_BUILDING);
    }
}
