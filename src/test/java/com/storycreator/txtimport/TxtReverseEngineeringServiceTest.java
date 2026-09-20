package com.storycreator.txtimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.port.ai.AiProvider;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.persistence.entity.ChapterOutlineEntity;
import com.storycreator.persistence.entity.CharacterEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.StoryOutlineEntity;
import com.storycreator.persistence.entity.TxtImportChapterEntity;
import com.storycreator.persistence.entity.TxtImportJobEntity;
import com.storycreator.persistence.entity.TxtImportReStepEntity;
import com.storycreator.persistence.entity.VolumeOutlineEntity;
import com.storycreator.persistence.entity.WorldSettingEntity;
import com.storycreator.persistence.entity.WorkflowStateEntity;
import com.storycreator.persistence.repository.ChapterOutlineRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.CharacterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.StoryOutlineRepository;
import com.storycreator.persistence.repository.TxtImportChapterRepository;
import com.storycreator.persistence.repository.TxtImportJobRepository;
import com.storycreator.persistence.repository.TxtImportReStepRepository;
import com.storycreator.persistence.repository.VolumeOutlineRepository;
import com.storycreator.persistence.repository.WorldSettingRepository;
import com.storycreator.persistence.repository.WorkflowStateRepository;
import com.storycreator.workflow.engine.AiUsageTracker;
import com.storycreator.workflow.background.BackgroundGenerationService;
import com.storycreator.workflow.engine.ContextSummaryService;
import com.storycreator.workflow.engine.WorldFacetElaborationService;
import com.storycreator.workflow.engine.WorkflowStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 逆向工程「专门的流程控制 + 实时显示 + 断点续跑」的固化测试。
 *
 * <p>之前这套逻辑是靠在跑起来的服务上手工 {@code curl} 验证的；此处落成自动化用例，
 * 用真实仓储（H2 + Flyway）＋ 假 {@link AiProvider}（不真正调 LLM）覆盖：
 * <ul>
 *   <li>{@link TxtReverseEngineeringService#plan} 以产出物扫描完成度（PARTIAL / COMPLETED / SKIPPED / RUNNING）</li>
 *   <li>续跑时<b>跳过已完成章节</b>、只补剩余（不再发起 LLM 请求）</li>
 *   <li>取消后不再发起 LLM 请求；全部完成时自动跳过</li>
 *   <li>超过 32K 的单章拆成多段分别调用；章节大纲 / 角色名解析去重</li>
 *   <li>手动停止与清空重跑的状态迁移</li>
 * </ul>
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:re_service_test;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
class TxtReverseEngineeringServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CONTEXT = 32 * 1024;
    private static final String REVERSE_CHARACTER_NAME = "逆向角色汇总";

    @Autowired private TxtImportJobRepository jobRepository;
    @Autowired private TxtImportChapterRepository importChapterRepository;
    @Autowired private TxtImportReStepRepository reStepRepository;
    @Autowired private ChapterOutlineRepository chapterOutlineRepository;
    @Autowired private VolumeOutlineRepository volumeOutlineRepository;
    @Autowired private WorldSettingRepository worldSettingRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private StoryOutlineRepository storyOutlineRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowStateRepository workflowStateRepository;
    @Autowired private ChapterRepository chapterRepository;
    @Autowired private TestEntityManager em;

    private FakeProvider provider;
    private TxtReverseEngineeringService service;
    private WorkflowStateService workflowStateService;
    private ContextSummaryService contextSummaryService;

    @BeforeEach
    void setUp() {
        provider = new FakeProvider();

        AiProviderRouter router = mock(AiProviderRouter.class);
        when(router.resolveModel(any(), any()))
                .thenReturn(new AiProviderRouter.ResolvedModel(provider, "fake-model"));
        when(router.resolveModelByConfigId(any()))
                .thenReturn(new AiProviderRouter.ResolvedModel(provider, "fake-model"));

        PromptTemplateRegistry promptRegistry = mock(PromptTemplateRegistry.class);
        when(promptRegistry.getSubStepTemplate(any(), any(), any())).thenReturn("TEMPLATE");
        when(promptRegistry.getSubStepSystemPrompt(any(), any(), any())).thenReturn("SYSTEM");
        when(promptRegistry.resolveTemplate(any(), any())).thenReturn("RESOLVED_PROMPT");

        AiUsageTracker usageTracker = mock(AiUsageTracker.class);

        // 真实 WorkflowStateService（用于验证 workflow_states 回填），仅 mock 其重依赖
        contextSummaryService = mock(ContextSummaryService.class);
        
        workflowStateService = new WorkflowStateService();
        workflowStateService.setWorkflowStateRepository(workflowStateRepository);
        workflowStateService.setProjectRepository(projectRepository);
        workflowStateService.setWorldSettingRepository(worldSettingRepository);
        workflowStateService.setCharacterRepository(characterRepository);
        workflowStateService.setStoryOutlineRepository(storyOutlineRepository);
        workflowStateService.setChapterOutlineRepository(chapterOutlineRepository);
        workflowStateService.setChapterRepository(chapterRepository);
        workflowStateService.setBackgroundGenerationService(mock(BackgroundGenerationService.class));
        workflowStateService.setContextSummaryService(contextSummaryService);
        workflowStateService.setWorldFacetElaborationService(mock(WorldFacetElaborationService.class));

        
        service = new TxtReverseEngineeringService();
        service.setJobRepository(jobRepository);
        service.setImportChapterRepository(importChapterRepository);
        service.setReStepRepository(reStepRepository);
        service.setChapterOutlineRepository(chapterOutlineRepository);
        service.setVolumeOutlineRepository(volumeOutlineRepository);
        service.setWorldSettingRepository(worldSettingRepository);
        service.setCharacterRepository(characterRepository);
        service.setStoryOutlineRepository(storyOutlineRepository);
        service.setProjectRepository(projectRepository);
        service.setProviderRouter(router);
        service.setPromptRegistry(promptRegistry);
        service.setAiUsageTracker(usageTracker);
        service.setWorkflowStateService(workflowStateService);
        service.setContextSummaryService(contextSummaryService);
    }

    // ==================================================================
    // plan()：以产出物扫描完成度
    // ==================================================================

    @Test
    void plan_countsCompletedArtifactsAndPhaseProgress() {
        Long projectId = newProject();
        TxtImportJobEntity job = newJob(projectId, 3, true, true, true);
        addChapters(job.getId(), 6);
        addChapterOutline(projectId, 1, "第一章大纲");
        addChapterOutline(projectId, 2, "第二章大纲");

        ReProtocol.RePlan plan = service.plan(job.getId());

        assertThat(plan.totalChapters()).isEqualTo(6);
        assertThat(plan.totalVolumes()).isEqualTo(2);
        // 6 章 + 2 卷 + 5 汇总（简介/世界/角色汇总/角色卡预估1/总纲）
        assertThat(plan.totalUnits()).isEqualTo(13);
        assertThat(plan.completedUnits()).isEqualTo(2);
        assertThat(plan.resumable()).isTrue();
        assertThat(plan.completed()).isFalse();

        ReProtocol.PhasePlan chapter = phaseOf(plan, "CHAPTER_OUTLINE");
        assertThat(chapter.status()).isEqualTo("PARTIAL");
        assertThat(chapter.completedUnits()).isEqualTo(2);
        assertThat(chapter.totalUnits()).isEqualTo(6);

        ReProtocol.PhasePlan arc = phaseOf(plan, "STORY_ARC");
        assertThat(arc.status()).isEqualTo("PENDING");
        assertThat(arc.totalUnits()).isEqualTo(2);

        assertThat(phaseOf(plan, "WORLD").status()).isEqualTo("PENDING");
    }

    @Test
    void plan_marksCompletedWhenAllArtifactsExist() {
        Long projectId = newProject();
        TxtImportJobEntity job = newJob(projectId, 3, true, true, true);
        addChapters(job.getId(), 6);
        for (int i = 1; i <= 6; i++) addChapterOutline(projectId, i, "第" + i + "章大纲");
        addVolumeOutline(projectId, 1, "第一卷弧线");
        addVolumeOutline(projectId, 2, "第二卷弧线");
        addWorldSetting(projectId, "世界观内容");
        addReverseCharacter(projectId, "角色汇总内容");
        addCharacterCard(projectId, 1, "姓名：林动\n身份：家族少年");
        addStoryOutline(projectId, "故事总纲内容");
        // 简介已生成（非占位）→ SYNOPSIS 阶段视为完成
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setDescription("一个少年的逆天崛起之路。");
            projectRepository.save(p);
        });

        ReProtocol.RePlan plan = service.plan(job.getId());

        // 6 章 + 2 卷 + 4 汇总（世界/角色汇总/角色卡/总纲）；简介已完成 → SYNOPSIS 不可执行，排除在总数外
        assertThat(plan.completedUnits()).isEqualTo(12);
        assertThat(plan.completed()).isTrue();
        assertThat(plan.resumable()).isFalse();
        assertThat(plan.phases()).allMatch(p -> !p.runnable() || "COMPLETED".equals(p.status()));
    }

    @Test
    void plan_marksDisabledPhasesAsSkippedAndExcludesFromTotals() {
        Long projectId = newProject();
        TxtImportJobEntity job = newJob(projectId, 3, false, false, false);
        addChapters(job.getId(), 6);

        ReProtocol.RePlan plan = service.plan(job.getId());

        assertThat(phaseOf(plan, "CHAPTER_OUTLINE").status()).isNotEqualTo("SKIPPED");
        assertThat(phaseOf(plan, "STORY_ARC").status()).isEqualTo("SKIPPED");
        assertThat(phaseOf(plan, "WORLD").status()).isEqualTo("SKIPPED");
        assertThat(phaseOf(plan, "CHARACTERS").status()).isEqualTo("SKIPPED");
        assertThat(phaseOf(plan, "STORY_OUTLINE").status()).isEqualTo("SKIPPED");
        // 只有章节阶段与简介阶段参与总进度（简介未生成，始终可执行）
        assertThat(plan.totalUnits()).isEqualTo(7);
    }

    @Test
    void plan_reportsRunningStatusForRePhaseJobStatus() {
        Long projectId = newProject();
        TxtImportJobEntity job = newJob(projectId, 3, true, true, true);
        job.setStatus("RE_CHAPTER_OUTLINE");
        jobRepository.save(job);
        addChapters(job.getId(), 3);

        assertThat(service.plan(job.getId()).jobStatus()).isEqualTo("RUNNING");
    }

    // ==================================================================
    // runReverseEngineering()：断点续跑 / 取消 / 实时事件
    // ==================================================================

    @Test
    void runReverseEngineering_resumesSkippingCompletedChapters() throws Exception {
        Long projectId = newProject();
        TxtImportJobEntity job = newJob(projectId, 3, true, true, true);
        addChapters(job.getId(), 6);
        addChapterOutline(projectId, 1, "第一章已完成大纲");   // 已完成 → 应被跳过

        List<String> out = service.runReverseEngineering(job.getId(), projectId, () -> false)
                .collectList().block();

        // 提示文案：「已完成 1/6，继续补做剩余 5 项」
        assertThat(noteTexts(out)).anySatisfy(t -> {
            assertThat(t).contains("已完成 1/6");
            assertThat(t).contains("剩余 5 项");
        });

        // 章节事件共 6 条，其中恰好 1 条标记为 skipped（第 1 章）
        List<ReProtocol.ReItem> chapterItems = items(out).stream()
                .filter(i -> "CHAPTER_OUTLINE".equals(i.phase())).toList();
        assertThat(chapterItems).hasSize(6);
        assertThat(chapterItems.stream().filter(ReProtocol.ReItem::skipped).toList())
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.index()).isEqualTo(1);
                    assertThat(i.body()).isEqualTo("第一章已完成大纲");
                });

        // 跳过已完成：章节阶段实际只发了 5 次 LLM 请求；弧线阶段再 2 次；角色清单提取再 1 次；简介再 1 次
        // （默认响应无编号清单 → 角色卡阶段 0 张卡，直接跳过）
        assertThat(provider.generateCallCount()).isEqualTo(9);

        // 6 章大纲最终全部落库（含新补的 5 章）
        assertThat(chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId)).hasSize(6);
    }

    @Test
    void runReverseEngineering_cancelledEmitsPlanButCallsNoLlm() {
        Long projectId = newProject();
        TxtImportJobEntity job = newJob(projectId, 3, true, true, true);
        addChapters(job.getId(), 3);

        List<String> out = service.runReverseEngineering(job.getId(), projectId, () -> true)
                .collectList().block();

        assertThat(out).isNotNull();
        // 计划仍然下发（前端据此渲染流程控制面板）
        assertThat(out).anyMatch(s -> s.startsWith("[[RE_PLAN:"));
        // 但绝不发起新的 LLM 调用
        assertThat(provider.generateCallCount()).isZero();
        assertThat(provider.streamCallCount()).isZero();
        // 且不会误标为完成
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isNotEqualTo("DONE");
    }

    @Test
    void runReverseEngineering_allDoneAutoSkipsWithoutLlm() throws Exception {
        Long projectId = newProject();
        TxtImportJobEntity job = newJob(projectId, 3, true, true, true);
        addChapters(job.getId(), 6);
        for (int i = 1; i <= 6; i++) addChapterOutline(projectId, i, "第" + i + "章大纲");
        addVolumeOutline(projectId, 1, "第一卷弧线");
        addVolumeOutline(projectId, 2, "第二卷弧线");
        addWorldSetting(projectId, "世界观");
        addReverseCharacter(projectId, "角色");
        addCharacterCard(projectId, 1, "姓名：林动\n身份：家族少年");
        addStoryOutline(projectId, "总纲");
        // 简介已生成 → SYNOPSIS 阶段自动跳过，不产生 LLM 调用
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setDescription("一个少年的逆天崛起之路。");
            projectRepository.save(p);
        });

        List<String> out = service.runReverseEngineering(job.getId(), projectId, () -> false)
                .collectList().block();

        assertThat(provider.generateCallCount()).isZero();
        assertThat(provider.streamCallCount()).isZero();
        assertThat(noteTexts(out)).allMatch(t -> t.contains("已完成") || t.contains("自动跳过"));
        assertThat(out).contains("[[RE_PHASE_DONE:CHAPTER_OUTLINE|6|6]]");
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo("DONE");
    }

    @Test
    void runReverseEngineering_detectsGenreWhenOther() {
        Long projectId = newProject();
        // 模拟导入时未指定题材：项目与任务都停留在「其他」
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        project.setGenre(Genre.OTHER);
        projectRepository.save(project);
        TxtImportJobEntity job = newJob(projectId, 3, true, true, true);
        job.setGenre("OTHER");
        job = jobRepository.save(job);
        addChapters(job.getId(), 2);
        // 非流式首调用即题材识别，返回可解析的题材行；其余调用返回角色清单
        provider.response = "题材：科幻";
        provider.streamResponse = "姓名：林动\n身份：少年";

        // 计划中 GENRE 阶段应可执行
        ReProtocol.RePlan plan = service.plan(job.getId());
        assertThat(phaseOf(plan, "GENRE").status()).isEqualTo("PENDING");
        assertThat(phaseOf(plan, "GENRE").runnable()).isTrue();

        service.runReverseEngineering(job.getId(), projectId, () -> false).collectList().block();

        // AI 识别结果写回项目与任务
        assertThat(projectRepository.findById(projectId).orElseThrow().getGenre()).isEqualTo(Genre.KEHUAN);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getGenre()).isEqualTo("KEHUAN");
        TxtImportReStepEntity step = reStepRepository
                .findByJobIdAndPhase(job.getId(), "GENRE").orElseThrow();
        assertThat(step.getStatus()).isEqualTo("COMPLETED");

        // 续跑：题材已明确，GENRE 阶段不再执行
        ReProtocol.RePlan replan = service.plan(job.getId());
        assertThat(phaseOf(replan, "GENRE").status()).isEqualTo("COMPLETED");
        assertThat(phaseOf(replan, "GENRE").runnable()).isFalse();
    }

    @Test
    void runReverseEngineering_synopsisPlaceholderTreatedAsPendingAndGenerated() {
        Long projectId = newProject();
        TxtImportJobEntity job = newJob(projectId, 3, false, false, false);
        addChapters(job.getId(), 1);
        // 模拟导入建项目时写入的占位简介
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setDescription(TxtImportService.IMPORT_DESCRIPTION_PLACEHOLDER);
            projectRepository.save(p);
        });

        // 占位简介 → SYNOPSIS 待执行
        ReProtocol.RePlan plan = service.plan(job.getId());
        assertThat(phaseOf(plan, "SYNOPSIS").status()).isEqualTo("PENDING");
        assertThat(phaseOf(plan, "SYNOPSIS").runnable()).isTrue();

        provider.response = "故事简介：\n一个少年在乱世中崛起，历经磨难，终成一代强者的热血故事。";

        service.runReverseEngineering(job.getId(), projectId, () -> false).collectList().block();

        // 真实简介写回项目（占位文案被替换、前缀被清洗）
        assertThat(projectRepository.findById(projectId).orElseThrow().getDescription())
                .isEqualTo("一个少年在乱世中崛起，历经磨难，终成一代强者的热血故事。");
        assertThat(reStepRepository.findByJobIdAndPhase(job.getId(), "SYNOPSIS").orElseThrow().getStatus())
                .isEqualTo("COMPLETED");

        // 续跑：简介已生成，SYNOPSIS 阶段不再执行
        ReProtocol.RePlan replan = service.plan(job.getId());
        assertThat(phaseOf(replan, "SYNOPSIS").status()).isEqualTo("COMPLETED");
        assertThat(phaseOf(replan, "SYNOPSIS").runnable()).isFalse();
    }

    @Test
    void runReverseEngineering_parsesSummaryAndDeduplicatesCharacterNames() {
        Long projectId = newProject();
        TxtImportJobEntity job = newJob(projectId, 3, false, false, false);
        addChapters(job.getId(), 1);
        provider.response = "===大纲===\n主角初入宗门，遭遇神秘老者\n===角色===\n沈砚、周穆、沈砚";

        service.runReverseEngineering(job.getId(), projectId, () -> false).collectList().block();

        ChapterOutlineEntity saved = chapterOutlineRepository
                .findByProjectIdAndChapterNumber(projectId, 1).orElseThrow();
        assertThat(saved.getSummary()).isEqualTo("主角初入宗门，遭遇神秘老者");
        // 「沈砚」重复出现，按「、」去重且保持出现顺序
        assertThat(saved.getCharacterNames()).isEqualTo("沈砚、周穆");
    }

    @Test
    void runReverseEngineering_generatesCharacterCardsAndBackfillsWorkflowStates() {
        Long projectId = newProject();
        TxtImportJobEntity job = newJob(projectId, 3, true, true, true);
        addChapters(job.getId(), 3);
        // 非流式调用（章节/弧线/角色汇总/角色清单）返回编号角色清单
        provider.response = "1. 林动：家族少年，性格坚韧\n2. 应欢欢：道宗天才少女";
        // 流式调用（世界/角色/总纲/角色卡）返回字段化卡片
        provider.streamResponse = "姓名：林动\n性别：男\n年龄：16\n身份：家族少年\n性格：坚韧不拔\n"
                + "背景：青阳镇林家子弟\n动机：为家族复仇\n能力：吞噬祖符\n关系：与应欢欢亦敌亦友";

        service.runReverseEngineering(job.getId(), projectId, () -> false).collectList().block();

        // 清单提取出 2 人 → 2 张卡（2 次流式卡片调用）
        assertThat(provider.streamCallCount()).isEqualTo(5); // 世界1 + 角色汇总1 + 卡片2 + 总纲1
        assertThat(provider.generateCallCount()).isEqualTo(6); // 简介1 + 章节3 + 弧线1 + 角色清单1

        List<CharacterEntity> chars = characterRepository.findByProjectIdOrderBySortOrder(projectId);
        assertThat(chars).hasSize(3); // 汇总(sortOrder=0) + 2 张独立卡
        assertThat(chars.get(0).getSortOrder()).isZero();
        assertThat(chars.get(0).getName()).isEqualTo(REVERSE_CHARACTER_NAME);
        CharacterEntity card1 = chars.get(1);
        assertThat(card1.getSortOrder()).isEqualTo(1);
        assertThat(card1.getName()).isEqualTo("林动");
        assertThat(card1.getRole()).isEqualTo("家族少年");
        assertThat(card1.getAbilities()).isEqualTo("吞噬祖符");
        assertThat(chars.get(2).getSortOrder()).isEqualTo(2);

        // 流程步骤表：CHARACTER_CARDS 完成，单元数被校正为真实卡数 2
        TxtImportReStepEntity step = reStepRepository
                .findByJobIdAndPhase(job.getId(), "CHARACTER_CARDS").orElseThrow();
        assertThat(step.getStatus()).isEqualTo("COMPLETED");
        assertThat(step.getTotalUnits()).isEqualTo(2);

        // workflow_states 回填：世界/角色/总纲三步均有真实内容（非占位符）
        for (com.storycreator.core.domain.WorkflowStep ws : com.storycreator.core.domain.WorkflowStep.values()) {
            if (ws == com.storycreator.core.domain.WorkflowStep.WORLD_BUILDING
                    || ws == com.storycreator.core.domain.WorkflowStep.CHARACTER_DESIGN
                    || ws == com.storycreator.core.domain.WorkflowStep.OUTLINE_GENERATION) {
                assertThat(workflowStateRepository.findByProjectIdAndStep(projectId, ws))
                        .as("workflow_states 应含 %s", ws)
                        .hasValueSatisfying(s -> assertThat(s.getGeneratedContent()).isNotBlank());
            }
        }
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo("DONE");
    }

    @Test
    void runReverseEngineering_splitsChapterContentOver32kIntoMultipleCalls() {
        Long projectId = newProject();
        TxtImportJobEntity job = newJob(projectId, 3, false, false, false);

        // 造一个 > 32K 的单章（按段落分行）
        StringBuilder big = new StringBuilder();
        String paragraph = "这是一段用于填充上下文长度的正文内容，重复出现以便超过三十二K。\n";
        while (big.length() <= MAX_CONTEXT + 4096) {
            big.append(paragraph);
        }
        TxtImportChapterEntity chapter = new TxtImportChapterEntity();
        chapter.setJobId(job.getId());
        chapter.setChapterNumber(1);
        chapter.setTitle("超长第一章");
        chapter.setContent(big.toString());
        chapter.setWordCount(big.length());
        chapter.setSortOrder(1);
        importChapterRepository.save(chapter);
        em.flush();

        service.runReverseEngineering(job.getId(), projectId, () -> false).collectList().block();

        // 单章被拆成多段 → 至少 2 次非流式调用
        assertThat(provider.generateCallCount()).isGreaterThanOrEqualTo(2);
    }

    // ==================================================================
    // 停止 / 清空重跑
    // ==================================================================

    @Test
    void markStopped_resetsRunningStepAndMarksJobInterrupted() {
        Long projectId = newProject();
        TxtImportJobEntity job = newJob(projectId, 3, true, true, true);
        addChapters(job.getId(), 2);
        saveStep(job.getId(), "CHAPTER_OUTLINE", "RUNNING", 1, 2);

        service.markStopped(job.getId());

        TxtImportReStepEntity step = reStepRepository
                .findByJobIdAndPhase(job.getId(), "CHAPTER_OUTLINE").orElseThrow();
        assertThat(step.getStatus()).isEqualTo("PENDING");
        // 已完成进度必须保留（断点续跑依据）
        assertThat(step.getCompletedUnits()).isEqualTo(1);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo("INTERRUPTED");
    }

    @Test
    void resetProgress_clearsArtifactsAndStepsBackToPending() {
        Long projectId = newProject();
        TxtImportJobEntity job = newJob(projectId, 3, true, true, true);
        addChapters(job.getId(), 2);
        addChapterOutline(projectId, 1, "大纲");
        addVolumeOutline(projectId, 1, "弧线");
        addWorldSetting(projectId, "世界");
        addReverseCharacter(projectId, "角色");
        addCharacterCard(projectId, 1, "姓名：林动\n身份：家族少年");
        addStoryOutline(projectId, "总纲");
        // 模拟逆向工程回填的 workflow_states
        workflowStateService.saveStepContent(projectId, com.storycreator.core.domain.WorkflowStep.WORLD_BUILDING, "世界观回填");
        workflowStateService.saveStepContent(projectId, com.storycreator.core.domain.WorkflowStep.CHARACTER_DESIGN, "角色回填");
        saveStep(job.getId(), "CHAPTER_OUTLINE", "COMPLETED", 1, 1);
        saveStep(job.getId(), "WORLD", "COMPLETED", 1, 1);
        em.flush();

        service.resetProgress(job.getId());
        em.flush();
        em.clear();

        assertThat(chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId)).isEmpty();
        assertThat(volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId)).isEmpty();
        assertThat(worldSettingRepository.findByProjectId(projectId)).isEmpty();
        assertThat(storyOutlineRepository.findByProjectId(projectId)).isEmpty();
        // 汇总与角色卡一并清空
        assertThat(characterRepository.findByProjectIdOrderBySortOrder(projectId)).isEmpty();
        // 回填内容被清空，状态复位
        assertThat(workflowStateRepository.findByProjectId(projectId))
                .allSatisfy(s -> {
                    assertThat(s.getGeneratedContent()).isNull();
                    assertThat(s.getStatus()).isEqualTo(com.storycreator.core.domain.StepStatus.NOT_STARTED);
                });

        assertThat(reStepRepository.findByJobIdOrderBySortOrder(job.getId()))
                .allSatisfy(s -> {
                    assertThat(s.getStatus()).isEqualTo("PENDING");
                    assertThat(s.getCompletedUnits()).isZero();
                });
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo("SPLIT_DONE");
    }

    // ==================================================================
    // 测试数据与工具
    // ==================================================================

    private Long newProject() {
        ProjectEntity project = new ProjectEntity();
        project.setTitle("逆向工程测试项目");
        project.setGenre(Genre.XUANHUAN);
        return projectRepository.save(project).getId();
    }

    private TxtImportJobEntity newJob(Long projectId, int perVolume,
                                      boolean world, boolean characters, boolean outline) {
        TxtImportJobEntity job = new TxtImportJobEntity();
        job.setTitle("测试小说");
        job.setGenre("XUANHUAN");
        job.setProjectId(projectId);
        job.setChaptersPerVolume(perVolume);
        job.setRunWorldBuilding(world);
        job.setRunCharacters(characters);
        job.setRunOutline(outline);
        job.setStatus("SPLIT_DONE");
        return jobRepository.save(job);
    }

    private void addChapters(Long jobId, int count) {
        for (int i = 1; i <= count; i++) {
            TxtImportChapterEntity chapter = new TxtImportChapterEntity();
            chapter.setJobId(jobId);
            chapter.setChapterNumber(i);
            chapter.setTitle("第" + i + "章");
            chapter.setContent("第" + i + "章正文内容，示例段落。");
            chapter.setWordCount(chapter.getContent().length());
            chapter.setSortOrder(i);
            importChapterRepository.save(chapter);
        }
    }

    private void addChapterOutline(Long projectId, int chapterNumber, String summary) {
        ChapterOutlineEntity entity = new ChapterOutlineEntity();
        entity.setProjectId(projectId);
        entity.setChapterNumber(chapterNumber);
        entity.setTitle("第" + chapterNumber + "章");
        entity.setSummary(summary);
        entity.setCharacterNames("沈砚");
        entity.setVolumeNumber(1);
        entity.setStatus("COMPLETED");
        chapterOutlineRepository.save(entity);
    }

    private void addVolumeOutline(Long projectId, int volumeNumber, String arcSummary) {
        VolumeOutlineEntity entity = new VolumeOutlineEntity();
        entity.setProjectId(projectId);
        entity.setVolumeNumber(volumeNumber);
        entity.setTitle("第" + volumeNumber + "卷");
        entity.setArcName("弧线" + volumeNumber);
        entity.setArcSummary(arcSummary);
        entity.setChapterStart((volumeNumber - 1) * 3 + 1);
        entity.setChapterEnd(volumeNumber * 3);
        volumeOutlineRepository.save(entity);
    }

    private void addWorldSetting(Long projectId, String content) {
        WorldSettingEntity entity = new WorldSettingEntity();
        entity.setProjectId(projectId);
        entity.setContent(content);
        worldSettingRepository.save(entity);
    }

    private void addReverseCharacter(Long projectId, String content) {
        CharacterEntity entity = new CharacterEntity();
        entity.setProjectId(projectId);
        entity.setName(REVERSE_CHARACTER_NAME);
        entity.setContent(content);
        entity.setStatus("GENERATED");
        entity.setSortOrder(0);
        characterRepository.save(entity);
    }

    private void addCharacterCard(Long projectId, int sortOrder, String content) {
        CharacterEntity entity = new CharacterEntity();
        entity.setProjectId(projectId);
        entity.setName("角色" + sortOrder);
        entity.setContent(content);
        entity.setStatus("GENERATED");
        entity.setSortOrder(sortOrder);
        characterRepository.save(entity);
    }

    private void addStoryOutline(Long projectId, String content) {
        StoryOutlineEntity entity = new StoryOutlineEntity();
        entity.setProjectId(projectId);
        entity.setContent(content);
        storyOutlineRepository.save(entity);
    }

    private void saveStep(Long jobId, String phase, String status, int done, int total) {
        TxtImportReStepEntity step = new TxtImportReStepEntity();
        step.setJobId(jobId);
        step.setPhase(phase);
        step.setSortOrder(0);
        step.setStatus(status);
        step.setCompletedUnits(done);
        step.setTotalUnits(total);
        reStepRepository.save(step);
    }

    private static ReProtocol.PhasePlan phaseOf(ReProtocol.RePlan plan, String phase) {
        return plan.phases().stream()
                .filter(p -> p.phase().equals(phase))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少阶段: " + phase));
    }

    private static List<ReProtocol.ReItem> items(List<String> out) throws Exception {
        List<ReProtocol.ReItem> result = new ArrayList<>();
        for (String s : out) {
            if (s != null && s.startsWith("[[RE_ITEM:")) {
                result.add(MAPPER.readValue(decode(s, "[[RE_ITEM:"), ReProtocol.ReItem.class));
            }
        }
        return result;
    }

    private static List<String> noteTexts(List<String> out) throws Exception {
        List<String> texts = new ArrayList<>();
        for (String s : out) {
            if (s != null && s.startsWith("[[RE_NOTE:")) {
                Map<?, ?> map = MAPPER.readValue(decode(s, "[[RE_NOTE:"), Map.class);
                texts.add(String.valueOf(map.get("text")));
            }
        }
        return texts;
    }

    private static String decode(String token, String prefix) {
        return new String(Base64.getDecoder().decode(ReProtocol.unwrap(token, prefix)), StandardCharsets.UTF_8);
    }

    /** 假的 AI provider：记录调用、按脚本返回内容，绝不真正访问网络。 */
    static class FakeProvider implements AiProvider {
        private final List<AiRequest> generateCalls = new ArrayList<>();
        private final List<AiRequest> streamCalls = new ArrayList<>();
        volatile String response = "===大纲===\n本章大纲内容\n===角色===\n沈砚、周穆";
        /** 流式（streamText）响应；为 null 时回落到 response。角色卡生成走流式。 */
        volatile String streamResponse;

        @Override
        public String getProviderName() {
            return "fake";
        }

        @Override
        public String generateText(AiRequest request) {
            generateCalls.add(request);
            return response;
        }

        @Override
        public Flux<String> streamText(AiRequest request) {
            streamCalls.add(request);
            return Flux.just(streamResponse != null ? streamResponse : response);
        }

        int generateCallCount() {
            return generateCalls.size();
        }

        int streamCallCount() {
            return streamCalls.size();
        }
    }
}
