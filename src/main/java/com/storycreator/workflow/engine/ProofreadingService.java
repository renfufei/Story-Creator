package com.storycreator.workflow.engine;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ProofreadingReportEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProofreadingReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.storycreator.workflow.engine.TextProcessingUtils.*;

@Service
public class ProofreadingService {

    private static final Logger log = LoggerFactory.getLogger(ProofreadingService.class);

    private final ChapterRepository chapterRepository;
    private final ProofreadingReportRepository proofreadingReportRepository;
    private final AiProviderRouter providerRouter;
    private final PromptTemplateRegistry promptRegistry;
    private final AiUsageTracker aiUsageTracker;
    private final GlobalSettingService globalSettingService;

    public ProofreadingService(ChapterRepository chapterRepository,
                               ProofreadingReportRepository proofreadingReportRepository,
                               AiProviderRouter providerRouter,
                               PromptTemplateRegistry promptRegistry,
                               AiUsageTracker aiUsageTracker,
                               GlobalSettingService globalSettingService) {
        this.chapterRepository = chapterRepository;
        this.proofreadingReportRepository = proofreadingReportRepository;
        this.providerRouter = providerRouter;
        this.promptRegistry = promptRegistry;
        this.aiUsageTracker = aiUsageTracker;
        this.globalSettingService = globalSettingService;
    }

    public ProofreadingReportRepository getProofreadingReportRepository() {
        return proofreadingReportRepository;
    }

    public Flux<String> runProofreading(Long projectId) {
        return runProofreading(projectId, null);
    }

