package com.storycreator.ai.router;

import com.storycreator.core.domain.ModelType;
import com.storycreator.core.port.image.ImageProvider;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.entity.GlobalSettingEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import com.storycreator.persistence.repository.GlobalSettingRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ImageProviderRegistry {

    private static final String GLOBAL_DEFAULT_IMAGE_KEY = "default_image_config_id";

    private final Map<String, ImageProvider> providers;
    private final AiModelConfigRepository configRepository;
    private final GlobalSettingRepository globalSettingRepository;

    public ImageProviderRegistry(List<ImageProvider> providerList,
                                 AiModelConfigRepository configRepository,
                                 GlobalSettingRepository globalSettingRepository) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(ImageProvider::getProviderName, Function.identity()));
        this.configRepository = configRepository;
        this.globalSettingRepository = globalSettingRepository;
    }

    public ResolvedImageConfig resolve(Long configId) {
        AiModelConfigEntity config = configRepository.findById(configId).orElse(null);
        if (config == null || !config.isActive() || config.getModelType() != ModelType.IMAGE) {
            return null;
        }
        ImageProvider provider = resolveProvider(config.getProvider());
        if (provider == null) return null;
        return new ResolvedImageConfig(configId, provider, config.getModelId(), config.getBaseUrl(), config.getApiKey(), config.getExtraParams());
    }

    public ResolvedImageConfig resolveGlobalDefault() {
        Long configId = getGlobalDefaultImageConfigId();
        if (configId != null) {
            ResolvedImageConfig resolved = resolve(configId);
            if (resolved != null) return resolved;
        }
        // Fallback: first active IMAGE config
        List<AiModelConfigEntity> configs = getActiveImageConfigs();
        for (AiModelConfigEntity config : configs) {
            ImageProvider provider = resolveProvider(config.getProvider());
            if (provider != null) {
                return new ResolvedImageConfig(config.getId(), provider, config.getModelId(), config.getBaseUrl(), config.getApiKey(), config.getExtraParams());
            }
        }
        return null;
    }

    public List<AiModelConfigEntity> getActiveImageConfigs() {
        return configRepository.findByActiveTrueAndModelType(ModelType.IMAGE);
    }

    public Long getGlobalDefaultImageConfigId() {
        return globalSettingRepository.findById(GLOBAL_DEFAULT_IMAGE_KEY)
                .map(s -> {
                    try { return Long.parseLong(s.getValue()); }
                    catch (Exception e) { return null; }
                })
                .orElse(null);
    }

    public void setGlobalDefaultImageConfigId(Long configId) {
        GlobalSettingEntity setting = globalSettingRepository.findById(GLOBAL_DEFAULT_IMAGE_KEY)
                .orElse(new GlobalSettingEntity(GLOBAL_DEFAULT_IMAGE_KEY, ""));
        setting.setValue(configId != null ? configId.toString() : "");
        globalSettingRepository.save(setting);
    }

    private ImageProvider resolveProvider(String providerName) {
        // Map common provider names to image provider names
        if ("openai".equals(providerName)) return providers.get("openai-image");
        if ("sd-webui".equals(providerName)) return providers.get("sd-webui");
        return providers.get(providerName);
    }

    public record ResolvedImageConfig(Long configId, ImageProvider provider, String modelId, String baseUrl, String apiKey, String extraParams) {}
}
