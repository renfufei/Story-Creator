package com.storycreator.web;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.ImageType;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.image.CharacterImageService;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import com.storycreator.sidestory.SideStoryWorkflowService;
import com.storycreator.workflow.engine.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
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
    @Mock private CharacterStateService characterStateService;
    @Mock private CharacterImageService characterImageService;
    @Mock private SideStoryWorkflowService sideStoryWorkflowService;
    @Mock private ProjectRepository projectRepository;
    @Mock private PromptTemplateRepository promptTemplateRepository;
    @Mock private WorldSettingRepository worldSettingRepository;
    @Mock private CharacterRepository characterRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private StepGuidanceRepository stepGuidanceRepository;
    @Mock private SideStoryRepository sideStoryRepository;
    @Mock private WorldFacetElaborationService worldFacetElaborationService;

    private PromptExploreService service;

    @BeforeEach
    void setUp() {
        service = new PromptExploreService(promptRegistry, contextBuilder,
                characterGenerationService, outlineGenerationService,
                proofreadingService,
                characterStateService, characterImageService,
                sideStoryWorkflowService,
                projectRepository, promptTemplateRepository,
                worldSettingRepository,
                characterRepository, chapterRepository,
                stepGuidanceRepository, sideStoryRepository,
                worldFacetElaborationService);
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

    private void stubTemplateResolution() {
        when(promptRegistry.getSubStepTemplate(any(), any(), any())).thenReturn("tmpl");
        when(promptRegistry.getSubStepSystemPrompt(any(), any(), any())).thenReturn("sys");
        when(promptRegistry.resolveTemplate(anyString(), anyMap())).thenReturn("rendered");
    }

    private PromptExploreContext ctx(Long projectId, Integer chapter, Long characterId, Integer card, Integer total, Integer volume) {
        PromptExploreContext ctx = new PromptExploreContext();
        ctx.setProjectId(projectId);
        ctx.setChapterNumber(chapter);
        ctx.setCharacterId(characterId);
        ctx.setCardNumber(card);
        ctx.setTotalCards(total);
        ctx.setVolumeNumber(volume);
        return ctx;
    }

    @Test
    void resolve_subStep_delegatesToCharacterCardVariables() {
        mockProject(Genre.XUANHUAN);
        Map<String, String> vars = Map.of("cardNumber", "2");
        when(characterGenerationService.buildCharacterCardVariables(1L, 2, 5)).thenReturn(vars);
        stubTemplateResolution();

        service.resolve(WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD,
                ctx(1L, null, null, 2, 5, null));

        verify(characterGenerationService).buildCharacterCardVariables(1L, 2, 5);
    }

    @Test
    void resolve_subStep_delegatesToChapterOutlineVariables() {
        mockProject(Genre.DUSHI);
        Map<String, String> vars = Map.of("chapterNumber", "3");
        when(outlineGenerationService.buildChapterOutlineVariables(1L, 3)).thenReturn(vars);
        stubTemplateResolution();

        service.resolve(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.CHAPTER_OUTLINE,
                ctx(1L, 3, null, null, null, null));

        verify(outlineGenerationService).buildChapterOutlineVariables(1L, 3);
    }

    @Test
    void resolve_subStep_delegatesToProofreadFixVariables() {
        mockProject(Genre.XUANYI);
        Map<String, String> vars = Map.of("reportSummary", "report");
        when(proofreadingService.buildProofreadFixVariables(1L, 5)).thenReturn(vars);
        stubTemplateResolution();

        service.resolve(WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_FIX,
                ctx(1L, 5, null, null, null, null));

        verify(proofreadingService).buildProofreadFixVariables(1L, 5);
    }

    @Test
    void resolve_subStep_delegatesToCharacterStateVariables() {
        mockProject(Genre.KEHUAN);
        Map<String, String> vars = Map.of("dimList", "dims");
        when(characterStateService.buildCharacterStateVariables(1L, 4)).thenReturn(vars);
        stubTemplateResolution();

        service.resolve(WorkflowStep.POLISHING, PromptSubStep.CHARACTER_STATES,
                ctx(1L, 4, null, null, null, null));

        verify(characterStateService).buildCharacterStateVariables(1L, 4);
    }

    @Test
    void resolve_subStep_delegatesToImagePromptVariables() {
        mockProject(Genre.QIHUAN);
        Map<String, String> vars = Map.of("gender", "女");
        when(characterImageService.buildImagePromptVariables(1L, 10L, ImageType.AVATAR)).thenReturn(vars);
        stubTemplateResolution();

        service.resolve(WorkflowStep.CHARACTER_DESIGN, PromptSubStep.IMAGE_PROMPT_AVATAR,
                ctx(1L, null, 10L, null, null, null));

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
                WorkflowStep.WORLD_BUILDING, null, ctx(1L, null, null, null, null, null));

        verify(contextBuilder).build(1L, 0);
        assertThat(result.templateContent()).isEqualTo("tmpl");
        assertThat(result.systemPrompt()).isEqualTo("sys");
    }

    @Test
    void resolve_projectNotFound_throwsException() {
        when(projectRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(
                WorkflowStep.WORLD_BUILDING, null, ctx(999L, null, null, null, null, null)))
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

        service.resolve(WorkflowStep.CHAPTER_WRITING, null, ctx(1L, null, null, null, null, null));

        verify(contextBuilder).build(1L, 0);
    }

    @Test
    void resolve_nullCardNumberDefaultsToOne() {
        mockProject(Genre.YANQING);
        when(characterGenerationService.buildCharacterCardVariables(1L, 1, 5)).thenReturn(Map.of());
        stubTemplateResolution();

        service.resolve(WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD,
                ctx(1L, null, null, null, 5, null));

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
                WorkflowStep.CHAPTER_WRITING, null, ctx(1L, 3, null, null, null, null));

        assertThat(result.templateContent()).isEqualTo("写第{{title}}章");
        assertThat(result.systemPrompt()).isEqualTo("你是写手");
        assertThat(result.renderedPrompt()).isEqualTo("写第武侠小说章");
    }

    // ═══════ CHAPTER_CONTEXT_BRIEFING ═══════

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

        mockStepGuidance(WorkflowStep.CHAPTER_WRITING, "写作指导");

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.CHAPTER_WRITING, PromptSubStep.CHAPTER_CONTEXT_BRIEFING,
                ctx(1L, 2, null, null, null, null));

        assertThat(result.variables()).containsEntry("title", "都市小说");
        assertThat(result.variables()).containsEntry("chapterNumber", "2");
        assertThat(result.variables()).containsEntry("previousChapterContent", "上一章内容");
        assertThat(result.variables()).containsEntry("chapterSummary", "章节摘要");
        assertThat(result.variables()).containsEntry("writingRules", "");
        assertThat(result.variables()).containsEntry("characterCards", "角色卡片");
        verify(contextBuilder).build(1L, 2);
    }

    // ═══════ stepGuidance fix tests ═══════

    @Test
    void resolve_mainStep_overridesStepGuidanceWithCorrectStep() {
        ProjectEntity project = mockProject(Genre.XUANHUAN);
        project.setCurrentStep(WorkflowStep.CHAPTER_WRITING);

        WorkflowContext ctx = mock(WorkflowContext.class);
        Map<String, String> vars = new HashMap<>();
        vars.put("stepGuidance", "章写指导-不应出现");
        vars.put("title", "test");
        when(ctx.toTemplateVariables()).thenReturn(vars);
        when(contextBuilder.build(1L, 0)).thenReturn(ctx);
        when(promptRegistry.getTemplate(any(), any())).thenReturn("tmpl");
        when(promptRegistry.getSystemPrompt(any(), any())).thenReturn(null);
        when(promptRegistry.resolveTemplate(anyString(), anyMap())).thenReturn("rendered");

        mockStepGuidance(WorkflowStep.WORLD_BUILDING, "世界观创作指导");

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.WORLD_BUILDING, null, ctx(1L, null, null, null, null, null));

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

        when(stepGuidanceRepository.findByProjectIdAndStep(1L, WorkflowStep.POLISHING))
                .thenReturn(Optional.empty());

        PromptExploreService.ExploreResult result = service.resolve(
                WorkflowStep.POLISHING, null, ctx(1L, null, null, null, null, null));

        assertThat(result.variables().get("stepGuidance")).isEmpty();
    }
}
