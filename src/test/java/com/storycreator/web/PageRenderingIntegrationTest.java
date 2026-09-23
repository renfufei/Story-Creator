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
                .contains("/api/projects")
                .as("首页「导入项目」按钮应指向 TXT 导入页，而非备份导入 /import")
                .contains("href=\"/import/txt\"")
                .doesNotContain("href=\"/import\"");
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
                .contains("/read-data")
                .as("阅读页应内置护眼背景色选择器：浮动按钮 + 弹出层 + 预设 + 本地记忆")
                .contains("class=\"bg-fab\"")
                .contains("阅读背景色")
                .contains("class=\"bg-popover\"")
                .contains("bgPresets: [")
                .contains("--reader-bg")
                .contains("reader_bg")
                .as("侧边栏与「恢复默认」按钮也要跟随背景色（侧栏配色由 mixHex 从底色+文字色推导）")
                .contains("--reader-sidebar-bg")
                .contains("--reader-sidebar-muted")
                .contains("mixHex(p.bg, p.fg")
                .contains("class=\"btn btn-sm w-100 bg-reset-btn\"");
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

    // ==================== Workflow Pages (6 step pages) ====================

    /**
     * 工作流入口：Hub 选择页已下线，{@code /workflow} 直接落到第一步「世界观设定」。
     * TestRestTemplate 会跟随重定向，故以最终落地的页面内容做断言。
     */
    @Test
    void workflowEntry_redirectsToWorldBuilding() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/workflow"), String.class);
        assertPageOk(response, "workflow-entry");
        assertThat(response.getBody())
                .as("/workflow 应重定向并渲染 world-building 步骤页（Hub 已下线）")
                .contains("worldBuildingApp()")
                .contains("var step = 'world-building'")
                .doesNotContain("workflowHubApp()");
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

    /**
     * 逆向工程的内置模板原先没有登记流程标签（workflowTagNamesCsv 为空），
     * 导致 /prompts 页面既筛不出【逆向】，流程列也显示为空。
     */
    @Test
    void promptsData_reverseTemplatesCarryReverseWorkflowTag() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/prompts/data"), String.class);
        assertThat(response.getStatusCode()).as("/prompts/data 应返回 200").isEqualTo(HttpStatus.OK);

        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody());
        int reverseCount = 0;
        for (var t : root.get("templates")) {
            String subStep = t.path("subStep").asText("");
            if (!subStep.startsWith("REVERSE_")) continue;
            reverseCount++;
            assertThat(t.path("workflowTagNamesCsv").asText())
                    .as("逆向模板 %s 应带 REVERSE 流程标签（否则页面【逆向】筛选命中不到）", subStep)
                    .contains("REVERSE");
        }
        assertThat(reverseCount).as("应存在逆向子步骤模板").isGreaterThan(0);

        assertThat(response.getBody())
                .as("/prompts/data 应下发【逆向】标签的展示名")
                .contains("\"displayName\":\"逆向\"");
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
                .contains("data-nav=\"import\"")
                .contains("action=\"/import\"")
                .as("备份导入页应提供跳转到 TXT 导入页的按钮")
                .contains("href=\"/import/txt\"")
                .contains("TXT导入");
    }

    @Test
    void navigationImportEntry_pointsToTxtImportPage() {
        // 导航栏「导入项目」的默认入口是 TXT 导入页；备份导入（/import）靠页内按钮进入。
        ResponseEntity<String> response = restTemplate.getForEntity(url("/js/nav.js"), String.class);
        assertThat(response.getStatusCode()).as("/js/nav.js 应可访问").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("导航栏「导入项目」应指向 /import/txt")
                .contains("text: '导入项目'")
                .contains("href: '/import/txt'")
                .doesNotContain("href: '/import'");
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
    void guidances_usesCardLayoutWithHiddenSelectionControls() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/settings/guidances"), String.class);
        assertPageOk(response, "guidances");
        assertThat(response.getBody())
                .as("列表应改为卡片式布局")
                .contains("sc-guidance-grid")
                .contains("sc-guidance-card")
                .as("勾选工具条默认不展示：勾选框、确认/取消导出仅进入导出模式后由脚本显示")
                .contains("id=\"exportBar\"")
                .contains("id=\"checkAll\"")
                .contains("id=\"checkAllText\"")
                .contains("id=\"btnExportConfirm\"")
                .contains("id=\"btnExportCancel\"")
                .as("全选控件应是带文字提示的独立按钮（放在最左）")
                .contains("class=\"sc-selectall\"")
                .as("删除入口应从列表页移除，只保留在编辑页")
                .doesNotContain("/delete");
    }

    @Test
    void guidanceEdit_deleteEntryMovedToEditPage() {
        Long id = createGuidance("__probe_guidance_delete__", WorkflowStep.WORLD_BUILDING, "delete probe");
        try {
            ResponseEntity<String> page = restTemplate.getForEntity(
                    url("/settings/guidances/" + id + "/edit"), String.class);
            assertPageOk(page, "guidance-edit");
            assertThat(page.getBody())
                    .as("删除按钮应出现在编辑页（列表页已移除）")
                    .contains("id=\"deleteForm\"")
                    .contains("btn-outline-danger");

            restTemplate.postForEntity(url("/settings/guidances/" + id + "/delete"), null, String.class);
            assertThat(guidanceLibraryRepository.findById(id))
                    .as("编辑页删除入口实际生效：库里应查不到该创作指导")
                    .isEmpty();
        } finally {
            if (guidanceLibraryRepository.existsById(id)) {
                guidanceLibraryRepository.deleteById(id);
            }
        }
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
        // 与「备份导入」页（/import）互为入口，避免用户只能靠手改 URL 来回切
        assertThat(body).as("TXT 导入页应提供跳转到备份导入页的按钮")
                .contains("href=\"/import\"")
                .contains("备份导入");
        // 第 2 步只做预览，必须回显第 1 步填写的项目名称，否则用户不知道自己在导入哪本书
        assertThat(body).as("第 2 步应回显项目名称")
                .contains("项目名称")
                .contains("x-text=\"title\"");
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

    // ==================== 全站链接外观统一（sc-link 样式族） ====================

    /**
     * app.css 必须提供整套链接样式族。缺任何一个，对应页面就会退回浏览器默认的
     * 蓝色下划线链接（「朴素链」），所以这里把族成员钉住防回归。
     */
    @Test
    void appCss_providesLinkStyleFamily() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/css/app.css"), String.class);
        assertThat(response.getStatusCode()).as("/css/app.css 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("app.css 应提供完整链接样式族")
                .contains(".sc-link {")
                .contains(".sc-chip-link {")
                .contains(".sc-card-link {")
                .contains(".sc-toc-link {")
                .contains(".sc-icon-link {")
                .contains(".sc-card-block {")
                .contains(".sc-card-cta {")
                .contains("a.list-group-item.sc-anchor-link")
                // 面包屑直接覆盖 Bootstrap，5 个页面无需改标记即可生效
                .contains("--bs-breadcrumb-divider")
                .contains(".breadcrumb-item > a");
    }

    /**
     * 关键页面必须用共享链接类，而不是旧的 text-decoration-none 裸链写法。
     */
    @Test
    void linkUpgrade_keyPagesUseSharedLinkClasses() {
        // 六步流程页：步骤导航带图标、项目名用卡片链
        String wf = restTemplate.getForEntity(
                url("/projects/" + projectId + "/workflow/chapters"), String.class).getBody();
        assertThat(wf).as("流程页步骤导航应渲染图标").contains(":class=\"s.icon\"");
        assertThat(wf).as("流程页项目名应用卡片链").contains("class=\"sc-card-link\"");
        assertThat(wf).as("流程页不应残留裸标题链").doesNotContain("text-decoration-none text-dark");
        assertThat(wf).as("流程页步骤链不应再靠 text-decoration-none 去下划线")
                .doesNotContain("class=\"workflow-step text-decoration-none\"");

        // 灵感列表：卡片标题链
        String insp = restTemplate.getForEntity(
                url("/projects/" + projectId + "/inspirations"), String.class).getBody();
        assertThat(insp).as("灵感列表标题应用卡片链").contains("sc-card-link");
        assertThat(insp).as("灵感列表「所属项目」应用内联链").contains("class=\"sc-link\" id=\"project-link\"");

        // 设置页：推荐模型用胶囊链
        String settings = restTemplate.getForEntity(url("/settings"), String.class).getBody();
        assertThat(settings).as("设置页推荐模型应用胶囊链").contains("class=\"sc-chip-link\"");
        assertThat(settings).as("设置页不应残留灰底徽章链")
                .doesNotContain("badge bg-secondary text-decoration-none");
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
                .as("教学首页应含乘法学习、音频设置与英语单词匹配入口")
                .contains("/learn/multiplication")
                .contains("/learn/multiplication/settings")
                .contains("英语单词匹配")
                .contains("/learn/word-match");
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
    void learnWordMatch_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/learn/word-match"), String.class);
        assertStaticPage(response, "learn-word-match", "wordMatchApp()");
        assertThat(response.getBody())
                .as("单词匹配页应含引导数据钩子、配对状态与音效开关")
                .contains("__WORD_MATCH_DATA__")
                .contains("/learn/word-match/data")
                .contains("选择配对")
                .contains("is-sel")
                .contains("is-done")
                .contains("is-wrong");
        assertThat(response.getBody())
                .as("单词匹配页应含自动学习 / 错题本 / 册次弹出框 / 上下关切换")
                .contains("word_match_auto_progress_v2")
                .contains("word_match_wrong_v1")
                .contains("自动学习")
                .contains("错题本")
                .contains("上一关")
                .contains("下一关")
                .contains("选择年级册次")
                .contains("听读音")             // 朗读时机前移到「点英文」那一刻
                .contains("speakOnPick")
                .doesNotContain("top: 42%")     // 过关提示不再浮在单词区正中遮挡卡片
                .doesNotContain("wm-select");   // 册次下拉框已改成弹出框
        assertThat(response.getBody())
                .as("册次选择器应按学段分组（小学 8 + 初中 5 + 高中 11）并可折叠，默认只展开当前册所在学段")
                .contains("bookGroups")
                .contains("wm-book-group-head")
                .contains("toggleStage")
                .contains("isStageOpen")
                .contains("高中");
        assertThat(response.getBody())
                .as("本册最后一关应把「下一关」换成「下一册」：否则打完最后一关、"
                        + "点掉通关窗的「继续看看」之后既不能前进也没有换册入口")
                .contains("atBookEnd")
                .contains("lastLevelDone")
                .contains("canNavNext")
                .contains("navNextLabel")
                .contains("navNextIcon")
                .contains("navNext()")
                .contains("下一册")
                .contains("最后一册");
        assertThat(response.getBody())
                .as("移动端浮动胶囊按钮必须是白底，且**连 hover/active/focus 的文字色一起钉死**："
                        + "真机点完会留下粘滞 :hover，Bootstrap 的 btn-outline-secondary:hover "
                        + "会把文字切成 #fff，只改 background 就会出现「白字压白底 = 文字消失」")
                .contains("--bs-btn-hover-color")
                .contains("--bs-btn-active-color")
                .contains("--bs-btn-disabled-color");
        assertThat(response.getBody())
                .as("设置弹层应提供「源码」条目：用真 <a>（中键/右键新标签页才照常可用）+ "
                        + "target=_blank + rel=noopener 指向仓库；导出件里**保留**这一条 —— "
                        + "它是导航链接、不加载资源，离线打开只是点了没反应，"
                        + "不破坏「零资源外链」自检（早期版本曾整条 remove，已回退）")
                .contains("wm-set-item is-source")
                .contains("https://github.com/renfufei/Story-Creator")
                .contains("rel=\"noopener noreferrer\"")
                .doesNotContain("srcLink.remove()");
        assertThat(response.getBody())
                .as("设置弹层应提供「导出为单页 HTML」：册次多选 + 全选 + 默认勾当前册，"
                        + "产物零资源外链（样式/脚本/图标字体/词库全内联，只留「源码」一条导航链接），双击即可玩")
                .contains("wm-set-item is-export")
                .contains("openExport()")
                .contains("wm-export-modal")
                .contains("wm-export-all")
                .contains("wm-export-stage")
                .contains("wm-export-item")
                .contains("wm-export-go")
                .contains("buildStandaloneWordMatch")
                .contains("exportSelList")
                .contains("exportToggleAll")
                .contains("data-standalone")
                .contains("downloadHtml")
                .contains("word-match-")
                .contains("stampDateTime");
        assertThat(response.getBody())
                .as("导出的单页件要能自己定位起始册：写死 __WM_DEFAULT_BOOK__（导出时正在学的那一册，"
                        + "那册没被勾选则退到勾选册里最靠前的一册），并配一个带时间戳的专属存储键 "
                        + "__WM_BOOK_KEY__ —— file:// 下所有本地文件同源，共用 word_match_book_v1 "
                        + "会让几个导出件互相串册、也会被线上页面的选择影响")
                .contains("window.__WM_DEFAULT_BOOK__")
                .contains("window.__WM_BOOK_KEY__")
                .contains("word_match_book_solo_")
                .contains("startBookId")
                .contains("this.bookKey || BOOK_KEY");
        assertThat(response.getBody())
                .as("册次选择器的学段标题应显示「N 册 · M 词」而不是关卡数"
                        + "（词汇量是用户关心的量级，关卡数只在进度里出现）")
                .contains("g.books.length + ' 册 · ' + g.wordCount + ' 词'")
                .doesNotContain("g.levelCount + ' 关'");
        assertThat(response.getBody())
                .as("配对成功要有反馈动画：两张卡一起闪浅绿约 .5s，然后收回「已完成」的灰。"
                        + "is-ok 与 is-done 同时存在，靠 CSS 源码顺序（.is-ok 必须排在 .is-done 之后）胜出 —— "
                        + "两条选择器同为 (0,2,0)，顺序一挪就会被灰底吃掉")
                .contains("'is-ok': card.ok")
                .contains("ok: false")
                .contains("flashOk(first, second)")
                .contains("flashOk: function")
                .contains("--wm-ok-bg")
                .contains("--wm-ok-hi")
                .contains(".wm-card.is-ok {")
                .contains("@keyframes wm-ok-blink")
                .contains("animation: wm-ok-blink .5s")
                .contains("okFlashMs")
                .contains("var OK_FLASH = pace('wmOkFlash', 500)")
                .contains("prefers-reduced-motion");
        assertThat(response.getBody())
                .as("大学是**独立词源**（2 册 / 2193 关 / 13159 条）：首屏只带它的册元信息（extraBooks），"
                        + "关卡按需拉 /learn/word-match/cet。选册、导出、自动连播跨册这三条路径都必须"
                        + "先把词库载完再进关，否则用户看到的是一块空棋盘")
                .contains("extraBooks")
                .contains("/learn/word-match/cet")
                .contains("isCetBook")
                .contains("ensureCet")
                .contains("applyBookWhenReady")
                .contains("cetLoaded")
                .contains("cetPromise")
                .contains("await this.enterBookInAuto(nextBook.id)")
                .contains("正在载入大学词库");
    }

    @Test
    void learnWordMatchCet_isSeparateSourceFromPep() {
        ResponseEntity<String> data = restTemplate.getForEntity(
                url("/learn/word-match/data"), String.class);
        assertThat(data.getBody())
                .as("首屏引导数据里只放大学的**册元信息**：全部关卡另有 gzip 约 225KB，"
                        + "而首屏数据是同步 XHR 取的，塞进来会让白屏时间翻两倍")
                .contains("\"extraBooks\"")
                .contains("cet-4")
                .contains("cet-6")
                .contains("大学")
                .doesNotContain("n. 通道，入口");     // 四级词条绝不能混进首屏数据

        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/learn/word-match/cet"), String.class);
        assertThat(response.getStatusCode()).as("大学词库端点应 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("大学词库：结构与首屏数据完全一致（books + levels），前端合并后无差别使用；"
                        + "主题只由词性归并而来")
                .contains("\"books\"")
                .contains("\"levels\"")
                .contains("\"pairs\"")
                .contains("\"theme\"")
                .contains("cet-4")
                .contains("cet-6")
                .contains("四级")
                .contains("六级")
                .contains("大学")
                .contains("名词")
                .contains("动词")
                .contains("形容词")
                .contains("副词")
                .contains("access")
                .contains("n. 通道，入口");
    }

    @Test
    void learnWordMatch_autoLearnAdvancesAcrossBooks() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/learn/word-match"), String.class);
        assertThat(response.getBody())
                .as("自动学习应支持跨册续学：本册学完给 5 秒跳转提示后自动进入下一册")
                .contains("wmBookGap")                 // 节奏可覆盖（测试用）
                .contains("AUTO_BOOK_GAP")
                .contains("BOOK_GAP_SEC")
                .contains("秒后进入下一册")
                .contains("本册学完！即将进入")
                .contains("nextBookRef")
                .contains("enterBookInAuto")
                .contains("全部 ' + this.books.length + ' 册都学完啦");
        assertThat(response.getBody())
                .as("自动学习状态条与过关提示必须单行，移动端不被撑成两行")
                .contains("wm-auto-tag")               // 固定短标签（步骤名）
                .contains("wm-auto-text")              // 详情，过长省略
                .contains("flex-wrap: nowrap")
                .doesNotContain("white-space: normal");// 原先窄屏把提示放成两行 -> 状态条被撑高
    }

    @Test
    void learnWordMatchData_returnsBooksAndLevels() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/learn/word-match/data"), String.class);
        assertThat(response.getStatusCode()).as("单词匹配引导数据应 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("引导数据应含册列表与按主题切好的关卡")
                .contains("\"books\"")
                .contains("\"levels\"")
                .contains("\"pairs\"")
                .contains("\"theme\"")
                .contains("三年级上册")
                .contains("六年级下册")
                .contains("pep-3-1")
                .contains("red")
                .contains("红色")
                .contains("\"stage\"")          // 学段，前端据此分组
                .contains("七年级上册")           // 初中 5 册（7~9 年级）
                .contains("九年级全一册")
                .contains("pep-7-1")
                .contains("environment")
                .contains("环境")
                .contains("必修1")              // 高中 11 册（必修1-5 + 选修6-11）
                .contains("选修11")
                .contains("pep-h-1")
                .contains("Unit 1")            // 高中按课本单元分组
                .contains("astronomy")
                .contains("天文学");
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

    // ==================== 页面骨架一致性（防回归） ====================

    /**
     * 常规静态页必须套统一骨架：{@code body.sc-page} + {@code main.sc-main.container py-4}
     * + 公共样式 {@code /css/style.css}、{@code /css/app.css}。
     *
     * <p>历史上多个页面直接裸放内容（既无容器也无公共 CSS），内容会贴到视口左右边缘、
     * 背景也与其它页不一致。此用例锁死这层约定，新增页面漏骨架会立刻失败。
     * 沉浸式全屏页（{@code /read}、{@code /inspect/chapters/{n}}）刻意不套，不在清单内。
     */
    @Test
    void regularPages_shareCommonSkeleton() {
        List<String> paths = List.of(
                "/projects/" + projectId + "/expansion",
                "/projects/" + projectId + "/inspect",
                "/projects/" + projectId + "/inspect/characters",
                "/projects/" + projectId + "/side-stories",
                "/projects/" + projectId + "/side-stories/" + sideStoryId,
                "/projects/" + projectId + "/inspirations",
                "/projects/" + projectId + "/inspirations/" + inspirationId,
                "/projects/" + projectId + "/inspirations/" + inspirationId + "/edit",
                "/inspirations"
        );
        for (String path : paths) {
            ResponseEntity<String> response = restTemplate.getForEntity(url(path), String.class);
            assertPageOk(response, path);
            assertThat(response.getBody())
                    .as("%s 应套用公共页面骨架（body.sc-page + main.sc-main + 公共样式）", path)
                    .contains("<body class=\"sc-page\"")
                    .contains("sc-main container")
                    .contains("/css/style.css")
                    .contains("/css/app.css");
        }
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
