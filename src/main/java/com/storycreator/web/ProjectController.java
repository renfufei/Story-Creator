package com.storycreator.web;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.StepGuidanceEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import com.storycreator.persistence.repository.AiUsageStatRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.StepGuidanceRepository;
import com.storycreator.persistence.repository.WorkflowStateRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final WorkflowStateRepository workflowStateRepository;
    private final AiModelConfigRepository modelConfigRepository;
    private final AiUsageStatRepository aiUsageStatRepository;
    private final ChapterRepository chapterRepository;
    private final StepGuidanceRepository stepGuidanceRepository;

    public ProjectController(ProjectRepository projectRepository,
                           WorkflowStateRepository workflowStateRepository,
                           AiModelConfigRepository modelConfigRepository,
                           AiUsageStatRepository aiUsageStatRepository,
                           ChapterRepository chapterRepository,
                           StepGuidanceRepository stepGuidanceRepository) {
        this.projectRepository = projectRepository;
        this.workflowStateRepository = workflowStateRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.aiUsageStatRepository = aiUsageStatRepository;
        this.chapterRepository = chapterRepository;
        this.stepGuidanceRepository = stepGuidanceRepository;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("projects", projectRepository.findAllByOrderByUpdatedAtDesc());
        return "dashboard";
    }

    @GetMapping("/projects/new")
    public String newProject(Model model) {
        model.addAttribute("form", new ProjectForm());
        model.addAttribute("genres", Genre.values());
        model.addAttribute("modelConfigs", modelConfigRepository.findByActiveTrue());
        model.addAttribute("workflowSteps", WorkflowStep.values());
        model.addAttribute("allProjects", projectRepository.findAllByOrderByUpdatedAtDesc());
        return "project-form";
    }

    @PostMapping("/projects")
    public String createProject(@Valid @ModelAttribute("form") ProjectForm form,
                               BindingResult result, Model model) {
        if (result.hasErrors()) {
            model.addAttribute("genres", Genre.values());
            model.addAttribute("modelConfigs", modelConfigRepository.findByActiveTrue());
            model.addAttribute("workflowSteps", WorkflowStep.values());
            model.addAttribute("allProjects", projectRepository.findAllByOrderByUpdatedAtDesc());
            return "project-form";
        }

        ProjectEntity project = new ProjectEntity();
        project.setTitle(form.getTitle());
        project.setGenre(form.getGenre());
        project.setDescription(form.getDescription());
        project.setTotalChapters(form.getTotalChapters() > 0 ? form.getTotalChapters() : 10);
        project.setChapterWordCount(form.getChapterWordCount() > 0 ? form.getChapterWordCount() : 5000);
        project.setChapterWordCountMin(form.getChapterWordCountMin() > 0 ? form.getChapterWordCountMin() : 4000);
        project.setChapterWordCountMax(form.getChapterWordCountMax() > 0 ? form.getChapterWordCountMax() : 6000);
        project.setCharacterCount(form.getCharacterCount() > 0 ? form.getCharacterCount() : 5);
        project.setDefaultModelConfigId(form.getDefaultModelConfigId());
        project.setAutoMode(form.isAutoMode());
        project.setCurrentStep(WorkflowStep.WORLD_BUILDING);
        project = projectRepository.save(project);

        // Save step guidances
        saveStepGuidances(project.getId(), form.getStepGuidances());

        return "redirect:/projects/" + project.getId() + "/workflow";
    }

    @GetMapping("/projects/{id}")
    public String viewProject(@PathVariable Long id, Model model) {
        ProjectEntity project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
        model.addAttribute("project", project);
        model.addAttribute("workflowStates", workflowStateRepository.findByProjectId(id));
        model.addAttribute("usageStats", aiUsageStatRepository.findByProjectIdOrderByTotalDurationMsDesc(id));
        return "project-detail";
    }

    @GetMapping("/projects/{id}/read")
    public String readProject(@PathVariable Long id, Model model) {
        ProjectEntity project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(id);
        model.addAttribute("project", project);
        model.addAttribute("chapters", chapters);
        return "reader";
    }

    @GetMapping("/projects/{id}/edit")
    public String editProject(@PathVariable Long id, Model model) {
        ProjectEntity project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
        ProjectForm form = new ProjectForm();
        form.setTitle(project.getTitle());
        form.setGenre(project.getGenre());
        form.setDescription(project.getDescription());
        form.setTotalChapters(project.getTotalChapters());
        form.setChapterWordCount(project.getChapterWordCount());
        form.setChapterWordCountMin(project.getChapterWordCountMin());
        form.setChapterWordCountMax(project.getChapterWordCountMax());
        form.setCharacterCount(project.getCharacterCount());
        form.setDefaultModelConfigId(project.getDefaultModelConfigId());
        form.setAutoMode(project.isAutoMode());

        // Load step guidances
        List<StepGuidanceEntity> guidances = stepGuidanceRepository.findByProjectId(id);
        Map<String, String> guidanceMap = new HashMap<>();
        for (StepGuidanceEntity g : guidances) {
            guidanceMap.put(g.getStep().name(), g.getGuidance());
        }
        form.setStepGuidances(guidanceMap);

        model.addAttribute("form", form);
        model.addAttribute("projectId", id);
        model.addAttribute("genres", Genre.values());
        model.addAttribute("modelConfigs", modelConfigRepository.findByActiveTrue());
        model.addAttribute("workflowSteps", WorkflowStep.values());
        model.addAttribute("allProjects", projectRepository.findAllByOrderByUpdatedAtDesc());
        return "project-form";
    }

    @PostMapping("/projects/{id}")
    public String updateProject(@PathVariable Long id,
                               @Valid @ModelAttribute("form") ProjectForm form,
                               BindingResult result, Model model) {
        if (result.hasErrors()) {
            model.addAttribute("projectId", id);
            model.addAttribute("genres", Genre.values());
            model.addAttribute("modelConfigs", modelConfigRepository.findByActiveTrue());
            model.addAttribute("workflowSteps", WorkflowStep.values());
            model.addAttribute("allProjects", projectRepository.findAllByOrderByUpdatedAtDesc());
            return "project-form";
        }

        ProjectEntity project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
        project.setTitle(form.getTitle());
        project.setGenre(form.getGenre());
        project.setDescription(form.getDescription());
        project.setTotalChapters(form.getTotalChapters() > 0 ? form.getTotalChapters() : 10);
        project.setChapterWordCount(form.getChapterWordCount() > 0 ? form.getChapterWordCount() : 5000);
        project.setChapterWordCountMin(form.getChapterWordCountMin() > 0 ? form.getChapterWordCountMin() : 4000);
        project.setChapterWordCountMax(form.getChapterWordCountMax() > 0 ? form.getChapterWordCountMax() : 6000);
        project.setCharacterCount(form.getCharacterCount() > 0 ? form.getCharacterCount() : 5);
        project.setDefaultModelConfigId(form.getDefaultModelConfigId());
        project.setAutoMode(form.isAutoMode());
        projectRepository.save(project);

        // Save step guidances
        saveStepGuidances(id, form.getStepGuidances());

        return "redirect:/projects/" + id;
    }

    @GetMapping("/projects/{id}/form-data")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getProjectFormData(@PathVariable Long id) {
        ProjectEntity project = projectRepository.findById(id).orElse(null);
        if (project == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> data = new HashMap<>();
        data.put("title", project.getTitle());
        data.put("genre", project.getGenre() != null ? project.getGenre().name() : "");
        data.put("description", project.getDescription());
        data.put("totalChapters", project.getTotalChapters());
        data.put("chapterWordCount", project.getChapterWordCount());
        data.put("chapterWordCountMin", project.getChapterWordCountMin());
        data.put("chapterWordCountMax", project.getChapterWordCountMax());
        data.put("characterCount", project.getCharacterCount());
        data.put("defaultModelConfigId", project.getDefaultModelConfigId());
        data.put("autoMode", project.isAutoMode());

        // Load step guidances
        List<StepGuidanceEntity> guidances = stepGuidanceRepository.findByProjectId(id);
        Map<String, String> guidanceMap = new HashMap<>();
        for (StepGuidanceEntity g : guidances) {
            guidanceMap.put(g.getStep().name(), g.getGuidance());
        }
        data.put("stepGuidances", guidanceMap);

        return ResponseEntity.ok(data);
    }

    @PostMapping("/projects/{id}/delete")
    public String deleteProject(@PathVariable Long id) {
        projectRepository.deleteById(id);
        return "redirect:/";
    }

    private void saveStepGuidances(Long projectId, Map<String, String> guidances) {
        if (guidances == null) return;
        for (Map.Entry<String, String> entry : guidances.entrySet()) {
            String stepName = entry.getKey();
            String guidance = entry.getValue();
            WorkflowStep step;
            try {
                step = WorkflowStep.valueOf(stepName);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (guidance == null || guidance.isBlank()) {
                // Remove guidance if empty
                stepGuidanceRepository.findByProjectIdAndStep(projectId, step)
                        .ifPresent(entity -> stepGuidanceRepository.delete(entity));
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

    public static class ProjectForm {
        @NotBlank(message = "标题不能为空")
        private String title;

        @NotNull(message = "请选择题材")
        private Genre genre;

        private String description;

        private int totalChapters = 10;

        private int chapterWordCount = 5000;
        private int chapterWordCountMin = 4000;
        private int chapterWordCountMax = 6000;

        private int characterCount = 5;

        private Long defaultModelConfigId;

        private boolean autoMode = true;

        private Map<String, String> stepGuidances = new HashMap<>();

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public Genre getGenre() { return genre; }
        public void setGenre(Genre genre) { this.genre = genre; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public int getTotalChapters() { return totalChapters; }
        public void setTotalChapters(int totalChapters) { this.totalChapters = totalChapters; }

        public int getChapterWordCount() { return chapterWordCount; }
        public void setChapterWordCount(int chapterWordCount) { this.chapterWordCount = chapterWordCount; }

        public int getChapterWordCountMin() { return chapterWordCountMin; }
        public void setChapterWordCountMin(int chapterWordCountMin) { this.chapterWordCountMin = chapterWordCountMin; }

        public int getChapterWordCountMax() { return chapterWordCountMax; }
        public void setChapterWordCountMax(int chapterWordCountMax) { this.chapterWordCountMax = chapterWordCountMax; }

        public int getCharacterCount() { return characterCount; }
        public void setCharacterCount(int characterCount) { this.characterCount = characterCount; }

        public Long getDefaultModelConfigId() { return defaultModelConfigId; }
        public void setDefaultModelConfigId(Long defaultModelConfigId) { this.defaultModelConfigId = defaultModelConfigId; }

        public boolean isAutoMode() { return autoMode; }
        public void setAutoMode(boolean autoMode) { this.autoMode = autoMode; }

        public Map<String, String> getStepGuidances() { return stepGuidances; }
        public void setStepGuidances(Map<String, String> stepGuidances) { this.stepGuidances = stepGuidances; }
    }
}
