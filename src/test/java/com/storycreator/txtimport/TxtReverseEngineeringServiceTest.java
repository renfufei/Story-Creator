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
import com.storycreator.persistence.repository.ChapterOutlineRepository;
import com.storycreator.persistence.repository.CharacterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.StoryOutlineRepository;
import com.storycreator.persistence.repository.TxtImportChapterRepository;
import com.storycreator.persistence.repository.TxtImportJobRepository;
import com.storycreator.persistence.repository.TxtImportReStepRepository;
import com.storycreator.persistence.repository.VolumeOutlineRepository;
import com.storycreator.persistence.repository.WorldSettingRepository;
import com.storycreator.workflow.engine.AiUsageTracker;
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
    @Autowired private TestEntityManager em;

    private FakeProvider provider;
    private TxtReverseEngineeringService service;

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

        service = new TxtReverseEngineeringService(jobRepository, importChapterRepository, reStepRepository,
                chapterOutlineRepository, volumeOutlineRepository, worldSettingRepository,
                characterRepository, storyOutlineRepository, router, promptRegistry, usageTracker);
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
        assertThat(plan.totalVolumes()).isEqualTo(2);          // 6 章 / 每卷 3 章
        assertThat(plan.totalUnits()).isEqualTo(11);           // 6 章 + 2 卷 + 3 汇总
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
        addStoryOutline(projectId, "故事总纲内容");

        ReProtocol.RePlan plan = service.plan(job.getId());

        assertThat(plan.completedUnits()).isEqualTo(11);
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
        // 只有章节阶段参与总进度
        assertThat(plan.totalUnits()).isEqualTo(6);
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

        // 跳过已完成：章节阶段实际只发了 5 次 LLM 请求；弧线阶段再 2 次 → 共 7 次非流式调用
        assertThat(provider.generateCallCount()).isEqualTo(7);

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
        addStoryOutline(projectId, "总纲");

        List<String> out = service.runReverseEngineering(job.getId(), projectId, () -> false)
                .collectList().block();

        assertThat(provider.generateCallCount()).isZero();
        assertThat(provider.streamCallCount()).isZero();
        assertThat(noteTexts(out)).allMatch(t -> t.contains("已完成") || t.contains("自动跳过"));
        assertThat(out).contains("[[RE_PHASE_DONE:CHAPTER_OUTLINE|6|6]]");
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo("DONE");
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
        addStoryOutline(projectId, "总纲");
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
        assertThat(characterRepository.findByProjectIdOrderBySortOrder(projectId)).isEmpty();

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
            return Flux.just(response);
        }

        int generateCallCount() {
            return generateCalls.size();
        }

        int streamCallCount() {
            return streamCalls.size();
        }
    }
}
