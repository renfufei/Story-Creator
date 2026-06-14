package com.storycreator.core.service;

import com.storycreator.persistence.entity.GlobalSettingEntity;
import com.storycreator.persistence.repository.GlobalSettingRepository;
import org.springframework.stereotype.Service;

@Service
public class GlobalSettingService {

    private static final String AI_TIMEOUT_KEY = "ai_timeout_seconds";
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

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
}
