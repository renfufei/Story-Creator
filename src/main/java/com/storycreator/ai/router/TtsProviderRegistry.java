package com.storycreator.ai.router;

import com.storycreator.core.domain.ModelType;
import com.storycreator.core.port.tts.TtsProvider;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TtsProviderRegistry {

    private final TtsProvider ttsProvider;
    private final AiModelConfigRepository configRepository;

    public TtsProviderRegistry(TtsProvider ttsProvider, AiModelConfigRepository configRepository) {
        this.ttsProvider = ttsProvider;
        this.configRepository = configRepository;
    }

    public ResolvedTtsConfig resolve(Long configId) {
        AiModelConfigEntity config = configRepository.findById(configId).orElse(null);
        if (config == null || !config.isActive() || config.getModelType() != ModelType.TTS) {
            return null;
        }
        return new ResolvedTtsConfig(ttsProvider, config.getModelId(), config.getBaseUrl(), config.getApiKey());
    }

    public List<AiModelConfigEntity> getActiveTtsConfigs() {
        return configRepository.findByActiveTrueAndModelType(ModelType.TTS);
    }

    public record ResolvedTtsConfig(TtsProvider provider, String modelId, String baseUrl, String apiKey) {}
}
