package com.storycreator.web;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.ModelType;
import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that verifies all Thymeleaf HTML pages render without errors.
 * Each test requests a page URL and asserts HTTP 200 with non-empty HTML body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:page_render_test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none"
})
class PageRenderingIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private ProjectRepository projectRepository;
    @Autowired private AiModelConfigRepository aiModelConfigRepository;
    @Autowired private GlobalSettingRepository globalSettingRepository;
    @Autowired private ChapterRepository chapterRepository;
    @Autowired private WorldSettingRepository worldSettingRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private ChapterOutlineRepository chapterOutlineRepository;
    @Autowired private StoryOutlineRepository storyOutlineRepository;
    @Autowired private VolumeOutlineRepository volumeOutlineRepository;
    @Autowired private WorkflowStateRepository workflowStateRepository;
    @Autowired private ProofreadingReportRepository proofreadingReportRepository;
    @Autowired private StepGuidanceRepository stepGuidanceRepository;
    @Autowired private StepModelConfigRepository stepModelConfigRepository;
    @Autowired private AiUsageStatRepository aiUsageStatRepository;
    @Autowired private AutoRunStepConfigRepository autoRunStepConfigRepository;
    @Autowired private CharacterStateDimensionRepository characterStateDimensionRepository;
    @Autowired private SideStoryRepository sideStoryRepository;
    @Autowired private SideStoryChapterRepository sideStoryChapterRepository;
    @Autowired private InspirationRepository inspirationRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long projectId;
    private Long configId;
    private Long sideStoryId;
    private Long inspirationId;

    @BeforeEach
    void setUp() {
        // Create AI model config
        AiModelConfigEntity config = new AiModelConfigEntity();
        config.setProvider("openai");
        config.setBaseUrl("http://localhost:" + port + "/mock");
        config.setModelId("mock-model");
        config.setDisplayName("Mock Model");
        config.setApiKey("mock-key");
        config.setActive(true);
        config.setModelType(ModelType.TEXT);
        config = aiModelConfigRepository.save(config);
        configId = config.getId();

        // Set global default
        GlobalSettingEntity defaultSetting = new GlobalSettingEntity("default_model_config_id", configId.toString());
        globalSettingRepository.save(defaultSetting);

        // Create test project
        ProjectEntity project = new ProjectEntity();
        project.setTitle("页面渲染测试项目");
        project.setGenre(Genre.XUANHUAN);
        project.setDescription("用于验证页面渲染的测试项目");
        project.setTotalChapters(3);
        project.setChapterWordCount(1000);
        project.setChapterWordCountMin(800);
        project.setChapterWordCountMax(1200);
        project.setCharacterCount(2);
        project.setCurrentStep(WorkflowStep.CHAPTER_WRITING);

        project.setDefaultModelConfigId(configId);
        project = projectRepository.save(project);
        projectId = project.getId();

        // Create world setting
        WorldSettingEntity ws = new WorldSettingEntity();
        ws.setProjectId(projectId);
        ws.setContent("这是一个测试世界观设定。修仙世界。");
        worldSettingRepository.save(ws);

        // Create characters
        CharacterEntity char1 = new CharacterEntity();
        char1.setProjectId(projectId);
        char1.setName("张三");
        char1.setDescription("主角，天赋异禀");
        char1.setSortOrder(1);
        char1.setStatus("GENERATED");
        characterRepository.save(char1);

        CharacterEntity char2 = new CharacterEntity();
        char2.setProjectId(projectId);
        char2.setName("李四");
        char2.setDescription("配角，忠实伙伴");
        char2.setSortOrder(2);
        char2.setStatus("GENERATED");
        characterRepository.save(char2);

        // Create volume outline
        VolumeOutlineEntity vol = new VolumeOutlineEntity();
        vol.setProjectId(projectId);
        vol.setVolumeNumber(1);
        vol.setTitle("第一卷");
        vol.setChapterStart(1);
        vol.setChapterEnd(3);
        vol.setArcSummary("初入修仙界");
        volumeOutlineRepository.save(vol);

        // Create chapter outlines
        for (int i = 1; i <= 3; i++) {
            ChapterOutlineEntity outline = new ChapterOutlineEntity();
            outline.setProjectId(projectId);
            outline.setChapterNumber(i);
            outline.setSummary("第" + i + "章大纲摘要");
            chapterOutlineRepository.save(outline);
        }

        // Create chapters with content
        for (int i = 1; i <= 3; i++) {
            ChapterEntity ch = new ChapterEntity();
            ch.setProjectId(projectId);
            ch.setChapterNumber(i);
            ch.setTitle("第" + i + "章 测试章节");
            ch.setContent("这是第" + i + "章的测试内容。" + "测试文字".repeat(50));
            ch.setWordCount(200);
            ch.setStatus(StepStatus.CONFIRMED);
            chapterRepository.save(ch);
        }

        // Create story outline
        StoryOutlineEntity storyOutline = new StoryOutlineEntity();
        storyOutline.setProjectId(projectId);
        storyOutline.setContent("这是总体大纲内容。");
        storyOutlineRepository.save(storyOutline);

        // Create side story
        SideStoryEntity sideStory = new SideStoryEntity();
        sideStory.setProjectId(projectId);
        sideStory.setTitle("番外：前尘往事");
        sideStory.setDescription("讲述主角前世的故事");
        sideStory.setType("SUPPLEMENTARY");
        sideStory.setStatus("OUTLINE_READY");
        sideStory.setSortOrder(1);
        sideStory.setOutline("番外大纲内容");
        sideStory = sideStoryRepository.save(sideStory);
        sideStoryId = sideStory.getId();

        // Create side story chapter
        SideStoryChapterEntity ssCh = new SideStoryChapterEntity();
        ssCh.setSideStoryId(sideStoryId);
        ssCh.setProjectId(projectId);
        ssCh.setChapterNumber(1);
        ssCh.setTitle("番外第一章");
        ssCh.setOutlineSummary("番外章节大纲");
        ssCh.setContent("番外章节内容");
        ssCh.setWordCount(100);
        ssCh.setStatus("COMPLETED");
        sideStoryChapterRepository.save(ssCh);

        // Create inspiration
        InspirationEntity inspiration = new InspirationEntity();
        inspiration.setProjectId(projectId);
        inspiration.setTitle("灵感：主角的第一次顿悟");
        inspiration.setContent("可以让主角在雨夜的山道上，从一道雷光里悟出剑意。");
        inspiration = inspirationRepository.save(inspiration);
        inspirationId = inspiration.getId();
    }

    @AfterEach
    void tearDown() {
        if (projectId != null) {
            transactionTemplate.executeWithoutResult(status -> {
                inspirationRepository.deleteByProjectId(projectId);
                sideStoryChapterRepository.deleteByProjectId(projectId);
                sideStoryRepository.deleteByProjectId(projectId);
                workflowStateRepository.deleteByProjectId(projectId);
                chapterRepository.deleteByProjectId(projectId);
                characterRepository.deleteByProjectId(projectId);
                chapterOutlineRepository.deleteByProjectId(projectId);
                storyOutlineRepository.deleteByProjectId(projectId);
                volumeOutlineRepository.deleteByProjectId(projectId);
                proofreadingReportRepository.deleteByProjectId(projectId);
                stepGuidanceRepository.deleteByProjectId(projectId);
                stepModelConfigRepository.deleteByProjectId(projectId);
                aiUsageStatRepository.deleteByProjectId(projectId);
                autoRunStepConfigRepository.deleteByProjectId(projectId);
                characterStateDimensionRepository.deleteByProjectId(projectId);
                worldSettingRepository.deleteByProjectId(projectId);
                projectRepository.deleteById(projectId);
            });
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (configId != null) {
                aiModelConfigRepository.deleteById(configId);
            }
            globalSettingRepository.deleteById("default_model_config_id");
        });
    }

    // ==================== Dashboard & Project Pages ====================

    @Test
    void dashboard_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/"), String.class);
        assertPageOk(response, "dashboard");
    }

    @Test
    void projectNew_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/projects/new"), String.class);
        assertPageOk(response, "project-form");
    }

    @Test
    void projectDetail_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/projects/" + projectId), String.class);
        assertPageOk(response, "project-detail");
        // 灵感入口：必须以新标签页打开独立页面
        assertThat(response.getBody())
                .as("项目信息页应包含以新标签页打开的灵感入口")
                .contains("/projects/" + projectId + "/inspirations")
                .contains("target=\"_blank\"");
    }

    @Test
    void projectEdit_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/projects/" + projectId + "/edit"), String.class);
        assertPageOk(response, "project-form (edit)");
    }

    @Test
    void reader_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/projects/" + projectId + "/read"), String.class);
        assertPageOk(response, "reader");
    }

    // ==================== Workflow Page ====================

    @Test
    void workflow_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/workflow"), String.class);
        assertPageOk(response, "workflow");
        // 灵感入口：创作页头部同样以新标签页打开
        assertThat(response.getBody())
                .as("创作页应包含以新标签页打开的灵感入口")
                .contains("/projects/" + projectId + "/inspirations")
                .contains("target=\"_blank\"");
    }

    @Test
    void workflow_withStepParam_rendersSuccessfully() {
        for (WorkflowStep step : WorkflowStep.values()) {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    url("/projects/" + projectId + "/workflow?step=" + step.name()), String.class);
            assertThat(response.getStatusCode())
                    .as("workflow page with step=%s should return 200", step.name())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    // ==================== Side Story Pages ====================

    @Test
    void sideStoryList_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/side-stories"), String.class);
        assertPageOk(response, "side-story-list");
    }

    @Test
    void sideStoryDetail_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/side-stories/" + sideStoryId), String.class);
        assertPageOk(response, "side-story");
    }

    // ==================== Inspiration Pages ====================

    @Test
    void inspirationsList_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspirations"), String.class);
        assertPageOk(response, "inspirations");
        assertThat(response.getBody())
                .as("灵感列表应展示已有条目")
                .contains("灵感：主角的第一次顿悟");
    }

    @Test
    void inspirationDetail_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspirations/" + inspirationId), String.class);
        assertPageOk(response, "inspiration-detail");
        assertThat(response.getBody())
                .as("灵感详情应展示正文")
                .contains("雨夜的山道");
    }

    @Test
    void inspirationEdit_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspirations/" + inspirationId + "/edit"), String.class);
        assertPageOk(response, "inspiration-edit");
        assertThat(response.getBody())
                .as("灵感编辑页应回填标题")
                .contains("灵感：主角的第一次顿悟");
    }

    /** 跨项目访问同一条灵感必须被拒绝（不能通过换 projectId 读到别人的数据）。 */
    @Test
    void inspirationDetail_rejectsCrossProjectAccess() {
        ProjectEntity other = new ProjectEntity();
        other.setTitle("另一个项目");
        other.setGenre(Genre.XUANHUAN);
        Long otherId = projectRepository.save(other).getId();
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    url("/projects/" + otherId + "/inspirations/" + inspirationId), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            transactionTemplate.executeWithoutResult(status -> projectRepository.deleteById(otherId));
        }
    }

    /**
     * 走一遍真实 HTTP 的新增 → 详情 → 更新 → 列表 → 删除 链路。
     *
     * <p>断言以「实际效果」为准，不依赖 302 具体形态：测试用的 RestTemplate 会跟随重定向，
     * 因此创建/更新/删除的响应可能是 3xx 也可能是重定向后的 2xx。
     */
    @Test
    void inspiration_crudRoundTripOverHttp() {
        MultiValueMap<String, String> createForm = new LinkedMultiValueMap<>();
        createForm.add("title", "HTTP 往返灵感");
        createForm.add("content", "第一行\n第二行");
        ResponseEntity<String> created = restTemplate.postForEntity(
                url("/projects/" + projectId + "/inspirations"), createForm, String.class);
        assertThat(created.getStatusCode().is2xxSuccessful() || created.getStatusCode().is3xxRedirection())
                .as("创建请求应成功（直接重定向或跟随重定向落到详情页）")
                .isTrue();

        Long newId = inspirationRepository.findByProjectIdOrderByCreatedAtDescIdDesc(projectId).stream()
                .filter(i -> "HTTP 往返灵感".equals(i.getTitle()))
                .map(InspirationEntity::getId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("新建的灵感未落库"));

        // 详情页能看到新建内容（换行原样保留）
        ResponseEntity<String> detail = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspirations/" + newId), String.class);
        assertPageOk(detail, "inspiration-detail (created)");
        assertThat(detail.getBody()).contains("HTTP 往返灵感").contains("第二行");

        // 更新
        MultiValueMap<String, String> updateForm = new LinkedMultiValueMap<>();
        updateForm.add("title", "HTTP 往返灵感（已改）");
        updateForm.add("content", "改后的内容");
        ResponseEntity<String> updated = restTemplate.postForEntity(
                url("/projects/" + projectId + "/inspirations/" + newId + "/update"), updateForm, String.class);
        assertThat(updated.getStatusCode().is2xxSuccessful() || updated.getStatusCode().is3xxRedirection()).isTrue();
        assertThat(restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspirations/" + newId), String.class).getBody())
                .contains("HTTP 往返灵感（已改）")
                .contains("改后的内容");

        // 列表里出现新标题，且原内容已被覆盖
        ResponseEntity<String> list = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspirations"), String.class);
        assertPageOk(list, "inspirations (after update)");
        assertThat(list.getBody()).contains("HTTP 往返灵感（已改）").contains("改后的内容").doesNotContain("第一行");

        // 删除
        ResponseEntity<String> deleted = restTemplate.postForEntity(
                url("/projects/" + projectId + "/inspirations/" + newId + "/delete"), null, String.class);
        assertThat(deleted.getStatusCode().is2xxSuccessful() || deleted.getStatusCode().is3xxRedirection()).isTrue();
        assertThat(restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspirations/" + newId), String.class).getBody())
                .as("删除后再访问详情应返回错误而非页面")
                .doesNotContain("<html");
        assertThat(restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspirations"), String.class).getBody())
                .doesNotContain("HTTP 往返灵感（已改）");
    }

    /** 标题为空白（或干脆没传 title 参数）时不应落库，也不应 500。 */
    @Test
    void inspiration_blankTitleIsRejected() {
        long before = inspirationRepository.countByProjectId(projectId);

        // 只有空白字符
        MultiValueMap<String, String> blank = new LinkedMultiValueMap<>();
        blank.add("title", "   ");
        blank.add("content", "没有标题的灵感");
        restTemplate.postForEntity(url("/projects/" + projectId + "/inspirations"), blank, String.class);

        // 完全没传 title 参数：应友好回退，而不是 500
        MultiValueMap<String, String> missing = new LinkedMultiValueMap<>();
        missing.add("content", "缺少标题参数");
        ResponseEntity<String> missingResp = restTemplate.postForEntity(
                url("/projects/" + projectId + "/inspirations"), missing, String.class);
        assertThat(missingResp.getStatusCode().is5xxServerError())
                .as("缺少 title 参数时应友好回退而非 500")
                .isFalse();

        assertThat(inspirationRepository.countByProjectId(projectId))
                .as("标题无效时不应落库")
                .isEqualTo(before);
        assertThat(restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspirations"), String.class).getBody())
                .doesNotContain("没有标题的灵感")
                .doesNotContain("缺少标题参数");
    }

    // ==================== Character Design ====================

    /** 手工新增角色后，状态应为「已生成」(GENERATED)，以便后续精修，而不是「未生成」(PENDING)。 */
    @Test
    void addCharacter_marksStatusAsGenerated() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("name", "手工角色-测试");
        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/projects/" + projectId + "/characters/add"), form, String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("新增角色请求应成功")
                .isTrue();

        CharacterEntity created = characterRepository.findByProjectIdOrderBySortOrder(projectId).stream()
                .filter(c -> "手工角色-测试".equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("手工新增的角色未落库"));
        assertThat(created.getStatus())
                .as("手工新增角色应为已生成状态（而非 PENDING）")
                .isEqualTo("GENERATED");
    }

    /** 对仍处于「未生成」(PENDING) 的角色手工保存后，应升级为「已生成」(GENERATED)。 */
    @Test
    void updateCharacter_upgradesPendingToGenerated() {
        CharacterEntity legacy = new CharacterEntity();
        legacy.setProjectId(projectId);
        legacy.setName("遗留待生成角色");
        legacy.setStatus("PENDING");
        legacy.setSortOrder(99);
        legacy = characterRepository.save(legacy);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("personality", "沉稳");
        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/projects/" + projectId + "/characters/" + legacy.getId()), form, String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("保存角色请求应成功")
                .isTrue();

        assertThat(characterRepository.findById(legacy.getId()).orElseThrow().getStatus())
                .as("手工保存后应升级为已生成状态")
                .isEqualTo("GENERATED");
    }

    // ==================== Inspect Pages ====================

    @Test
    void inspect_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspect"), String.class);
        assertPageOk(response, "inspect");
    }

    @Test
    void inspectChapter_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspect/chapters/1"), String.class);
        assertPageOk(response, "inspect-chapter");
    }

    @Test
    void inspectCharacters_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspect/characters"), String.class);
        assertPageOk(response, "inspect-characters");
    }

    // ==================== Settings Pages ====================

    @Test
    void settings_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/settings"), String.class);
        assertPageOk(response, "settings");
    }

    @Test
    void guidances_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/settings/guidances"), String.class);
        assertPageOk(response, "guidances");
    }

    @Test
    void materials_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/settings/materials"), String.class);
        assertPageOk(response, "materials");
    }

    @Test
    void ttsTemplates_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/settings/tts-templates"), String.class);
        assertPageOk(response, "tts-templates");
    }

    // ==================== Prompt Pages ====================

    @Test
    void prompts_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/prompts"), String.class);
        assertPageOk(response, "prompts");
    }

    @Test
    void promptExplore_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/prompts/explore"), String.class);
        assertPageOk(response, "prompt-explore");
    }

    // ==================== Import Page ====================

    @Test
    void importPage_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/import"), String.class);
        assertPageOk(response, "import");
    }

    /**
     * TXT 导入页（含逆向工程流程控制与 SSE 实时显示脚本）。
     *
     * <p>回归保护：该模板脚本内含逆向工程协议标记字面量（形如双左方括号开头的标签），
     * Thymeleaf 3 在 HTML 模式下默认把这类文本当作内联表达式解析。若脚本所在的
     * {@code <script>} 缺少 {@code th:inline="none"}，模板会在遇到该字面量的位置
     * 静默中断渲染，返回一个被截断的 200 响应（页面尾部监听器全部丢失）。
     * 由于响应缓冲区已 flush，状态码无法改为 500，因此这类故障只能靠内容断言发现。
     */
    @Test
    void txtImport_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/import/txt"), String.class);
        assertPageOk(response, "txt-import");

        String body = response.getBody();
        assertThat(body).as("txt-import 页面必须完整渲染到 </script> 结束").contains("</script>");
        // 位于协议标记字面量之后的监听器——若模板被截断，这些会全部缺失
        for (String listener : new String[] {
                "addEventListener('phase'",
                "addEventListener('phase-done'",
                "addEventListener('phase-skip'",
                "addEventListener('note'",
                "addEventListener('item'",
                "addEventListener('progress'",
                "addEventListener('done'",
                "addEventListener('stopped'" }) {
            assertThat(body).as("txt-import 页面应包含 SSE 监听器 %s", listener).contains(listener);
        }
    }

    // ==================== TTS Export Pages ====================

    @Test
    void ttsExport_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/tts-export"), String.class);
        assertPageOk(response, "tts-export");
    }

    @Test
    void ttsExport_withProjectId_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/tts-export?projectId=" + projectId), String.class);
        assertPageOk(response, "tts-export (with projectId)");
    }

    // ==================== Helpers ====================

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void assertPageOk(ResponseEntity<String> response, String pageName) {
        assertThat(response.getStatusCode())
                .as("%s page should return HTTP 200", pageName)
                .isEqualTo(HttpStatus.OK);
        // 断言文档完整：Thymeleaf 渲染中途出错时，响应缓冲区可能已 flush，
        // 状态码仍是 200 但正文被截断，只有校验尾部闭合标签才能发现。
        assertThat(response.getBody())
                .as("%s page should be a fully rendered HTML document", pageName)
                .isNotNull()
                .isNotEmpty()
                .contains("<html")
                .contains("</html>");
    }
}
