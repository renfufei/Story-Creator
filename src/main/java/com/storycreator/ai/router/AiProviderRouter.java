package com.storycreator.ai.router;

import com.storycreator.core.port.ai.AiProvider;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.entity.GlobalSettingEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.WorkflowStateEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import com.storycreator.persistence.repository.GlobalSettingRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.WorkflowStateRepository;
import com.storycreator.core.domain.WorkflowStep;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AiProviderRouter {

    private static final String GLOBAL_DEFAULT_KEY = "default_model_config_id";

    private final Map<String, AiProvider> providers;
    private final AiModelConfigRepository configRepository;
    private final GlobalSettingRepository globalSettingRepository;
    private final ProjectRepository projectRepository;
    private final WorkflowStateRepository workflowStateRepository;

    public AiProviderRouter(List<AiProvider> providerList,
                           AiModelConfigRepository configRepository,
                           GlobalSettingRepository globalSettingRepository,
                           ProjectRepository projectRepository,
                           WorkflowStateRepository workflowStateRepository) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(AiProvider::getProviderName, Function.identity()));
        this.configRepository = configRepository;
        this.globalSettingRepository = globalSettingRepository;
        this.projectRepository = projectRepository;
        this.workflowStateRepository = workflowStateRepository;
    }

    /**
     * Resolve model config with 3-level fallback:
     * 1. Step-level (workflow_states.model_config_id)
     * 2. Project-level (projects.default_model_config_id)
     * 3. Global default (global_settings)
     */
    public ResolvedModel resolveModel(Long projectId, WorkflowStep step) {
        // Level 1: step-level override
        if (projectId != null && step != null) {
            WorkflowStateEntity state = workflowStateRepository
                    .findByProjectIdAndStep(projectId, step).orElse(null);
            if (state != null && state.getModelConfigId() != null) {
                ResolvedModel resolved = fromConfigId(state.getModelConfigId());
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

        // Ultimate fallback: first active config with API key
        List<AiModelConfigEntity> activeConfigs = configRepository.findByActiveTrue();
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
        if (config != null && config.isActive()) {
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
