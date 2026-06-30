package com.storycreator.workflow.autorun;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.ModelType;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.entity.GlobalSettingEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.GlobalSettingRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.WorldSettingRepository;
import com.storycreator.persistence.entity.WorldSettingEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:autorun_test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none"
})
class AutoRunIntegrationTest {

    @Autowired private AutoRunService autoRunService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private AiModelConfigRepository aiModelConfigRepository;
    @Autowired private GlobalSettingRepository globalSettingRepository;
    @Autowired private ChapterRepository chapterRepository;
    @Autowired private WorldSettingRepository worldSettingRepository;

    private Long projectId;
    private Long mockConfigId;

    @BeforeEach
    void setUp() {
        // Create mock model config
        AiModelConfigEntity config = new AiModelConfigEntity();
        config.setProvider("mock");
        config.setModelId("mock-model");
        config.setDisplayName("Mock Model");
        config.setApiKey("mock-key");
        config.setActive(true);
        config.setModelType(ModelType.TEXT);
        config = aiModelConfigRepository.save(config);
        mockConfigId = config.getId();

        // Set as global default
        GlobalSettingEntity defaultSetting = new GlobalSettingEntity("default_model_config_id", mockConfigId.toString());
        globalSettingRepository.save(defaultSetting);

        // Create test project
        ProjectEntity project = new ProjectEntity();
        project.setTitle("测试小说");
        project.setGenre(Genre.XUANHUAN);
        project.setDescription("用于集成测试的项目");
        project.setTotalChapters(2);
        project.setChapterWordCount(500);
        project.setChapterWordCountMin(400);
        project.setChapterWordCountMax(600);
        project.setCharacterCount(5);
        project.setCurrentStep(WorkflowStep.WORLD_BUILDING);
        project.setAutoRunStrategy("DEFAULT");
        project.setDefaultModelConfigId(mockConfigId);
        project = projectRepository.save(project);
        projectId = project.getId();
    }

    @Test
    void autoRun_completesFullWorkflow() throws Exception {
        autoRunService.startAutoRun(projectId);

        // Poll for completion (max 60s for full workflow with mock)
        boolean completed = pollForStatus(AutoRunStatus.COMPLETED, 60);

        assertThat(completed).as("Auto-run should complete within timeout").isTrue();

        // Verify world setting has content
        var ws = worldSettingRepository.findByProjectId(projectId);
        assertThat(ws).isPresent();
        assertThat(ws.get().getContent()).isNotEmpty();

        // Verify chapters have content
        var chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        assertThat(chapters).hasSizeGreaterThanOrEqualTo(2);
        for (var ch : chapters) {
            if (ch.getChapterNumber() <= 2) {
                assertThat(ch.getContent()).as("Chapter %d should have content", ch.getChapterNumber())
                        .isNotNull().isNotEmpty();
            }
        }
    }

    @Test
    void autoRun_stopSignal_haltsExecution() throws Exception {
        autoRunService.startAutoRun(projectId);

        // Give it a brief moment to start, then stop
        Thread.sleep(200);
        autoRunService.stopAutoRun(projectId);

        // Poll for IDLE or FAILED (stopped state)
        boolean stopped = pollForAnyStatus(new AutoRunStatus[]{AutoRunStatus.IDLE, AutoRunStatus.FAILED}, 15);
        assertThat(stopped).as("Auto-run should stop within timeout").isTrue();

        ProjectEntity p = projectRepository.findById(projectId).orElseThrow();
        // When stopped normally it goes to IDLE
        assertThat(p.getAutoRunStatus()).isIn(AutoRunStatus.IDLE, AutoRunStatus.FAILED);
    }

    @Test
    void autoRun_resumesFromBreakpoint() throws Exception {
        // Pre-fill world building content so it should skip that step (must be >50 chars)
        WorldSettingEntity ws = new WorldSettingEntity();
        ws.setProjectId(projectId);
        ws.setContent("这是一个预先填充的世界观设定内容，用于验证断点续跑功能。" +
                "本世界名为测试大陆，包含多个势力和丰富的修炼体系。这段内容必须超过五十个字符才能通过完整性检验。");
        worldSettingRepository.save(ws);

        autoRunService.startAutoRun(projectId);

        // Poll for completion (should be faster since world building is skipped)
        boolean completed = pollForStatus(AutoRunStatus.COMPLETED, 60);
        assertThat(completed).as("Auto-run should complete within timeout").isTrue();

        // World setting content should still be the pre-filled content (not overwritten)
        var savedWs = worldSettingRepository.findByProjectId(projectId).orElseThrow();
        assertThat(savedWs.getContent()).contains("测试大陆");
    }

    // --- Helpers ---

    private boolean pollForStatus(AutoRunStatus target, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ProjectEntity p = projectRepository.findById(projectId).orElseThrow();
            if (p.getAutoRunStatus() == target) return true;
            if (p.getAutoRunStatus() == AutoRunStatus.FAILED) {
                System.err.println("Auto-run FAILED: " + p.getAutoRunError());
                if (target == AutoRunStatus.COMPLETED) return false;
            }
            Thread.sleep(500);
        }
        ProjectEntity p = projectRepository.findById(projectId).orElseThrow();
        System.err.println("Timeout waiting for " + target + ", current: " + p.getAutoRunStatus()
                + ", progress: " + p.getAutoRunProgress() + ", error: " + p.getAutoRunError());
        return false;
    }

    private boolean pollForAnyStatus(AutoRunStatus[] targets, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ProjectEntity p = projectRepository.findById(projectId).orElseThrow();
            for (AutoRunStatus target : targets) {
                if (p.getAutoRunStatus() == target) return true;
            }
            Thread.sleep(500);
        }
        return false;
    }
}
