package com.storycreator.web;

import com.storycreator.api.InspirationApiController;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.GuidanceLibraryStep;
import com.storycreator.core.domain.MaterialCategory;
import com.storycreator.core.domain.ModelType;
import com.storycreator.core.domain.PromptSubStep;
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

import java.nio.charset.StandardCharsets;
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
        // 无项目灵感（project_id = 0）不属于任何项目，按项目清理带不走它们 —— 单独兜底，
        // 免得某条用例断言失败时把残留留给下一条（汇总接口那条断言依赖「0 号组置顶」）。
        transactionTemplate.executeWithoutResult(status ->
                inspirationRepository.deleteByProjectId(InspirationApiController.NO_PROJECT_ID));
    }

    // ==================== Dashboard & Project Pages ====================

    @Test
    void home_rendersModuleCards() {
        // 2026-10-09 改版：/ 由「项目列表」改为模块入口卡片墙，项目列表降级为 /projects
        ResponseEntity<String> response = restTemplate.getForEntity(url("/"), String.class);
        assertPageOk(response, "home (模块卡片首页)");
        assertThat(response.getBody())
                .as("首页应为静态 HTML，不再使用 Thymeleaf")
                .doesNotContain("xmlns:th")
                .doesNotContain("th:href")
                .as("首页应挂载共享导航栏与公共脚本")
                .contains("id=\"site-nav\"")
                .contains("/js/common.js")
                .contains("/js/nav.js")
                .as("首页应为五个模块的卡片墙（故事创作 / 教学 / 聊天 / 设置 / 资源共享）")
                .contains("故事创作")
                .contains(">教学</h5>")
                .contains(">聊天</h5>")
                .contains(">设置</h5>")
                .contains(">资源共享</h5>")
                .as("首页只做模块分发，本身不再渲染项目列表")
                .contains("href=\"/projects\"")
                .doesNotContain("id=\"projectContainer\"");
    }

    @Test
    void projectList_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/projects"), String.class);
        assertPageOk(response, "projects (项目列表二级页)");
        String body = response.getBody();
        assertThat(body)
                .as("项目列表页应挂载导航栏、Ajax 数据源与工具栏")
                .contains("id=\"site-nav\"")
                .contains("/js/common.js")
                .contains("/js/nav.js")
                .contains("/api/projects")
                .contains("id=\"projectContainer\"")
                .as("「导入项目」按钮应指向 TXT 导入页，而非备份导入 /import")
                .contains("href=\"/import/txt\"")
                .doesNotContain("href=\"/import\"")
                .as("语音导出从导航栏下线后收在页头按钮组（2026-10-09）")
                .contains("href=\"/tts-export\"");
        assertThat(body.indexOf("href=\"/tts-export\""))
                .as("语音导出按钮应排在【导入项目】的左侧")
                .isLessThan(body.indexOf("href=\"/import/txt\""));
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
    void learnBlockBlastPageIsServed() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/learn/block-blast"), String.class);
        assertPageOk(response, "learn-block-blast (俄罗斯方块)");
        assertThat(response.getBody())
                .as("从 ../BlockBlast/index.html 迁入后必须仍是自包含单页：不引任何外部资源")
                .doesNotContain("http://")
                .doesNotContain("https://")
                .doesNotContain("<script src=")
                .as("迁入时把原版的「退出游戏」换成了跳回教学模块首页的「返回」")
                .contains("menu-item-label\">返回<")
                .contains("window.location.href = '/learn'")
                .as("原来的 window.close() + alert 兜底必须去掉（浏览器会拦截并弹框）")
                .doesNotContain("请直接关闭浏览器标签页")
                .doesNotContain("electronAPI")
                .as("存档 key 保持原前缀，避免与同源其它模块串数据")
                .contains("blockblast_save");
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
        // 静态化后：阅读页通过异步 fetch 拉取 /api/projects/{id}/read-data，由 Alpine 渲染章节
        assertStaticPage(response, "reader", "readerApp()");
        assertThat(response.getBody())
                .as("阅读页应通过异步 fetch 拉取引导数据（不再是解析期同步 XHR）")
                .contains("/read-data")
                .contains("window.loadReaderData")
                .contains("await window.loadReaderData()")
                .as("正文还在路上时先出骨架屏，不能白屏、也不能先闪空态")
                .contains("booting: true")
                .contains("sc-skel")
                .as("骨架最后才收：数据铺完再置 booting=false，否则空书会冲掉展开状态")
                .contains("async boot()")
                .doesNotContain("xhr.open(")
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
                .contains("class=\"btn btn-sm w-100 bg-reset-btn\"")
                .as("章节标题旁不再挂「标记为扩写」按钮（扩写备选统一在 /projects/{id}/expansion 管理）")
                .doesNotContain("toggleExpansionMark")
                .doesNotContain("标记为扩写备选")
                .as("章节 TTS 是三态按钮组（播放/暂停/停止），不再是单个播放即停止的按钮")
                .contains("tts-chapter-actions")
                .contains("pauseChapter")
                .contains("resumeChapter")
                .contains("stopChapter")
                .doesNotContain("toggleChapter(")
                .as("一章播完等 3 秒再自动接下一章")
                .contains("onTtsChapterEnded")
                .contains("3000");
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
                .doesNotContain("workflowHubApp()")
                .as("引导数据改成异步：页面里不该再有同步 XHR，先出骨架等数据")
                .doesNotContain("xhr.open(")
                .contains("x-show=\"booting\"")
                .contains("/js/workflow/core.js");
    }

    /** 6 个按步骤拆分的工作流页面各自独立渲染，引导数据统一由 core.js 异步拉取。 */
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
                    .as("step page %s 应挂载对应 app，并由 core.js 异步拉取引导数据", route)
                    .contains(e.getValue())
                    .contains("/js/workflow/core.js")
                    .as("数据在路上先出骨架，页面里不该再有解析期同步 XHR")
                    .contains("x-show=\"booting\"")
                    .contains("sc-skel")
                    .doesNotContain("xhr.open(");
        }

        // 引导数据的异步装配在 core.js 里：一次性断言，避免 6 个页面重复
        ResponseEntity<String> core = restTemplate.getForEntity(url("/js/workflow/core.js"), String.class);
        assertThat(core.getStatusCode()).as("core.js 应可访问").isEqualTo(HttpStatus.OK);
        assertThat(core.getBody())
                .as("core.js 应暴露 loadWorkflowData，由 init() await 后 applyBootstrap")
                .contains("window.loadWorkflowData")
                .contains("applyBootstrap")
                .contains("booting: true")
                .as("共享 loader 必须把结果合并进同一个对象（const d 捕获的是引用）")
                .contains("Object.assign(window.__WORKFLOW_DATA__")
                .as("分步页与枢纽页的 URL 约定")
                .contains("/workflow/data")
                .doesNotContain("xhr.open(");
    }

    /**
     * 润色 / 精修页的「预览」换成与扩写页**同一套**前后对照面板：
     * 贴在各自章节行下方、两栏并排、句级差异高亮、左右同步滚动。
     * 三页共用 js/compare-panel.js + app.css 里的 .sc-cmp* 样式，页面里不该再有裸 split-view。
     */
    @Test
    void workflowPreviewPanels_shareComparePanelWithExpansion() {
        ResponseEntity<String> polish = restTemplate.getForEntity(
                url("/projects/" + projectId + "/workflow/polishing"), String.class);
        assertThat(polish.getBody())
                .as("润色页：预览面板贴在章节行下方，两栏并排 + 差异高亮 + 同步滚动")
                .contains("/js/compare-panel.js")
                .contains("togglePreview(")
                .contains("closePolishPreview(")
                .contains("sc-cmp-pane")
                .contains("syncPreviewScroll(")
                .contains("润色前后对照")
                .contains("差异高亮")
                .as("两个开关并排：差异高亮 + 同步滚动（同步靠 :data-anc 锚点句对齐）")
                .contains("同步滚动")
                .contains("sc-cmp-sync")
                .contains(":data-anc=\"op.k\"")
                .doesNotContain("split-view-container");

        ResponseEntity<String> proof = restTemplate.getForEntity(
                url("/projects/" + projectId + "/workflow/proofreading"), String.class);
        assertThat(proof.getBody())
                .as("精修页：同样换成共享面板，旧的 split-view 预览下线")
                .contains("/js/compare-panel.js")
                .contains("toggleProofreadPreview(")
                .contains("closeProofreadPreview(")
                .contains("sc-cmp-pane")
                .contains("syncPreviewScroll(")
                .contains("精修前后对照")
                .contains("同步滚动")
                .contains("sc-cmp-sync")
                .contains(":data-anc=\"op.k\"")
                .doesNotContain("split-view-container");

        ResponseEntity<String> shared = restTemplate.getForEntity(url("/js/compare-panel.js"), String.class);
        assertThat(shared.getStatusCode()).as("共享面板脚本应可直接访问").isEqualTo(HttpStatus.OK);
        assertThat(shared.getBody())
                .as("共享脚本要提供句级差异、增量文案与按内容对齐的同步滚动")
                .contains("preparePair")
                .contains("deltaText")
                .contains("syncScroll")
                .contains("sc-cmp-pane")
                .as("同步滚动按锚点句对齐，不是按百分比")
                .contains("measureAnchors")
                .contains("anchorTarget")
                .as("开关归一：面板上没有【同步滚动】输入框时按「开」处理")
                .contains("sc-cmp-sync");
    }

    /** 按步骤拆分的数据接口返回合法引导 JSON，且 currentStep 与路由一致。 */
    @Test
    void workflowStepData_returnsBootstrapJson() {        java.util.Map<String, String> expected = java.util.Map.of(
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
        // 静态化后：列表页通过异步 fetch 拉取 /side-stories/list-data，由 Alpine 渲染卡片
        assertStaticPage(response, "side-story-list", "sideStoryList()");
        assertThat(response.getBody())
                .as("番外列表页引导数据走异步 fetch：先出骨架卡，别先闪「暂无番外篇」")
                .contains("/side-stories/list-data")
                .contains("loadSideStoryListData")
                .contains("booting: true")
                .contains("sc-skel")
                .doesNotContain("xhr.open(");
    }

    @Test
    void sideStoryDetail_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/side-stories/" + sideStoryId), String.class);
        // 静态化后：详情页通过异步 fetch 拉取 /side-stories/{id}/data，由 Alpine 渲染
        assertStaticPage(response, "side-story", "sideStoryWorkflow()");
        assertThat(response.getBody())
                .as("番外详情页引导数据走异步 fetch：先出骨架，别整片白屏")
                .contains("/side-stories/")
                .contains("'/data'")
                .contains("loadSideStoryData")
                .contains("applyBootstrap")
                .contains("booting: true")
                .contains("sc-skel")
                .doesNotContain("xhr.open(");
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
        // 静态化后：拓展页通过 /expansion/data 拉引导数据，由 Alpine 渲染
        assertStaticPage(response, "expansion", "expansionPage()");
        assertThat(response.getBody())
                .as("情节拓展页引导数据走**异步** fetch（同步 XHR 会卡住首屏绘制）")
                .contains("/expansion/data")
                .contains("loadExpansionData")
                .contains("PROJECT_ID")
                .doesNotContain("xhr.open(")
                .as("数据在路上时先渲染骨架屏，别让用户先看到「队列为空」")
                .contains("booting")
                .contains("sc-skel")
                .as("骨架收得晚一点：等章节数据铺完再置 booting=false")
                .contains("applyBootstrap");
        assertThat(response.getBody())
                .as("扩写队列必须限高滚动（约 12 行）：队列标记几十章时，"
                        + "以前会把下面的「全部章节」推到几屏之外，想回队列点个「确认」得先滚过整条队列")
                .contains("exp-queue-scroll")
                .contains("--exp-queue-rows: 12")
                .contains("--exp-queue-maxvh")
                .contains("position: sticky")
                .contains("exp-queue-hint")
                // 提示条的阈值与 CSS 的行数必须对得上，否则「列表内可滚动」会误报/漏报
                .contains("queueVisibleRows");
        assertThat(response.getBody())
                .as("队列按分卷分组 + 可折叠：与下方「全部章节」同一套卷划分（queueVolumes 复用 "
                        + "groupedVolumes），卷头吸顶，折叠时连卷内的预览对比一起收起")
                .contains("queueVolumes")
                .contains("exp-queue-vol-head")
                .contains("toggleQueueVolume")
                .contains("collapseCompareInVolume")
                .contains("toggleAllQueueVolumes")
                .contains("isQueueVolumeExpanded");
        assertThat(response.getBody())
                .as("★ 有预览面板打开时队列必须换成 .exp-queue-flow（max-height:none）："
                        + "「队列小窗」和「面板内左右两栏」两个滚动容器套在一起时，"
                        + "在面板里滚到底会接力把队列滚走，右侧还并排两条滚动条，面板下半截也被裁掉")
                .contains("exp-queue-flow")
                .contains("anyCompareOpen")
                .contains("max-height: none");
        assertThat(response.getBody())
                .as("扩写指导：只读态限高 20 行（420px）内滚、编辑框默认 20 行，"
                        + "并给【展开】按内容自动撑高 —— 指导写长了会把下面的队列整块推走，"
                        + "但压到 8 行（原 10rem）又太窄，读一段几百字的指导得来回滚")
                .contains("exp-guidance-view")
                .contains("--exp-guidance-rows: 20")
                .contains("max-height: calc(var(--exp-guidance-rows, 20)")
                .contains("exp-guidance-edit")
                .contains("rows=\"20\"")
                .contains("toggleGuidanceExpand")
                .contains("syncGuidanceHeight");
        assertThat(response.getBody())
                .as("单章扩写完成后前端必须**自己**翻状态：后端每章落库后补发 chapter-saved，"
                        + "前端据此把该行从「扩写中」变「已扩写」并刷新字数。"
                        + "以前只靠整批 done 触发整页刷新，几十章要跑一小时，期间一直显示「扩写中」")
                .contains("chapter-saved")
                // 页面里是正则 /\[\[EXPAND:SAVED:(\d+)…/，字面量带转义，别断言带方括号的原串
                .contains("EXPAND:SAVED:")
                .contains("resyncChapters");
        assertThat(response.getBody())
                .as("轮询兜底：服务器计数与本地行对不上时要补拉章节状态 "
                        + "（原来直接把服务器计数写进徽标、行状态不动 ⇒「5 已扩写」和满屏「扩写中」并存）")
                .contains("applyChapterStates")
                .contains("localExpanded !== data.expandedCount");
        assertThat(response.getBody())
                .as("整批结束不再整页 reload（会把实时日志、勾选、展开的分卷和对照面板一起丢掉）")
                .doesNotContain("window.location.reload()");
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

    @Test
    void expansionQueue_offersCompareToggle() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/expansion"), String.class);
        assertStaticPage(response, "expansion", "expansionPage()");
        assertThat(response.getBody())
                .as("已扩写章节要有「预览」按钮，点开在该行下方并排展示扩写前后")
                .contains("toggleCompare(")
                .contains("/expansion/compare/")
                // 每章一组 tbody：主行 + 紧贴其下的预览行（允许同时展开多个面板）
                .contains("exp-chapter-group")
                .contains("exp-compare-row")
                // 面板与润色/精修页共用一套（.sc-cmp*，实现在 js/compare-panel.js）
                .contains("sc-cmp-pane")
                .contains("/js/compare-panel.js")
                // 左右两栏按「同一句话」同步滚动（不是按百分比）：:data-anc 就是锚点号
                .contains("syncCompareScroll(")
                .contains(":data-anc=\"op.k\"")
                .contains("扩写前（原文）")
                .contains("差异高亮")
                .as("差异高亮旁边还有【同步滚动】开关，两个都默认开")
                .contains("同步滚动")
                .contains("sc-cmp-sync")
                .contains("cmpSyncScroll")
                .as("按钮文案已从「对比」改成「预览」").contains("'收起' : '预览'");
    }

    @Test
    void expansionCompare_returnsBeforeAndAfterText() {
        ChapterEntity ch = chapterRepository.findByProjectIdAndChapterNumber(projectId, 2).orElseThrow();
        String before = "扩写前原文。只有两句。";
        String after = "扩写前原文。只有两句。补了一段环境描写。又补了一段心理活动。";
        ch.setContentBeforeExpansion(before);
        ch.setContent(after);
        ch.setWordCount(after.length());
        ch.setExpansionStatus("EXPANDED");
        chapterRepository.save(ch);

        ResponseEntity<String> resp = restTemplate.getForEntity(
                url("/projects/" + projectId + "/expansion/compare/2"), String.class);
        assertThat(resp.getStatusCode()).as("对比接口应 200").isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody())
                .as("对比接口要同时下发原文备份与扩写后正文，并给出两侧字数")
                .contains("\"hasBefore\":true")
                .contains("扩写前原文")
                .contains("补了一段环境描写")
                .contains("\"beforeWordCount\":")
                .contains("\"afterWordCount\":");

        // 没有备份（例如已确认）时不报错，用 hasBefore=false 让页面给提示
        ch = chapterRepository.findByProjectIdAndChapterNumber(projectId, 2).orElseThrow();
        ch.setContentBeforeExpansion(null);
        chapterRepository.save(ch);
        ResponseEntity<String> noBackup = restTemplate.getForEntity(
                url("/projects/" + projectId + "/expansion/compare/2"), String.class);
        assertThat(noBackup.getStatusCode()).as("无备份时仍应 200").isEqualTo(HttpStatus.OK);
        assertThat(noBackup.getBody()).as("无备份要以 hasBefore=false 标记").contains("\"hasBefore\":false");

        ResponseEntity<String> missing = restTemplate.getForEntity(
                url("/projects/" + projectId + "/expansion/compare/99"), String.class);
        assertThat(missing.getStatusCode()).as("未知章节对比应 404").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void expansion_guidanceCardOffersReuseAndSaveAsShared() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/projects/" + projectId + "/expansion"), String.class);
        assertStaticPage(response, "expansion", "expansionPage()");
        assertThat(response.getBody())
                .as("扩写指导应与其他创作页一致：配置 / 存为通用 / 复用指导")
                .contains("存为通用")
                .contains("复用指导")
                .contains("/settings/guidances/save-from-project")
                .contains("/settings/guidances/list-json")
                .contains("reuseGuidanceModal")
                .as("存为通用要落到指导库的「情节拓展」分类")
                .contains("EXPANSION");
    }

    @Test
    void guidanceLibrary_offersExpansionCategory() {
        ResponseEntity<String> data = restTemplate.getForEntity(url("/settings/guidances/data"), String.class);
        assertThat(data.getStatusCode()).as("指导库引导数据应 200").isEqualTo(HttpStatus.OK);
        assertThat(data.getBody())
                .as("指导库步骤选项应包含「情节拓展」，否则扩写指导无处归类")
                .contains("\"EXPANSION\"")
                .contains("情节拓展");
    }

    @Test
    void saveGuidanceFromProject_expansionCopiesExpansionGuidance() {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        String original = project.getExpansionGuidance();
        String marker = "__probe_expansion_guidance__";
        project.setExpansionGuidance(marker);
        projectRepository.save(project);

        Long createdId = null;
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("projectId", String.valueOf(projectId));
            form.add("step", "EXPANSION");
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    url("/settings/guidances/save-from-project"), form, String.class);
            assertThat(resp.getStatusCode()).as("扩写指导应能存为通用").isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).as("默认名应带「情节拓展」").contains("情节拓展");

            createdId = guidanceLibraryRepository.findByStepOrderByUpdatedAtDesc(GuidanceLibraryStep.EXPANSION)
                    .stream()
                    .filter(g -> marker.equals(g.getGuidance()))
                    .map(GuidanceLibraryEntity::getId)
                    .findFirst()
                    .orElse(null);
            assertThat(createdId).as("指导库应出现一条情节拓展分类的指导").isNotNull();

            ResponseEntity<String> list = restTemplate.getForEntity(
                    url("/settings/guidances/list-json?step=EXPANSION"), String.class);
            assertThat(list.getBody()).as("按 EXPANSION 过滤应能查到刚存的指导").contains(marker);
        } finally {
            if (createdId != null) {
                guidanceLibraryRepository.deleteById(createdId);
            }
            ProjectEntity restore = projectRepository.findById(projectId).orElseThrow();
            restore.setExpansionGuidance(original);
            projectRepository.save(restore);
        }
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

    /**
     * ★ 全站红线：引导数据的同步 XHR 已清零（2026-10-20 骨架化改造）。
     * 同步 XHR（xhr.open(url, false)）会把首屏卡死在 HTML 解析期：数据慢 = 白屏，
     * JS/DOM 全都动不了。新页面一律 fetch + 骨架，这里做反断言防回归。
     * 注意 learn-word-match 还有一个**异步** xhr.open(url, true)（CET 词库），
     * 正则只匹配 `, false)` 收尾，不会误伤。
     */
    private void assertNoSyncXhr(String body, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("open\\s*\\([^)]*,\\s*false\\s*\\)").matcher(body);
        assertThat(m.find())
                .as("%s 不应再包含同步 XHR（xhr.open(url, false)）；引导数据请走 fetch + 骨架", name)
                .isFalse();
    }

    /**
     * 引导数据异步化 + 骨架的三件套断言（静态页用）：
     * ① 空壳数据变量还在（数据到达前先有壳）；② loadXxxData 异步加载器在；
     * ③ sc-skel 骨架占位在；④ 没有同步 XHR。
     */
    private void assertAsyncBootstrap(ResponseEntity<String> response, String name,
                                      String dataVar, String loaderFn) {
        assertPageOk(response, name);
        String body = response.getBody();
        assertThat(body)
                .as("%s 应包含空壳引导数据变量 %s（数据到达前先有壳）", name, dataVar)
                .contains(dataVar);
        assertThat(body)
                .as("%s 应包含异步加载器 %s", name, loaderFn)
                .contains(loaderFn);
        assertThat(body)
                .as("%s 应包含骨架占位（sc-skel）：数据到达前不白屏、不闪空表单", name)
                .contains("sc-skel");
        assertNoSyncXhr(body, name);
    }

    /** Alpine 异步引导页变体：引导数据在共享 JS 里取（页面只有骨架与 booting 状态）。 */
    private void assertAsyncBootPage(ResponseEntity<String> response, String name) {
        assertPageOk(response, name);
        String body = response.getBody();
        assertThat(body)
                .as("%s 应为静态页（无 Thymeleaf 内联表达式痕迹）", name)
                .doesNotContain("xmlns:th")
                .doesNotContain("th:replace");
        assertThat(body)
                .as("%s 应有 booting 骨架状态：x-show=\"booting\" 遮住内容区", name)
                .contains("x-show=\"booting\"")
                .contains("sc-skel");
        assertNoSyncXhr(body, name);
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

    /**
     * 「所有灵感」页可以直接新建**无项目**灵感（project_id = 0）。
     *
     * <p>0 号是哨兵值：projects 表里根本没有这一行，所以项目存在性校验必须对它放行，
     * 否则新建 / 查看 / 编辑 / 删除整条链路都会 400。
     */
    @Test
    void inspiration_canBeCreatedWithoutProject() {
        String apiBase = url("/api/projects/" + InspirationApiController.NO_PROJECT_ID + "/inspirations");
        try {
            ResponseEntity<String> created = restTemplate.postForEntity(
                    apiBase, jsonEntity("无项目灵感：雨夜的灯塔", "先记下来，回头再归到某个项目。"), String.class);
            assertThat(created.getStatusCode().is2xxSuccessful()).as("不带项目的灵感应能创建").isTrue();
            assertThat(created.getBody()).as("返回体里的 projectId 必须是 0").contains("\"projectId\":0");
            Long id = extractId(created.getBody());

            // 详情与列表都要能读到 —— 这是 requireProject 对 0 号放行的证据
            assertThat(restTemplate.getForEntity(apiBase + "/" + id, String.class).getBody())
                    .contains("雨夜的灯塔");
            assertThat(restTemplate.getForEntity(apiBase, String.class).getBody()).contains("雨夜的灯塔");

            // 隔离：真实项目的灵感列表里绝不能出现它
            //（0 号是「没有项目」，不是「第 0 个项目」）
            assertThat(restTemplate.getForEntity(
                    url("/api/projects/" + projectId + "/inspirations"), String.class).getBody())
                    .as("无项目灵感不能串到真实项目里")
                    .doesNotContain("雨夜的灯塔");

            // 拿真实项目的 projectId 去访问这条 0 号灵感，同样要被拒
            assertThat(restTemplate.getForEntity(
                    url("/api/projects/" + projectId + "/inspirations/" + id), String.class).getStatusCode())
                    .as("跨项目访问 0 号灵感也必须被拒")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            transactionTemplate.executeWithoutResult(status ->
                    inspirationRepository.deleteByProjectId(InspirationApiController.NO_PROJECT_ID));
        }
    }

    /**
     * 汇总接口（「所有灵感」页的数据源）：无项目灵感要独立成一组，名字是「未归属项目」
     * （不能顺着项目表兜底成「项目 0」），并且**固定置顶** —— 那一组正是本页新建灵感的落点，
     * 刚记下来的东西必须一眼可见。
     */
    @Test
    void allInspirationsApi_putsProjectLessGroupFirstWithItsOwnName() {
        try {
            restTemplate.postForEntity(
                    url("/api/projects/" + InspirationApiController.NO_PROJECT_ID + "/inspirations"),
                    jsonEntity("无项目灵感：置顶检查", "x"), String.class);

            String body = restTemplate.getForEntity(url("/api/inspirations"), String.class).getBody();
            assertThat(body)
                    .as("无项目分组必须排在最前，标题是「未归属项目」而不是「项目 0」")
                    .startsWith("[{\"projectId\":0,\"projectTitle\":\"未归属项目\"");
            assertThat(body).contains("无项目灵感：置顶检查");
        } finally {
            transactionTemplate.executeWithoutResult(status ->
                    inspirationRepository.deleteByProjectId(InspirationApiController.NO_PROJECT_ID));
        }
    }

    /** 「所有灵感」页要能直接新建无项目灵感：入口 + 表单 + 0 号哨兵都得在静态页里。 */
    @Test
    void allInspirationsPage_offersCreateWithoutProject() {
        ResponseEntity<String> page = restTemplate.getForEntity(url("/inspirations"), String.class);
        assertStaticPage(page, "all-inspirations", "新建灵感");
        assertThat(page.getBody())
                .as("新建入口必须落在「无项目」哨兵上（SC.NO_PROJECT_ID），而不是某个具体项目")
                .contains("SC.NO_PROJECT_ID")
                .contains("id=\"toggle-add\"")
                .contains("id=\"save-new\"");
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
        assertThat(response.getBody())
                .as("提示词页引导数据走异步 fetch：先摆 3 行表格骨架，别先闪空表")
                .contains("loadPromptsData")
                .contains("sc-skel")
                .contains("data-booting")
                .doesNotContain("xhr.open(");
        assertThat(response.getBody())
                .as("流程筛选应有【扩写】按钮，且 CHAPTER_EXPANSION 的标签配色已登记")
                .contains("data-workflow=\"EXPANSION\"")
                .contains("name === 'EXPANSION'");
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
     * 回归：同一步骤存在**多条**默认模板时 /prompts/data 必须正常返回。
     * <p>真实库里就是这种情况 —— CHAPTER_WRITING 同时有「写作核心」和「章节扩写」两条默认。
     * 老实现按 step 用 {@code Optional} 查默认，碰上两条直接抛 {@code NonUniqueResultException}，
     * 整页 500；同样的查询还用在正文生成上，写作流程会跟着一起崩。
     */
    @Test
    void promptsData_toleratesMultipleDefaultsForSameStep() {
        PromptTemplateEntity mainDefault = new PromptTemplateEntity();
        mainDefault.setStep(WorkflowStep.CHAPTER_WRITING);
        mainDefault.setSubStep(PromptSubStep.CHAPTER_WRITING_PRIMARY);
        mainDefault.setName("__probe_writing_default__");
        mainDefault.setTemplate("probe 正文 {{content}}");
        mainDefault.setDefault(true);

        PromptTemplateEntity expansionDefault = new PromptTemplateEntity();
        expansionDefault.setStep(WorkflowStep.CHAPTER_WRITING);
        expansionDefault.setSubStep(PromptSubStep.CHAPTER_EXPANSION);
        expansionDefault.setName("__probe_expansion_default__");
        expansionDefault.setTemplate("probe 扩写 {{content}}");
        expansionDefault.setDefault(true);

        List<Long> ids = transactionTemplate.execute(st -> List.of(
                promptTemplateRepository.save(mainDefault).getId(),
                promptTemplateRepository.save(expansionDefault).getId()));
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url("/prompts/data"), String.class);
            assertThat(response.getStatusCode())
                    .as("同一步骤多条默认模板时 /prompts/data 仍应 200")
                    .isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .as("两条自定义默认都应出现在列表里")
                    .contains("__probe_writing_default__")
                    .contains("__probe_expansion_default__");
        } finally {
            transactionTemplate.executeWithoutResult(st -> ids.forEach(promptTemplateRepository::deleteById));
        }
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

    /**
     * 章节扩写（CHAPTER_EXPANSION）原先没有登记流程标签，
     * /prompts 页面的【流程】列显示为空 —— 它不属于 auto-run 标准流程，独享【扩写】标签。
     */
    @Test
    void promptsData_chapterExpansionCarriesExpansionWorkflowTag() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/prompts/data"), String.class);
        assertThat(response.getStatusCode()).as("/prompts/data 应返回 200").isEqualTo(HttpStatus.OK);

        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody());
        int expansionCount = 0;
        for (var t : root.get("templates")) {
            if (!"CHAPTER_EXPANSION".equals(t.path("subStep").asText(""))) continue;
            expansionCount++;
            assertThat(t.path("workflowTagNamesCsv").asText())
                    .as("章节扩写模板应带 EXPANSION 流程标签（否则【流程】列为空）")
                    .contains("EXPANSION");
        }
        assertThat(expansionCount).as("应存在章节扩写模板").isGreaterThan(0);

        assertThat(response.getBody())
                .as("/prompts/data 应下发【扩写】标签的展示名")
                .contains("\"displayName\":\"扩写\"");
    }

    @Test
    void promptExplore_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/prompts/explore"), String.class);
        assertStaticPage(response, "prompt-explore", "__PROMPT_EXPLORE_DATA__");
        assertAsyncBootstrap(response, "prompt-explore", "__PROMPT_EXPLORE_DATA__", "loadPromptExploreData");
        assertThat(response.getBody())
                .as("提示词探索页应通过异步接口拉取引导数据")
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
            assertAsyncBootstrap(page, "prompt-edit", "__PROMPT_EDIT_DATA__", "loadPromptEditData");

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
        assertAsyncBootstrap(page, "prompt-edit", "__PROMPT_EDIT_DATA__", "loadPromptEditData");

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
                // 导入页归属「故事创作」一级入口（导航栏已无独立的「导入项目」项，2026-10-09）
                .contains("data-nav=\"projects\"")
                .contains("action=\"/import\"")
                .as("备份导入页应提供跳转到 TXT 导入页的按钮")
                .contains("href=\"/import/txt\"")
                .contains("TXT导入");
    }

    @Test
    void navigationKeepsOnlyTopLevelEntries() {
        // 2026-10-09：导航栏精简为「一级入口」—— 首页 + 首页那三张模块卡（AI教学/AI聊天/故事创作）+ 设置下拉。
        // 语音导出与导入项目从导航栏下线，两者都收进 /projects 页头的按钮组（见 projectList_rendersSuccessfully）。
        // 「AI」前缀只在导航栏用，首页卡片标题仍是「教学」「聊天」（用户要求只改导航栏）。
        ResponseEntity<String> response = restTemplate.getForEntity(url("/js/nav.js"), String.class);
        assertThat(response.getStatusCode()).as("/js/nav.js 应可访问").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("导航栏应保留四个一级入口")
                .contains("text: '首页'")
                .contains("text: 'AI教学'")
                .contains("text: 'AI聊天'")
                .contains("text: '故事创作'")
                .as("旧文案已退役（「教学」「聊天」须带 AI 前缀）")
                .doesNotContain("text: '教学'")
                .doesNotContain("text: '聊天'")
                .as("二级入口必须从导航栏下线")
                .doesNotContain("text: '语音导出'")
                .doesNotContain("text: '导入项目'")
                .as("导航项不应再指向这两个二级页")
                .doesNotContain("href: '/tts-export'")
                .doesNotContain("href: '/import/txt'");

        // 顺序也钉住：首页 → AI教学 → AI聊天 → 故事创作（设置下拉由模板追加在最后）。
        String js = response.getBody();
        assertThat(js.indexOf("text: '首页'")).as("首页应是第一个导航项")
                .isGreaterThanOrEqualTo(0).isLessThan(js.indexOf("text: 'AI教学'"));
        assertThat(js.indexOf("text: 'AI教学'")).as("AI教学 应排在 AI聊天 之前")
                .isLessThan(js.indexOf("text: 'AI聊天'"));
        assertThat(js.indexOf("text: 'AI聊天'")).as("AI聊天 应排在 故事创作 之前")
                .isLessThan(js.indexOf("text: '故事创作'"));

        // 设置下拉末位的外链（GitHub 仓库）：必须 external 标记 + 新窗口 + noopener。
        assertThat(js)
                .as("设置下拉末位应挂 GitHub 仓库外链（新窗口打开、带 noopener）")
                .contains("text: 'Github项目'")
                .contains("href: 'https://github.com/renfufei/Story-Creator'")
                .contains("external: true")
                .contains("rel=\"noopener noreferrer\"");
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
            assertAsyncBootstrap(page, "guidance-edit", "__GUIDANCE_EDIT_DATA__", "loadGuidanceEditData");
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
            assertAsyncBootstrap(page, "guidance-edit", "__GUIDANCE_EDIT_DATA__", "loadGuidanceEditData");
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
            assertAsyncBootstrap(page, "material-edit", "__MATERIAL_EDIT_DATA__", "loadMaterialEditData");
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
        entity.setStep(GuidanceLibraryStep.of(step));
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
        assertAsyncBootstrap(response, "txt-import", "__TXT_IMPORT_DATA__", "loadTxtImportData");

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
        // 引导数据已改由 /js/reverse.js 的 loadReverseData() 异步取：页面只有骨架 + booting 状态
        assertAsyncBootPage(response, "reverse-options");
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
        // 引导数据已改由 /js/reverse.js 的 loadReverseData() 异步取：页面只有骨架 + booting 状态
        assertAsyncBootPage(response, "reverse-progress");

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
        // 引导数据异步化（原同步 XHR 已移除）：
        assertThat(body).as("reverse.js 应提供异步加载器 loadReverseData()").contains("loadReverseData");
        assertThat(body).as("reverse.js 应在引导失败时清空壳预置的 error（避免旧文案误报）")
                .contains("delete window.__REVERSE_DATA__.error");
        // ★ mixin 必须挂原型（Object.create）而不是 Object.assign 进字面量：
        //   assign 会把 getter（planHeadline / startButtonText / planSubline）求值一次并
        //   摊平成静态值，plan 刷新后按钮文案/提示永不更新（2026-10-20 探针抓到的真 bug）。
        assertThat(body).as("reverseOptions/reverseProgress 应以 Object.create 挂 mixin（getter 不能被 assign 摊平）")
                .contains("Object.create(reverseMixin())");
        assertThat(body).as("页面字面量里不应再残留 allCompleted getter（已 defineProperty 挂 base）")
                .doesNotContain("get allCompleted()");
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
        assertThat(response.getBody())
                .as("TTS 导出页引导数据走异步 fetch：任务表先出骨架行，别先闪「还没有导出任务」")
                .contains("loadTtsExportData")
                .contains("booting: true")
                .contains("sc-skel")
                .doesNotContain("xhr.open(");
    }

    /**
     * 任务详情面板的措辞与收起方式（2026-10-19 用户反馈）。
     *
     * <p>原来的三处文案都不达意：
     * <ul>
     *   <li>「关闭」听起来像关闭任务 / 关闭页面，实际只是收起这块详情 ⇒ 改为「折叠」；</li>
     *   <li>「编辑章节」与向导第 2 步「选择章节」其实是同一件事 ⇒ 统一口径；</li>
     *   <li>「进度」太泛，实际是 SSE 实时日志 ⇒ 改为「实时进度」，和表格里的进度条区分开。</li>
     * </ul>
     * 顺带钉住：行内那颗「详情」按钮展开后要变成「折叠」并高亮 —— 否则用户只能回面板里找收起入口，
     * 或者根本不知道再点一下会收起。
     */
    @Test
    void ttsExport_detailPanel_wordingAndToggle() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/tts-export"), String.class);
        assertThat(response.getStatusCode()).as("/tts-export 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("详情面板：三处文案与折叠/展开的切换状态")
                .contains("<i class=\"bi bi-chevron-up\"></i> 折叠")      // 面板里的收起按钮
                .contains("<i class=\"bi bi-terminal\"></i> 实时进度")    // 原来的「进度」
                .contains("<i class=\"bi bi-ui-checks\"></i> 选择章节")   // 原来的「编辑章节」
                .contains("expandedTaskId === task.id ? '折叠' : '详情'") // 行内按钮跟着状态换文案
                .contains("saveEditChapters(task.id)")                   // 保存动作仍在（改名不误删）
                .contains("保存选择")
                .doesNotContain("编辑章节")
                .doesNotContain("bi-x-lg\"></i> 关闭");
    }

    /**
     * 「使用 ffmpeg 压缩音频」旁的问号说明必须**真的可点击**。
     *
     * 曾经那里只有 data-bs-toggle 的 tooltip 写法，而全站没有一处 new bootstrap.Tooltip()
     * —— Bootstrap 5 不会按该 data 属性自动初始化 tooltip，于是点击毫无反应（用户反馈的 BUG）。
     * 现改为 Alpine 驱动的点击展开说明卡，这条断言把关键接线钉住，避免以后又退回去。
     */
    @Test
    void ttsExport_ffmpegHint_isClickable() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/tts-export"), String.class);
        assertThat(response.getStatusCode()).as("/tts-export 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("问号必须是可点击的展开触发器（不是死的 tooltip data 属性）")
                .contains("ffmpeg-hint-trigger")                    // 触发器
                .contains("ffmpeg-hint-pop")                        // 说明卡
                .contains("id=\"ffmpegHintPop\"")
                .contains("x-cloak")                                // 首屏不闪
                .contains("aria-expanded")                          // 无障碍状态
                .contains("@click=\"ffmpegHintOpen = !ffmpegHintOpen\"")
                .contains("@click.outside")                         // 点别处收起
                .contains("x-show=\"ffmpegHintOpen\"")
                .contains("ffmpegHintOpen: false")                  // 状态必须声明，否则 Alpine 驱动不了切换
                .contains("仅 MP3 格式生效")                          // 口径：只有 mp3 会走 ffmpeg 压缩
                .doesNotContain("data-bs-toggle=\"tooltip\"");       // 全站无 Tooltip 初始化，该属性是死写法
    }

    /**
     * ffmpeg 可用性必须**每次打开页面都真探测一次**，不能沿用服务启动时那份结论。
     *
     * 曾经的 BUG：探测结果被永久缓存，用户 brew install ffmpeg 之后页面一直显示「不可用」，
     * 只能重启后台服务才能恢复。现在探测入口抽成 refreshFfmpegStatus()（init 时调一次），
     * 并在说明卡里提供「重新检测」按钮，装好后不刷新页面也能立刻用上。
     */
    @Test
    void ttsExport_rechecksFfmpegOnEveryPageLoad() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/tts-export"), String.class);
        assertThat(response.getStatusCode()).as("/tts-export 应返回 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("页面必须提供可重复触发的 ffmpeg 重新探测入口")
                .contains("async refreshFfmpegStatus()")             // 统一入口（init 与「重新检测」共用）
                .contains("await this.refreshFfmpegStatus()")        // 打开页面就探测一次
                .contains("@click=\"refreshFfmpegStatus()\"")        // 说明卡里的「重新检测」按钮
                .contains("ffmpegChecking")                          // 探测中标志（防连点、给用户反馈）
                .contains("重新检测")
                .contains("bi-arrow-clockwise")                      // 按钮图标
                .doesNotContain("// Check ffmpeg");                  // 旧的 init 内联探测写法不得回潮
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
        assertAsyncBootstrap(response, "tts-templates", "__TTS_TEMPLATES_DATA__", "loadTtsTemplatesData");
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
        assertAsyncBootstrap(page, "tts-template-view", "__TTS_TEMPLATE_VIEW_DATA__", "loadTtsTemplateViewData");

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
            assertAsyncBootstrap(page, "tts-template-edit", "__TTS_TEMPLATE_EDIT_DATA__", "loadTtsTemplateEditData");

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
        assertAsyncBootstrap(page, "tts-template-bindings", "__TTS_BINDINGS_DATA__", "loadTtsBindingsData");

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
    void chatBootstrap_isAsyncAndShowsSkeleton() {
        ResponseEntity<String> page = restTemplate.getForEntity(url("/chat"), String.class);
        String body = page.getBody();
        assertThat(body)
                .as("引导数据不能再用同步 XHR（会阻塞解析与首屏，且已被浏览器弃用）")
                .doesNotContain("xhr.open('GET', '/chat/data', false)")
                .doesNotContain("XMLHttpRequest")
                .contains("window.loadChatData")
                .contains("booting: true")
                .contains("正在加载会话");
        assertThat(body)
                .as("异步化之后必须有骨架屏：否则数据没回来时用户看到空白会话列表")
                .contains("加载会话…");
    }

    @Test
    void chatMessages_haveSelectAndCopyMenu() {
        ResponseEntity<String> page = restTemplate.getForEntity(url("/chat"), String.class);
        assertThat(page.getBody())
                .as("每条消息（用户输入和模型返回）旁边都要有「⋯」：点开是【全选】【复制】")
                .contains("msg-line")
                .contains("msg-more")
                .contains("msg-menu")
                .contains("data-msg-key")
                .contains("openMsgMenu")
                .contains("selectMsg")
                .contains("copyMsg")
                .contains("全选")
                .contains("复制");
        assertThat(page.getBody())
                .as("菜单必须 fixed 定位：消息区是 overflow:auto，absolute 的浮层会被裁掉")
                .contains(".msg-menu {")
                .contains("min-width: 7.5rem")
                .contains("position: fixed;");
        assertThat(page.getBody())
                .as("点另一个图标的 @click.stop 不能丢：否则 Alpine 的 .outside 会把刚打开的菜单立刻关掉")
                .contains("@click.stop=\"openMsgMenu(msg, $event)\"")
                .contains("@click.outside=\"closeMsgMenu()\"");
        assertThat(page.getBody())
                .as("剪贴板要有退路：非安全上下文（http 内网 IP）没有 navigator.clipboard")
                .contains("navigator.clipboard")
                .contains("legacyCopy")
                .contains("execCommand");
    }

    @Test
    void chatLayout_hasMobileDrawer() {
        ResponseEntity<String> page = restTemplate.getForEntity(url("/chat"), String.class);
        assertThat(page.getBody())
                .as("窄屏会话列表要做成抽屉：原来固定 col-3，390px 屏只分到 97px，标题全被截断")
                .contains("chat-sidebar")
                .contains("chat-sidebar-open")
                .contains("chat-mobile-bar")
                .contains("chat-shell")
                .contains("sidebarOpen");
    }

    @Test
    void chatStream_sendsRenderingIsThrottledAndScrollIsPolite() {
        ResponseEntity<String> page = restTemplate.getForEntity(url("/chat"), String.class);
        assertThat(page.getBody())
                .as("流式渲染要按帧合并（否则每个 token 一次重渲染 + 一次滚动），滚动要「贴底才跟随」")
                .contains("scheduleFlush")
                .contains("flushTokens")
                .contains("autoScroll")
                .contains("_pendingTokens")
                // 收尾必须 flush，否则缓冲会把尾巴吃掉
                .contains("self.flushTokens();")
                .doesNotContain("self.streamingContent += data;");
    }

    @Test
    void chatStream_usesPostBodyNotQueryParams() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/chat"), String.class);
        assertThat(response.getBody())
                .as("聊天流式请求必须是 POST + JSON body：原文不能再进 URL（8KB 请求头上限 / 浏览器历史 / 访问日志 / Referer）")
                .contains("method: 'POST'")
                .contains("JSON.stringify({ configId: self.selectedConfigId, input: input })")
                .doesNotContain("/stream?configId=")
                .doesNotContain("encodeURIComponent(input)");
    }

    @Test
    void chatStream_getMethodIsGone() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/api/chat/sessions/1/stream?configId=1&input=hi"), String.class);
        assertThat(response.getStatusCode())
                .as("旧的 GET 流式接口应已下线（只剩 POST，GET 得 405）")
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void chatStream_rejectsBlankInput() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/chat/sessions/1/stream"),
                new HttpEntity<>("{\"configId\":1,\"input\":\"  \"}", headers),
                String.class);
        assertThat(response.getStatusCode())
                .as("空 input 应当场 400，不开流也不落库")
                .isEqualTo(HttpStatus.BAD_REQUEST);
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
        assertStaticPage(response, "learn", "乘法口诀");
        assertThat(response.getBody())
                .as("教学首页应含乘法学习、音频设置、英语单词匹配与俄罗斯方块入口")
                .contains("/learn/multiplication")
                .contains("/learn/multiplication/settings")
                .contains("英语单词匹配")
                .contains("/learn/word-match")
                .contains("/learn/block-blast")
                // 模块卡共用一套规格（见 app.css 第 9 节）：同款图标色块、同款底部动作区。
                // 曾经是三种按钮色（btn-primary/success/warning）+ 一堆行内 display-1，故正反两面都钉住。
                // 2026-10-18 加入第四张卡【成语填空】后仍是这一套，断言不用动。
                .contains("sc-learn-card")
                .contains("sc-learn-icon")
                .contains("sc-learn-actions")
                .contains("sc-learn-extra")
                .doesNotContain("btn-primary")
                .doesNotContain("btn-success")
                .doesNotContain("btn-warning")
                .doesNotContain("display-1")
                // 俄罗斯方块那张卡的图标是内联 SVG（多色方块），不是 bootstrap-icons，
                // 也不能引外部图标库 —— 详见 scripts/gen_block_blast_icon.py
                .contains("wm-bb-icon")
                .doesNotContain("cdn.")
                .doesNotContain("unpkg")
                .doesNotContain("jsdelivr");
    }

    @Test
    void learnMultiplication_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/learn/multiplication"), String.class);
        assertStaticPage(response, "learn-multiplication", "multiplicationApp()");
        assertThat(response.getBody())
                .as("乘法页应含口诀数据与音频取值逻辑")
                .contains("九九八十一")
                // 大乘法口诀：九九（45 句）之外的第二种表（81 句 = 9 组 × 9 句）。
                // 它由 buildBigTable 从九九表算出来 —— 口诀文字与音频 key 全部复用，
                // 所以服务端 54 条音频（45 口诀 + 9 组名前缀）一条都不用加。
                // 逐句内容 / 组号 / 前缀音频 key 由 scripts/probe_multiplication.mjs 在真浏览器里核。
                .contains("大九九乘法口诀")
                .contains("buildBigTable")
                .contains("FORMULAS_BIG")
                .contains("multiplication_table_mode")
                .contains("/api/learn/multiplication/audio/")
                // 没有已生成音频时降级到浏览器本地朗读（Web Speech API）读汉字口诀，
                // 读不出来就只当没声音 —— 降级链与「不许报错」由 scripts/probe_multiplication.mjs 逐段验
                .contains("speakText")
                .contains("speechSynthesis")
                .contains("textByKey")
                .contains("playServerAudio")
                // 格子配色：一句一色、按句轮换（一格取「最早点亮它的那一句」的颜色，形成色带）。
                // 反断言是关键 —— 退回成整片都用 currentColor 涂色就又变回"一片同色"了。
                // 逐格颜色由 scripts/probe_multiplication.mjs 在真浏览器里按行量。
                .contains("cellStyle(row, col)")
                .contains("cellColorAt")
                .doesNotContain("'background-color:' + currentColor")
                // 控制条：设置段（口诀表 / 高亮，柔和选中）+ 主控段（常驻五键）。
                .contains("mul-set")
                .contains("mul-ctl")
                .contains("mul-transport")
                .contains("mul-play")
                // 序号升成「第 N / M 句」，进度走卡片底边那条 3px 细线（绝对定位，不占垂直空间）
                .contains("mul-progress")
                .contains("progressPercent")
                // 播放器三键：停着点【下一句】只前进一句并朗读，播放中点【下一句】只挪下标。
                .contains("togglePlay")
                .contains("playFromCurrent")
                .contains("stepPrev")
                .contains("stepNext")
                .contains("replayCurrent")
                // 连播的中断手段：stop() 递增令牌，正在跑的那条链自己退出
                .contains("playToken")
                // 交互改造的反断言：「自动 / 手动」档位必须**不在**了 ——
                // 旧实现按住档位把整簇按钮换掉，想暂停得先确认自己在哪一档。
                // 注意 multiplication_table_mode 不含这个子串，反断言不会误伤口诀表开关。
                .doesNotContain("multiplication_mode")
                .doesNotContain("startAuto")
                .doesNotContain("console.error")
                .doesNotContain("alert(");
    }

    @Test
    void learnWordMatch_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/learn/word-match"), String.class);
        assertStaticPage(response, "learn-word-match", "wordMatchApp()");
        assertAsyncBootstrap(response, "learn-word-match", "__WORD_MATCH_DATA__", "loadWordMatchData");
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
                .as("类别标签应可点开「本册该类别全部单词与关卡」面板，并能进入「只练这些关」的分类模式："
                        + "作用域只收窄可走的关卡，不改写本册关卡数组（levelIndex 仍是本册真实下标，"
                        + "进度 / 错题本 / 关号口径都不变）")
                .contains("openTheme()")
                .contains("themeInfo")
                .contains("wm-theme-modal")
                .contains("练习本分类")
                .contains("startThemePractice")
                .contains("themeScope")
                .contains("wm-scope-bar")
                .contains("atScopeEnd")
                .contains("nextIndexOf")
                .contains("结束分类")
                .as("分类练习里【自动学习】的起点必须是该分类第一关，按钮提示也得跟着改口 —— "
                        + "否则按钮写着「从第 1 关开始」，点下去却从别处开始（文案与行为不一致）")
                .contains("autoStartTitle");
        assertThat(response.getBody())
                .as("序列配对册（有序词群：数字 / 序数 / 星期 / 月份 / 季节 / 时间）"
                        + "的发牌必须**左列英文保持词库顺序、只打乱中文** —— 这类词的价值就在按序列背，"
                        + "左列一乱练的就不是同一个东西了；锁序只对 stage=专题 生效，别误伤普通册")
                .contains("TOPIC_STAGE")
                .contains("wm-home-entries")
                .contains("topicThemes")
                .contains("enterTopicBook")
                .contains("stageOf(this.bookId) === TOPIC_STAGE")
                .contains("lockLeft")
                .contains("英文按顺序 · 中文打乱");
        assertThat(response.getBody())
                .as("首页底部的两个练习入口（与小程序 pages/index 页尾对齐）：趣味配对 + 序列配对，"
                        + "各占一行、右侧一个【进入】，点一次直接进棋盘 —— 起点是该册第一个没通关的关，"
                        + "全通关则从第 1 关重来；别再展开专题 chips 做二次选择（那会把检索页拉长半屏）")
                .contains("wm-home-entry")
                .contains("wm-fun-entry")
                .contains("wm-topic-entry")
                .contains("wm-home-entry-emoji")   // 趣味配对图标 = 小程序同款 🎯
                .contains("🎯")
                .contains("enterFunPlay")
                .contains("enterTopicBook")
                .contains("funSummary")
                .contains("topicSummary")
                .doesNotContain("wm-home-chip")
                .doesNotContain("topicOpen");
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
                .as("大学是**独立词源**（2 册 / 2203 关 / 13159 条）：首屏只带它的册元信息（extraBooks），"
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
        assertThat(response.getBody())
                .as("默认落地在**检索首页**（与小程序 pages/index 同一形态：先查词、查到再进玩法页），"
                        + "页头下拉停在占位文案【选择关卡练习】上 —— 首次访问刻意**不预设册次**，"
                        + "否则占位文案永远不出现，那个按钮也就没人看得出是干什么的")
                .contains("wm-home")
                .contains("homeStatText")
                .contains("wm-home-drop")
                .contains("wm-home-result")
                .contains("wm-home-guide")
                .contains("homeDropOpen")
                .contains("HOME_DEBOUNCE")
                .contains("onHomeInput")
                .contains("queryGo")
                .contains("enterPlay")
                .contains("选择关卡练习")
                .contains("wm-back-btn")
                .contains("goSearch()");
        assertThat(response.getBody())
                .as("棋盘用 x-if 而不是 x-show 挂在 view 上：未渲染时 x-show 对 template 无效；"
                        + "棋盘专属按钮（返回搜索 / 自动学习 / 暂停 / 搜索）也必须一并挂 view === 'play'，"
                        + "否则检索首页上会浮出一排点不通的按钮。暂停是 autoMode 的三态之一，"
                        + "条件得写成 `view === 'play' && autoMode` 一个表达式 —— "
                        + "同一元素上叠两个 x-show 只有后一个生效")
                .contains("levels.length && view === 'play'")
                .contains("view === 'play' && autoMode");
        assertThat(response.getBody())
                .as("导出件例外：单页 HTML 是「发出去双击就能玩」的东西，落在检索页反而多一步，"
                        + "所以视图初值由 __WM_STANDALONE__ 三分 —— 导出件直接进棋盘")
                .contains("window.__WM_STANDALONE__) ? 'play' : 'search'");
    }

    @Test
    void learnIdiomFill_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/learn/idiom-fill"), String.class);
        assertStaticPage(response, "learn-idiom-fill", "idiomApp()");
        assertAsyncBootstrap(response, "learn-idiom-fill", "__IDIOM_FILL_DATA__", "loadIdiomFillData");
        assertThat(response.getBody())
                .as("成语填空页应含引导数据钩子与三学段入口")
                .contains("__IDIOM_FILL_DATA__")
                .contains("/learn/idiom-fill/data")
                .contains("idiom_fill_v1")
                .contains("ic-stage")
                .contains("ic-board")
                .contains("ic-item")
                .contains("ic-tile");
        assertThat(response.getBody())
                .as("★ 同一关拆出来的字必须两两不同：选位不能用「先到先得的贪心」，"
                        + "否则前面几条把字用光后会出现两个一样的字块，玩家无从判断该填哪格。"
                        + "页面里必须留下增广路匹配的实现")
                .contains("assignDistinctPositions")
                .contains("augment(")
                .contains("Object.create(null)");
        assertThat(response.getBody())
                .as("缺口位置与成语顺序要「每次进关重新随机」：刷新一次就换一个字缺、换一种排列"
                        + "（2026-10-18 用户反馈：种子写死后每次刷新缺的字和顺序都一模一样）")
                .contains("this.shuffle(lv.items, Math.random)")
                .contains("assignDistinctPositions(charLists, Math.random)")
                .doesNotContain("mulberry32")
                .doesNotContain("hashStr");
        assertThat(response.getBody())
                .as("答题反馈：答对变绿 + 显示释义 + 朗读；答错抖动 + 记失误")
                .contains("is-done")
                .contains("is-wrong")
                .contains("showTip")
                .contains("showFlash")
                .contains("mistakes")
                .contains("speak(it.idiom)");
        assertThat(response.getBody())
                .as("朗读失败不能阻塞玩法：没有 speechSynthesis / 没有中文音色时静默返回")
                .contains("window.speechSynthesis")
                .contains("u.onerror");
        assertThat(response.getBody())
                .as("完成页要给出本关成语与释义（学的落点），并可直接进入下一关 / 下一学段")
                .contains("本关完成")
                .contains("ic-done-list")
                .contains("nextLevel()")
                .contains("下一学段");
    }

    @Test
    void learnIndex_linksIdiomFill() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/learn"), String.class);
        assertThat(response.getBody())
                .as("教学首页应有成语填空卡（六卡统一 col-12 col-sm-6 col-xl-4，"
                        + "不能留 col-md-4，否则 768~991 会提前变三列、文字挤成四行）")
                .contains("/learn/idiom-fill")
                .contains("成语填空")
                .contains("data-accent=\"amber\"")
                // 只看真实的 class 属性：注释里提了一句「别改回 col-md-4」，
                // 直接 doesNotContain("col-md-4") 会把那句注释也判成违例
                .doesNotContain("class=\"col-md-4\"");
    }

    // ==================== 数独 ====================

    @Test
    void learnSudoku_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/learn/sudoku"), String.class);
        assertStaticPage(response, "learn-sudoku", "window.__sudoku");
        assertNoSyncXhr(response.getBody(), "learn-sudoku");

        assertThat(response.getBody())
                .as("数独页应自包含：棋盘 / 数字键盘 / 工具条 三件套，且没有任何 /data 依赖"
                        + "（题目现场生成，不该多一次白屏等待）")
                .contains("id=\"sdBoard\"")
                .contains("id=\"sdPad\"")
                .contains("id=\"sdTools\"")
                .contains("sd-cell")
                .contains("sd-key")
                .contains("sudoku_save_v2")
                .doesNotContain("/learn/sudoku/data");

        assertThat(response.getBody())
                .as("★ 唯一解是硬约束：挖空时必须「数到 2 个解才停」，"
                        + "只判断「有没有解」会发出多解的题 —— 玩家填出另一套合法答案却判错")
                .contains("countSolutions")
                .contains("countSolutions(puzzle, 2, deadline)")
                .contains("fill: function (grid, deadline)");

        assertThat(response.getBody())
                .as("尺寸与规则：3/5/7 是素数、切不出等宽宫格 ⇒ 只有拉丁方规则；"
                        + "4/6/8/9 才有宫（6 是 2 行 3 列、8 是 2 行 4 列，不是方宫）")
                .contains("var BOX = { 4: [2, 2], 6: [2, 3], 8: [2, 4], 9: [3, 3] }")
                .contains("拉丁方")
                .contains("MINI_GIVENS");

        assertThat(response.getBody())
                .as("移动端键盘固定贴底（fixed 而不是 sticky：sticky 会停在正文中间），"
                        + "正文要留出等高的 padding-bottom，否则最后一行被键盘盖住")
                .contains("env(safe-area-inset-bottom")
                .contains("padding-bottom: 8.4rem");

        assertThat(response.getBody())
                .as("尺寸选择是下拉面板（2026-10-21 从 7 个平铺 chip 改过来）："
                        + "常玩只露 3×3 / 6×6，其余折叠进【更多尺寸】，"
                        + "按钮上写的就是上次选的尺寸，最近用过的排在面板最前")
                .contains("id=\"sdSizeBtn\"")
                .contains("id=\"sdSizePop\"")
                .contains("id=\"sdCommon\"")
                .contains("id=\"sdMoreList\"")
                .contains("var COMMON_N = [3, 6]")
                .contains("recent: [], teachSeen: false")
                .contains("function recentList()")
                // 旧的平铺 chip 必须彻底消失，否则下拉与平铺会同时出现
                .doesNotContain("sd-chip");

        assertThat(response.getBody())
                .as("教学关卡：固定 4×4 唯一解题目 + 6 步推演（数空格 → 列排除 → 换宫看），"
                        + "教学盘独立于主棋盘；教学期间主局计时必须暂停，"
                        + "否则玩家在看讲解也在被计时")
                .contains("id=\"sdTeach\"")
                .contains("id=\"sdTeachBoard\"")
                .contains("var TEACH_PUZZLE = '1.3.' + '...2' + '.1..' + '.32.'")
                .contains("var TEACH_STEPS = [")
                .contains("数空格")
                .contains("state.paused = true")
                .contains("!state.running || state.paused || document.hidden || state.solved")
                // 写死的教学题也要现场验一遍唯一解，不能自相矛盾
                .contains("教学题不是唯一解");

        assertThat(response.getBody())
                .as("提示次数按「空格数 × 难度系数」分配（2026-10-21 从固定无限次改过来）："
                        + "格子越多、难度越高给得越多；小盘按尺寸保底（3×3 按比例算是 0 次），"
                        + "上限卡在空格数的一半，否则提示多到能把整盘填完")
                .contains("var HINT_RATE = { easy: 0.09, normal: 0.14, hard: 0.17 }")
                .contains("var HINT_FLOOR = { 3: 1, 4: 1, 5: 2, 6: 2, 7: 2, 8: 3, 9: 3 }")
                .contains("function hintQuota(n, diffKey, givens)")
                .contains("state.hintTotal = hintQuota(")
                .contains("本局 ' + state.hintTotal + ' 次提示已用完")
                .contains("id=\"sdHintBadge\"")
                // 额度不进存档，读档时按题目重算 —— 旧存档没有这个字段，
                // 而且旧版本的提示是无上限的，可能已经超过新额度
                .contains("state.hints = Math.min(state.hintTotal");
    }

    @Test
    void learnIndex_linksSudoku() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/learn"), String.class);
        assertThat(response.getBody())
                .as("教学首页应有数独卡：第 7 张，强调色 indigo，图标不能用乘法口诀那张同款")
                .contains("/learn/sudoku")
                .contains("数独")
                .contains("data-accent=\"indigo\"")
                .contains("bi-grid-3x3\"");
    }

    // ==================== 近义词 / 反义词匹配 ====================

    @Test
    void learnSynonymMatch_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/learn/synonym-match"), String.class);
        assertStaticPage(response, "learn-word-relation", "relationApp()");
        assertAsyncBootstrap(response, "learn-word-relation", "__RELATION_DATA__", "loadRelationData");
        assertThat(response.getBody())
                .as("近义词匹配页应含引导数据钩子、词库端点与三学段骨架")
                .contains("__RELATION_DATA__")
                .contains("/learn/word-relation/data")
                .contains("word_relation_v1_")
                .contains("wr-stage")
                .contains("wr-board")
                .contains("wr-item")
                .contains("wr-tile");
        assertThat(response.getBody())
                .as("模式由路径判断：两个路由 forward 到同一张静态页，地址栏里没有 ?mode=")
                .contains("/\\/learn\\/antonym-match/")
                .contains("mode-antonym");
        assertThat(response.getBody())
                .as("右列候选顺序要「每次进关重新随机」：刷新一次位置就换一遍，别让人背位置"
                        + "（2026-10-18 从固定种子改过来）")
                .contains("this.tiles = this.shuffle(tileList)")
                .doesNotContain("mulberry32");
        assertThat(response.getBody())
                .as("配对反馈：左边就地补出答案与辨析、答错抖动、完成页列出全部辨析")
                .contains("showTip")
                .contains("showFlash")
                .contains("wr-done-list")
                .contains("nextLevel()")
                .contains("下一学段");
        assertThat(response.getBody())
                .as("朗读失败不能阻塞玩法")
                .contains("window.speechSynthesis")
                .contains("u.onerror");
    }

    @Test
    void learnAntonymMatch_usesSamePageWithOwnAccent() {
        ResponseEntity<String> page = restTemplate.getForEntity(
                url("/learn/antonym-match"), String.class);
        assertStaticPage(page, "learn-word-relation", "relationApp()");
        assertAsyncBootstrap(page, "learn-word-relation", "__RELATION_DATA__", "loadRelationData");
        assertThat(page.getBody())
                .as("反义词用玫红强调色（--wr-accent 在 .mode-antonym 下被覆盖）")
                .contains(".wr-root.mode-antonym")
                .contains("#e11d48");
    }

    @Test
    void learnPages_shareSettingsPanelWithExportAndGithub() {
        // 三个新页面统一挂同一套「设置」弹层：错题本 / 重做本关 / 朗读 / 导出 / 源码 / 重置进度。
        // 逻辑与样式共用（js/learn-tools.js + app.css 的 .lt-* 段），结构各页一份静态标记。
        for (String path : List.of("/learn/idiom-fill", "/learn/synonym-match", "/learn/antonym-match")) {
            String body = restTemplate.getForEntity(url(path), String.class).getBody();
            assertThat(body)
                    .as("%s 应有设置弹层骨架", path)
                    .contains("class=\"lt-set-wrap\"")
                    .contains("lt-set-btn")
                    .contains("lt-set-pop")
                    .contains("/js/learn-tools.js")
                    .contains("runExport()")
                    .contains("exportOptions:")
                    .contains("wrongCount")
                    .contains("lt-wrong-list");
            assertThat(body)
                    .as("%s 的设置里应有错题本 / 导出 / 源码 / 重置进度 四个条目", path)
                    .contains("错题本")
                    .contains("导出")
                    .contains("源码")
                    .contains("重置进度");
            assertThat(body)
                    .as("%s 的源码应指向 GitHub 项目仓库（用 <a> 而不是按钮，"
                            + "中键/右键新标签页等原生行为才照常可用）", path)
                    .contains(":href=\"githubUrl\"")
                    .contains("target=\"_blank\"")
                    .contains("github.com/renfufei/Story-Creator");
        }
    }

    @Test
    void learnToolsScript_isServedAndExportsStandalonePage() {
        ResponseEntity<String> js = restTemplate.getForEntity(url("/js/learn-tools.js"), String.class);
        assertThat(js.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(js.getBody())
                .as("导出逻辑只有一份：取回本页 → 内联 CSS/JS/图标字体 → 词库就地写死")
                .contains("exportPage")
                .contains("__LEARN_STANDALONE__")
                .contains("data-standalone")
                .contains("bootstrap-icons")
                .contains("wrongBook")
                // 自检必须在：产物里只要还残留外链资源就报错，避免导出一个断网打不开的壳
                .contains("导出后仍有外链脚本")
                .contains("导出后仍有外链样式");
    }

    @Test
    void learnWordRelationData_servesBothModes() {
        ResponseEntity<String> synonym = restTemplate.getForEntity(
                url("/learn/word-relation/data?mode=synonym"), String.class);
        assertThat(synonym.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(synonym.getBody())
                .as("近义词词库：小学 / 初中 / 高中，含辨析")
                .contains("\"mode\":\"synonym\"")
                .contains("\"label\":\"近义词\"")
                .contains("小学")
                .contains("初中")
                .contains("高中")
                .contains("\"word\":\"高兴\"")
                .contains("\"match\":\"快乐\"")
                .as("语义组只在服务端校验用，不下发前端")
                .doesNotContain("\"group\"");

        ResponseEntity<String> antonym = restTemplate.getForEntity(
                url("/learn/word-relation/data?mode=antonym"), String.class);
        assertThat(antonym.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(antonym.getBody())
                .as("反义词词库是另一份数据，不能和近义词混在一起")
                .contains("\"mode\":\"antonym\"")
                .contains("\"label\":\"反义词\"")
                .contains("\"word\":\"大\"")
                .contains("\"match\":\"小\"")
                .doesNotContain("\"match\":\"快乐\"");

        // 缺省模式 = 近义词（页面漏带参数时不会 500）
        ResponseEntity<String> fallback = restTemplate.getForEntity(
                url("/learn/word-relation/data"), String.class);
        assertThat(fallback.getBody()).contains("\"mode\":\"synonym\"");
    }

    @Test
    void learnWordRelationData_rejectsUnknownMode() {
        ResponseEntity<String> bad = restTemplate.getForEntity(
                url("/learn/word-relation/data?mode=whatever"), String.class);
        assertThat(bad.getStatusCode())
                .as("未知模式必须 400，而不是静默退回近义词（否则反义词页面会拿到近义词库）")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void learnIndex_linksRelationMatches() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/learn"), String.class);
        assertThat(response.getBody())
                .as("教学首页应有近义词 / 反义词两张卡，且六卡统一 col-12 col-sm-6 col-xl-4")
                .contains("/learn/synonym-match")
                .contains("/learn/antonym-match")
                .contains("近义词匹配")
                .contains("反义词匹配")
                .contains("data-accent=\"teal\"")
                .contains("data-accent=\"rose\"")
                .contains("col-12 col-sm-6 col-xl-4")
                .doesNotContain("class=\"col-md-4\"")
                .doesNotContain("class=\"col-12 col-sm-6 col-xl-3\"");
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
        /* 2026-10-16：自动学习「听读音」这一步改成先念英文、再念中文释义。
           逐条规则（剥词性缩写 / 省略号 / 「J.K.罗琳」别被啃）与「朗读失败不阻塞」
           由 scripts/probe_wm_auto_zh.mjs 在真浏览器里核；这里只把结构钉进服务端吐出的页面。 */
        assertThat(response.getBody())
                .as("自动学习要连读英文 + 中文释义：清洗、成链、失败兜底一个都不能少")
                .contains("zhSpeechText")              // 中文释义 -> 朗读文本（剥词性缩写）
                .contains("autoReadItems")             // 一个单词要念哪几条
                .contains("speakAllThenWait")          // 按顺序念完，onend/onerror/超时兜底
                .contains("speakOne")                  // 单条永不 reject
                .contains("speakCapMs")                // 超时上限（引擎不回调也不能卡死）
                .contains("ZH_POS_RE")                 // POS 白名单（不是 [A-Za-z]+\.）
                .contains("'zh-CN'")
                .contains("wmZh")                      // ?wmZh=0 可只念英文
                .contains("wmZhRate")                  // 中文语速可调
                .contains("已开启：点到英文即朗读，自动学习会连读中英文")
                .doesNotContain("AUTO_SPEAK_MAX");     // 已退役：改成按文本长度的 speakCapMs
        /* 2026-10-16：自动学习时在「即将被点选」的那张卡右下角浮一只卡通小手做引导。
           细则（位置/暂停收起/开关/320x568 不越界/不吃点击）由
           scripts/probe_wm_auto_hand.mjs 在真浏览器里核；这里只钉结构。 */
        assertThat(response.getBody())
                .as("自动学习的小手引导：状态、判据、样式与开关都要在页面里")
                .contains("wm-hand")                   // 小手容器
                .contains("isAutoSpot")                // 只在「即将点选」的那张卡上显示
                .contains("autoSpot")                  // 即将被点选卡的 uid（点完立刻清空）
                .contains("wm-hand-tap")               // 点头动画
                .contains("wmHand")                    // ?wmHand=0 可整体关掉
                .contains("AUTO_HAND_ON")
                .contains("pointer-events: none");     // 不吃点击（卡片是 <button>，别缩小点击区）
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
                .contains("人物与称谓")           // 高中已按「跨学段统一语义域」重分类
                .doesNotContain("\"theme\":\"Unit 1\"")  // 原先是课本单元 Unit 1~5，已退役
                .contains("astronomy")
                .contains("天文学")
                .contains("pep-topic")          // 第 25 册：序列配对（stage=专题）
                .contains("亲人关系")             // 亲属称谓按辈分排（左列锁序才讲得通）
                .contains("grandfather")
                .contains("granddaughter");
    }

    @Test
    void learnMultiplicationSettings_rendersSuccessfully() {
        ResponseEntity<String> page = restTemplate.getForEntity(
                url("/learn/multiplication/settings"), String.class);
        assertStaticPage(page, "learn-multiplication-settings", "__LEARN_SETTINGS_DATA__");
        assertAsyncBootstrap(page, "learn-multiplication-settings", "__LEARN_SETTINGS_DATA__", "loadLearnSettingsData");
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

    // ==================== 单词匹配：导出小程序源码 ====================

    @Test
    void wordMatchMiniprogramZip_isDownloadableProject() {
        ResponseEntity<byte[]> response = restTemplate.getForEntity(
                url("/learn/word-match/miniprogram.zip"), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("zip");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("word-pair-miniprogram.zip");

        byte[] body = response.getBody();
        assertThat(body).isNotNull();
        // zip 魔数："PK" —— 导出失败时这里会是错误页或空 body
        assertThat(new String(body, 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        assertThat(body.length).as("含 752KB 词库，压完仍有上百 KB").isGreaterThan(100_000);
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
