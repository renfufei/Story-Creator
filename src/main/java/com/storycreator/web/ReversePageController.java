package com.storycreator.web;

import com.storycreator.persistence.entity.TxtImportJobEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.TxtImportJobRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 逆向工程流程页（按项目 id 定位，可随时重新打开）。
 *
 * <p>把原 /import/txt 单页的第 3 步（逆向选项）与第 4 步（执行监控）拆为独立页面：
 * <ul>
 *   <li>{@code GET /projects/{projectId}/reverse/options}  —— 逆向选项页（静态页 + 引导 JSON）</li>
 *   <li>{@code GET /projects/{projectId}/reverse/progress} —— 执行监控页（SSE 实时流，重开可见计划与状态）</li>
 *   <li>{@code GET /projects/{projectId}/reverse/data}     —— 两页共用的引导数据（按项目解析最近一次导入 job）</li>
 * </ul>
 * 业务接口（reverse-plan / start-reverse / stream / stop 等）仍由 {@link TxtImportApiController} 提供。
 */
@Controller
public class ReversePageController {

    private final TxtImportJobRepository jobRepository;
    private final ProjectRepository projectRepository;
    private final AiModelConfigRepository aiModelConfigRepository;

    public ReversePageController(TxtImportJobRepository jobRepository,
                                 ProjectRepository projectRepository,
                                 AiModelConfigRepository aiModelConfigRepository) {
        this.jobRepository = jobRepository;
        this.projectRepository = projectRepository;
        this.aiModelConfigRepository = aiModelConfigRepository;
    }

    @GetMapping("/projects/{projectId}/reverse/options")
    public String optionsPage() {
        return "forward:/pages/reverse/options.html";
    }

    @GetMapping("/projects/{projectId}/reverse/progress")
    public String progressPage() {
        return "forward:/pages/reverse/progress.html";
    }

    /**
     * 引导数据：按项目 id 解析最近一次 TXT 导入 job，回传逆向选项需要的全部字段。
     * 项目不存在或没有关联导入任务时返回 404 + error。
     */
    @GetMapping("/projects/{projectId}/reverse/data")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> data(@PathVariable Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            return ResponseEntity.status(404).body(Map.of("error", "项目不存在: " + projectId));
        }
        TxtImportJobEntity job = jobRepository.findFirstByProjectIdOrderByIdDesc(projectId).orElse(null);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of("error",
                    "该项目没有关联的 TXT 导入任务（仅通过 TXT 逆向工程创建的项目才有）"));
        }
        Map<String, Object> m = new HashMap<>();
        m.put("jobId", job.getId());
        m.put("status", job.getStatus());
        m.put("progressNote", job.getProgressNote() == null ? "" : job.getProgressNote());
        m.put("errorMessage", job.getErrorMessage() == null ? "" : job.getErrorMessage());
        m.put("title", job.getTitle());
        m.put("genre", job.getGenre());
        m.put("author", job.getAuthor());
        m.put("chapterCount", job.getChapterCount());
        m.put("runWorldBuilding", job.isRunWorldBuilding());
        m.put("runCharacters", job.isRunCharacters());
        m.put("runOutline", job.isRunOutline());
        m.put("chaptersPerVolume", job.getChaptersPerVolume());
        m.put("modelConfigId", job.getModelConfigId());
        m.put("modelConfigs", aiModelConfigRepository.findAll().stream()
                .map(c -> Map.of(
                        "id", c.getId(),
                        "displayName", c.getDisplayName() == null ? "" : c.getDisplayName(),
                        "provider", c.getProvider() == null ? "" : c.getProvider()))
                .toList());
        return ResponseEntity.ok(m);
    }
}