    public Flux<String> runProofreading(Long projectId, Set<String> enabledSubSteps) {
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);

        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.PROOFREADING);

        List<String> accumulatedForeshadowing = new ArrayList<>();

        return Flux.fromIterable(chapters)
                .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                .concatMap(ch -> proofreadSingleChapter(ch, chapters, resolved, accumulatedForeshadowing, enabledSubSteps));
    }

    public Flux<String> runProofreadingSingleChapter(Long projectId, int chapterNumber) {
        return runProofreadingSingleChapter(projectId, chapterNumber, null);
    }

    public Flux<String> runProofreadingSingleChapter(Long projectId, int chapterNumber, Set<String> enabledSubSteps) {
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);

        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.PROOFREADING);

        List<String> accumulatedForeshadowing = new ArrayList<>();

        ChapterEntity targetChapter = chapters.stream()
                .filter(ch -> ch.getChapterNumber() == chapterNumber)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterNumber));

        if (targetChapter.getContent() == null || targetChapter.getContent().isBlank()) {
            return Flux.empty();
        }

        // Build accumulated foreshadowing from prior chapters' reports
        for (ChapterEntity ch : chapters) {
            if (ch.getChapterNumber() >= chapterNumber) break;
            proofreadingReportRepository.findByProjectIdAndChapterNumber(projectId, ch.getChapterNumber())
                    .ifPresent(report -> {
                        if (report.getForeshadowing() != null && !report.getForeshadowing().equals("[]")) {
                            accumulatedForeshadowing.add(report.getForeshadowing());
                        }
                    });
        }

        return proofreadSingleChapter(targetChapter, chapters, resolved, accumulatedForeshadowing, enabledSubSteps);
    }

    public Flux<String> proofreadFixSingleChapter(Long projectId, int chapterNumber) {
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterNumber));

        ProofreadingReportEntity report = proofreadingReportRepository
                .findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Proofreading report not found for chapter: " + chapterNumber));

        String originalContent = chapter.getContent();
        if (originalContent == null || originalContent.isBlank()) {
            return Flux.error(new IllegalStateException("Chapter has no content to fix"));
        }

        // Save original content as backup
        chapter.setContentBeforeFix(originalContent);
        chapter.setProofreadFixStatus(StepStatus.GENERATING);
        chapterRepository.save(chapter);

        // Build proofreading report summary
        StringBuilder reportSummary = new StringBuilder();
        if (report.getCharacterIssues() != null && !report.getCharacterIssues().equals("[]")) {
            reportSummary.append("【角色校正问题】\n").append(report.getCharacterIssues()).append("\n\n");
        }
        if (report.getConsistencyIssues() != null && !report.getConsistencyIssues().equals("[]")) {
            reportSummary.append("【一致性问题】\n").append(report.getConsistencyIssues()).append("\n\n");
        }
        if (report.getContinuityIssues() != null && !report.getContinuityIssues().equals("[]")) {
            reportSummary.append("【衔接问题】\n").append(report.getContinuityIssues()).append("\n\n");
        }

        if (reportSummary.isEmpty()) {
            chapter.setProofreadFixStatus(StepStatus.GENERATED);
            chapterRepository.save(chapter);
            return Flux.just(originalContent);
        }

        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.PROOFREADING);

        String fixTemplate = promptRegistry.getSubStepTemplate(WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_FIX, null);
        String userPrompt = promptRegistry.resolveTemplate(fixTemplate, Map.of(
                "reportSummary", reportSummary.toString(),
                "originalContent", originalContent));
        String systemPrompt = promptRegistry.getSubStepSystemPrompt(WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_FIX, null);
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "你是一位专业小说编辑，根据校对报告修改章节正文。只修改校对报告中指出的问题，保持原文的文风、情节和结构不变。直接输出修改后的完整章节正文，不要添加任何说明或标注。";
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .maxTokens(8192)
                .temperature(0.3)
                .build();
        applyResolvedConfig(request, resolved);

        StringBuilder fixedContent = new StringBuilder();
        long startTime = System.currentTimeMillis();

        return resolved.provider().streamText(request)
                .doOnNext(fixedContent::append)
                .doOnComplete(() -> {
                    aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(),
                            System.currentTimeMillis() - startTime);
                    chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber).ifPresent(ch -> {
                        ch.setContent(stripAiFormatting(fixedContent.toString()));
                        ch.setWordCount(ch.getContent().length());
                        ch.setProofreadFixStatus(StepStatus.GENERATED);
                        chapterRepository.save(ch);
                    });
                    log.info("[P{}] Proofread fix completed for chapter {}", projectId, chapterNumber);
                })
                .doOnError(e -> {
                    chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber).ifPresent(ch -> {
                        ch.setProofreadFixStatus(StepStatus.NOT_STARTED);
                        ch.setContent(originalContent);
                        chapterRepository.save(ch);
                    });
                    log.error("[P{}] Proofread fix failed for chapter {}: {}", projectId, chapterNumber, e.getMessage());
                });
    }

    public void proofreadFixSingleChapterSync(Long projectId, int chapterNumber) {
        int timeoutSeconds = globalSettingService.getAiTimeoutSeconds();
        proofreadFixSingleChapter(projectId, chapterNumber)
                .blockLast(java.time.Duration.ofSeconds(timeoutSeconds * 2L));
    }

    public Flux<String> runProofreadingFix(Long projectId) {
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        List<ChapterEntity> needsFix = chapters.stream()
                .filter(ch -> ch.getProofreadStatus() == StepStatus.GENERATED || ch.getProofreadStatus() == StepStatus.CONFIRMED)
                .filter(ch -> ch.getProofreadFixStatus() != StepStatus.GENERATED && ch.getProofreadFixStatus() != StepStatus.CONFIRMED)
                .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                .toList();

        if (needsFix.isEmpty()) {
            return Flux.empty();
        }

        return Flux.fromIterable(needsFix)
                .concatMap(ch -> Flux.just("[[PROOFREAD_FIX:CHAPTER:" + ch.getChapterNumber() + "]]")
                        .concatWith(Flux.defer(() -> {
                            var reportOpt = proofreadingReportRepository
                                    .findByProjectIdAndChapterNumber(projectId, ch.getChapterNumber());
                            if (reportOpt.isEmpty()) {
                                return Flux.empty();
                            }
                            return proofreadFixSingleChapter(projectId, ch.getChapterNumber());
                        })));
    }

    // --- Public variable builders (for prompt explore) ---

    public Map<String, String> buildProofreadPlotSummaryVariables(Long projectId, int chapterNumber) {
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterNumber));
        String content = chapter.getContent() != null ? chapter.getContent() : "";
        return Map.of("chapterContent", wrapContent(truncate(content, 6000)));
    }


    public Map<String, String> buildProofreadForeshadowingVariables(Long projectId, int chapterNumber) {
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterNumber));
        List<String> accumulatedForeshadowing = new ArrayList<>();
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        for (ChapterEntity ch : chapters) {
            if (ch.getChapterNumber() >= chapterNumber) break;
            proofreadingReportRepository.findByProjectIdAndChapterNumber(projectId, ch.getChapterNumber())
                    .ifPresent(report -> {
                        if (report.getForeshadowing() != null && !report.getForeshadowing().equals("[]")) {
                            accumulatedForeshadowing.add(report.getForeshadowing());
                        }
                    });
        }
        String prevForeshadowing = accumulatedForeshadowing.isEmpty() ? "无"
                : String.join("\n", accumulatedForeshadowing);
        String content = chapter.getContent() != null ? chapter.getContent() : "";
        return Map.of(
                "accumulatedForeshadowing", prevForeshadowing,
                "chapterNumber", String.valueOf(chapterNumber),
                "chapterContent", wrapContent(truncate(content, 5000)));
    }

    public Map<String, String> buildProofreadFixVariables(Long projectId, int chapterNumber) {
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterNumber));
        ProofreadingReportEntity report = proofreadingReportRepository
                .findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Proofreading report not found for chapter: " + chapterNumber));

        String originalContent = chapter.getContent() != null ? chapter.getContent() : "";
        StringBuilder reportSummary = new StringBuilder();
        if (report.getCharacterIssues() != null && !report.getCharacterIssues().equals("[]")) {
            reportSummary.append("【角色校正问题】\n").append(report.getCharacterIssues()).append("\n\n");
        }
        if (report.getConsistencyIssues() != null && !report.getConsistencyIssues().equals("[]")) {
            reportSummary.append("【一致性问题】\n").append(report.getConsistencyIssues()).append("\n\n");
        }
        if (report.getContinuityIssues() != null && !report.getContinuityIssues().equals("[]")) {
            reportSummary.append("【衔接问题】\n").append(report.getContinuityIssues()).append("\n\n");
        }
        return Map.of(
                "reportSummary", reportSummary.toString(),
                "originalContent", originalContent);
    }

    // --- Private helpers ---

    private boolean isSubStepEnabled(Set<String> enabledSubSteps, String subStep) {
        return enabledSubSteps == null || enabledSubSteps.contains(subStep);
    }

    private Flux<String> proofreadSingleChapter(ChapterEntity chapter,
                                                  List<ChapterEntity> allChapters,
                                                  AiProviderRouter.ResolvedModel resolved,
                                                  List<String> accumulatedForeshadowing,
                                                  Set<String> enabledSubSteps) {
        int chNum = chapter.getChapterNumber();
        String content = chapter.getContent();

        AtomicReference<String> plotSummaryRef = new AtomicReference<>("");
        AtomicReference<String> foreshadowingRef = new AtomicReference<>("[]");

        String proofSysPrompt = promptRegistry.getSystemPrompt(WorkflowStep.PROOFREADING, null);
        if (proofSysPrompt == null || proofSysPrompt.isBlank()) {
            proofSysPrompt = "你是一位专业的小说校对编辑，擅长发现前后文矛盾和人名错误。";
        }
        final String proofSystemPrompt = proofSysPrompt;

        // Sub-step 1: Plot Summary
        Flux<String> step1 = !isSubStepEnabled(enabledSubSteps, "PROOFREAD_PLOT_SUMMARY") ? Flux.empty()
                : Flux.just("[[PROOFREAD:CHAPTER:" + chNum + ":PLOT_SUMMARY]]")
                .concatWith(Flux.defer(() -> {
                    long s1Start = System.currentTimeMillis();
                    String s1Template = promptRegistry.getSubStepTemplate(WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_PLOT_SUMMARY, null);
                    String prompt = promptRegistry.resolveTemplate(s1Template, Map.of("chapterContent", wrapContent(truncate(content, 6000))));
                    String s1Sys = promptRegistry.getSubStepSystemPrompt(WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_PLOT_SUMMARY, null);
                    if (s1Sys == null || s1Sys.isBlank()) s1Sys = proofSystemPrompt;
                    AiRequest req = AiRequest.builder()
                            .systemPrompt(s1Sys)
                            .userPrompt(prompt)
                            .maxTokens(256)
                            .temperature(0.3)
                            .build();
                    applyResolvedConfig(req, resolved);
                    StringBuilder sb = new StringBuilder();
                    return resolved.provider().streamText(req)
                            .doOnNext(sb::append)
                            .doOnComplete(() -> {
                                plotSummaryRef.set(sb.toString().trim());
                                aiUsageTracker.record(chapter.getProjectId(), resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - s1Start);
                            });
                }));


        // Sub-step 5: Foreshadowing Tracking
        Flux<String> step5 = !isSubStepEnabled(enabledSubSteps, "PROOFREAD_FORESHADOWING") ? Flux.empty()
                : Flux.just("[[PROOFREAD:CHAPTER:" + chNum + ":FORESHADOWING]]")
                .concatWith(Flux.defer(() -> {
                    long s5Start = System.currentTimeMillis();
                    String prevForeshadowing = accumulatedForeshadowing.isEmpty() ? "无"
                            : String.join("\n", accumulatedForeshadowing);
                    String s5Template = promptRegistry.getSubStepTemplate(WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_FORESHADOWING, null);
                    String prompt = promptRegistry.resolveTemplate(s5Template, Map.of(
                            "accumulatedForeshadowing", prevForeshadowing,
                            "chapterNumber", String.valueOf(chNum),
                            "chapterContent", wrapContent(truncate(content, 5000))));
                    String s5Sys = promptRegistry.getSubStepSystemPrompt(WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_FORESHADOWING, null);
                    if (s5Sys == null || s5Sys.isBlank()) s5Sys = proofSystemPrompt;
                    AiRequest req = AiRequest.builder()
                            .systemPrompt(s5Sys)
                            .userPrompt(prompt)
                            .maxTokens(1024)
                            .temperature(0.3)
                            .build();
                    applyResolvedConfig(req, resolved);
                    StringBuilder sb = new StringBuilder();
                    return resolved.provider().streamText(req)
                            .doOnNext(sb::append)
                            .doOnComplete(() -> {
                                String result = sb.toString().trim();
                                foreshadowingRef.set(result);
                                accumulatedForeshadowing.add("第" + chNum + "章: " + result);
                                aiUsageTracker.record(chapter.getProjectId(), resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - s5Start);
                            });
                }));

        // After all sub-steps, save results
        Flux<String> saveStep = Flux.defer(() -> {
            saveProofreadingResults(chapter, plotSummaryRef.get(), foreshadowingRef.get());
            return Flux.empty();
        });

        return step1.concatWith(step5).concatWith(saveStep);
    }

    private void saveProofreadingResults(ChapterEntity chapter, String plotSummary, String foreshadowing) {
        final String cleanPlotSummary = stripAiFormatting(plotSummary);
        final String cleanForeshadowing = stripAiFormatting(foreshadowing);
        Long projectId = chapter.getProjectId();
        int chNum = chapter.getChapterNumber();

        ProofreadingReportEntity report = proofreadingReportRepository
                .findByProjectIdAndChapterNumber(projectId, chNum)
                .orElseGet(() -> {
                    ProofreadingReportEntity r = new ProofreadingReportEntity();
                    r.setProjectId(projectId);
                    r.setChapterNumber(chNum);
                    return r;
                });
        report.setPlotSummary(cleanPlotSummary != null && cleanPlotSummary.length() > 500 ? cleanPlotSummary.substring(0, 500) : cleanPlotSummary);
        report.setForeshadowing(cleanForeshadowing);
        proofreadingReportRepository.save(report);

        chapter.setPlotSummary(cleanPlotSummary != null && cleanPlotSummary.length() > 500 ? cleanPlotSummary.substring(0, 500) : cleanPlotSummary);
        chapter.setProofreadStatus(StepStatus.GENERATED);
        chapterRepository.save(chapter);

        log.info("Saved proofreading results for project {} chapter {}", projectId, chNum);
    }
}
