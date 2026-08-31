package com.storycreator.ai.router;

import com.storycreator.core.domain.ModelType;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiProvider;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.entity.GlobalSettingEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.StepModelConfigEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import com.storycreator.persistence.repository.GlobalSettingRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.StepModelConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiProviderRouterTest {

    @Mock AiModelConfigRepository configRepository;
    @Mock GlobalSettingRepository globalSettingRepository;
    @Mock ProjectRepository projectRepository;
    @Mock StepModelConfigRepository stepModelConfigRepository;

    // Minimal stub providers
    private final AiProvider claudeProvider = new StubProvider("claude");
    private final AiProvider openaiProvider = new StubProvider("openai");

    private AiProviderRouter router;

    @BeforeEach
    void setUp() {
        router = new AiProviderRouter(
                List.of(claudeProvider, openaiProvider),
                configRepository,
                globalSettingRepository,
                projectRepository,
                stepModelConfigRepository
        );
        // Default: repositories return empty unless overridden per test
        when(stepModelConfigRepository.findByProjectIdAndStep(any(), any())).thenReturn(Optional.empty());
        when(projectRepository.findById(any())).thenReturn(Optional.empty());
        when(globalSettingRepository.findById(any())).thenReturn(Optional.empty());
        when(configRepository.findByActiveTrueAndModelType(ModelType.TEXT)).thenReturn(List.of());
    }

    // ── Level 1: step-level override ─────────────────────────────────────────

    @Test
    void resolveModel_stepLevelOverride_usedWhenPresent() {
        AiModelConfigEntity config = activeTextConfig(10L, "claude", "claude-3", "http://api.claude.ai", "key1");

        StepModelConfigEntity stepConfig = new StepModelConfigEntity();
        stepConfig.setProjectId(1L);
        stepConfig.setStep(WorkflowStep.CHAPTER_WRITING);
        stepConfig.setModelConfigId(10L);

        when(stepModelConfigRepository.findByProjectIdAndStep(1L, WorkflowStep.CHAPTER_WRITING))
                .thenReturn(Optional.of(stepConfig));
        when(configRepository.findById(10L)).thenReturn(Optional.of(config));

        AiProviderRouter.ResolvedModel resolved = router.resolveModel(1L, WorkflowStep.CHAPTER_WRITING);

        assertThat(resolved.provider()).isSameAs(claudeProvider);
        assertThat(resolved.modelId()).isEqualTo("claude-3");
        assertThat(resolved.apiKey()).isEqualTo("key1");
    }

    // ── Level 2: project-level default ───────────────────────────────────────

    @Test
    void resolveModel_projectDefault_usedWhenNoStepConfig() {
        AiModelConfigEntity config = activeTextConfig(20L, "openai", "gpt-4o", "http://api.openai.com", "key2");

        ProjectEntity project = new ProjectEntity();
        project.setDefaultModelConfigId(20L);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(configRepository.findById(20L)).thenReturn(Optional.of(config));

        AiProviderRouter.ResolvedModel resolved = router.resolveModel(1L, WorkflowStep.CHAPTER_WRITING);

        assertThat(resolved.provider()).isSameAs(openaiProvider);
        assertThat(resolved.modelId()).isEqualTo("gpt-4o");
    }

    // ── Level 3: global default ───────────────────────────────────────────────

    @Test
    void resolveModel_globalDefault_usedWhenNoProjectDefault() {
        AiModelConfigEntity config = activeTextConfig(30L, "claude", "claude-sonnet", "http://api.claude.ai", "key3");

        when(globalSettingRepository.findById("default_model_config_id"))
                .thenReturn(Optional.of(new GlobalSettingEntity("default_model_config_id", "30")));
        when(configRepository.findById(30L)).thenReturn(Optional.of(config));

        AiProviderRouter.ResolvedModel resolved = router.resolveModel(1L, WorkflowStep.CHAPTER_WRITING);

        assertThat(resolved.provider()).isSameAs(claudeProvider);
        assertThat(resolved.modelId()).isEqualTo("claude-sonnet");
    }

    // ── Ultimate fallback: first active config ────────────────────────────────

    @Test
    void resolveModel_ultimateFallback_firstActiveTextConfig() {
        AiModelConfigEntity config = activeTextConfig(40L, "openai", "gpt-4", "http://api.openai.com", "key4");

        when(configRepository.findByActiveTrueAndModelType(ModelType.TEXT)).thenReturn(List.of(config));

        AiProviderRouter.ResolvedModel resolved = router.resolveModel(null, null);

        assertThat(resolved.provider()).isSameAs(openaiProvider);
        assertThat(resolved.modelId()).isEqualTo("gpt-4");
    }

    // ── Priority order: step > project > global ───────────────────────────────

    @Test
    void resolveModel_stepTakesPriorityOverProject() {
        AiModelConfigEntity stepCfg = activeTextConfig(10L, "claude", "claude-step", "http://api.claude.ai", "step-key");
        AiModelConfigEntity projectCfg = activeTextConfig(20L, "openai", "gpt-project", "http://api.openai.com", "proj-key");

        StepModelConfigEntity stepConfig = new StepModelConfigEntity();
        stepConfig.setProjectId(1L);
        stepConfig.setStep(WorkflowStep.CHAPTER_WRITING);
        stepConfig.setModelConfigId(10L);

        ProjectEntity project = new ProjectEntity();
        project.setDefaultModelConfigId(20L);

        when(stepModelConfigRepository.findByProjectIdAndStep(1L, WorkflowStep.CHAPTER_WRITING))
                .thenReturn(Optional.of(stepConfig));
        when(configRepository.findById(10L)).thenReturn(Optional.of(stepCfg));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(configRepository.findById(20L)).thenReturn(Optional.of(projectCfg));

        AiProviderRouter.ResolvedModel resolved = router.resolveModel(1L, WorkflowStep.CHAPTER_WRITING);

        assertThat(resolved.modelId()).isEqualTo("claude-step");
    }

    @Test
    void resolveModel_projectTakesPriorityOverGlobal() {
        AiModelConfigEntity projectCfg = activeTextConfig(20L, "openai", "gpt-project", "http://api.openai.com", "proj-key");
        AiModelConfigEntity globalCfg = activeTextConfig(30L, "claude", "claude-global", "http://api.claude.ai", "global-key");

        ProjectEntity project = new ProjectEntity();
        project.setDefaultModelConfigId(20L);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(configRepository.findById(20L)).thenReturn(Optional.of(projectCfg));
        when(globalSettingRepository.findById("default_model_config_id"))
                .thenReturn(Optional.of(new GlobalSettingEntity("default_model_config_id", "30")));
        when(configRepository.findById(30L)).thenReturn(Optional.of(globalCfg));

        AiProviderRouter.ResolvedModel resolved = router.resolveModel(1L, WorkflowStep.CHAPTER_WRITING);

        assertThat(resolved.modelId()).isEqualTo("gpt-project");
    }

    // ── fromConfigId edge cases ───────────────────────────────────────────────

    @Test
    void resolveModel_configInactive_skippedFallsThrough() {
        AiModelConfigEntity inactive = activeTextConfig(10L, "claude", "claude-3", "http://api.claude.ai", "key");
        inactive.setActive(false);

        StepModelConfigEntity stepConfig = new StepModelConfigEntity();
        stepConfig.setProjectId(1L);
        stepConfig.setStep(WorkflowStep.CHAPTER_WRITING);
        stepConfig.setModelConfigId(10L);

        when(stepModelConfigRepository.findByProjectIdAndStep(1L, WorkflowStep.CHAPTER_WRITING))
                .thenReturn(Optional.of(stepConfig));
        when(configRepository.findById(10L)).thenReturn(Optional.of(inactive));

        // Falls through to global/fallback (both empty → last resort)
        AiProviderRouter.ResolvedModel resolved = router.resolveModel(1L, WorkflowStep.CHAPTER_WRITING);
        // Should not return the inactive config's model
        assertThat(resolved.modelId()).isNotEqualTo("claude-3");
    }

    @Test
    void resolveModel_ttsConfigIgnored_notReturnedForTextResolution() {
        AiModelConfigEntity ttsConfig = activeTextConfig(10L, "openai", "tts-1", "http://api.openai.com", "key");
        ttsConfig.setModelType(ModelType.TTS);

        StepModelConfigEntity stepConfig = new StepModelConfigEntity();
        stepConfig.setProjectId(1L);
        stepConfig.setStep(WorkflowStep.CHAPTER_WRITING);
        stepConfig.setModelConfigId(10L);

        when(stepModelConfigRepository.findByProjectIdAndStep(1L, WorkflowStep.CHAPTER_WRITING))
                .thenReturn(Optional.of(stepConfig));
        when(configRepository.findById(10L)).thenReturn(Optional.of(ttsConfig));

        // TTS config should not be returned by text resolution
        AiProviderRouter.ResolvedModel resolved = router.resolveModel(1L, WorkflowStep.CHAPTER_WRITING);
        assertThat(resolved.modelId()).isNotEqualTo("tts-1");
    }

    @Test
    void resolveModel_globalSettingInvalidNumber_skipped() {
        when(globalSettingRepository.findById("default_model_config_id"))
                .thenReturn(Optional.of(new GlobalSettingEntity("default_model_config_id", "not-a-number")));

        // Should not throw, falls through to ultimate fallback
        AiProviderRouter.ResolvedModel resolved = router.resolveModel(null, null);
        assertThat(resolved).isNotNull();
    }

    @Test
    void resolveModelByConfigId_activeTextConfig_returnsResolved() {
        AiModelConfigEntity config = activeTextConfig(50L, "claude", "claude-haiku", "http://api.claude.ai", "key5");
        when(configRepository.findById(50L)).thenReturn(Optional.of(config));

        AiProviderRouter.ResolvedModel resolved = router.resolveModelByConfigId(50L);

        assertThat(resolved).isNotNull();
        assertThat(resolved.modelId()).isEqualTo("claude-haiku");
    }

    @Test
    void resolveModelByConfigId_nonExistentId_returnsNull() {
        when(configRepository.findById(999L)).thenReturn(Optional.empty());
        assertThat(router.resolveModelByConfigId(999L)).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AiModelConfigEntity activeTextConfig(Long id, String provider, String modelId, String baseUrl, String apiKey) {
        AiModelConfigEntity c = new AiModelConfigEntity();
        c.setId(id);
        c.setProvider(provider);
        c.setModelId(modelId);
        c.setBaseUrl(baseUrl);
        c.setApiKey(apiKey);
        c.setActive(true);
        c.setModelType(ModelType.TEXT);
        return c;
    }

    private static class StubProvider implements AiProvider {
        private final String name;
        StubProvider(String name) { this.name = name; }

        @Override public String getProviderName() { return name; }
        @Override public String generateText(AiRequest request) { return ""; }
        @Override public Flux<String> streamText(AiRequest request) { return Flux.empty(); }
    }
}
