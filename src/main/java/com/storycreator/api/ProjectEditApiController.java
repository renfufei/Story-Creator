package com.storycreator.api;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.ModelType;
import com.storycreator.core.domain.ProjectStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.StepGuidanceEntity;
import com.storycreator.persistence.entity.StepModelConfigEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import com.storycreator.persistence.repository.AiUsageStatRepository;
import com.storycreator.persistence.repository.AutoRunStepConfigRepository;
import com.storycreator.persistence.repository.ChapterOutlineRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.CharacterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.ProofreadingReportRepository;
import com.storycreator.persistence.repository.StepGuidanceRepository;
import com.storycreator.persistence.repository.StepModelConfigRepository;
import com.storycreator.persistence.repository.StoryOutlineRepository;
import com.storycreator.persistence.repository.VolumeOutlineRepository;
import com.storycreator.persistence.repository.WorkflowStateRepository;
import com.storycreator.persistence.repository.WorldSettingRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目「新建 / 编辑 / 删除」的 JSON API（供 static/pages/project-form.html 使用）。
 *
 * <p>校验失败返回 400 + {@code {"message": "..."}}，前端 {@code SC.api} 会直接把 message toast 出来。
 */
@RestController
@RequestMapping("/api")
public class ProjectEditApiController {

    public record ProjectFormRequest(
            String title,
            String genre,
            String description,
            String author,
            Integer totalChapters,
            Integer chapterWordCount,
            Integer chapterWordCountMin,
            Integer chapterWordCountMax,
            Integer characterCount,
            Integer chaptersPerVolume,
            Double recurringCharacterRate,
            Double tempCharacterRate,
            Long defaultModelConfigId,
            Boolean autoMode,
            String projectStatus,
            Map<String, String> stepGuidances,
            Map<String, Object> stepModelConfigs
    ) {}

    private final ProjectRepository projectRepository;
    private final AiModelConfigRepository modelConfigRepository;
    private final StepGuidanceRepository stepGuidanceRepository;
    private final StepModelConfigRepository stepModelConfigRepository;
    private final WorkflowStateRepository workflowStateRepository;
    private final ChapterRepository chapterRepository;
    private final CharacterRepository characterRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final StoryOutlineRepository storyOutlineRepository;
    private final VolumeOutlineRepository volumeOutlineRepository;
    private final ProofreadingReportRepository proofreadingReportRepository;
    private final AiUsageStatRepository aiUsageStatRepository;
    private final AutoRunStepConfigRepository autoRunStepConfigRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final GlobalSettingService globalSettingService;

    public ProjectEditApiController(ProjectRepository projectRepository,
                                    AiModelConfigRepository modelConfigRepository,
                                    StepGuidanceRepository stepGuidanceRepository,
                                    StepModelConfigRepository stepModelConfigRepository,
                                    WorkflowStateRepository workflowStateRepository,
                                    ChapterRepository chapterRepository,
                                    CharacterRepository characterRepository,
                                    ChapterOutlineRepository chapterOutlineRepository,
                                    StoryOutlineRepository storyOutlineRepository,
                                    VolumeOutlineRepository volumeOutlineRepository,
                                    ProofreadingReportRepository proofreadingReportRepository,
                                    AiUsageStatRepository aiUsageStatRepository,
                                    AutoRunStepConfigRepository autoRunStepConfigRepository,
                                    WorldSettingRepository worldSettingRepository,
                                    GlobalSettingService globalSettingService) {
        this.projectRepository = projectRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.stepGuidanceRepository = stepGuidanceRepository;
        this.stepModelConfigRepository = stepModelConfigRepository;
        this.workflowStateRepository = workflowStateRepository;
        this.chapterRepository = chapterRepository;
        this.characterRepository = characterRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.storyOutlineRepository = storyOutlineRepository;
        this.volumeOutlineRepository = volumeOutlineRepository;
        this.proofreadingReportRepository = proofreadingReportRepository;
        this.aiUsageStatRepository = aiUsageStatRepository;
        this.autoRunStepConfigRepository = autoRunStepConfigRepository;
        this.worldSettingRepository = worldSettingRepository;
        this.globalSettingService = globalSettingService;
    }

    /* ==================== 表单元数据 ==================== */

    /** 新建/编辑表单需要的下拉选项 */
    @GetMapping("/project-form/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new HashMap<>();
        m.put("genres", enumOptions(Genre.values()));
        m.put("projectStatuses", enumOptions(ProjectStatus.values()));
        m.put("workflowSteps", enumOptions(WorkflowStep.values()));
        List<Map<String, Object>> modelConfigs = new ArrayList<>();
        modelConfigRepository.findByActiveTrueAndModelType(ModelType.TEXT).forEach(mc -> {
            Map<String, Object> o = new HashMap<>();
            o.put("id", mc.getId());
            o.put("displayName", mc.getDisplayName());
            o.put("provider", mc.getProvider());
            o.put("modelId", mc.getModelId());
            modelConfigs.add(o);
        });
        m.put("modelConfigs", modelConfigs);
        return m;
    }

