package com.storycreator.web;

import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.GuidanceLibraryEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.StepGuidanceEntity;
import com.storycreator.persistence.repository.GuidanceLibraryRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.StepGuidanceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/settings/guidances")
public class GuidanceLibraryController {

    private final GuidanceLibraryRepository guidanceLibraryRepository;
    private final ProjectRepository projectRepository;
    private final StepGuidanceRepository stepGuidanceRepository;

    public GuidanceLibraryController(GuidanceLibraryRepository guidanceLibraryRepository,
                                     ProjectRepository projectRepository,
                                     StepGuidanceRepository stepGuidanceRepository) {
        this.guidanceLibraryRepository = guidanceLibraryRepository;
        this.projectRepository = projectRepository;
        this.stepGuidanceRepository = stepGuidanceRepository;
    }

    @GetMapping
    public String listPage(Model model) {
        List<GuidanceLibraryEntity> items = guidanceLibraryRepository.findAllByOrderByUpdatedAtDesc();
        model.addAttribute("items", items);
        model.addAttribute("steps", WorkflowStep.values());
        return "guidances";
    }

    @PostMapping
    public String create(@RequestParam String name,
                         @RequestParam WorkflowStep step,
                         @RequestParam(defaultValue = "") String guidance) {
        GuidanceLibraryEntity entity = new GuidanceLibraryEntity();
        entity.setName(name);
        entity.setStep(step);
        entity.setGuidance(guidance);
        guidanceLibraryRepository.save(entity);
        return "redirect:/settings/guidances";
    }

    @GetMapping("/{id}/edit")
    public String editPage(@PathVariable Long id, Model model) {
        GuidanceLibraryEntity entity = guidanceLibraryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Guidance not found: " + id));
        model.addAttribute("item", entity);
        model.addAttribute("steps", WorkflowStep.values());
        return "guidance-edit";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam WorkflowStep step,
                         @RequestParam(defaultValue = "") String guidance) {
        GuidanceLibraryEntity entity = guidanceLibraryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Guidance not found: " + id));
        entity.setName(name);
        entity.setStep(step);
        entity.setGuidance(guidance);
        guidanceLibraryRepository.save(entity);
        return "redirect:/settings/guidances";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id) {
        guidanceLibraryRepository.deleteById(id);
        return "redirect:/settings/guidances";
    }

    @PostMapping("/save-from-project")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveFromProject(@RequestParam Long projectId,
                                                                @RequestParam WorkflowStep step) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        StepGuidanceEntity stepGuidance = stepGuidanceRepository.findByProjectIdAndStep(projectId, step)
                .orElse(null);
        if (stepGuidance == null || stepGuidance.getGuidance() == null || stepGuidance.getGuidance().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "当前步骤没有创作指导内容"));
        }

        String defaultName = project.getTitle() + "-" + step.getDisplayName() + "-创作指导";
        GuidanceLibraryEntity entity = new GuidanceLibraryEntity();
        entity.setName(defaultName);
        entity.setStep(step);
        entity.setGuidance(stepGuidance.getGuidance());
        guidanceLibraryRepository.save(entity);

        return ResponseEntity.ok(Map.of("success", true, "name", defaultName));
    }

    @GetMapping("/list-json")
    @ResponseBody
    public List<Map<String, Object>> listJson(@RequestParam(required = false) WorkflowStep step) {
        List<GuidanceLibraryEntity> items;
        if (step != null) {
            items = guidanceLibraryRepository.findByStepOrderByUpdatedAtDesc(step);
        } else {
            items = guidanceLibraryRepository.findAllByOrderByUpdatedAtDesc();
        }
        return items.stream().map(item -> Map.<String, Object>of(
                "id", item.getId(),
                "name", item.getName(),
                "step", item.getStep().name(),
                "stepLabel", item.getStep().getDisplayName(),
                "guidance", item.getGuidance() != null ? item.getGuidance() : ""
        )).toList();
    }
}
