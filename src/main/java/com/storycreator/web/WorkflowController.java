package com.storycreator.web;

import com.storycreator.core.domain.CharacterStateDimension;
import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.service.CharacterStateDimensionService;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import com.storycreator.persistence.entity.AutoRunStepConfigEntity;
import com.storycreator.persistence.repository.AutoRunStepConfigRepository;
import com.storycreator.workflow.background.BackgroundGenerationService;
import com.storycreator.workflow.engine.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Controller
@RequestMapping("/projects/{projectId}")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);
    private final WorkflowEngine workflowEngine;
    private final ProjectRepository projectRepository;
    private final WorkflowStateRepository workflowStateRepository;
    private final ChapterRepository chapterRepository;
    private final CharacterRepository characterRepository;
    private final StoryOutlineRepository storyOutlineRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final VolumeOutlineRepository volumeOutlineRepository;
    private final AiModelConfigRepository modelConfigRepository;
    private final StepGuidanceRepository stepGuidanceRepository;
    private final ProofreadingReportRepository proofreadingReportRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final GlobalSettingService globalSettingService;
    private final BackgroundGenerationService backgroundGenerationService;
    private final AutoRunStepConfigRepository autoRunStepConfigRepository;
    private final CharacterStateDimensionService characterStateDimensionService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public WorkflowController(WorkflowEngine workflowEngine,
                             ProjectRepository projectRepository,
                             WorkflowStateRepository workflowStateRepository,
                             ChapterRepository chapterRepository,
                             CharacterRepository characterRepository,
                             StoryOutlineRepository storyOutlineRepository,
                             ChapterOutlineRepository chapterOutlineRepository,
                             VolumeOutlineRepository volumeOutlineRepository,
                             AiModelConfigRepository modelConfigRepository,
                             StepGuidanceRepository stepGuidanceRepository,
                             ProofreadingReportRepository proofreadingReportRepository,
                             WorldSettingRepository worldSettingRepository,
                             GlobalSettingService globalSettingService,
                             BackgroundGenerationService backgroundGenerationService,
                             AutoRunStepConfigRepository autoRunStepConfigRepository,
                             CharacterStateDimensionService characterStateDimensionService) {
        this.workflowEngine = workflowEngine;
        this.projectRepository = projectRepository;
        this.workflowStateRepository = workflowStateRepository;
        this.chapterRepository = chapterRepository;
        this.characterRepository = characterRepository;
        this.storyOutlineRepository = storyOutlineRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.volumeOutlineRepository = volumeOutlineRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.stepGuidanceRepository = stepGuidanceRepository;
        this.proofreadingReportRepository = proofreadingReportRepository;
        this.worldSettingRepository = worldSettingRepository;
        this.globalSettingService = globalSettingService;
        this.backgroundGenerationService = backgroundGenerationService;
        this.autoRunStepConfigRepository = autoRunStepConfigRepository;
        this.characterStateDimensionService = characterStateDimensionService;
    }

    @GetMapping("/workflow")
    public String workflow(@PathVariable Long projectId,
                          @RequestParam(required = false) WorkflowStep step,
                          Model model) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        List<WorkflowStateEntity> states = workflowStateRepository.findByProjectId(projectId);
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);

        // Allow viewing any step, default to project's current step
        WorkflowStep viewStep = (step != null) ? step : project.getCurrentStep();

        model.addAttribute("project", project);
        model.addAttribute("steps", WorkflowStep.values());
        model.addAttribute("states", states);
        model.addAttribute("chapters", chapters);
        model.addAttribute("viewStep", viewStep);
        model.addAttribute("totalChapters", project.getTotalChapters());

        // Get content for viewed step (skip for chapter-level steps, content loaded via AJAX)
        if (viewStep != WorkflowStep.CHAPTER_WRITING && viewStep != WorkflowStep.POLISHING && viewStep != WorkflowStep.PROOFREADING) {
            workflowStateRepository.findByProjectIdAndStep(projectId, viewStep)
                    .ifPresent(state -> model.addAttribute("currentContent", state.getEffectiveContent()));
        }

        // Pass step confirmed status
        boolean stepConfirmed = workflowStateRepository.findByProjectIdAndStep(projectId, viewStep)
                .map(s -> s.getStatus() == StepStatus.CONFIRMED)
                .orElse(false);
        model.addAttribute("stepConfirmed", stepConfirmed);

        // Model configs for step-level selection
        model.addAttribute("modelConfigs", modelConfigRepository.findByActiveTrue());

        // Viewed step's model config id
        workflowStateRepository.findByProjectIdAndStep(projectId, viewStep)
                .ifPresent(state -> model.addAttribute("stepModelConfigId", state.getModelConfigId()));

        // Load step guidance
        String stepGuidance = stepGuidanceRepository.findByProjectIdAndStep(projectId, viewStep)
                .map(StepGuidanceEntity::getGuidance)
                .orElse("");
        model.addAttribute("stepGuidance", stepGuidance);

        // Auto-run step configs: ensure rows exist, pass to model
        if (project.isAutoMode()) {
            List<AutoRunStepConfigEntity> configs = autoRunStepConfigRepository.findByProjectId(projectId);
            Set<WorkflowStep> existingSteps = new HashSet<>();
            for (AutoRunStepConfigEntity c : configs) {
                existingSteps.add(c.getStep());
            }
            for (WorkflowStep ws : WorkflowStep.values()) {
                if (!existingSteps.contains(ws)) {
                    boolean defaultEnabled = (ws != WorkflowStep.PROOFREADING);
                    AutoRunStepConfigEntity newConfig = new AutoRunStepConfigEntity(projectId, ws, defaultEnabled);
                    autoRunStepConfigRepository.save(newConfig);
                    configs.add(newConfig);
                }
            }
            Map<String, Boolean> stepConfigMap = new LinkedHashMap<>();
            for (WorkflowStep ws : WorkflowStep.values()) {
                for (AutoRunStepConfigEntity c : configs) {
                    if (c.getStep() == ws) {
                        stepConfigMap.put(ws.name(), c.isEnabled());
                        break;
                    }
                }
            }
            model.addAttribute("autoRunStepConfigs", stepConfigMap);
        } else {
            model.addAttribute("autoRunStepConfigs", new LinkedHashMap<>());
        }

        // Character state dimension configs
        var dimEntities = characterStateDimensionService.ensureAndGet(projectId);
        List<Map<String, Object>> dimConfigs = new ArrayList<>();
        for (var dimEntity : dimEntities) {
            Map<String, Object> dimMap = new LinkedHashMap<>();
            dimMap.put("key", dimEntity.getDimKey().name());
            dimMap.put("displayName", dimEntity.getDimKey().getDisplayName());
            dimMap.put("enabled", dimEntity.isEnabled());
            dimMap.put("defaultEnabled", dimEntity.getDimKey().isDefaultEnabled());
            dimConfigs.add(dimMap);
        }
        model.addAttribute("characterStateDimConfigs", dimConfigs);

        return "workflow";
    }

    @PostMapping("/step-model")
    public String setStepModel(@PathVariable Long projectId,
                              @RequestParam WorkflowStep step,
                              @RequestParam(required = false) Long modelConfigId) {
        WorkflowStateEntity state = workflowStateRepository
                .findByProjectIdAndStep(projectId, step)
                .orElseGet(() -> {
                    WorkflowStateEntity s = new WorkflowStateEntity();
                    s.setProjectId(projectId);
                    s.setStep(step);
                    s.setStatus(StepStatus.NOT_STARTED);
                    return s;
                });
        state.setModelConfigId(modelConfigId);
        workflowStateRepository.save(state);
        return "redirect:/projects/" + projectId + "/workflow";
    }

    @PostMapping("/step-guidance")
    @ResponseBody
    public ResponseEntity<Map<String, String>> saveStepGuidance(@PathVariable Long projectId,
                                                                 @RequestParam WorkflowStep step,
                                                                 @RequestParam String guidance) {
        StepGuidanceEntity entity = stepGuidanceRepository.findByProjectIdAndStep(projectId, step)
                .orElseGet(() -> {
                    StepGuidanceEntity sg = new StepGuidanceEntity();
                    sg.setProjectId(projectId);
                    sg.setStep(step);
                    return sg;
                });
        entity.setGuidance(guidance);
        stepGuidanceRepository.save(entity);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter generate(@PathVariable Long projectId,
                               @RequestParam WorkflowStep step,
                               @RequestParam(defaultValue = "0") int chapter) {
        // Multi-substep flows need timeout proportional to number of AI calls
        long timeoutMs;
        if (step == WorkflowStep.OUTLINE_GENERATION) {
            // volumes + chapters + summary: each call ~aiTimeout seconds
            int totalCh = projectRepository.findById(projectId).map(ProjectEntity::getTotalChapters).orElse(30);
            int volumeCount = (totalCh + 9) / 10;
            int totalCalls = volumeCount + totalCh + totalCh + 1; // volumes + chapters + refine + summary
            timeoutMs = (long) totalCalls * globalSettingService.getAiTimeoutSeconds() * 1000L;
        } else if (step == WorkflowStep.CHARACTER_DESIGN || step == WorkflowStep.PROOFREADING) {
            timeoutMs = 120 * 60 * 1000L; // 2 hours
        } else {
            timeoutMs = globalSettingService.getAiTimeoutSeconds() * 1000L;
        }
        log.info("[P{}] SSE generate request step={} chapter={} timeout={}s", projectId, step, chapter, timeoutMs / 1000);
        SseEmitter emitter = new SseEmitter(timeoutMs);

        // Track whether generation completed successfully
        final boolean[] completed = {false};

        // On timeout or client disconnect, reset stuck GENERATING status (unless bg task is active)
        Runnable resetStatus = () -> {
            if (!completed[0] && !backgroundGenerationService.isActive(projectId, step, chapter)) {
                log.warn("[P{}] SSE disconnected/timeout step={} chapter={} (generation incomplete)", projectId, step, chapter);
                workflowEngine.resetGeneratingStatus(projectId, step, chapter);
            }
        };
        emitter.onTimeout(resetStatus);
        emitter.onCompletion(resetStatus);

        executor.submit(() -> {
            StringBuilder fullContent = new StringBuilder();
            try {
                workflowEngine.generate(projectId, step, chapter)
                        .doOnNext(token -> {
                            try {
                                if (step == WorkflowStep.CHARACTER_DESIGN && token.startsWith("[[CHAR:")) {
                                    emitter.send(SseEmitter.event()
                                            .name("char-section")
                                            .data(token));
                                } else if (step == WorkflowStep.OUTLINE_GENERATION && token.startsWith("[[SECTION:")) {
                                    emitter.send(SseEmitter.event()
                                            .name("outline-section")
                                            .data(token));
                                } else if (step == WorkflowStep.PROOFREADING && token.startsWith("[[PROOFREAD:")) {
                                    emitter.send(SseEmitter.event()
                                            .name("proofread-section")
                                            .data(token));
                                } else if (step == WorkflowStep.PROOFREADING && token.startsWith("[[PROOFREAD_FIX:")) {
                                    emitter.send(SseEmitter.event()
                                            .name("proofread-fix-section")
                                            .data(token));
                                } else {
                                    fullContent.append(token);
                                    emitter.send(SseEmitter.event()
                                            .name("token")
                                            .data(token));
                                }
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                completed[0] = true;
                                workflowEngine.saveGeneratedContent(projectId, step, fullContent.toString(), chapter);
                                emitter.send(SseEmitter.event().name("done").data("complete"));
                                emitter.complete();
                                // After SSE is closed, async generate character states (no concurrency issue in manual mode)
                                if (step == WorkflowStep.CHAPTER_WRITING && chapter > 0) {
                                    final int chNum = chapter;
                                    Thread.startVirtualThread(() -> {
                                        try {
                                            workflowEngine.generateCharacterStates(projectId, chNum);
                                        } catch (Exception e) {
                                            log.warn("[P{}] Failed to generate character states for ch{}: {}", projectId, chNum, e.getMessage());
                                        }
                                    });
                                }
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnError(error -> {
                            log.error("Generation error", error);
                            workflowEngine.resetGeneratingStatus(projectId, step, chapter);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(error.getMessage()));
                            } catch (IOException e) {
                                // ignore
                            }
                            emitter.completeWithError(error);
                        })
                        .blockLast();
            } catch (Exception e) {
                log.error("Stream execution error", e);
                workflowEngine.resetGeneratingStatus(projectId, step, chapter);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (IOException ex) {
                    // ignore
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @PostMapping("/confirm")
    public String confirmStep(@PathVariable Long projectId, @RequestParam WorkflowStep step) {
        workflowEngine.confirmStep(projectId, step);
        return "redirect:/projects/" + projectId + "/workflow";
    }

    @PostMapping("/confirm-ajax")
    @ResponseBody
    public ResponseEntity<Map<String, String>> confirmStepAjax(
            @PathVariable Long projectId, @RequestParam WorkflowStep step) {
        workflowEngine.confirmStep(projectId, step);
        WorkflowStep nextStep = step.next();
        Map<String, String> result = new HashMap<>();
        result.put("status", "ok");
        result.put("nextStep", nextStep != null ? nextStep.name() : "COMPLETED");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/confirm-only-ajax")
    @ResponseBody
    public ResponseEntity<Map<String, String>> confirmStepOnlyAjax(
            @PathVariable Long projectId, @RequestParam WorkflowStep step) {
        workflowEngine.confirmStepOnly(projectId, step);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/advance-ajax")
    @ResponseBody
    public ResponseEntity<Map<String, String>> advanceStepAjax(
            @PathVariable Long projectId, @RequestParam WorkflowStep step) {
        workflowEngine.advanceStep(projectId, step);
        WorkflowStep nextStep = step.next();
        Map<String, String> result = new HashMap<>();
        result.put("status", "ok");
        result.put("nextStep", nextStep != null ? nextStep.name() : "COMPLETED");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/save-edit")
    public String saveEdit(@PathVariable Long projectId,
                          @RequestParam WorkflowStep step,
                          @RequestParam String content) {
        workflowEngine.saveUserEdit(projectId, step, content);
        return "redirect:/projects/" + projectId + "/workflow";
    }

    @PostMapping("/chapters/{chapterNumber}/save")
    public String saveChapter(@PathVariable Long projectId,
                             @PathVariable int chapterNumber,
                             @RequestParam String content) {
        ChapterEntity chapter = chapterRepository
                .findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseGet(() -> {
                    ChapterEntity c = new ChapterEntity();
                    c.setProjectId(projectId);
                    c.setChapterNumber(chapterNumber);
                    return c;
                });
        chapter.setContent(content);
        chapter.setWordCount(content.length());
        chapter.setStatus(StepStatus.CONFIRMED);
        chapterRepository.save(chapter);
        return "redirect:/projects/" + projectId + "/workflow";
    }

    @PostMapping("/chapters/{chapterNumber}/save-ajax")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveChapterAjax(@PathVariable Long projectId,
                                                                @PathVariable int chapterNumber,
                                                                @RequestParam String content) {
        ChapterEntity chapter = chapterRepository
                .findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseGet(() -> {
                    ChapterEntity c = new ChapterEntity();
                    c.setProjectId(projectId);
                    c.setChapterNumber(chapterNumber);
                    return c;
                });
        chapter.setContent(content);
        chapter.setWordCount(content.length());
        chapter.setStatus(StepStatus.CONFIRMED);
        chapterRepository.save(chapter);
        return ResponseEntity.ok(Map.of("status", "ok", "wordCount", content.length()));
    }

    /**
     * JSON endpoint to get chapter list (for AJAX refresh)
     */
    @GetMapping("/chapters/list")
    @ResponseBody
    public List<Map<String, Object>> getChapterList(@PathVariable Long projectId) {
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        return chapters.stream().map(ch -> {
            Map<String, Object> map = new HashMap<>();
            map.put("chapterNumber", ch.getChapterNumber());
            map.put("title", ch.getTitle());
            map.put("wordCount", ch.getWordCount());
            map.put("status", ch.getStatus().name());
            map.put("polishStatus", ch.getPolishStatus() != null ? ch.getPolishStatus().name() : "NOT_STARTED");
            map.put("polishNote", ch.getPolishNote());
            map.put("proofreadStatus", ch.getProofreadStatus() != null ? ch.getProofreadStatus().name() : "NOT_STARTED");
            map.put("proofreadFixStatus", ch.getProofreadFixStatus() != null ? ch.getProofreadFixStatus().name() : "NOT_STARTED");
            map.put("hasDraft", ch.getContentDraft() != null && !ch.getContentDraft().isBlank());
            return map;
        }).toList();
    }

    /**
     * JSON endpoint to get single chapter content (for preview)
     */
    @GetMapping("/chapters/{chapterNumber}/content")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getChapterContent(@PathVariable Long projectId,
                                                                  @PathVariable int chapterNumber) {
        return chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .map(ch -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("chapterNumber", ch.getChapterNumber());
                    map.put("title", ch.getTitle());
                    map.put("content", ch.getContent());
                    map.put("contentDraft", ch.getContentDraft());
                    map.put("wordCount", ch.getWordCount());
                    map.put("polishNote", ch.getPolishNote());
                    map.put("polishStatus", ch.getPolishStatus() != null ? ch.getPolishStatus().name() : "NOT_STARTED");
                    map.put("contentBeforeFix", ch.getContentBeforeFix());
                    map.put("proofreadFixStatus", ch.getProofreadFixStatus() != null ? ch.getProofreadFixStatus().name() : "NOT_STARTED");
                    return ResponseEntity.ok(map);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Save polish note for a chapter
     */
    @PostMapping("/chapters/{chapterNumber}/polish-note")
    @ResponseBody
    public ResponseEntity<Map<String, String>> savePolishNote(@PathVariable Long projectId,
                                                              @PathVariable int chapterNumber,
                                                              @RequestParam String note) {
        ChapterEntity chapter = chapterRepository
                .findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found"));
        chapter.setPolishNote(note);
        // Mark as needing re-polish if note is non-empty
        if (note != null && !note.isBlank()) {
            chapter.setPolishStatus(StepStatus.NOT_STARTED);
        }
        chapterRepository.save(chapter);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * SSE endpoint to run proofread fix on a single chapter
     */
    @GetMapping(value = "/proofread-fix/{chapterNumber}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter proofreadFix(@PathVariable Long projectId, @PathVariable int chapterNumber) {
        long timeoutMs = globalSettingService.getAiTimeoutSeconds() * 10 * 1000L;
        log.info("[P{}] Proofread fix request chapter={} timeout={}s", projectId, chapterNumber, timeoutMs / 1000);
        SseEmitter emitter = new SseEmitter(timeoutMs);

        executor.submit(() -> {
            try {
                workflowEngine.proofreadFixSingleChapter(projectId, chapterNumber)
                        .doOnNext(token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data("complete"));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnError(error -> {
                            log.error("Proofread fix error", error);
                            try {
                                emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                            } catch (IOException e) {
                                // ignore
                            }
                            emitter.completeWithError(error);
                        })
                        .blockLast();
            } catch (Exception e) {
                log.error("Proofread fix execution error", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (IOException ex) {
                    // ignore
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Get all characters for a project (sort_order > 0, i.e. individual cards)
     */
    @GetMapping("/characters")
    @ResponseBody
    public List<Map<String, Object>> getCharacters(@PathVariable Long projectId) {
        List<CharacterEntity> chars = characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        return chars.stream().map(ch -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", ch.getId());
            map.put("name", ch.getName());
            map.put("gender", ch.getGender());
            map.put("age", ch.getAge());
            map.put("role", ch.getRole());
            map.put("personality", ch.getPersonality());
            map.put("appearance", ch.getAppearance());
            map.put("background", ch.getBackground());
            map.put("motivation", ch.getMotivation());
            map.put("abilities", ch.getAbilities());
            map.put("relationships", ch.getRelationships());
            map.put("description", ch.getDescription());
            map.put("sortOrder", ch.getSortOrder());
            map.put("status", ch.getStatus() != null ? ch.getStatus() : "GENERATED");
            return map;
        }).toList();
    }

    /**
     * Get character overview (sort_order=0) for a project
     */
    @GetMapping("/characters/overview")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getCharacterOverview(@PathVariable Long projectId) {
        return characterRepository.findByProjectIdOrderBySortOrder(projectId).stream()
                .filter(c -> c.getSortOrder() == 0)
                .findFirst()
                .map(overview -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("content", overview.getContent());
                    return ResponseEntity.ok(map);
                })
                .orElse(ResponseEntity.ok(Map.of("content", "")));
    }

    /**
     * SSE endpoint to refine all character cards via AI
     */
    @GetMapping(value = "/characters/refine-all", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter refineAllCharacters(@PathVariable Long projectId) {
        long timeoutMs = 120 * 60 * 1000L; // 2 hours
        SseEmitter emitter = new SseEmitter(timeoutMs);

        executor.submit(() -> {
            try {
                workflowEngine.refineAllCharacters(projectId)
                        .doOnNext(token -> {
                            try {
                                if (token.startsWith("[[CHAR:REFINE:")) {
                                    emitter.send(SseEmitter.event()
                                            .name("char-refine")
                                            .data(token));
                                } else {
                                    emitter.send(SseEmitter.event()
                                            .name("token")
                                            .data(token));
                                }
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data("complete"));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnError(error -> {
                            log.error("Character refine error", error);
                            try {
                                emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                            } catch (IOException e) {
                                // ignore
                            }
                            emitter.completeWithError(error);
                        })
                        .blockLast();
            } catch (Exception e) {
                log.error("Character refine execution error", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (IOException ex) {
                    // ignore
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Update a single character's structured fields
     */
    @PostMapping("/characters/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, String>> updateCharacter(@PathVariable Long projectId,
                                                               @PathVariable Long id,
                                                               @RequestParam(required = false) String name,
                                                               @RequestParam(required = false) String gender,
                                                               @RequestParam(required = false) String age,
                                                               @RequestParam(required = false) String role,
                                                               @RequestParam(required = false) String personality,
                                                               @RequestParam(required = false) String appearance,
                                                               @RequestParam(required = false) String background,
                                                               @RequestParam(required = false) String motivation,
                                                               @RequestParam(required = false) String abilities,
                                                               @RequestParam(required = false) String relationships,
                                                               @RequestParam(required = false) String description) {
        CharacterEntity ch = characterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));
        if (name != null) ch.setName(name);
        if (gender != null) ch.setGender(gender);
        if (age != null) ch.setAge(age);
        if (role != null) ch.setRole(role);
        if (personality != null) ch.setPersonality(personality);
        if (appearance != null) ch.setAppearance(appearance);
        if (background != null) ch.setBackground(background);
        if (motivation != null) ch.setMotivation(motivation);
        if (abilities != null) ch.setAbilities(abilities);
        if (relationships != null) ch.setRelationships(relationships);
        if (description != null) ch.setDescription(description);
        characterRepository.save(ch);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Add a new character manually
     */
    @PostMapping("/characters/add")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addCharacter(@PathVariable Long projectId,
                                                             @RequestParam String name) {
        // Get max sort_order for this project
        List<CharacterEntity> existing = characterRepository.findByProjectIdOrderBySortOrder(projectId);
        int maxOrder = existing.stream().mapToInt(CharacterEntity::getSortOrder).max().orElse(0);

        CharacterEntity ch = new CharacterEntity();
        ch.setProjectId(projectId);
        ch.setName(name);
        ch.setStatus("PENDING");
        ch.setSortOrder(maxOrder + 1);
        characterRepository.save(ch);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("id", ch.getId());
        return ResponseEntity.ok(result);
    }

    /**
     * Delete a character
     */
    @PostMapping("/characters/{id}/delete")
    @ResponseBody
    public ResponseEntity<Map<String, String>> deleteCharacter(@PathVariable Long projectId,
                                                               @PathVariable Long id) {
        CharacterEntity character = characterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Character not found: " + id));
        if (!character.getProjectId().equals(projectId)) {
            return ResponseEntity.status(403).body(Map.of("status", "error", "message", "角色不属于该项目"));
        }
        characterRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Generate titles for all chapters based on content (called after polishing)
     */
    @GetMapping(value = "/chapters/generate-titles", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter generateChapterTitles(@PathVariable Long projectId) {
        SseEmitter emitter = new SseEmitter(120_000L);

        executor.submit(() -> {
            try {
                List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
                for (ChapterEntity ch : chapters) {
                    if (ch.getContent() == null || ch.getContent().isBlank()) continue;

                    String contentPreview = ch.getContent().length() > 1000
                            ? ch.getContent().substring(0, 1000) : ch.getContent();
                    String titlePrompt = "请为以下小说章节内容生成一个简短的章节标题，要求：4-12个字，不要带第X章前缀，只输出标题文字，不要标点符号。\n\n"
                            + contentPreview;

                    var resolved = workflowEngine.resolveModelForProject(projectId, com.storycreator.core.domain.WorkflowStep.CHAPTER_WRITING);
                    var request = com.storycreator.core.port.ai.AiRequest.builder()
                            .systemPrompt("你是一位小说编辑，擅长给章节起标题。要求每个标题控制在4-12个字，风格统一，长度尽量一致（建议6-8字）。只输出标题文字，不要任何额外内容。")
                            .userPrompt(titlePrompt)
                            .maxTokens(30)
                            .temperature(0.5)
                            .build();
                    if (resolved.modelId() != null) request.setModel(resolved.modelId());

                    StringBuilder title = new StringBuilder();
                    resolved.provider().streamText(request)
                            .doOnNext(title::append)
                            .blockLast();

                    String generatedTitle = title.toString().trim()
                            .replaceAll("[\"'\\n\\r]", "")
                            .replaceAll("[\u201c\u201d\u2018\u2019]", "");
                    if (generatedTitle.length() > 12) {
                        generatedTitle = generatedTitle.substring(0, 12);
                    }
                    ch.setTitle(generatedTitle);
                    chapterRepository.save(ch);

                    emitter.send(SseEmitter.event().name("title")
                            .data(ch.getChapterNumber() + "|" + generatedTitle));
                }
                emitter.send(SseEmitter.event().name("done").data("complete"));
                emitter.complete();
            } catch (Exception e) {
                log.error("Title generation error", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (IOException ex) { /* ignore */ }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Get workflow state status for a step (used for polling from other tabs)
     */
    @GetMapping("/workflow-state-status")
    @ResponseBody
    public ResponseEntity<Map<String, String>> getWorkflowStateStatus(@PathVariable Long projectId,
                                                                        @RequestParam WorkflowStep step) {
        String status = workflowStateRepository.findByProjectIdAndStep(projectId, step)
                .map(s -> s.getStatus().name())
                .orElse("NOT_STARTED");
        return ResponseEntity.ok(Map.of("status", status));
    }

    /**
     * Get all workflow states for the workflow state setting modal
     */
    @GetMapping("/workflow-states")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getWorkflowStates(@PathVariable Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        List<WorkflowStateEntity> states = workflowStateRepository.findByProjectId(projectId);

        Map<String, String> stepStatuses = new LinkedHashMap<>();
        Map<String, List<Map<String, String>>> allowedStatuses = new LinkedHashMap<>();
        for (WorkflowStep ws : WorkflowStep.values()) {
            String status = states.stream()
                    .filter(s -> s.getStep() == ws)
                    .map(s -> s.getStatus().name())
                    .findFirst()
                    .orElse("NOT_STARTED");
            stepStatuses.put(ws.name(), status);
            allowedStatuses.put(ws.name(), ws.getAllowedManualStatuses().stream()
                    .map(s -> Map.of("name", s.name(), "label", s.getDisplayName()))
                    .toList());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("currentStep", project.getCurrentStep().name());
        result.put("stepStatuses", stepStatuses);
        result.put("allowedStatuses", allowedStatuses);
        return ResponseEntity.ok(result);
    }

    /**
     * Save workflow states (set current step and per-step statuses)
     */
    @PostMapping("/workflow-states")
    @ResponseBody
    public ResponseEntity<Map<String, String>> saveWorkflowStates(@PathVariable Long projectId,
                                                                    @RequestBody Map<String, Object> payload) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        // Block changes while auto-run is active
        if (project.getAutoRunStatus() != null
                && (project.getAutoRunStatus().name().equals("RUNNING")
                    || project.getAutoRunStatus().name().equals("STOPPING"))) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "自动创作运行中，无法修改工作流状态"));
        }

        // Update current step
        String currentStepStr = (String) payload.get("currentStep");
        if (currentStepStr != null) {
            project.setCurrentStep(WorkflowStep.valueOf(currentStepStr));
            projectRepository.save(project);
        }

        // Update per-step statuses
        @SuppressWarnings("unchecked")
        Map<String, String> stepStatuses = (Map<String, String>) payload.get("stepStatuses");
        if (stepStatuses != null) {
            for (Map.Entry<String, String> entry : stepStatuses.entrySet()) {
                WorkflowStep ws = WorkflowStep.valueOf(entry.getKey());
                StepStatus status = StepStatus.valueOf(entry.getValue());

                // Validate status is allowed for this step
                if (!ws.getAllowedManualStatuses().contains(status)) {
                    return ResponseEntity.badRequest().body(Map.of("status", "error",
                            "message", ws.getDisplayName() + " 不允许设置为 " + status.getDisplayName()));
                }

                WorkflowStateEntity state = workflowStateRepository
                        .findByProjectIdAndStep(projectId, ws)
                        .orElse(null);

                if (status == StepStatus.NOT_STARTED) {
                    // If set to NOT_STARTED, delete the state row if exists
                    if (state != null) {
                        workflowStateRepository.delete(state);
                    }
                } else {
                    // Create or update
                    if (state == null) {
                        state = new WorkflowStateEntity();
                        state.setProjectId(projectId);
                        state.setStep(ws);
                        state.setGeneratedContent("[manually set]");
                    }
                    state.setStatus(status);
                    workflowStateRepository.save(state);
                }
            }
        }

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    // --- Outline structured data endpoints ---

    /**
     * Get structured outline data (volumes + chapters + summary)
     */
    @GetMapping("/outline/data")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getOutlineData(@PathVariable Long projectId) {
        Map<String, Object> result = new HashMap<>();

        // Story summary
        String storySummary = storyOutlineRepository.findByProjectId(projectId)
                .map(StoryOutlineEntity::getContent)
                .orElse("");
        result.put("storySummary", storySummary);

        // Volumes with their chapters
        List<VolumeOutlineEntity> volumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
        List<ChapterOutlineEntity> allChapters = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);

        List<Map<String, Object>> volumeList = new ArrayList<>();
        for (VolumeOutlineEntity vol : volumes) {
            Map<String, Object> volMap = new HashMap<>();
            volMap.put("volumeNumber", vol.getVolumeNumber());
            volMap.put("title", vol.getTitle());
            volMap.put("arcSummary", vol.getArcSummary());
            volMap.put("chapterStart", vol.getChapterStart());
            volMap.put("chapterEnd", vol.getChapterEnd());

            // Filter chapters for this volume
            List<Map<String, Object>> chapterList = allChapters.stream()
                    .filter(ch -> ch.getChapterNumber() >= vol.getChapterStart()
                            && ch.getChapterNumber() <= vol.getChapterEnd())
                    .map(ch -> {
                        Map<String, Object> chMap = new HashMap<>();
                        chMap.put("chapterNumber", ch.getChapterNumber());
                        chMap.put("title", ch.getTitle());
                        chMap.put("summary", ch.getSummary());
                        chMap.put("characterNames", ch.getCharacterNames());
                        chMap.put("status", ch.getStatus() != null ? ch.getStatus() : "COMPLETED");
                        return chMap;
                    })
                    .toList();
            volMap.put("chapters", chapterList);
            volumeList.add(volMap);
        }
        result.put("volumes", volumeList);

        // If no volumes but chapters exist (legacy data), return flat chapter list
        if (volumes.isEmpty() && !allChapters.isEmpty()) {
            List<Map<String, Object>> chapterList = allChapters.stream()
                    .map(ch -> {
                        Map<String, Object> chMap = new HashMap<>();
                        chMap.put("chapterNumber", ch.getChapterNumber());
                        chMap.put("title", ch.getTitle());
                        chMap.put("summary", ch.getSummary());
                        chMap.put("characterNames", ch.getCharacterNames());
                        chMap.put("status", ch.getStatus() != null ? ch.getStatus() : "COMPLETED");
                        return chMap;
                    })
                    .toList();
            result.put("legacyChapters", chapterList);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Edit a chapter outline
     */
    @PostMapping("/outline/edit-chapter")
    @ResponseBody
    public ResponseEntity<Map<String, String>> editChapterOutline(@PathVariable Long projectId,
                                                                    @RequestParam int chapterNumber,
                                                                    @RequestParam(required = false) String title,
                                                                    @RequestParam(required = false) String summary,
                                                                    @RequestParam(required = false) String characterNames) {
        ChapterOutlineEntity entity = chapterOutlineRepository
                .findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter outline not found: " + chapterNumber));
        if (title != null) entity.setTitle(title);
        if (summary != null) entity.setSummary(summary);
        if (characterNames != null) entity.setCharacterNames(characterNames);
        chapterOutlineRepository.save(entity);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Regenerate a single chapter outline via SSE
     */
    @GetMapping(value = "/outline/regenerate-chapter", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter regenerateChapterOutline(@PathVariable Long projectId,
                                               @RequestParam int chapterNumber) {
        long timeoutMs = globalSettingService.getAiTimeoutSeconds() * 1000L;
        SseEmitter emitter = new SseEmitter(timeoutMs);

        executor.submit(() -> {
            try {
                workflowEngine.regenerateChapterOutline(projectId, chapterNumber)
                        .doOnNext(token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data("complete"));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnError(error -> {
                            log.error("Regenerate chapter outline error", error);
                            try {
                                emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                            } catch (IOException e) {
                                // ignore
                            }
                            emitter.completeWithError(error);
                        })
                        .blockLast();
            } catch (Exception e) {
                log.error("Regenerate chapter outline execution error", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (IOException ex) {
                    // ignore
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Edit a volume arc
     */
    @PostMapping("/outline/edit-volume")
    @ResponseBody
    public ResponseEntity<Map<String, String>> editVolumeOutline(@PathVariable Long projectId,
                                                                   @RequestParam int volumeNumber,
                                                                   @RequestParam(required = false) String arcSummary) {
        VolumeOutlineEntity entity = volumeOutlineRepository
                .findByProjectIdAndVolumeNumber(projectId, volumeNumber)
                .orElseThrow(() -> new IllegalArgumentException("Volume outline not found: " + volumeNumber));
        if (arcSummary != null) entity.setArcSummary(arcSummary);
        volumeOutlineRepository.save(entity);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Edit story summary
     */
    @PostMapping("/outline/edit-summary")
    @ResponseBody
    public ResponseEntity<Map<String, String>> editStorySummary(@PathVariable Long projectId,
                                                                 @RequestParam String content) {
        StoryOutlineEntity outline = storyOutlineRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    StoryOutlineEntity o = new StoryOutlineEntity();
                    o.setProjectId(projectId);
                    return o;
                });
        outline.setContent(content);
        storyOutlineRepository.save(outline);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    // === Proofreading Endpoints ===

    /**
     * Get all proofreading reports for a project
     */
    @GetMapping("/proofreading/data")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getProofreadingData(@PathVariable Long projectId) {
        List<ProofreadingReportEntity> reports = proofreadingReportRepository.findByProjectIdOrderByChapterNumber(projectId);
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (ChapterEntity ch : chapters) {
            if (ch.getContent() == null || ch.getContent().isBlank()) continue;
            Map<String, Object> map = new HashMap<>();
            map.put("chapterNumber", ch.getChapterNumber());
            map.put("title", ch.getTitle());
            map.put("proofreadStatus", ch.getProofreadStatus() != null ? ch.getProofreadStatus().name() : "NOT_STARTED");
            map.put("proofreadFixStatus", ch.getProofreadFixStatus() != null ? ch.getProofreadFixStatus().name() : "NOT_STARTED");
            map.put("plotSummary", ch.getPlotSummary());

            // Find matching report
            reports.stream()
                    .filter(r -> r.getChapterNumber() == ch.getChapterNumber())
                    .findFirst()
                    .ifPresent(r -> {
                        map.put("characterIssues", r.getCharacterIssues());
                        map.put("consistencyIssues", r.getConsistencyIssues());
                        map.put("continuityIssues", r.getContinuityIssues());
                        map.put("foreshadowing", r.getForeshadowing());
                    });

            result.add(map);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Get single chapter proofreading report
     */
    @GetMapping("/proofreading/{chapterNumber}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getProofreadingReport(@PathVariable Long projectId,
                                                                       @PathVariable int chapterNumber) {
        return proofreadingReportRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .map(r -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("chapterNumber", r.getChapterNumber());
                    map.put("plotSummary", r.getPlotSummary());
                    map.put("characterIssues", r.getCharacterIssues());
                    map.put("consistencyIssues", r.getConsistencyIssues());
                    map.put("continuityIssues", r.getContinuityIssues());
                    map.put("foreshadowing", r.getForeshadowing());
                    return ResponseEntity.ok(map);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Global find/replace across all project data (SSE progress)
     */
    @GetMapping(value = "/global-replace", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter globalReplace(@PathVariable Long projectId,
                                    @RequestParam List<String> oldTexts,
                                    @RequestParam List<String> newTexts) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout

        executor.submit(() -> {
            try {
                // Build valid replacement pairs (skip empty oldText)
                List<int[]> pairs = new java.util.ArrayList<>();
                for (int i = 0; i < oldTexts.size() && i < newTexts.size(); i++) {
                    if (oldTexts.get(i) != null && !oldTexts.get(i).isBlank()) {
                        pairs.add(new int[]{i});
                    }
                }
                if (pairs.isEmpty()) {
                    emitter.send(SseEmitter.event().name("error").data("没有有效的替换对"));
                    emitter.complete();
                    return;
                }

                // Helper: apply all replacements to a string
                java.util.function.Function<String, String> applyReplace = (text) -> {
                    if (text == null) return null;
                    String result = text;
                    for (int[] pair : pairs) {
                        int idx = pair[0];
                        result = result.replace(oldTexts.get(idx), newTexts.get(idx));
                    }
                    return result;
                };

                // 1. Replace in world setting
                worldSettingRepository.findByProjectId(projectId).ifPresent(ws -> {
                    ws.setContent(applyReplace.apply(ws.getContent()));
                    ws.setSummary(applyReplace.apply(ws.getSummary()));
                    worldSettingRepository.save(ws);
                });
                emitter.send(SseEmitter.event().name("progress").data("世界观设定替换完成"));

                // 2. Replace in characters
                List<CharacterEntity> characters = characterRepository.findByProjectIdOrderBySortOrder(projectId);
                for (CharacterEntity ch : characters) {
                    ch.setName(applyReplace.apply(ch.getName()));
                    ch.setContent(applyReplace.apply(ch.getContent()));
                    ch.setDescription(applyReplace.apply(ch.getDescription()));
                    ch.setPersonality(applyReplace.apply(ch.getPersonality()));
                    ch.setAppearance(applyReplace.apply(ch.getAppearance()));
                    ch.setBackground(applyReplace.apply(ch.getBackground()));
                    ch.setMotivation(applyReplace.apply(ch.getMotivation()));
                    ch.setRelationships(applyReplace.apply(ch.getRelationships()));
                    ch.setAbilities(applyReplace.apply(ch.getAbilities()));
                    characterRepository.save(ch);
                }
                emitter.send(SseEmitter.event().name("progress").data("角色设计替换完成 (" + characters.size() + "个角色)"));

                // 3. Replace in story outline
                storyOutlineRepository.findByProjectId(projectId).ifPresent(so -> {
                    so.setContent(applyReplace.apply(so.getContent()));
                    storyOutlineRepository.save(so);
                });
                emitter.send(SseEmitter.event().name("progress").data("故事大纲替换完成"));

                // 4. Replace in volume outlines
                List<VolumeOutlineEntity> volumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
                for (VolumeOutlineEntity vol : volumes) {
                    vol.setTitle(applyReplace.apply(vol.getTitle()));
                    vol.setArcSummary(applyReplace.apply(vol.getArcSummary()));
                    volumeOutlineRepository.save(vol);
                }
                emitter.send(SseEmitter.event().name("progress").data("卷大纲替换完成 (" + volumes.size() + "卷)"));

                // 5. Replace in chapter outlines
                List<ChapterOutlineEntity> chapterOutlines = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);
                for (ChapterOutlineEntity co : chapterOutlines) {
                    co.setTitle(applyReplace.apply(co.getTitle()));
                    co.setSummary(applyReplace.apply(co.getSummary()));
                    co.setCharacterNames(applyReplace.apply(co.getCharacterNames()));
                    chapterOutlineRepository.save(co);
                }
                emitter.send(SseEmitter.event().name("progress").data("分章大纲替换完成 (" + chapterOutlines.size() + "章)"));

                // 6. Replace in chapters (including derived fields)
                List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
                for (ChapterEntity ch : chapters) {
                    ch.setTitle(applyReplace.apply(ch.getTitle()));
                    ch.setContent(applyReplace.apply(ch.getContent()));
                    ch.setContentDraft(applyReplace.apply(ch.getContentDraft()));
                    ch.setPlotSummary(applyReplace.apply(ch.getPlotSummary()));
                    ch.setCharacterStates(applyReplace.apply(ch.getCharacterStates()));
                    ch.setContentBeforeFix(applyReplace.apply(ch.getContentBeforeFix()));
                    // Update word count
                    if (ch.getContent() != null) {
                        ch.setWordCount(ch.getContent().length());
                    }
                    chapterRepository.save(ch);
                }
                emitter.send(SseEmitter.event().name("progress").data("章节内容替换完成 (" + chapters.size() + "章)"));

                // 7. Replace in proofreading reports
                List<ProofreadingReportEntity> reports = proofreadingReportRepository.findByProjectIdOrderByChapterNumber(projectId);
                for (ProofreadingReportEntity report : reports) {
                    report.setPlotSummary(applyReplace.apply(report.getPlotSummary()));
                    report.setCharacterIssues(applyReplace.apply(report.getCharacterIssues()));
                    report.setConsistencyIssues(applyReplace.apply(report.getConsistencyIssues()));
                    report.setContinuityIssues(applyReplace.apply(report.getContinuityIssues()));
                    report.setForeshadowing(applyReplace.apply(report.getForeshadowing()));
                    proofreadingReportRepository.save(report);
                }
                emitter.send(SseEmitter.event().name("progress").data("校对报告替换完成 (" + reports.size() + "份)"));

                // Done
                emitter.send(SseEmitter.event().name("done").data("全局替换完成"));
                emitter.complete();
            } catch (Exception e) {
                log.error("Global replace error", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data("替换出错: " + e.getMessage()));
                } catch (IOException ex) { /* ignore */ }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @GetMapping("/character-state-dims")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getCharacterStateDims(@PathVariable Long projectId) {
        var dimEntities = characterStateDimensionService.ensureAndGet(projectId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var dimEntity : dimEntities) {
            Map<String, Object> dimMap = new LinkedHashMap<>();
            dimMap.put("key", dimEntity.getDimKey().name());
            dimMap.put("displayName", dimEntity.getDimKey().getDisplayName());
            dimMap.put("enabled", dimEntity.isEnabled());
            dimMap.put("defaultEnabled", dimEntity.getDimKey().isDefaultEnabled());
            result.add(dimMap);
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping("/character-state-dims")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> toggleCharacterStateDim(
            @PathVariable Long projectId,
            @RequestBody Map<String, Object> body) {
        String dimKey = (String) body.get("dimKey");
        Boolean enabled = (Boolean) body.get("enabled");
        if (dimKey == null || enabled == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dimKey and enabled are required"));
        }
        try {
            CharacterStateDimension dim = CharacterStateDimension.valueOf(dimKey);
            characterStateDimensionService.setEnabled(projectId, dim, enabled);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid dimension key: " + dimKey));
        }
    }
}
