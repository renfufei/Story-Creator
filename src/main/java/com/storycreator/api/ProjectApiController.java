package com.storycreator.api;

import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.AiUsageStatEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.WorkflowStateEntity;
import com.storycreator.persistence.repository.AiUsageStatRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.WorkflowStateRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 项目相关 JSON API（供 static/pages 下的静态页通过 Ajax 调用）。
 *
 * <p>约定：所有 {@code /api/**} 接口只返回数据，不做页面跳转；
 * 出错时返回非 2xx 状态码，由前端统一提示。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectApiController {

    private final ProjectRepository projectRepository;
    private final ChapterRepository chapterRepository;
    private final WorkflowStateRepository workflowStateRepository;
    private final AiUsageStatRepository aiUsageStatRepository;

    public ProjectApiController(ProjectRepository projectRepository,
                                ChapterRepository chapterRepository,
                                WorkflowStateRepository workflowStateRepository,
                                AiUsageStatRepository aiUsageStatRepository) {
        this.projectRepository = projectRepository;
        this.chapterRepository = chapterRepository;
        this.workflowStateRepository = workflowStateRepository;
        this.aiUsageStatRepository = aiUsageStatRepository;
    }

    /** 项目列表（含章节数与字数统计），按更新时间倒序 */
    @GetMapping
    public List<Map<String, Object>> list() {
        Map<Long, long[]> stats = new HashMap<>();
        for (Object[] row : chapterRepository.countAndWordCountByProject()) {
            stats.put((Long) row[0], new long[]{(Long) row[1], (Long) row[2]});
        }
        return projectRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(p -> toDto(p, stats.get(p.getId()), null, null))
                .toList();
    }

    /** 项目详情（含工作流进度与 AI 用时统计） */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id) {
        return projectRepository.findById(id)
                .map(p -> ResponseEntity.ok(toDto(p, statsOf(id), workflowStatesOf(id), usageStatsOf(id))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private long[] statsOf(Long projectId) {
        for (Object[] row : chapterRepository.countAndWordCountByProject()) {
            if (projectId.equals(row[0])) {
                return new long[]{(Long) row[1], (Long) row[2]};
            }
        }
        return null;
    }

    /** 补全六个步骤的状态，缺失的按「未开始」展示 */
    private List<Map<String, Object>> workflowStatesOf(Long projectId) {
        Map<WorkflowStep, WorkflowStateEntity> stateMap = workflowStateRepository.findByProjectId(projectId)
                .stream()
                .collect(Collectors.toMap(WorkflowStateEntity::getStep, Function.identity(), (a, b) -> b));
        return Arrays.stream(WorkflowStep.values())
                .sorted(Comparator.comparingInt(WorkflowStep::getOrder))
                .map(step -> {
                    WorkflowStateEntity e = stateMap.get(step);
                    StepStatus status = e == null ? StepStatus.NOT_STARTED : e.getStatus();
                    Map<String, Object> m = new HashMap<>();
                    m.put("step", step.name());
                    m.put("stepName", step.getDisplayName());
                    m.put("status", status == null ? null : status.name());
                    m.put("statusName", status == null ? "未开始" : status.getDisplayName());
                    m.put("statusClass", statusClass(status));
                    return m;
                })
                .toList();
    }

    private String statusClass(StepStatus status) {
        if (status == null) return "bg-secondary";
        return switch (status) {
            case CONFIRMED -> "bg-success";
            case GENERATED, PARTIALLY_DONE -> "bg-warning text-dark";
            case GENERATING -> "bg-info";
            default -> "bg-secondary";
        };
    }

    private List<Map<String, Object>> usageStatsOf(Long projectId) {
        return aiUsageStatRepository.findByProjectIdOrderByTotalDurationMsDesc(projectId)
                .stream()
                .map(this::usageDto)
                .toList();
    }

    private Map<String, Object> usageDto(AiUsageStatEntity s) {
        Map<String, Object> m = new HashMap<>();
        m.put("modelId", s.getModelId());
        m.put("providerName", s.getProviderName());
        m.put("totalDurationMs", s.getTotalDurationMs());
        m.put("formattedDuration", s.getFormattedDuration());
        return m;
    }

    private Map<String, Object> toDto(ProjectEntity p,
                                      long[] stats,
                                      List<Map<String, Object>> workflowStates,
                                      List<Map<String, Object>> usageStats) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.getId());
        m.put("title", p.getTitle());
        m.put("description", p.getDescription());
        m.put("author", p.getAuthor());
        m.put("genreCode", p.getGenre() == null ? null : p.getGenre().name());
        m.put("genre", p.getGenre() == null ? null : p.getGenre().getDisplayName());
        m.put("statusCode", p.getStatus() == null ? null : p.getStatus().name());
        m.put("status", p.getStatus() == null ? null : p.getStatus().getDisplayName());
        m.put("currentStepCode", p.getCurrentStep() == null ? null : p.getCurrentStep().name());
        m.put("currentStep", p.getCurrentStep() == null ? null : p.getCurrentStep().getDisplayName());
        m.put("updatedAt", p.getUpdatedAt() == null ? null : p.getUpdatedAt().toString());
        m.put("createdAt", p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
        m.put("chapterCount", stats == null ? 0 : stats[0]);
        m.put("wordCount", stats == null ? 0 : stats[1]);
        m.put("wordCountText", formatWordCount(stats == null ? 0 : stats[1]));
        m.put("totalChapters", p.getTotalChapters());
        m.put("chaptersPerVolume", p.getChaptersPerVolume());
        m.put("autoRunStatus", p.getAutoRunStatus() == null ? null : p.getAutoRunStatus().name());
        if (workflowStates != null) m.put("workflowStates", workflowStates);
        if (usageStats != null) m.put("usageStats", usageStats);
        return m;
    }

    /** 超过一万按「x.x万」显示 */
    private String formatWordCount(long wordCount) {
        if (wordCount >= 10000) {
            double wan = wordCount / 10000.0;
            if (wordCount % 10000 == 0) {
                return (long) wan + "万";
            }
            return String.format("%.1f万", wan);
        }
        return String.valueOf(wordCount);
    }
}
