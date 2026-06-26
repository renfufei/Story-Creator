package com.storycreator.core.service;

import com.storycreator.persistence.entity.GlobalSettingEntity;
import com.storycreator.persistence.repository.GlobalSettingRepository;
import org.springframework.stereotype.Service;

@Service
public class GlobalSettingService {

    private static final String AI_TIMEOUT_KEY = "ai_timeout_seconds";
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final String TTS_DEBUG_MODE_KEY = "tts_debug_mode";

    private final GlobalSettingRepository globalSettingRepository;

    public GlobalSettingService(GlobalSettingRepository globalSettingRepository) {
        this.globalSettingRepository = globalSettingRepository;
    }

    public int getAiTimeoutSeconds() {
        return globalSettingRepository.findById(AI_TIMEOUT_KEY)
                .map(s -> {
                    try { return Integer.parseInt(s.getValue()); }
                    catch (Exception e) { return DEFAULT_TIMEOUT_SECONDS; }
                })
                .orElse(DEFAULT_TIMEOUT_SECONDS);
    }

    public void setAiTimeoutSeconds(int seconds) {
        GlobalSettingEntity setting = globalSettingRepository.findById(AI_TIMEOUT_KEY)
                .orElse(new GlobalSettingEntity(AI_TIMEOUT_KEY, ""));
        setting.setValue(String.valueOf(seconds));
        globalSettingRepository.save(setting);
    }

    public boolean isTtsDebugMode() {
        return globalSettingRepository.findById(TTS_DEBUG_MODE_KEY)
                .map(s -> "true".equalsIgnoreCase(s.getValue()))
                .orElse(false);
    }

    public void setTtsDebugMode(boolean enabled) {
        GlobalSettingEntity setting = globalSettingRepository.findById(TTS_DEBUG_MODE_KEY)
                .orElse(new GlobalSettingEntity(TTS_DEBUG_MODE_KEY, ""));
        setting.setValue(String.valueOf(enabled));
        globalSettingRepository.save(setting);
    }
}
