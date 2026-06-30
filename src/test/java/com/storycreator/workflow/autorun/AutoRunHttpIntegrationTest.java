package com.storycreator.workflow.autorun;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.ModelType;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.entity.GlobalSettingEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:autorun_http_test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none"
})
class AutoRunHttpIntegrationTest {

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
    @Autowired private TransactionTemplate transactionTemplate;

    private Long projectId;
    private Long mockConfigId;

    @BeforeEach
    void setUp() {
        // Create OpenAI-compatible model config pointing to MockApiController
        // OpenAI provider appends "/v1/chat/completions" to baseUrl, so use /mock as base
        AiModelConfigEntity config = new AiModelConfigEntity();
        config.setProvider("openai");
        config.setBaseUrl("http://localhost:" + port + "/mock");
        config.setModelId("mock-model");
        config.setDisplayName("Mock OpenAI Model");
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
        project.setTitle("HTTP集成测试小说");
        project.setGenre(Genre.XUANHUAN);
        project.setDescription("用于HTTP集成测试的项目");
        project.setTotalChapters(2);
        project.setChapterWordCount(500);
        project.setChapterWordCountMin(400);
        project.setChapterWordCountMax(600);
        project.setCharacterCount(3);
        project.setCurrentStep(WorkflowStep.WORLD_BUILDING);
        project.setAutoRunStrategy("DEFAULT");
        project.setDefaultModelConfigId(mockConfigId);
        project = projectRepository.save(project);
        projectId = project.getId();
    }

    @AfterEach
    void tearDown() {
        if (projectId != null) {
            // Wait for any running auto-run to stop
            try {
                restTemplate.postForEntity(url("/projects/{id}/auto-run/stop"), null, Map.class, projectId);
                pollForAnyStatus(new String[]{"IDLE", "FAILED", "COMPLETED"}, 15);
            } catch (Exception ignored) {}

            transactionTemplate.executeWithoutResult(status -> {
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
            if (mockConfigId != null) {
                aiModelConfigRepository.deleteById(mockConfigId);
            }
            globalSettingRepository.deleteById("default_model_config_id");
        });
    }

    @Test
    void testModel_mockEndpointReachable() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url("/mock/v1/models"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("object")).isEqualTo("list");
    }

    @Test
    @Timeout(90)
    void fullWorkflow_allStepsEnabled_completesSuccessfully() throws Exception {
        // Start auto-run via HTTP
        ResponseEntity<Map<String, Object>> startResponse = restTemplate.exchange(
                url("/projects/{id}/auto-run/start"),
                HttpMethod.POST,
                null,
                new ParameterizedTypeReference<>() {},
                projectId
        );
        assertThat(startResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Poll for completion
        boolean completed = pollForStatus("COMPLETED", 80);
        assertThat(completed).as("Auto-run should complete within timeout").isTrue();

        // Verify world setting
        var ws = worldSettingRepository.findByProjectId(projectId);
        assertThat(ws).isPresent();
        assertThat(ws.get().getContent()).isNotEmpty();

        // Verify characters created
        var characters = characterRepository.findByProjectIdOrderBySortOrder(projectId);
        assertThat(characters).isNotEmpty();

        // Verify chapters have content
        var chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        assertThat(chapters).hasSizeGreaterThanOrEqualTo(2);
        for (var ch : chapters) {
            if (ch.getChapterNumber() <= 2) {
                assertThat(ch.getContent())
                        .as("Chapter %d should have content", ch.getChapterNumber())
                        .isNotNull().isNotEmpty();
            }
        }

        // Verify chapter outlines exist
        var outlines = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);
        assertThat(outlines).isNotEmpty();
    }

