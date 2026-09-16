package com.storycreator.workflow.engine;

import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.AutoRunStepConfigEntity;
import com.storycreator.persistence.repository.AutoRunStepConfigRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.ProofreadingReportRepository;
import com.storycreator.persistence.repository.StepGuidanceRepository;
import com.storycreator.persistence.repository.VolumeOutlineRepository;
import com.storycreator.web.MaterialLibraryService;
import com.storycreator.workflow.step.WorkflowStepHandler;
import com.storycreator.core.port.ai.AiRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.storycreator.workflow.engine.TextProcessingUtils.applyResolvedConfig;

/**
 * Facade that delegates to specialized services.
 * Retains all public method signatures for backward compatibility.
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private Map<WorkflowStep, WorkflowStepHandler> handlers;
    private AiProviderRouter providerRouter;
    private ProjectRepository projectRepository;
    private ChapterRepository chapterRepository;
    private StepGuidanceRepository stepGuidanceRepository;
    private VolumeOutlineRepository volumeOutlineRepository;
    private AiUsageTracker aiUsageTracker;

    // Delegated services
    private WorkflowContextBuilder contextBuilder;
    private WorkflowStateService stateService;
    private CharacterStateService characterStateService;
    private CharacterGenerationService characterGenerationService;
    private OutlineGenerationService outlineGenerationService;
    private ProofreadingService proofreadingService;
    private MaterialLibraryService materialLibraryService;
    private AutoRunStepConfigRepository autoRunStepConfigRepository;

    @Autowired
    public void setHandlers(List<WorkflowStepHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(WorkflowStepHandler::getStep, Function.identity()));
    }

    @Autowired
    public void setProviderRouter(AiProviderRouter providerRouter) {
        this.providerRouter = providerRouter;
    }

    @Autowired
    public void setProjectRepository(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Autowired
    public void setChapterRepository(ChapterRepository chapterRepository) {
        this.chapterRepository = chapterRepository;
    }

    @Autowired
    public void setStepGuidanceRepository(StepGuidanceRepository stepGuidanceRepository) {
        this.stepGuidanceRepository = stepGuidanceRepository;
    }

    @Autowired
    public void setVolumeOutlineRepository(VolumeOutlineRepository volumeOutlineRepository) {
        this.volumeOutlineRepository = volumeOutlineRepository;
    }

    @Autowired
    public void setAiUsageTracker(AiUsageTracker aiUsageTracker) {
        this.aiUsageTracker = aiUsageTracker;
    }

    @Autowired
    public void setContextBuilder(WorkflowContextBuilder contextBuilder) {
        this.contextBuilder = contextBuilder;
    }

    @Autowired
    public void setStateService(WorkflowStateService stateService) {
        this.stateService = stateService;
    }

    @Autowired
    public void setCharacterStateService(CharacterStateService characterStateService) {
        this.characterStateService = characterStateService;
    }

    @Autowired
    public void setCharacterGenerationService(CharacterGenerationService characterGenerationService) {
        this.characterGenerationService = characterGenerationService;
    }

    @Autowired
    public void setOutlineGenerationService(OutlineGenerationService outlineGenerationService) {
        this.outlineGenerationService = outlineGenerationService;
    }

    @Autowired
    public void setProofreadingService(ProofreadingService proofreadingService) {
        this.proofreadingService = proofreadingService;
    }

    @Autowired
    public void setMaterialLibraryService(@Lazy MaterialLibraryService materialLibraryService) {
        this.materialLibraryService = materialLibraryService;
    }

    @Autowired
    public void setAutoRunStepConfigRepository(AutoRunStepConfigRepository autoRunStepConfigRepository) {
        this.autoRunStepConfigRepository = autoRunStepConfigRepository;
    }

    // --- Model resolution ---

    public AiProviderRouter.ResolvedModel resolveModelForProject(Long projectId, WorkflowStep step) {
        return providerRouter.resolveModel(projectId, step);
    }

    public AiProviderRouter.ResolvedModel resolveModelForProject(Long projectId) {
        return providerRouter.resolveModel(projectId, null);
    }

    // --- Repository accessors (kept for backward compat) ---

    public VolumeOutlineRepository getVolumeOutlineRepository() {
        return volumeOutlineRepository;
    }

    public ProofreadingReportRepository getProofreadingReportRepository() {
        return proofreadingService.getProofreadingReportRepository();
    }

    // --- Context building (delegates to WorkflowContextBuilder) ---

    public WorkflowContext buildContext(Long projectId) {
        return contextBuilder.build(projectId);
    }

    public WorkflowContext buildContext(Long projectId, int chapterNumber) {
        return contextBuilder.build(projectId, chapterNumber);
    }

    // --- Generation dispatch ---

    public Flux<String> generate(Long projectId, WorkflowStep step) {
        return generate(projectId, step, 0, Collections.emptyList());
    }

    public Flux<String> generate(Long projectId, WorkflowStep step, int chapterNumber) {
        return generate(projectId, step, chapterNumber, Collections.emptyList());
    }

    public Flux<String> generate(Long projectId, WorkflowStep step, int chapterNumber, List<Long> materialIds) {
        // Check project status
        ProjectEntity proj = projectRepository.findById(projectId).orElse(null);
        if (proj != null && proj.getStatus() != null) {
            if (proj.getStatus() == com.storycreator.core.domain.ProjectStatus.COMPLETED) {
                return Flux.error(new IllegalStateException("项目状态为「已完本」，无法进行AI生成"));
            }
            if (proj.getStatus() == com.storycreator.core.domain.ProjectStatus.ABANDONED) {
                return Flux.error(new IllegalStateException("项目状态为「已废弃」，无法进行AI生成"));
            }
        }

        log.info("[P{}] Generate START step={} chapter={}", projectId, step, chapterNumber);
        long startTime = System.currentTimeMillis();

        // Mark as generating
        stateService.updateStepStatus(projectId, step, StepStatus.GENERATING);

        // Dispatch to specialized services
        if (step == WorkflowStep.PROOFREADING) {
            Set<String> enabledProofSubSteps = resolveEnabledProofreadSubSteps(projectId);
            Flux<String> proofFlux = (chapterNumber > 0)
                    ? proofreadingService.runProofreadingSingleChapter(projectId, chapterNumber, enabledProofSubSteps)
                    : proofreadingService.runProofreading(projectId, enabledProofSubSteps);
            return proofFlux
                    .doOnComplete(() -> log.info("[P{}] Generate DONE step={} chapter={} elapsed={}s",
                            projectId, step, chapterNumber, (System.currentTimeMillis() - startTime) / 1000))
                    .doOnError(e -> log.error("[P{}] Generate FAILED step={} chapter={} elapsed={}s error={}",
                            projectId, step, chapterNumber, (System.currentTimeMillis() - startTime) / 1000, e.getMessage()));
        }

        if (step == WorkflowStep.OUTLINE_GENERATION) {
            return outlineGenerationService.generateOutlineByChapters(projectId)
                    .doOnComplete(() -> log.info("[P{}] Generate DONE step={} elapsed={}s",
                            projectId, step, (System.currentTimeMillis() - startTime) / 1000))
                    .doOnError(e -> log.error("[P{}] Generate FAILED step={} elapsed={}s error={}",
                            projectId, step, (System.currentTimeMillis() - startTime) / 1000, e.getMessage()));
        }

        if (step == WorkflowStep.CHARACTER_DESIGN) {
            return characterGenerationService.generateCharactersByCards(projectId)
                    .doOnComplete(() -> log.info("[P{}] Generate DONE step={} elapsed={}s",
                            projectId, step, (System.currentTimeMillis() - startTime) / 1000))
                    .doOnError(e -> log.error("[P{}] Generate FAILED step={} elapsed={}s error={}",
                            projectId, step, (System.currentTimeMillis() - startTime) / 1000, e.getMessage()));
        }

        // Default: use handler-based generation
        WorkflowStepHandler handler = handlers.get(step);
        if (handler == null) {
            return Flux.error(new IllegalArgumentException("No handler for step: " + step));
        }

        WorkflowContext context = contextBuilder.build(projectId, chapterNumber);
        context.setCurrentStep(step);

        // Inject reference materials if any
        if (materialIds != null && !materialIds.isEmpty()) {
            String refMaterials = materialLibraryService.listForInjection(materialIds);
            context.setReferenceMaterials(refMaterials);
        }

        // Override guidance for the specific step being generated
        stepGuidanceRepository.findByProjectIdAndStep(projectId, step)
                .ifPresent(sg -> context.setStepGuidance(sg.getGuidance()));

        // For polishing, load the chapter content and polish note
        if (step == WorkflowStep.POLISHING && chapterNumber > 0) {
            chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                    .ifPresent(ch -> {
                        context.setContentToPolish(ch.getContent());
                        ch.setContentDraft(ch.getContent());
                        chapterRepository.save(ch);
                        if (ch.getPolishNote() != null && !ch.getPolishNote().isBlank()) {
                            context.setPolishNote("【修改意见】\n" + ch.getPolishNote());
                        }
                    });
        }

        AiRequest request = handler.buildRequest(context);

        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, step);
        applyResolvedConfig(request, resolved);

        return resolved.provider().streamText(request)
                .doOnComplete(() -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("[P{}] Generate DONE step={} chapter={} elapsed={}s",
                            projectId, step, chapterNumber, elapsed / 1000);
                    aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), elapsed);
                })
                .doOnError(e -> log.error("[P{}] Generate FAILED step={} chapter={} elapsed={}s error={}",
                        projectId, step, chapterNumber, (System.currentTimeMillis() - startTime) / 1000, e.getMessage()));
    }

    // --- Outline regeneration ---

    public Flux<String> regenerateChapterOutline(Long projectId, int chapterNumber) {
        return outlineGenerationService.regenerateChapterOutline(projectId, chapterNumber);
    }

    // --- State management (delegates to WorkflowStateService) ---

    @Transactional
    public void saveGeneratedContent(Long projectId, WorkflowStep step, String content) {
        stateService.saveGeneratedContent(projectId, step, content);
    }

    @Transactional
    public void saveGeneratedContent(Long projectId, WorkflowStep step, String content, int chapterNumber) {
        stateService.saveGeneratedContent(projectId, step, content, chapterNumber);
    }

    @Transactional
    public void ensureWorkflowStateExists(Long projectId, WorkflowStep step) {
        stateService.ensureWorkflowStateExists(projectId, step);
    }

    @Transactional
    public void confirmStep(Long projectId, WorkflowStep step) {
        stateService.confirmStep(projectId, step);
    }

    @Transactional
    public void confirmStepOnly(Long projectId, WorkflowStep step) {
        stateService.confirmStepOnly(projectId, step);
    }

    @Transactional
    public void advanceStep(Long projectId, WorkflowStep step) {
        stateService.advanceStep(projectId, step);
    }

    @Transactional
    public void saveUserEdit(Long projectId, WorkflowStep step, String editedContent) {
        stateService.saveUserEdit(projectId, step, editedContent);
    }

    @Transactional
    public void resetGeneratingStatus(Long projectId, WorkflowStep step, int chapterNumber) {
        stateService.resetGeneratingStatus(projectId, step, chapterNumber);
    }

    // --- Character states ---

    public void generateCharacterStates(Long projectId, int chapterNumber) {
        characterStateService.generateCharacterStates(projectId, chapterNumber);
    }

    public void generateCharacterStates(Long projectId, int chapterNumber, Consumer<String> tokenSink) {
        characterStateService.generateCharacterStates(projectId, chapterNumber, tokenSink);
    }

    // --- Character refinement ---

    public Flux<String> refineAllCharacters(Long projectId) {
        return characterGenerationService.refineAllCharacters(projectId);
    }

    // --- Proofreading ---

    public Flux<String> proofreadFixSingleChapter(Long projectId, int chapterNumber) {
        return proofreadingService.proofreadFixSingleChapter(projectId, chapterNumber);
    }

    public void proofreadFixSingleChapterSync(Long projectId, int chapterNumber) {
        proofreadingService.proofreadFixSingleChapterSync(projectId, chapterNumber);
    }

    private static final List<String> PROOFREAD_SUB_STEPS = List.of(
            "PROOFREAD_PLOT_SUMMARY", "PROOFREAD_FORESHADOWING");

    private Set<String> resolveEnabledProofreadSubSteps(Long projectId) {
        List<AutoRunStepConfigEntity> configs = autoRunStepConfigRepository.findByProjectId(projectId);
        // If no configs stored, all sub-steps are enabled (null = all enabled in ProofreadingService)
        boolean hasAnyProofConfig = configs.stream()
                .anyMatch(c -> PROOFREAD_SUB_STEPS.contains(c.getStep()));
        if (!hasAnyProofConfig) {
            return null;
        }
        Map<String, Boolean> configMap = configs.stream()
                .collect(Collectors.toMap(AutoRunStepConfigEntity::getStep, AutoRunStepConfigEntity::isEnabled,
                        (a, b) -> b));
        return PROOFREAD_SUB_STEPS.stream()
                .filter(s -> configMap.getOrDefault(s, true))
                .collect(Collectors.toSet());
    }
}
