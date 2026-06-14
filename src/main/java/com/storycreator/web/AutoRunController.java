package com.storycreator.web;

import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.AutoRunStepConfigEntity;
import com.storycreator.persistence.repository.AutoRunStepConfigRepository;
import com.storycreator.workflow.autorun.AutoRunService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/projects/{projectId}/auto-run")
public class AutoRunController {

    private final AutoRunService autoRunService;
    private final AutoRunStepConfigRepository autoRunStepConfigRepository;

    public AutoRunController(AutoRunService autoRunService,
                            AutoRunStepConfigRepository autoRunStepConfigRepository) {
        this.autoRunService = autoRunService;
        this.autoRunStepConfigRepository = autoRunStepConfigRepository;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> start(@PathVariable Long projectId) {
        try {
            autoRunService.startAutoRun(projectId);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stop(@PathVariable Long projectId) {
        autoRunService.stopAutoRun(projectId);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable Long projectId) {
        return ResponseEntity.ok(autoRunService.getStatus(projectId));
    }

    @PutMapping("/step-config")
    public ResponseEntity<Map<String, Object>> updateStepConfig(@PathVariable Long projectId,
                                                                 @RequestParam String step,
                                                                 @RequestParam boolean enabled) {
        WorkflowStep ws = WorkflowStep.valueOf(step);
        AutoRunStepConfigEntity config = autoRunStepConfigRepository.findByProjectIdAndStep(projectId, ws)
                .orElseGet(() -> new AutoRunStepConfigEntity(projectId, ws, enabled));
        config.setEnabled(enabled);
        autoRunStepConfigRepository.save(config);
        return ResponseEntity.ok(Map.of("status", "ok", "step", step, "enabled", enabled));
    }
}