    @Test
    @Timeout(60)
    void partialWorkflow_disableOutlineAndLater_stopsAfterCharacterDesign() throws Exception {
        // Disable steps 3-6 via HTTP
        for (WorkflowStep step : new WorkflowStep[]{
                WorkflowStep.OUTLINE_GENERATION,
                WorkflowStep.CHAPTER_WRITING,
                WorkflowStep.POLISHING,
                WorkflowStep.PROOFREADING
        }) {
            ResponseEntity<Map<String, Object>> configResponse = restTemplate.exchange(
                    url("/projects/{id}/auto-run/step-config?step={step}&enabled=false"),
                    HttpMethod.PUT,
                    null,
                    new ParameterizedTypeReference<>() {},
                    projectId, step.name()
            );
            assertThat(configResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        // Start auto-run
        restTemplate.exchange(
                url("/projects/{id}/auto-run/start"),
                HttpMethod.POST,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {},
                projectId
        );

        // Poll for completion
        boolean completed = pollForStatus("COMPLETED", 50);
        assertThat(completed).as("Partial auto-run should complete within timeout").isTrue();

        // Verify world setting exists
        var ws = worldSettingRepository.findByProjectId(projectId);
        assertThat(ws).isPresent();
        assertThat(ws.get().getContent()).isNotEmpty();

        // Verify characters exist
        var characters = characterRepository.findByProjectIdOrderBySortOrder(projectId);
        assertThat(characters).isNotEmpty();

        // Verify no chapters were written (outline + writing disabled)
        var chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        boolean anyHasContent = chapters.stream().anyMatch(ch -> ch.getContent() != null && !ch.getContent().isEmpty());
        assertThat(anyHasContent).as("No chapters should have content when writing is disabled").isFalse();
    }

    @Test
    void stepConfig_updateViaHttp_reflectedInDatabase() {
        // Disable POLISHING step
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url("/projects/{id}/auto-run/step-config?step=POLISHING&enabled=false"),
                HttpMethod.PUT,
                null,
                new ParameterizedTypeReference<>() {},
                projectId
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("enabled", false);

        // Verify in DB
        var config = autoRunStepConfigRepository.findByProjectIdAndStep(projectId, WorkflowStep.POLISHING);
        assertThat(config).isPresent();
        assertThat(config.get().isEnabled()).isFalse();

        // Toggle back to enabled
        ResponseEntity<Map<String, Object>> toggleResponse = restTemplate.exchange(
                url("/projects/{id}/auto-run/step-config?step=POLISHING&enabled=true"),
                HttpMethod.PUT,
                null,
                new ParameterizedTypeReference<>() {},
                projectId
        );
        assertThat(toggleResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(toggleResponse.getBody()).containsEntry("enabled", true);

        // Verify toggle persisted
        var updatedConfig = autoRunStepConfigRepository.findByProjectIdAndStep(projectId, WorkflowStep.POLISHING);
        assertThat(updatedConfig).isPresent();
        assertThat(updatedConfig.get().isEnabled()).isTrue();
    }

    @Test
    @Timeout(30)
    void stopViaHttp_haltsAutoRun() throws Exception {
        // Start auto-run
        restTemplate.exchange(
                url("/projects/{id}/auto-run/start"),
                HttpMethod.POST,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {},
                projectId
        );

        // Give it a moment to start
        Thread.sleep(300);

        // Stop via HTTP
        ResponseEntity<Map<String, Object>> stopResponse = restTemplate.exchange(
                url("/projects/{id}/auto-run/stop"),
                HttpMethod.POST,
                null,
                new ParameterizedTypeReference<>() {},
                projectId
        );
        assertThat(stopResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Poll until stopped
        boolean stopped = pollForAnyStatus(new String[]{"IDLE", "FAILED"}, 15);
        assertThat(stopped).as("Auto-run should stop after HTTP stop request").isTrue();

        // Verify final status via HTTP status endpoint
        ResponseEntity<Map<String, Object>> statusResponse = restTemplate.exchange(
                url("/projects/{id}/auto-run/status"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {},
                projectId
        );
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String status = (String) statusResponse.getBody().get("status");
        assertThat(status).isIn("IDLE", "FAILED");
    }

    @Test
    void doubleStart_secondCallRejectedWith400() throws Exception {
        // First start should succeed
        ResponseEntity<Map<String, Object>> first = restTemplate.exchange(
                url("/projects/{id}/auto-run/start"),
                HttpMethod.POST,
                null,
                new ParameterizedTypeReference<>() {},
                projectId
        );
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Brief wait to ensure it's running
        Thread.sleep(200);

        // Second start should be rejected with 400
        ResponseEntity<Map<String, Object>> second = restTemplate.exchange(
                url("/projects/{id}/auto-run/start"),
                HttpMethod.POST,
                null,
                new ParameterizedTypeReference<>() {},
                projectId
        );
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Cleanup: stop the running auto-run
        restTemplate.exchange(
                url("/projects/{id}/auto-run/stop"),
                HttpMethod.POST,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {},
                projectId
        );
    }

    // --- Helpers ---

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private boolean pollForStatus(String target, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url("/projects/{id}/auto-run/status"),
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {},
                    projectId
            );
            if (response.getBody() != null) {
                String status = (String) response.getBody().get("status");
                if (target.equals(status)) return true;
                if ("FAILED".equals(status) && !target.equals("FAILED")) {
                    System.err.println("Auto-run FAILED: " + response.getBody().get("error"));
                    return false;
                }
            }
            Thread.sleep(500);
        }
        // Log timeout info
        ResponseEntity<Map<String, Object>> last = restTemplate.exchange(
                url("/projects/{id}/auto-run/status"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {},
                projectId
        );
        System.err.println("Timeout waiting for " + target + ", last status: " + last.getBody());
        return false;
    }

    private boolean pollForAnyStatus(String[] targets, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url("/projects/{id}/auto-run/status"),
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {},
                    projectId
            );
            if (response.getBody() != null) {
                String status = (String) response.getBody().get("status");
                for (String target : targets) {
                    if (target.equals(status)) return true;
                }
            }
            Thread.sleep(500);
        }
        return false;
    }
}
