package com.storycreator.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.GuidanceLibraryEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.StepGuidanceEntity;
import com.storycreator.persistence.repository.GuidanceLibraryRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.StepGuidanceRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
    public String listPage() {
        return "forward:/pages/guidances.html";
    }

    /** 列表引导数据（静态页同步 XHR 读取） */
    @GetMapping("/data")
    @ResponseBody
    public Map<String, Object> data() {
        List<GuidanceLibraryEntity> items = guidanceLibraryRepository.findAllByOrderByUpdatedAtDesc();

        List<Map<String, Object>> itemList = items.stream().map(e -> {
            String guidance = e.getGuidance() != null ? e.getGuidance() : "";
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("name", e.getName());
            m.put("step", e.getStep().name());
            m.put("stepLabel", e.getStep().getDisplayName());
            m.put("guidance", guidance);
            m.put("preview", guidance.length() > 50 ? guidance.substring(0, 50) + "..." : guidance);
            m.put("updatedAt", e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null);
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", itemList);
        result.put("itemIds", items.stream().map(GuidanceLibraryEntity::getId).toList());
        result.put("steps", stepOptions());
        return result;
    }

    private static List<Map<String, Object>> stepOptions() {
        return Arrays.stream(WorkflowStep.values())
                .map(s -> Map.<String, Object>of("name", s.name(), "displayName", s.getDisplayName()))
                .toList();
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
    public String editPage(@PathVariable Long id) {
        guidanceLibraryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Guidance not found: " + id));
        return "forward:/pages/guidance-edit.html";
    }

    /** 编辑页引导数据 */
    @GetMapping("/{id}/edit-data")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> editData(@PathVariable Long id) {
        return guidanceLibraryRepository.findById(id)
                .map(e -> {
                    String guidance = e.getGuidance() != null ? e.getGuidance() : "";
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("name", e.getName());
                    m.put("step", e.getStep().name());
                    m.put("stepLabel", e.getStep().getDisplayName());
                    m.put("guidance", guidance);
                    m.put("steps", stepOptions());
                    return ResponseEntity.ok(m);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
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

    @PostMapping("/export")
    @ResponseBody
    public ResponseEntity<byte[]> exportGuidances(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        List<GuidanceLibraryEntity> entities = guidanceLibraryRepository.findAllById(ids);
        List<Map<String, String>> items = entities.stream().map(e -> Map.of(
                "name", e.getName(),
                "step", e.getStep().name(),
                "guidance", e.getGuidance() != null ? e.getGuidance() : ""
        )).toList();

        Map<String, Object> exportData = Map.of(
                "version", 1,
                "exportedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                "items", items
        );

        try {
            ObjectMapper mapper = new ObjectMapper();
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(exportData);
            String filename = "guidances-export-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/import")
    public String importGuidances(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return "redirect:/settings/guidances?err=empty";
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> data = mapper.readValue(file.getInputStream(), new TypeReference<>() {});
            Object itemsObj = data.get("items");
            if (!(itemsObj instanceof List<?> itemsList)) {
                return "redirect:/settings/guidances?err=invalid";
            }

            int count = 0;
            for (Object obj : itemsList) {
                if (obj instanceof Map<?, ?> itemMap) {
                    String name = (String) itemMap.get("name");
                    String stepStr = (String) itemMap.get("step");
                    String guidance = (String) itemMap.get("guidance");
                    if (name == null || stepStr == null) continue;

                    WorkflowStep step;
                    try {
                        step = WorkflowStep.valueOf(stepStr);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }

                    GuidanceLibraryEntity entity = new GuidanceLibraryEntity();
                    entity.setName(name);
                    entity.setStep(step);
                    entity.setGuidance(guidance != null ? guidance : "");
                    guidanceLibraryRepository.save(entity);
                    count++;
                }
            }
            return "redirect:/settings/guidances?imported=" + count;
        } catch (Exception e) {
            return "redirect:/settings/guidances?err=io";
        }
    }
}
