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
    @Autowired private TransactionTemplate transactionTemplate;

    private Long projectId;
    private Long configId;
    private Long sideStoryId;

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
        project.setAutoRunStrategy("DEFAULT");
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
    }

    @AfterEach
    void tearDown() {
        if (projectId != null) {
            transactionTemplate.executeWithoutResult(status -> {
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
        assertThat(response.getBody())
                .as("%s page should have non-empty HTML body", pageName)
                .isNotNull()
                .isNotEmpty()
                .contains("<html");
    }
}