    private List<Map<String, Object>> enumOptions(Object[] values) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object v : values) {
            Map<String, Object> o = new HashMap<>();
            o.put("code", ((Enum<?>) v).name());
            try {
                o.put("displayName", String.valueOf(v.getClass().getMethod("getDisplayName").invoke(v)));
            } catch (ReflectiveOperationException e) {
                o.put("displayName", ((Enum<?>) v).name());
            }
            out.add(o);
        }
        return out;
    }

    /** 编辑时的表单初值（也用于「参考已有项目」） */
    @GetMapping("/project-form/{id}")
    public ResponseEntity<Map<String, Object>> formData(@PathVariable Long id) {
        ProjectEntity project = projectRepository.findById(id).orElse(null);
        if (project == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> data = new HashMap<>();
        data.put("id", project.getId());
        data.put("title", project.getTitle());
        data.put("genre", project.getGenre() == null ? "" : project.getGenre().name());
        data.put("description", project.getDescription());
        data.put("author", project.getAuthor());
        data.put("totalChapters", project.getTotalChapters());
        data.put("chapterWordCount", project.getChapterWordCount());
        data.put("chapterWordCountMin", project.getChapterWordCountMin());
        data.put("chapterWordCountMax", project.getChapterWordCountMax());
        data.put("characterCount", project.getCharacterCount());
        data.put("chaptersPerVolume", project.getChaptersPerVolume());
        data.put("recurringCharacterRate", project.getRecurringCharacterRate());
        data.put("tempCharacterRate", project.getTempCharacterRate());
        data.put("defaultModelConfigId", project.getDefaultModelConfigId());
        data.put("autoMode", project.isAutoMode());
        data.put("projectStatus", project.getStatus() == null ? null : project.getStatus().name());
        data.put("stepGuidances", loadGuidances(id));
        data.put("stepModelConfigs", loadStepModelConfigs(id));
        return ResponseEntity.ok(data);
    }

    private Map<String, String> loadGuidances(Long projectId) {
        Map<String, String> map = new HashMap<>();
        for (StepGuidanceEntity g : stepGuidanceRepository.findByProjectId(projectId)) {
            map.put(g.getStep().name(), g.getGuidance());
        }
        return map;
    }

    private Map<String, Long> loadStepModelConfigs(Long projectId) {
        Map<String, Long> map = new HashMap<>();
        for (StepModelConfigEntity c : stepModelConfigRepository.findByProjectId(projectId)) {
            map.put(c.getStep().name(), c.getModelConfigId());
        }
        return map;
    }

    /* ==================== 新建 / 更新 ==================== */

    @PostMapping("/projects")
    @Transactional
    public ResponseEntity<?> create(@RequestBody ProjectFormRequest req) {
        String error = validate(req);
        if (error != null) return bad(error);

        ProjectEntity project = new ProjectEntity();
        apply(project, req, false);
        project.setCurrentStep(WorkflowStep.WORLD_BUILDING);
        project = projectRepository.save(project);
        saveStepGuidances(project.getId(), req.stepGuidances());
        saveStepModelConfigs(project.getId(), req.stepModelConfigs());

        Map<String, Object> body = new HashMap<>();
        body.put("id", project.getId());
        body.put("redirect", "/projects/" + project.getId() + "/workflow");
        return ResponseEntity.ok(body);
    }

    @PutMapping("/projects/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody ProjectFormRequest req) {
        String error = validate(req);
        if (error != null) return bad(error);

        ProjectEntity project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
        apply(project, req, true);
        projectRepository.save(project);
        saveStepGuidances(id, req.stepGuidances());
        saveStepModelConfigs(id, req.stepModelConfigs());

        Map<String, Object> body = new HashMap<>();
        body.put("id", id);
        body.put("redirect", "/projects/" + id);
        return ResponseEntity.ok(body);
    }

    private void apply(ProjectEntity p, ProjectFormRequest req, boolean allowStatus) {
        p.setTitle(req.title().trim());
        p.setGenre(Genre.valueOf(req.genre()));
        p.setDescription(req.description());
        String author = req.author();
        if (author == null || author.isBlank()) {
            author = globalSettingService.getDefaultAuthor();
        }
        p.setAuthor(author);
        p.setTotalChapters(req.totalChapters() != null && req.totalChapters() > 0 ? req.totalChapters() : 10);
        p.setChapterWordCount(req.chapterWordCount() != null && req.chapterWordCount() > 0 ? req.chapterWordCount() : 5000);
        p.setChapterWordCountMin(req.chapterWordCountMin() != null && req.chapterWordCountMin() > 0 ? req.chapterWordCountMin() : 4000);
        p.setChapterWordCountMax(req.chapterWordCountMax() != null && req.chapterWordCountMax() > 0 ? req.chapterWordCountMax() : 6000);
        p.setCharacterCount(req.characterCount() != null && req.characterCount() > 0 ? req.characterCount() : 5);
        p.setChaptersPerVolume(req.chaptersPerVolume() != null && req.chaptersPerVolume() > 0 ? req.chaptersPerVolume() : 10);
        p.setRecurringCharacterRate(req.recurringCharacterRate() != null && req.recurringCharacterRate() > 0 ? req.recurringCharacterRate() : 0.5);
        p.setTempCharacterRate(req.tempCharacterRate() != null && req.tempCharacterRate() > 0 ? req.tempCharacterRate() : 3.0);
        p.setDefaultModelConfigId(req.defaultModelConfigId());
        p.setAutoMode(req.autoMode() != null ? req.autoMode() : true);
        if (allowStatus && req.projectStatus() != null && !req.projectStatus().isBlank()) {
            p.setStatus(ProjectStatus.valueOf(req.projectStatus()));
        }
    }

    private String validate(ProjectFormRequest req) {
        if (req == null) return "请求内容为空";
        if (req.title() == null || req.title().isBlank()) return "标题不能为空";
        if (req.genre() == null || req.genre().isBlank()) return "请选择题材";
        try {
            Genre.valueOf(req.genre());
        } catch (IllegalArgumentException e) {
            return "未知的题材类型：" + req.genre();
        }
        return null;
    }

    private ResponseEntity<Map<String, String>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    /* ==================== 删除 ==================== */

    @DeleteMapping("/projects/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        ProjectEntity project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
        if (project.getStatus() != ProjectStatus.ABANDONED) {
            throw new IllegalStateException("只能删除已废弃的项目");
        }
        workflowStateRepository.deleteByProjectId(id);
        chapterRepository.deleteByProjectId(id);
        characterRepository.deleteByProjectId(id);
        chapterOutlineRepository.deleteByProjectId(id);
        storyOutlineRepository.deleteByProjectId(id);
        volumeOutlineRepository.deleteByProjectId(id);
        proofreadingReportRepository.deleteByProjectId(id);
        stepGuidanceRepository.deleteByProjectId(id);
        stepModelConfigRepository.deleteByProjectId(id);
        aiUsageStatRepository.deleteByProjectId(id);
        autoRunStepConfigRepository.deleteByProjectId(id);
        worldSettingRepository.deleteByProjectId(id);
        projectRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("id", id, "redirect", "/"));
    }

    /* ==================== 步骤配置 ==================== */

    private void saveStepGuidances(Long projectId, Map<String, String> guidances) {
        if (guidances == null) return;
        for (Map.Entry<String, String> entry : guidances.entrySet()) {
            WorkflowStep step;
            try {
                step = WorkflowStep.valueOf(entry.getKey());
            } catch (IllegalArgumentException e) {
                continue;
            }
            String guidance = entry.getValue();
            if (guidance == null || guidance.isBlank()) {
                stepGuidanceRepository.findByProjectIdAndStep(projectId, step)
                        .ifPresent(stepGuidanceRepository::delete);
            } else {
                StepGuidanceEntity entity = stepGuidanceRepository
                        .findByProjectIdAndStep(projectId, step)
                        .orElseGet(() -> {
                            StepGuidanceEntity e = new StepGuidanceEntity();
                            e.setProjectId(projectId);
                            e.setStep(step);
                            return e;
                        });
                entity.setGuidance(guidance);
                stepGuidanceRepository.save(entity);
            }
        }
    }

    private void saveStepModelConfigs(Long projectId, Map<String, Object> configs) {
        if (configs == null) return;
        for (Map.Entry<String, Object> entry : configs.entrySet()) {
            WorkflowStep step;
            try {
                step = WorkflowStep.valueOf(entry.getKey());
            } catch (IllegalArgumentException e) {
                continue;
            }
            Long modelConfigId = toLong(entry.getValue());
            if (modelConfigId == null || modelConfigId == 0) {
                stepModelConfigRepository.findByProjectIdAndStep(projectId, step)
                        .ifPresent(stepModelConfigRepository::delete);
            } else {
                StepModelConfigEntity entity = stepModelConfigRepository
                        .findByProjectIdAndStep(projectId, step)
                        .orElseGet(() -> {
                            StepModelConfigEntity e = new StepModelConfigEntity();
                            e.setProjectId(projectId);
                            e.setStep(step);
                            return e;
                        });
                entity.setModelConfigId(modelConfigId);
                stepModelConfigRepository.save(entity);
            }
        }
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
