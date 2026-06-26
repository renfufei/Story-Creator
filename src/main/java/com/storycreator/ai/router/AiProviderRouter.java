package com.storycreator.ai.router;

import com.storycreator.core.domain.ModelType;
import com.storycreator.core.port.ai.AiProvider;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.entity.GlobalSettingEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.StepModelConfigEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import com.storycreator.persistence.repository.GlobalSettingRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.StepModelConfigRepository;
import com.storycreator.core.domain.WorkflowStep;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AiProviderRouter {

    private static final String GLOBAL_DEFAULT_KEY = "default_model_config_id";
    private static final String GLOBAL_DEFAULT_TTS_KEY = "default_tts_config_id";

    private final Map<String, AiProvider> providers;
    private final AiModelConfigRepository configRepository;
    private final GlobalSettingRepository globalSettingRepository;
    private final ProjectRepository projectRepository;
    private final StepModelConfigRepository stepModelConfigRepository;

    public AiProviderRouter(List<AiProvider> providerList,
                           AiModelConfigRepository configRepository,
                           GlobalSettingRepository globalSettingRepository,
                           ProjectRepository projectRepository,
                           StepModelConfigRepository stepModelConfigRepository) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(AiProvider::getProviderName, Function.identity()));
        this.configRepository = configRepository;
        this.globalSettingRepository = globalSettingRepository;
        this.projectRepository = projectRepository;
        this.stepModelConfigRepository = stepModelConfigRepository;
    }

    /**
     * Resolve model config with 3-level fallback:
     * 1. Step-level (workflow_states.model_config_id)
     * 2. Project-level (projects.default_model_config_id)
     * 3. Global default (global_settings)
     */
    public ResolvedModel resolveModel(Long projectId, WorkflowStep step) {
        // Level 1: step-level override (from step_model_configs table)
        if (projectId != null && step != null) {
            StepModelConfigEntity stepConfig = stepModelConfigRepository
                    .findByProjectIdAndStep(projectId, step).orElse(null);
            if (stepConfig != null && stepConfig.getModelConfigId() != null) {
                ResolvedModel resolved = fromConfigId(stepConfig.getModelConfigId());
                if (resolved != null) return resolved;
            }
        }

        // Level 2: project-level default
        if (projectId != null) {
            ProjectEntity project = projectRepository.findById(projectId).orElse(null);
            if (project != null && project.getDefaultModelConfigId() != null) {
                ResolvedModel resolved = fromConfigId(project.getDefaultModelConfigId());
                if (resolved != null) return resolved;
            }
        }

        // Level 3: global default
        GlobalSettingEntity globalSetting = globalSettingRepository.findById(GLOBAL_DEFAULT_KEY).orElse(null);
        if (globalSetting != null && globalSetting.getValue() != null && !globalSetting.getValue().isBlank()) {
            try {
                Long configId = Long.parseLong(globalSetting.getValue());
                ResolvedModel resolved = fromConfigId(configId);
                if (resolved != null) return resolved;
            } catch (NumberFormatException ignored) {}
        }

        // Ultimate fallback: first active TEXT config with API key
        List<AiModelConfigEntity> activeConfigs = configRepository.findByActiveTrueAndModelType(ModelType.TEXT);
        for (AiModelConfigEntity config : activeConfigs) {
            if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                AiProvider provider = providers.get(config.getProvider());
                if (provider != null) {
                    return new ResolvedModel(provider, config.getModelId(), config.getBaseUrl(), config.getApiKey(), config.getExtraParams());
                }
            }
        }

        // Last resort: return any provider
        return new ResolvedModel(providers.values().iterator().next(), null);
    }

    public AiProvider getDefaultProvider() {
        ResolvedModel resolved = resolveModel(null, null);
        return resolved.provider();
    }

    private ResolvedModel fromConfigId(Long configId) {
        AiModelConfigEntity config = configRepository.findById(configId).orElse(null);
        if (config != null && config.isActive() && config.getModelType() == ModelType.TEXT) {
            AiProvider provider = providers.get(config.getProvider());
            if (provider != null) {
                return new ResolvedModel(provider, config.getModelId(), config.getBaseUrl(), config.getApiKey(), config.getExtraParams());
            }
        }
        return null;
    }

    public List<AiModelConfigEntity> getActiveConfigs() {
        return configRepository.findByActiveTrue();
    }

    public Long getGlobalDefaultConfigId() {
        return globalSettingRepository.findById(GLOBAL_DEFAULT_KEY)
                .map(s -> {
                    try { return Long.parseLong(s.getValue()); }
                    catch (Exception e) { return null; }
                })
                .orElse(null);
    }

    public void setGlobalDefaultConfigId(Long configId) {
        GlobalSettingEntity setting = globalSettingRepository.findById(GLOBAL_DEFAULT_KEY)
                .orElse(new GlobalSettingEntity(GLOBAL_DEFAULT_KEY, ""));
        setting.setValue(configId != null ? configId.toString() : "");
        globalSettingRepository.save(setting);
    }

    public Long getGlobalDefaultTtsConfigId() {
        return globalSettingRepository.findById(GLOBAL_DEFAULT_TTS_KEY)
                .map(s -> {
                    try { return Long.parseLong(s.getValue()); }
                    catch (Exception e) { return null; }
                })
                .orElse(null);
    }

    public void setGlobalDefaultTtsConfigId(Long configId) {
        GlobalSettingEntity setting = globalSettingRepository.findById(GLOBAL_DEFAULT_TTS_KEY)
                .orElse(new GlobalSettingEntity(GLOBAL_DEFAULT_TTS_KEY, ""));
        setting.setValue(configId != null ? configId.toString() : "");
        globalSettingRepository.save(setting);
    }

    public ResolvedModel resolveModelByConfigId(Long configId) {
        return fromConfigId(configId);
    }

    public record ResolvedModel(AiProvider provider, String modelId, String baseUrl, String apiKey, String extraParams) {
        public ResolvedModel(AiProvider provider, String modelId) {
            this(provider, modelId, null, null, null);
        }
        public ResolvedModel(AiProvider provider, String modelId, String baseUrl, String apiKey) {
            this(provider, modelId, baseUrl, apiKey, null);
        }
    }
}
