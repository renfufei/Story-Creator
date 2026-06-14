package com.storycreator.persistence.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "ai_usage_stats", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "model_id"}))
public class AiUsageStatEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "model_id", nullable = false, length = 100)
    private String modelId;

    @Column(name = "provider_name", nullable = false, length = 50)
    private String providerName;

    @Column(name = "total_duration_ms", nullable = false)
    private long totalDurationMs;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(long totalDurationMs) { this.totalDurationMs = totalDurationMs; }

    /**
     * Returns formatted duration string like "12h 36m 40s" or "3m 12s" or "800ms".
     */
    public String getFormattedDuration() {
        long ms = totalDurationMs;
        if (ms < 1000) return ms + "ms";

        long seconds = ms / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (secs > 0 || sb.isEmpty()) sb.append(secs).append("s");
        return sb.toString().trim();
    }
}
