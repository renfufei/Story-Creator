package com.storycreator.workflow.engine;

import com.storycreator.persistence.entity.AiUsageStatEntity;
import com.storycreator.persistence.repository.AiUsageStatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiUsageTracker {

    private static final Logger log = LoggerFactory.getLogger(AiUsageTracker.class);

    private final AiUsageStatRepository repository;

    public AiUsageTracker(AiUsageStatRepository repository) {
        this.repository = repository;
    }

    /**
     * Record AI call duration for a project/model combination.
     * Increments the cumulative total atomically. Safe to call from any thread — failures are logged and swallowed.
     */
    @Transactional
    public void record(Long projectId, String modelId, String providerName, long durationMs) {
        if (projectId == null || modelId == null || modelId.isBlank()) return;
        if (durationMs <= 0) return;
        try {
            int updated = repository.incrementDuration(projectId, modelId, durationMs);
            if (updated == 0) {
                // Row doesn't exist yet — insert it
                AiUsageStatEntity stat = new AiUsageStatEntity();
                stat.setProjectId(projectId);
                stat.setModelId(modelId);
                stat.setProviderName(providerName != null ? providerName : "unknown");
                stat.setTotalDurationMs(durationMs);
                repository.save(stat);
            }
        } catch (Exception e) {
            log.warn("[P{}] Failed to record AI usage for model {}: {}", projectId, modelId, e.getMessage());
        }
    }
}
