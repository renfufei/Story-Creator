package com.storycreator.web;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.MaterialCategory;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;

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
    @Autowired private PromptTemplateRepository promptTemplateRepository;
    @Autowired private GuidanceLibraryRepository guidanceLibraryRepository;
    @Autowired private MaterialLibraryRepository materialLibraryRepository;
    @Autowired private TtsReplacementTemplateRepository ttsReplacementTemplateRepository;
    @Autowired private TxtImportJobRepository txtImportJobRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long projectId;
    private Long configId;
    private Long sideStoryId;
    private Long inspirationId;
    private Long customPromptTemplateId;

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
        // 首页已改造为静态页：不得再出现 Thymeleaf 痕迹，且须挂载导航栏 + Ajax 数据源
        assertThat(response.getBody())
                .as("首页应为静态 HTML，不再使用 Thymeleaf")
                .doesNotContain("xmlns:th")
                .doesNotContain("th:href")
                .as("首页应挂载共享导航栏并引用项目列表 API")
                .contains("id=\"site-nav\"")
                .contains("/js/common.js")
                .contains("/js/nav.js")
                .contains("/api/projects");
    }

    @Test
    void projectApi_returnsProjectListAsJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/projects"), String.class);
        assertThat(response.getStatusCode())
                .as("/api/projects should return HTTP 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("项目列表 API 应返回包含本测试项目的 JSON 数组")
                .isNotNull()
                .contains("页面渲染测试项目")
                .contains("\"genre\":\"玄幻\"")
                .contains("\"wordCountText\"")
                .contains("\"currentStep\":\"分章节写作\"");
    }

    @Test
    void staticDashboardAssetsAreServed() {
        // 静态页依赖的公共资源必须可访问，否则页面无样式/无脚本
        for (String asset : new String[]{"/pages/dashboard.html", "/js/common.js", "/js/nav.js", "/css/app.css"}) {
            ResponseEntity<String> response = restTemplate.getForEntity(url(asset), String.class);
            assertThat(response.getStatusCode())
                    .as("静态资源 %s 应可访问", asset)
                    .isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).as("%s 不应为空", asset).isNotEmpty();
        }
    }

    @Test
    void projectNew_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/projects/new"), String.class);
        assertPageOk(response, "project-form (new)");
        assertThat(response.getBody())
                .as("新建项目页应为静态 HTML，不再使用 Thymeleaf")
                .doesNotContain("xmlns:th")
                .doesNotContain("th:field")
                .as("新建项目页应引用表单元数据 API")
                .contains("/api/project-form/meta")
                .contains("/api/projects");
    }

    @Test
    void projectDetail_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/projects/" + projectId), String.class);
        assertPageOk(response, "project-detail");
        // 项目详情页已改造为静态页：不得再出现 Thymeleaf 痕迹
        assertThat(response.getBody())
                .as("项目详情页应为静态 HTML，不再使用 Thymeleaf")
                .doesNotContain("xmlns:th")
                .doesNotContain("th:href");
        // 灵感入口：必须以新标签页打开独立页面（静态页里以模板字符串形式存在）
        assertThat(response.getBody())
                .as("项目信息页应包含以新标签页打开的灵感入口")
                .contains("/inspirations")
                .contains("target=\"_blank\"");
    }

    @Test
    void projectDetailApi_returnsDetailAsJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/projects/" + projectId), String.class);
        assertThat(response.getStatusCode())
                .as("/api/projects/{id} should return HTTP 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("项目详情 API 应包含标题、工作流进度与 AI 用时统计")
                .isNotNull()
                .contains("页面渲染测试项目")
                .contains("\"workflowStates\"")
                .contains("\"stepName\":\"世界观设定\"")
                .contains("\"usageStats\"");
    }

    @Test
    void projectDetailApi_unknownId_returns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/projects/99999999"), String.class);
        assertThat(response.getStatusCode())
                .as("不存在的项目应返回 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void projectEdit_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/projects/" + projectId + "/edit"), String.class);
        assertPageOk(response, "project-form (edit)");
        assertThat(response.getBody())
                .as("编辑项目页应为静态 HTML，不再使用 Thymeleaf")
                .doesNotContain("xmlns:th")
                .doesNotContain("th:field")
                .as("编辑项目页应回读项目表单数据")
                .contains("/api/project-form/");
    }

    @Test
    void projectFormMeta_returnsOptionsAsJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/project-form/meta"), String.class);
        assertThat(response.getStatusCode())
                .as("/api/project-form/meta should return HTTP 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("表单元数据应包含题材、项目状态、模型配置与工作流步骤")
                .isNotNull()
                .contains("\"genres\"")
                .contains("\"code\":\"XUANHUAN\"")
                .contains("\"projectStatuses\"")
                .contains("\"modelConfigs\"")
                .contains("\"workflowSteps\"")
                .contains("\"code\":\"CHARACTER_DESIGN\"");
    }

    @Test
    void projectFormData_returnsCurrentValues() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/project-form/" + projectId), String.class);
        assertThat(response.getStatusCode())
                .as("/api/project-form/{id} should return HTTP 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("表单初值应回显当前项目配置")
                .isNotNull()
                .contains("页面渲染测试项目")
                .contains("\"genre\":\"XUANHUAN\"")
                .contains("\"totalChapters\":3")
                .contains("\"stepGuidances\"")
                .contains("\"stepModelConfigs\"");
    }

    @Test
    void projectApi_createUpdateDelete_roundTrip() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String createBody = "{\"title\":\"ZZ静态化往返测试项目\",\"genre\":\"KEHUAN\"," +
                "\"description\":\"临时项目，用例结束即删除\",\"totalChapters\":2,\"chaptersPerVolume\":10," +
                "\"characterCount\":1,\"chapterWordCount\":1000,\"chapterWordCountMin\":800," +
                "\"chapterWordCountMax\":1200,\"recurringCharacterRate\":0.5,\"tempCharacterRate\":3.0," +
                "\"autoMode\":true}";

        ResponseEntity<String> created = restTemplate.postForEntity(
                url("/api/projects"), new HttpEntity<>(createBody, headers), String.class);
        assertThat(created.getStatusCode()).as("新建项目应成功").isEqualTo(HttpStatus.OK);
        assertThat(created.getBody()).isNotNull().contains("\"id\"");
        Long newId = Long.valueOf(created.getBody().replaceAll("(?s).*\"id\":(\\d+).*", "$1"));
        assertThat(projectRepository.findById(newId)).as("项目应已落库").isPresent();

        // 校验：标题为空应被拒绝
        ResponseEntity<String> bad = restTemplate.postForEntity(
                url("/api/projects"), new HttpEntity<>("{\"title\":\"  \",\"genre\":\"KEHUAN\"}", headers), String.class);
        assertThat(bad.getStatusCode()).as("标题为空应返回 400").isEqualTo(HttpStatus.BAD_REQUEST);

        // 更新（含状态改为「已废弃」，以便后续可删除）
        String updateBody = "{\"title\":\"ZZ静态化往返测试项目(改)\",\"genre\":\"KEHUAN\",\"totalChapters\":5," +
                "\"projectStatus\":\"ABANDONED\"}";
        ResponseEntity<String> updated = restTemplate.exchange(
                url("/api/projects/" + newId), HttpMethod.PUT, new HttpEntity<>(updateBody, headers), String.class);
        assertThat(updated.getStatusCode()).as("更新项目应成功").isEqualTo(HttpStatus.OK);
        assertThat(projectRepository.findById(newId))
                .as("更新后标题与状态应生效")
                .isPresent()
                .get()
                .satisfies(p -> {
                    assertThat(p.getTitle()).isEqualTo("ZZ静态化往返测试项目(改)");
                    assertThat(p.getStatus()).isEqualTo(com.storycreator.core.domain.ProjectStatus.ABANDONED);
                });

        // 删除（仅「已废弃」状态允许删除）
        ResponseEntity<String> deleted = restTemplate.exchange(
                url("/api/projects/" + newId), HttpMethod.DELETE, null, String.class);
        assertThat(deleted.getStatusCode()).as("删除项目应成功").isEqualTo(HttpStatus.OK);
        assertThat(projectRepository.findById(newId)).as("项目应已删除").isEmpty();
    }

    @Test
    void reader_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/projects/" + projectId + "/read"), String.class);
        // 静态化后：阅读页通过同步 XHR 拉取 /api/projects/{id}/read-data，由 Alpine 渲染章节
        assertStaticPage(response, "reader", "readerApp()");
        assertThat(response.getBody())
                .as("阅读页应通过同步 XHR 拉取引导数据")
                .contains("/read-data");
    }

    @Test
    void readerData_returnsBootstrapJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/api/projects/" + projectId + "/read-data"), String.class);
        assertThat(response.getStatusCode()).as("read-data 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("read-data 应含项目/分卷/章节（含正文）/番外数据")
                .contains("\"projectId\"")
                .contains("\"projectTitle\"")
                .contains("\"volumes\"")
                .contains("\"chapters\"")
                .contains("这是第1章的测试内容。")
                .contains("\"sideStories\"")
                .contains("番外章节内容");

        ResponseEntity<String> missing = restTemplate.getForEntity(
                url("/api/projects/999999/read-data"), String.class);
        assertThat(missing.getStatusCode()).as("未知项目 read-data 应 404").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================== Workflow Pages (split into hub + 6 step pages) ====================

    /** Hub 页：列出 6 个步骤供选择，引导脚本拉取 world-building 数据拿到 projectId/title。 */
    @Test
    void workflowHub_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/workflow"), String.class);
        assertPageOk(response, "workflow-hub");
        String body = response.getBody();
        assertThat(body)
                .as("工作流 Hub 页应通过 workflowHubApp() 渲染，并列出可进入的 6 个步骤页")
                .contains("workflowHubApp()")
                .contains("/workflow/world-building")
                .contains("/workflow/characters")
                .contains("/workflow/outline")
                .contains("/workflow/chapters")
                .contains("/workflow/polishing")
                .contains("/workflow/proofreading")
                .as("Hub 引导脚本拉取首步数据")
                .contains("/workflow/world-building/data");
    }

    /** 6 个按步骤拆分的工作流页面各自独立渲染，并通过同步 XHR 拉取自己的引导数据。 */
    @Test
    void workflowStepPages_renderSuccessfully() {
        java.util.Map<String, String> routeApp = java.util.Map.of(
                "world-building", "worldBuildingApp()",
                "characters", "charactersApp()",
                "outline", "outlineApp()",
                "chapters", "chaptersApp()",
                "polishing", "polishingApp()",
                "proofreading", "proofreadingApp()");
        for (var e : routeApp.entrySet()) {
            String route = e.getKey();
            ResponseEntity<String> response = restTemplate.getForEntity(
                    url("/projects/" + projectId + "/workflow/" + route), String.class);
            assertThat(response.getStatusCode())
                    .as("workflow step page %s should return 200", route)
                    .isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .as("step page %s 应挂载对应 app 并通过同步 XHR 拉取自己的引导数据", route)
                    .contains(e.getValue())
                    .contains("var step = '" + route + "'");
        }
    }

    /** 按步骤拆分的数据接口返回合法引导 JSON，且 currentStep 与路由一致。 */
    @Test
    void workflowStepData_returnsBootstrapJson() {
        java.util.Map<String, String> expected = java.util.Map.of(
                "world-building", "WORLD_BUILDING",
                "characters", "CHARACTER_DESIGN",
                "outline", "OUTLINE_GENERATION",
                "chapters", "CHAPTER_WRITING",
                "polishing", "POLISHING",
                "proofreading", "PROOFREADING");
        for (var e : expected.entrySet()) {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    url("/projects/" + projectId + "/workflow/" + e.getKey() + "/data"), String.class);
            assertThat(response.getStatusCode())
                    .as("workflow/%s/data should return 200", e.getKey())
                    .isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .as("workflow/%s/data 应包含项目引导数据且 currentStep 一致", e.getKey())
                    .contains("\"projectId\"")
                    .contains("\"projectTitle\"")
                    .contains("\"currentStep\":\"" + e.getValue() + "\"");
        }
    }

    /** 旧链接 /workflow?step=X 应重定向到对应的按步骤页面（兼容书签）。 */
    @Test
    void workflowLegacyStepParam_redirectsToStepPage() {
        for (WorkflowStep step : WorkflowStep.values()) {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    url("/projects/" + projectId + "/workflow?step=" + step.name()), String.class);
            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("legacy /workflow?step=%s 经重定向后应落到步骤页", step.name())
                    .isTrue();
        }
    }

    /** 不存在的项目访问按步骤数据接口应给出 400（与旧接口一致，避免静默 200）。 */
    @Test
    void workflowStepData_unknownProject_returns400() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/999999/workflow/world-building/data"), String.class);
        assertThat(response.getStatusCode())
                .as("未知项目的步骤数据应返回 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ==================== Side Story Pages ====================

    @Test
    void sideStoryList_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/side-stories"), String.class);
        // 静态化后：列表页通过同步 XHR 拉取 /side-stories/list-data，由 Alpine 渲染卡片
        assertStaticPage(response, "side-story-list", "sideStoryList()");
        assertThat(response.getBody())
                .as("番外列表页应通过同步 XHR 拉取引导数据")
                .contains("/side-stories/list-data");
    }

    @Test
    void sideStoryDetail_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/side-stories/" + sideStoryId), String.class);
        // 静态化后：详情页通过同步 XHR 拉取 /side-stories/{id}/data，由 Alpine 渲染
        assertStaticPage(response, "side-story", "sideStoryWorkflow()");
        assertThat(response.getBody())
                .as("番外详情页应通过同步 XHR 拉取引导数据")
                .contains("/side-stories/")
                .contains("'/data'");
    }

    @Test
    void sideStoryListData_returnsBootstrapJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/side-stories/list-data"), String.class);
        assertThat(response.getStatusCode()).as("list-data 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("list-data 应含项目/番外列表/角色/分卷引导数据")
                .contains("\"projectId\"")
                .contains("\"projectTitle\"")
                .contains("\"sideStories\"")
                .contains("\"番外：前尘往事\"")
                .contains("\"characters\"")
                .contains("\"volumes\"");

        ResponseEntity<String> missing = restTemplate.getForEntity(
                url("/projects/999999/side-stories/list-data"), String.class);
        assertThat(missing.getStatusCode()).as("未知项目 list-data 应 404").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void sideStoryDetailData_returnsBootstrapJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/side-stories/" + sideStoryId + "/data"), String.class);
        assertThat(response.getStatusCode()).as("detail-data 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("detail-data 应含番外/章节/角色关联/角色/分卷引导数据")
                .contains("\"projectId\"")
                .contains("\"projectTitle\"")
                .contains("\"sideStory\"")
                .contains("\"番外：前尘往事\"")
                .contains("\"OUTLINE_READY\"")
                .contains("\"chapters\"")
                .contains("\"characterIds\"")
                .contains("\"characters\"")
                .contains("\"volumes\"");

        ResponseEntity<String> missingProject = restTemplate.getForEntity(
                url("/projects/999999/side-stories/" + sideStoryId + "/data"), String.class);
        assertThat(missingProject.getStatusCode()).as("未知项目 detail-data 应 404").isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> missingStory = restTemplate.getForEntity(
                url("/projects/" + projectId + "/side-stories/999999/data"), String.class);
        assertThat(missingStory.getStatusCode()).as("未知番外 detail-data 应 404").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================== Expansion (情节拓展) Page ====================

    @Test
    void expansion_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/expansion"), String.class);
        // 静态化后：拓展页通过同步 XHR 拉取 /expansion/data，由 Alpine 渲染
        assertStaticPage(response, "expansion", "expansionPage()");
        assertThat(response.getBody())
                .as("情节拓展页应通过同步 XHR 拉取引导数据")
                .contains("/expansion/data")
                .contains("PROJECT_ID");
    }

    @Test
    void expansionData_returnsBootstrapJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/expansion/data"), String.class);
        assertThat(response.getStatusCode()).as("expansion/data 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("expansion/data 应含项目/章节引导数据")
                .contains("\"projectId\"")
                .contains("\"projectTitle\"")
                .contains("\"expansionGuidance\"")
                .contains("\"chapters\"")
                .contains("\"chaptersPerVolume\"");

        ResponseEntity<String> missing = restTemplate.getForEntity(
                url("/projects/999999/expansion/data"), String.class);
        assertThat(missing.getStatusCode()).as("未知项目 expansion/data 应 404").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================== Volume Manager (分卷管理) ====================

    @Test
    void volumeManagerPage_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/volumes/manage"), String.class);
        assertStaticPage(response, "volumes", "volumeManagerApp()");
        assertThat(response.getBody())
                .as("分卷管理页应加载 volumes.js")
                .contains("/js/volumes.js");
    }

    @Test
    void volumeApi_returnsVolumesAndChapters() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/api/projects/" + projectId + "/volumes"), String.class);
        assertThat(response.getStatusCode()).as("volumes API 应 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("volumes API 应含项目/分卷/章节/绑定开关")
                .contains("\"projectTitle\"")
                .contains("\"chaptersPerVolume\"")
                .contains("\"bindingEnabled\"")
                .contains("\"chapterNumbers\"")
                .contains("\"unboundChapterNumbers\"");

        ResponseEntity<String> missing = restTemplate.getForEntity(
                url("/api/projects/999999/volumes"), String.class);
        assertThat(missing.getStatusCode()).as("未知项目 volumes API 应 404").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================== Inspiration Pages (静态页 + JSON API) ====================

    /** 静态页渲染骨架：返回 200 + 完整 HTML + 含静态页专用标记，且不含 Thymeleaf 痕迹。 */
    private void assertStaticPage(ResponseEntity<String> response, String name, String marker) {
        assertPageOk(response, name);
        assertThat(response.getBody())
                .as("%s 应为静态页（无 Thymeleaf 内联表达式痕迹）", name)
                .doesNotContain("xmlns:th")
                .doesNotContain("th:replace")
                .contains(marker);
    }

    @Test
    void inspirationsList_rendersSuccessfully() {
        ResponseEntity<String> page = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspirations"), String.class);
        assertStaticPage(page, "inspirations-list", "新建灵感");
        // 数据由 API 提供：列表 API 应返回已播种的灵感
        ResponseEntity<String> api = restTemplate.getForEntity(
                url("/api/projects/" + projectId + "/inspirations"), String.class);
        assertThat(api.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.getBody()).contains("灵感：主角的第一次顿悟");
    }

    @Test
    void inspirationDetail_rendersSuccessfully() {
        ResponseEntity<String> page = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspirations/" + inspirationId), String.class);
        assertStaticPage(page, "inspiration-detail", "灵感列表");
        // 详情数据由 API 提供：应包含正文
        ResponseEntity<String> api = restTemplate.getForEntity(
                url("/api/projects/" + projectId + "/inspirations/" + inspirationId), String.class);
        assertThat(api.getBody()).contains("雨夜的山道");
    }

    @Test
    void inspirationEdit_rendersSuccessfully() {
        ResponseEntity<String> page = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspirations/" + inspirationId + "/edit"), String.class);
        // 标题回填由前端 JS 完成，这里只校验静态页骨架 + 表单标记
        assertStaticPage(page, "inspiration-edit", "保存修改");
        ResponseEntity<String> api = restTemplate.getForEntity(
                url("/api/projects/" + projectId + "/inspirations/" + inspirationId), String.class);
        assertThat(api.getBody()).contains("灵感：主角的第一次顿悟");
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
                    url("/api/projects/" + otherId + "/inspirations/" + inspirationId), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            transactionTemplate.executeWithoutResult(status -> projectRepository.deleteById(otherId));
        }
    }

    private HttpEntity<Map<String, String>> jsonEntity(String title, String content) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(Map.of("title", title, "content", content == null ? "" : content), h);
    }

    private Long extractId(String jsonBody) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(jsonBody);
        assertThat(m.find()).as("响应 JSON 应包含 id 字段").isTrue();
        return Long.valueOf(m.group(1));
    }

    /**
     * 走一遍真实 HTTP 的新增 → 详情 → 更新 → 列表 → 删除 链路（JSON API 形态）。
     */
    @Test
    void inspiration_crudRoundTripOverHttp() {
        String apiBase = url("/api/projects/" + projectId + "/inspirations");
        ResponseEntity<String> created = restTemplate.postForEntity(
                apiBase, jsonEntity("HTTP 往返灵感", "第一行\n第二行"), String.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("创建请求应成功").isTrue();
        Long newId = extractId(created.getBody());

        // 详情 API 能看到新建内容（换行原样保留）
        ResponseEntity<String> detail = restTemplate.getForEntity(apiBase + "/" + newId, String.class);
        assertThat(detail.getBody()).contains("HTTP 往返灵感").contains("第二行");

        // 更新
        ResponseEntity<String> updated = restTemplate.exchange(
                apiBase + "/" + newId, HttpMethod.PUT, jsonEntity("HTTP 往返灵感（已改）", "改后的内容"), String.class);
        assertThat(updated.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(restTemplate.getForEntity(apiBase + "/" + newId, String.class).getBody())
                .contains("HTTP 往返灵感（已改）").contains("改后的内容");

        // 列表里出现新标题，且原内容已被覆盖
        ResponseEntity<String> list = restTemplate.getForEntity(apiBase, String.class);
        assertThat(list.getBody()).contains("HTTP 往返灵感（已改）").contains("改后的内容").doesNotContain("第一行");

        // 删除
        ResponseEntity<String> deleted = restTemplate.exchange(
                apiBase + "/" + newId, HttpMethod.DELETE, null, String.class);
        assertThat(deleted.getStatusCode().is2xxSuccessful()).isTrue();
        ResponseEntity<String> afterDelete = restTemplate.getForEntity(apiBase + "/" + newId, String.class);
        assertThat(afterDelete.getStatusCode())
                .as("删除后再访问详情应返回错误")
                .isNotEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity(apiBase, String.class).getBody())
                .doesNotContain("HTTP 往返灵感（已改）");
    }

    /** 标题为空白（或干脆没传 title 参数）时不应落库，也不应 500。 */
    @Test
    void inspiration_blankTitleIsRejected() {
        long before = inspirationRepository.countByProjectId(projectId);
        String apiBase = url("/api/projects/" + projectId + "/inspirations");

        // 只有空白字符
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.postForEntity(apiBase, new HttpEntity<>(Map.of("title", "   ", "content", "没有标题的灵感"), h), String.class);

        // 完全没传 title 参数：应返回 400 而非 500
        ResponseEntity<String> missingResp = restTemplate.postForEntity(
                apiBase, new HttpEntity<>(Map.of("content", "缺少标题参数"), h), String.class);
        assertThat(missingResp.getStatusCode())
                .as("缺少 title 参数时应返回 400 而非 5xx")
                .isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(inspirationRepository.countByProjectId(projectId))
                .as("标题无效时不应落库")
                .isEqualTo(before);
        assertThat(restTemplate.getForEntity(apiBase, String.class).getBody())
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
        assertStaticPage(response, "inspect", "inspectOverview()");
        assertThat(response.getBody())
                .as("创作透视页应通过同步 XHR 拉取引导数据")
                .contains("/inspect/data");
    }

    @Test
    void inspectChapter_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspect/chapters/1"), String.class);
        assertStaticPage(response, "inspect-chapter", "inspectChapter()");
        assertThat(response.getBody())
                .as("章节透视页应通过同步 XHR 拉取引导数据")
                .contains("/inspect/chapters/");
    }

    @Test
    void inspectCharacters_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspect/characters"), String.class);
        assertStaticPage(response, "inspect-characters", "inspectCharacters()");
        assertThat(response.getBody())
                .as("角色透视页应通过同步 XHR 拉取引导数据")
                .contains("/inspect/characters/data");
    }

    @Test
    void inspectData_returnsBootstrapJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspect/data"), String.class);
        assertThat(response.getStatusCode()).as("inspect/data 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("inspect/data 应含项目/大纲/卷/分章大纲/章节完整度引导数据")
                .contains("\"projectId\"")
                .contains("\"projectTitle\"")
                .contains("\"storyOutline\"")
                .contains("\"volumes\"")
                .contains("\"outlines\"")
                .contains("\"chapterList\"");

        ResponseEntity<String> missing = restTemplate.getForEntity(
                url("/projects/999999/inspect/data"), String.class);
        assertThat(missing.getStatusCode()).as("未知项目 inspect/data 应 404").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void inspectChapterData_returnsBootstrapJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspect/chapters/1/data"), String.class);
        assertThat(response.getStatusCode()).as("inspect/chapters/1/data 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("inspect/chapters/1/data 应含章节字段可用性与目录引导数据")
                .contains("\"projectId\"")
                .contains("\"chapterNum\"")
                .contains("\"fieldAvail\"")
                .contains("\"chapterMetas\"");
    }

    @Test
    void inspectCharactersData_returnsBootstrapJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspect/characters/data"), String.class);
        assertThat(response.getStatusCode()).as("inspect/characters/data 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("inspect/characters/data 应含角色列表引导数据")
                .contains("\"projectId\"")
                .contains("\"characters\"");
    }

    // ==================== Settings Pages ====================

    @Test
    void settings_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/settings"), String.class);
        assertPageOk(response, "settings");
    }

    // 创作指导库 / 素材库 / 章节分割配置 / TTS替换模板 / 聊天 的渲染用例见下方对应分区（均已迁移为静态页）

    // ==================== Prompt Pages ====================

    @Test
    void prompts_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/prompts"), String.class);
        assertStaticPage(response, "prompts", "__PROMPTS_DATA__");
    }

    @Test
    void promptsData_returnsBootstrapJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/prompts/data"), String.class);
        assertThat(response.getStatusCode()).as("/prompts/data 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("/prompts/data 应含模板列表与枚举选项")
                .contains("\"templates\"")
                .contains("\"steps\"")
                .contains("\"genres\"")
                .contains("\"subSteps\"")
                .contains("stepDisplayName")
                .contains("workflowTagNamesCsv");
    }

    @Test
    void promptExplore_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/prompts/explore"), String.class);
        assertStaticPage(response, "prompt-explore", "__PROMPT_EXPLORE_DATA__");
        assertThat(response.getBody())
                .as("提示词探索页应通过同步 XHR 拉取引导数据")
                .contains("/prompts/explore/data");
    }

    @Test
    void promptExploreData_returnsBootstrapJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/prompts/explore/data"), String.class);
        assertThat(response.getStatusCode()).as("/prompts/explore/data 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("/prompts/explore/data 应含模板原文/变量提示/项目与模型下拉")
                .contains("\"templateContent\"")
                .contains("\"systemPromptContent\"")
                .contains("\"variableNames\"")
                .contains("\"projects\"")
                .contains("\"modelConfigs\"");
    }

    @Test
    void promptExploreData_unknownBuiltinKeyReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/prompts/explore/data?templateKey=NOT_A_REAL_TEMPLATE_KEY"), String.class);
        assertThat(response.getStatusCode()).as("未知 templateKey 应 404").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void promptEdit_customTemplate_rendersSuccessfully() {
        Long id = createCustomPromptTemplate();
        try {
            ResponseEntity<String> page = restTemplate.getForEntity(url("/prompts/" + id + "/edit"), String.class);
            assertStaticPage(page, "prompt-edit", "__PROMPT_EDIT_DATA__");

            ResponseEntity<String> data = restTemplate.getForEntity(
                    url("/prompts/" + id + "/edit-data"), String.class);
            assertThat(data.getStatusCode()).as("自定义模板 edit-data 应返回 200").isEqualTo(HttpStatus.OK);
            assertThat(data.getBody())
                    .as("edit-data 应含模板字段与只读视图所需信息")
                    .contains("\"isBuiltin\":false")
                    .contains("\"stepDisplayName\"")
                    .contains("__probe_prompt_name__");
        } finally {
            transactionTemplate.executeWithoutResult(status -> promptTemplateRepository.deleteById(id));
        }
    }

    @Test
    void promptEdit_builtinTemplate_rendersSuccessfully() {
        String key = "WORLD_BUILDING|WORLD_BUILDING_PRIMARY|";
        ResponseEntity<String> page = restTemplate.getForEntity(url("/prompts/builtin/" + key), String.class);
        assertStaticPage(page, "prompt-edit", "__PROMPT_EDIT_DATA__");

        ResponseEntity<String> data = restTemplate.getForEntity(
                url("/prompts/builtin/" + key + "/edit-data"), String.class);
        assertThat(data.getStatusCode()).as("内置模板 edit-data 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(data.getBody())
                .as("内置模板 edit-data 应标记 isBuiltin=true 并给出步骤显示名")
                .contains("\"isBuiltin\":true")
                .contains("\"stepDisplayName\"");
    }

    @Test
    void promptEdit_unknownIdReturns404() {
        ResponseEntity<String> data = restTemplate.getForEntity(url("/prompts/999999/edit-data"), String.class);
        assertThat(data.getStatusCode()).as("未知模板 edit-data 应 404").isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** 造一个自定义模板，供编辑页用例复用。 */
    private Long createCustomPromptTemplate() {
        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setStep(WorkflowStep.WORLD_BUILDING);
        entity.setGenre(null);
        entity.setName("__probe_prompt_name__");
        entity.setSystemPrompt("probe system");
        entity.setTemplate("probe template {{title}}");
        entity.setDefault(false);
        return transactionTemplate.execute(status -> promptTemplateRepository.save(entity).getId());
    }

    // ==================== Import Page ====================

    @Test
    void importPage_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/import"), String.class);
        assertPageOk(response, "import");
        assertThat(response.getBody())
                .as("导入页已改造为静态页：不得再出现 Thymeleaf 痕迹")
                .doesNotContain("xmlns:th")
                .doesNotContain("th:action")
                .as("导入页应挂载导航栏、提交到 /import 并保留 TXT 导入入口")
                .contains("id=\"site-nav\"")
                .contains("action=\"/import\"")
                .contains("/import/txt");
    }

    // ==================== 创作指导库 ====================

    @Test
    void guidances_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/settings/guidances"), String.class);
        assertPageOk(response, "guidances");
        assertThat(response.getBody())
                .as("创作指导库已改造为静态页：不得再出现 Thymeleaf 痕迹")
                .doesNotContain("xmlns:th")
                .doesNotContain("th:href")
                .as("创作指导库应挂载导航栏并同步注入引导数据")
                .contains("id=\"site-nav\"")
                .contains("/settings/guidances/data")
                .contains("__GUIDANCES_DATA__");
    }

    @Test
    void guidancesData_returnsBootstrapJson() {
        Long id = createGuidance("__probe_guidance__", WorkflowStep.WORLD_BUILDING, "probe guidance content");
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url("/settings/guidances/data"), String.class);
            assertThat(response.getStatusCode()).as("指导库引导数据应 200").isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .as("引导数据应含条目、步骤枚举与显示名")
                    .contains("__probe_guidance__")
                    .contains("\"stepLabel\"")
                    .contains("\"steps\"")
                    .contains("\"itemIds\"");
        } finally {
            guidanceLibraryRepository.deleteById(id);
        }
    }

    @Test
    void guidanceEdit_rendersStaticPageAndData() {
        Long id = createGuidance("__probe_guidance_edit__", WorkflowStep.OUTLINE_GENERATION, "edit guidance body");
        try {
            ResponseEntity<String> page = restTemplate.getForEntity(
                    url("/settings/guidances/" + id + "/edit"), String.class);
            assertPageOk(page, "guidance-edit");
            assertThat(page.getBody())
                    .as("指导编辑页应为静态页")
                    .doesNotContain("xmlns:th")
                    .contains("__GUIDANCE_EDIT_DATA__");

            ResponseEntity<String> data = restTemplate.getForEntity(
                    url("/settings/guidances/" + id + "/edit-data"), String.class);
            assertThat(data.getStatusCode()).as("指导编辑引导数据应 200").isEqualTo(HttpStatus.OK);
            assertThat(data.getBody())
                    .as("编辑引导数据应含名称/内容/步骤选项")
                    .contains("__probe_guidance_edit__")
                    .contains("edit guidance body")
                    .contains("\"steps\"");
        } finally {
            guidanceLibraryRepository.deleteById(id);
        }
    }

    @Test
    void guidanceEdit_unknownIdReturns404() {
        ResponseEntity<String> data = restTemplate.getForEntity(
                url("/settings/guidances/999999/edit-data"), String.class);
        assertThat(data.getStatusCode()).as("未知指导 edit-data 应 404").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================== 素材库 ====================

    @Test
    void materials_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/settings/materials"), String.class);
        assertPageOk(response, "materials");
        assertThat(response.getBody())
                .as("素材库已改造为静态页：不得再出现 Thymeleaf 痕迹")
                .doesNotContain("xmlns:th")
                .doesNotContain("th:href")
                .as("素材库应挂载导航栏并同步注入引导数据")
                .contains("id=\"site-nav\"")
                .contains("/settings/materials/data")
                .contains("__MATERIALS_DATA__");
    }

    @Test
    void materialsData_returnsBootstrapJson() {
        Long id = createMaterial("__probe_material__", "probe material content");
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url("/settings/materials/data"), String.class);
            assertThat(response.getStatusCode()).as("素材库引导数据应 200").isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .as("引导数据应含条目、分类与文本模型选项")
                    .contains("__probe_material__")
                    .contains("\"categoryLabel\"")
                    .contains("\"categories\"")
                    .contains("\"modelConfigs\"");
        } finally {
            materialLibraryRepository.deleteById(id);
        }
    }

    @Test
    void materialEdit_rendersStaticPageAndData() {
        Long id = createMaterial("__probe_material_edit__", "edit material body");
        try {
            ResponseEntity<String> page = restTemplate.getForEntity(
                    url("/settings/materials/" + id + "/edit"), String.class);
            assertPageOk(page, "material-edit");
            assertThat(page.getBody())
                    .as("素材编辑页应为静态页")
                    .doesNotContain("xmlns:th")
                    .contains("__MATERIAL_EDIT_DATA__");

            ResponseEntity<String> data = restTemplate.getForEntity(
                    url("/settings/materials/" + id + "/edit-data"), String.class);
            assertThat(data.getStatusCode()).as("素材编辑引导数据应 200").isEqualTo(HttpStatus.OK);
            assertThat(data.getBody())
                    .as("编辑引导数据应含名称/内容/分类选项")
                    .contains("__probe_material_edit__")
                    .contains("edit material body")
                    .contains("\"categories\"");
        } finally {
            materialLibraryRepository.deleteById(id);
        }
    }

    @Test
    void materialEdit_unknownIdReturns404() {
        ResponseEntity<String> data = restTemplate.getForEntity(
                url("/settings/materials/999999/edit-data"), String.class);
        assertThat(data.getStatusCode()).as("未知素材 edit-data 应 404").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================== 章节分割配置 ====================

    @Test
    void chapterSplitConfigs_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/settings/chapter-split-configs"), String.class);
        assertPageOk(response, "chapter-split-configs");
        assertThat(response.getBody())
                .as("章节分割配置页已改造为静态页：不得再出现 Thymeleaf 痕迹")
                .doesNotContain("xmlns:th")
                .doesNotContain("th:href")
                .as("配置页应挂载导航栏并同步注入引导数据")
                .contains("id=\"site-nav\"")
                .contains("/settings/chapter-split-configs/data")
                .contains("__SPLIT_CONFIG_DATA__");
    }

    @Test
    void chapterSplitConfigsData_returnsConfigList() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/settings/chapter-split-configs/data"), String.class);
        assertThat(response.getStatusCode()).as("分章配置引导数据应 200").isEqualTo(HttpStatus.OK);
        // 内置配置由 Flyway 迁移写入，这里只断言结构字段
        assertThat(response.getBody())
                .as("引导数据应含配置数组与渲染所需字段")
                .contains("\"configs\"")
                .contains("\"pattern\"")
                .contains("\"builtin\"")
                .contains("\"enabled\"");
    }

    // ==================== 测试数据辅助 ====================

    private Long createGuidance(String name, WorkflowStep step, String guidance) {
        GuidanceLibraryEntity entity = new GuidanceLibraryEntity();
        entity.setName(name);
        entity.setStep(step);
        entity.setGuidance(guidance);
        return transactionTemplate.execute(status -> guidanceLibraryRepository.save(entity).getId());
    }

    private Long createMaterial(String name, String content) {
        MaterialLibraryEntity entity = new MaterialLibraryEntity();
        entity.setName(name);
        entity.setCategory(MaterialCategory.OTHER);
        entity.setContent(content);
        entity.setSourceHint("probe source");
        return transactionTemplate.execute(status -> materialLibraryRepository.save(entity).getId());
    }

    // ==================== TXT 导入页（静态页 + 引导 JSON） ====================

    /**
     * TXT 导入页：拆分后只负责第 1 步（上传分割）与第 2 步（预览调整），
     * 之后的逆向选项 / 执行监控拆到 /projects/{id}/reverse/* 独立页面（见 reverse 相关用例）。
     */
    @Test
    void txtImport_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/import/txt"), String.class);
        assertStaticPage(response, "txt-import", "__TXT_IMPORT_DATA__");

        String body = response.getBody();
        assertThat(body).as("txt-import 页面必须完整渲染到 </script> 结束").contains("</script>");
        // 第 1/2 步关键元素仍然在页面上
        assertThat(body).as("应包含上传分割入口").contains("uploadAndSplit");
        assertThat(body).as("应包含保存并进入逆向工程").contains("saveAndGoToOptions");
        // 完成第 2 步后跳转到按项目定位的逆向选项页
        assertThat(body).as("完成后应跳转逆向选项页").contains("/reverse/options");
    }

    @Test
    void txtImportData_returnsBootstrapJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/import/txt/data"), String.class);
        assertThat(response.getStatusCode()).as("/import/txt/data 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("/import/txt/data 应含分割配置 / 题材 / 模型列表")
                .contains("\"splitConfigs\"")
                .contains("\"genres\"")
                .contains("\"modelConfigs\"")
                .contains("displayName");
    }

    // ==================== Reverse Engineering Pages (split from /import/txt step 3/4) ====================

    @Test
    void reverseOptionsPage_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/reverse/options"), String.class);
        assertStaticPage(response, "reverse-options", "__REVERSE_DATA__");
        assertThat(response.getBody())
                .as("逆向选项页应挂载 reverseOptions()、包含启动逻辑并引用共享 JS")
                .contains("reverseOptions()")
                .contains("startReverse")
                .contains("/js/reverse.js");
    }

    @Test
    void reverseProgressPage_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/reverse/progress"), String.class);
        assertStaticPage(response, "reverse-progress", "__REVERSE_DATA__");

        String body = response.getBody();
        assertThat(body).as("监控页必须完整渲染到 </script> 结束").contains("</script>");
        assertThat(body)
                .as("监控页应挂载 reverseProgress()、含输出区并引用共享 JS")
                .contains("reverseProgress()")
                .contains("id=\"reOutput\"")
                .contains("/js/reverse.js");
    }

    /** 逆向流程页的 SSE 监听器与启动跳转集中在共享 JS（自 txt-import 页拆出）。 */
    @Test
    void reverseSharedJs_containsSseListenersAndRedirect() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/js/reverse.js"), String.class);
        assertThat(response.getStatusCode()).as("/js/reverse.js 应返回 200").isEqualTo(HttpStatus.OK);

        String body = response.getBody();
        // 位于协议标记字面量之后的监听器——若脚本被截断，这些会全部缺失
        for (String listener : new String[] {
                "addEventListener('phase'",
                "addEventListener('phase-done'",
                "addEventListener('phase-skip'",
                "addEventListener('note'",
                "addEventListener('item'",
                "addEventListener('progress'",
                "addEventListener('done'",
                "addEventListener('stopped'" }) {
            assertThat(body).as("reverse.js 应包含 SSE 监听器 %s", listener).contains(listener);
        }
        assertThat(body).as("启动成功后应跳转到独立监控页").contains("/reverse/progress");
    }

    @Test
    void reverseData_withoutImportJob_returns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/reverse/data"), String.class);
        assertThat(response.getStatusCode()).as("无导入任务的项目应返回 404").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("error");
    }

    @Test
    void reverseData_withImportJob_returnsJobBootstrap() {
        Long jobId = transactionTemplate.execute(status -> {
            TxtImportJobEntity job = new TxtImportJobEntity();
            job.setProjectId(projectId);
            job.setTitle("逆向引导数据测试");
            job.setStatus("DONE");
            job.setChapterCount(3);
            job.setTotalWordCount(600);
            job.setRunWorldBuilding(true);
            job.setRunCharacters(true);
            job.setRunOutline(false);
            job.setChaptersPerVolume(3);
            return txtImportJobRepository.save(job).getId();
        });
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    url("/projects/" + projectId + "/reverse/data"), String.class);
            assertThat(response.getStatusCode()).as("有关联导入任务的项目应返回 200").isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .as("引导数据应包含 job 关键字段与模型列表")
                    .contains("\"jobId\":" + jobId)
                    .contains("\"runOutline\":false")
                    .contains("\"chaptersPerVolume\":3")
                    .contains("\"modelConfigs\"");
        } finally {
            transactionTemplate.executeWithoutResult(status -> txtImportJobRepository.deleteById(jobId));
        }
    }

    // ==================== TTS Export / Full Play Pages ====================

    @Test
    void ttsExport_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/tts-export"), String.class);
        assertStaticPage(response, "tts-export", "__TTS_EXPORT_DATA__");
    }

    @Test
    void ttsExport_withProjectId_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/tts-export?projectId=" + projectId), String.class);
        assertStaticPage(response, "tts-export (with projectId)", "__TTS_EXPORT_DATA__");
        assertThat(response.getBody())
                .as("预选 projectId 应由静态页自行解析并随引导请求带回")
                .contains("projectId");
    }

    @Test
    void ttsExportData_returnsBootstrapJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/tts-export/data"), String.class);
        assertThat(response.getStatusCode()).as("/tts-export/data 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("/tts-export/data 应含项目列表与预选字段")
                .contains("\"projects\"")
                .contains("\"preselectedProjectId\"");
    }

    @Test
    void ttsFullplay_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/tts-fullplay?taskId=1"), String.class);
        assertStaticPage(response, "tts-fullplay", "fullPlayApp()");
        assertThat(response.getBody())
                .as("全文收听页应从查询串读取 taskId")
                .contains("__TTS_FULLPLAY_TASK_ID__");
    }

    // ==================== TTS 替换模板 Pages ====================

    @Test
    void ttsTemplates_asStaticPage_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/settings/tts-templates"), String.class);
        assertStaticPage(response, "tts-templates", "__TTS_TEMPLATES_DATA__");
    }

    @Test
    void ttsTemplatesData_returnsBootstrapJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/settings/tts-templates/data"), String.class);
        assertThat(response.getStatusCode()).as("TTS 模板引导数据应 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("引导数据应含内置 / 自定义模板数组")
                .contains("\"builtinTemplates\"")
                .contains("\"userTemplates\"");
    }

    @Test
    void ttsTemplateView_rendersSuccessfully() {
        String builtinId = firstBuiltinTtsTemplateId();
        ResponseEntity<String> page = restTemplate.getForEntity(
                url("/settings/tts-templates/builtin/" + builtinId + "/view"), String.class);
        assertStaticPage(page, "tts-template-view", "__TTS_TEMPLATE_VIEW_DATA__");

        ResponseEntity<String> data = restTemplate.getForEntity(
                url("/settings/tts-templates/builtin/" + builtinId + "/view-data"), String.class);
        assertThat(data.getStatusCode()).as("内置模板 view-data 应 200").isEqualTo(HttpStatus.OK);
        assertThat(data.getBody())
                .as("view-data 应含名称与规则数组")
                .contains("\"name\"")
                .contains("\"rules\"")
                .contains("\"pattern\"");
    }

    @Test
    void ttsTemplateView_unknownIdReturns404() {
        ResponseEntity<String> data = restTemplate.getForEntity(
                url("/settings/tts-templates/builtin/NOT_A_REAL_BUILTIN/view-data"), String.class);
        assertThat(data.getStatusCode()).as("未知内置模板应 404").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ttsTemplateEdit_rendersSuccessfully() {
        Long id = createTtsReplacementTemplate("__probe_tts_tpl__");
        try {
            ResponseEntity<String> page = restTemplate.getForEntity(
                    url("/settings/tts-templates/" + id + "/edit"), String.class);
            assertStaticPage(page, "tts-template-edit", "__TTS_TEMPLATE_EDIT_DATA__");

            ResponseEntity<String> data = restTemplate.getForEntity(
                    url("/settings/tts-templates/" + id + "/edit-data"), String.class);
            assertThat(data.getStatusCode()).as("模板 edit-data 应 200").isEqualTo(HttpStatus.OK);
            assertThat(data.getBody())
                    .as("edit-data 应含模板信息与规则数组")
                    .contains("\"template\"")
                    .contains("\"rules\"")
                    .contains("__probe_tts_tpl__");
        } finally {
            transactionTemplate.executeWithoutResult(status -> ttsReplacementTemplateRepository.deleteById(id));
        }
    }

    @Test
    void ttsTemplateEdit_unknownIdReturns404() {
        ResponseEntity<String> data = restTemplate.getForEntity(
                url("/settings/tts-templates/999999/edit-data"), String.class);
        assertThat(data.getStatusCode()).as("未知模板 edit-data 应 404").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ttsTemplateBindings_rendersSuccessfully() {
        // 绑定页只需 configId 即可渲染（无绑定时展示空态）
        ResponseEntity<String> page = restTemplate.getForEntity(
                url("/settings/tts-templates/bindings/1"), String.class);
        assertStaticPage(page, "tts-template-bindings", "__TTS_BINDINGS_DATA__");

        ResponseEntity<String> data = restTemplate.getForEntity(
                url("/settings/tts-templates/bindings/1/data"), String.class);
        assertThat(data.getStatusCode()).as("绑定引导数据应 200").isEqualTo(HttpStatus.OK);
        assertThat(data.getBody())
                .as("绑定引导数据应含 configId / bindings / allOptions")
                .contains("\"configId\"")
                .contains("\"bindings\"")
                .contains("\"allOptions\"");
    }

    // ==================== Chat Page ====================

    @Test
    void chat_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/chat"), String.class);
        assertStaticPage(response, "chat", "__CHAT_DATA__");
        assertThat(response.getBody())
                .as("聊天页应含 chatApp 与流式解析逻辑")
                .contains("chatApp()")
                .contains("/api/chat/sessions/");
    }

    @Test
    void chatData_returnsBootstrapJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/chat/data"), String.class);
        assertThat(response.getStatusCode()).as("/chat/data 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("/chat/data 应含会话与三类模型配置")
                .contains("\"sessions\"")
                .contains("\"textConfigs\"")
                .contains("\"ttsConfigs\"")
                .contains("\"imageConfigs\"");
    }

    // ==================== Learn Pages ====================

    @Test
    void learn_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/learn"), String.class);
        assertStaticPage(response, "learn", "九九乘法口诀");
        assertThat(response.getBody())
                .as("教学首页应含乘法学习与音频设置入口")
                .contains("/learn/multiplication")
                .contains("/learn/multiplication/settings");
    }

    @Test
    void learnMultiplication_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/learn/multiplication"), String.class);
        assertStaticPage(response, "learn-multiplication", "multiplicationApp()");
        assertThat(response.getBody())
                .as("乘法页应含口诀数据与音频取值逻辑")
                .contains("九九八十一")
                .contains("/api/learn/multiplication/audio/");
    }

    @Test
    void learnMultiplicationSettings_rendersSuccessfully() {
        ResponseEntity<String> page = restTemplate.getForEntity(
                url("/learn/multiplication/settings"), String.class);
        assertStaticPage(page, "learn-multiplication-settings", "__LEARN_SETTINGS_DATA__");
        assertThat(page.getBody())
                .as("音频管理页应含 settingsApp 与音频状态接口")
                .contains("settingsApp()")
                .contains("/api/learn/multiplication/audio-status");
    }

    @Test
    void learnMultiplicationSettingsData_returnsBootstrapJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/learn/multiplication/settings/data"), String.class);
        assertThat(response.getStatusCode()).as("音频管理引导数据应 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("引导数据应含 TTS 配置 / 默认配置 / 口诀 / 前缀 / 音频总数")
                .contains("\"ttsConfigs\"")
                .contains("\"defaultTtsConfigId\"")
                .contains("\"formulas\"")
                .contains("\"prefixes\"")
                .contains("\"totalAudioCount\"")
                .contains("九九八十一");
    }

    // ==================== 测试数据辅助（TTS 替换模板） ====================

    /** 取第一个内置 TTS 替换模板 id（避免在测试里硬编码内置模板标识）。 */
    @SuppressWarnings("unchecked")
    private String firstBuiltinTtsTemplateId() {
        Map<String, Object> data = restTemplate.getForObject(
                url("/settings/tts-templates/data"), Map.class);
        List<Map<String, Object>> builtins = (List<Map<String, Object>>) data.get("builtinTemplates");
        assertThat(builtins).as("应至少存在一个内置 TTS 替换模板").isNotEmpty();
        return String.valueOf(builtins.get(0).get("id"));
    }

    private Long createTtsReplacementTemplate(String name) {
        TtsReplacementTemplateEntity entity = new TtsReplacementTemplateEntity();
        entity.setName(name);
        entity.setDescription("probe description");
        entity.setEnabled(true);
        return transactionTemplate.execute(status -> ttsReplacementTemplateRepository.save(entity).getId());
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
