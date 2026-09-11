package com.storycreator.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 逆向工程流程控制表：一个导入任务在每个阶段上的执行状态。
 * 持久化在库里，因此服务重启后仍能扫描出「哪些部分已完成、剩余哪些」。
 */
@Entity
@Table(name = "txt_import_re_steps",
        uniqueConstraints = @UniqueConstraint(columnNames = {"job_id", "phase"}))
public class TxtImportReStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    /** {@code com.storycreator.txtimport.RePhase} 名称。 */
    @Column(nullable = false, length = 40)
    private String phase;

    /** {@code RePhase.Status} 名称。 */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /** 该阶段需要处理的单元总数（章节数 / 卷数 / 1）。 */
    @Column(name = "total_units", nullable = false)
    private int totalUnits = 0;

    /** 已完成的单元数。 */
    @Column(name = "completed_units", nullable = false)
    private int completedUnits = 0;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public int getTotalUnits() { return totalUnits; }
    public void setTotalUnits(int totalUnits) { this.totalUnits = totalUnits; }

    public int getCompletedUnits() { return completedUnits; }
    public void setCompletedUnits(int completedUnits) { this.completedUnits = completedUnits; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
