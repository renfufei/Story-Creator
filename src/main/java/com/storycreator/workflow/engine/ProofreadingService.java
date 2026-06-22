package com.storycreator.workflow.engine;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ChapterOutlineEntity;
import com.storycreator.persistence.entity.CharacterEntity;
import com.storycreator.persistence.entity.ProofreadingReportEntity;
import com.storycreator.persistence.repository.ChapterOutlineRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.CharacterRepository;
import com.storycreator.persistence.repository.ProofreadingReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.storycreator.workflow.engine.TextProcessingUtils.*;

@Service
public class ProofreadingService {

    private static final Logger log = LoggerFactory.getLogger(ProofreadingService.class);

    private final ChapterRepository chapterRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final CharacterRepository characterRepository;
    private final ProofreadingReportRepository proofreadingReportRepository;
    private final AiProviderRouter providerRouter;
    private final PromptTemplateRegistry promptRegistry;
    private final AiUsageTracker aiUsageTracker;
    private final GlobalSettingService globalSettingService;

    public ProofreadingService(ChapterRepository chapterRepository,
                               ChapterOutlineRepository chapterOutlineRepository,
                               CharacterRepository characterRepository,
                               ProofreadingReportRepository proofreadingReportRepository,
                               AiProviderRouter providerRouter,
                               PromptTemplateRegistry promptRegistry,
                               AiUsageTracker aiUsageTracker,
                               GlobalSettingService globalSettingService) {
        this.chapterRepository = chapterRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.characterRepository = characterRepository;
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
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        List<CharacterEntity> characters = characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        List<String> characterNames = characters.stream().map(CharacterEntity::getName).toList();

        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.PROOFREADING);

        List<String> accumulatedForeshadowing = new ArrayList<>();

        return Flux.fromIterable(chapters)
                .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                .concatMap(ch -> proofreadSingleChapter(ch, chapters, characterNames, characters, resolved, accumulatedForeshadowing));
    }

    public Flux<String> runProofreadingSingleChapter(Long projectId, int chapterNumber) {
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        List<CharacterEntity> characters = characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        List<String> characterNames = characters.stream().map(CharacterEntity::getName).toList();

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

        return proofreadSingleChapter(targetChapter, chapters, characterNames, characters, resolved, accumulatedForeshadowing);
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

    // --- Private helpers ---

    private Flux<String> proofreadSingleChapter(ChapterEntity chapter,
                                                  List<ChapterEntity> allChapters,
                                                  List<String> characterNames,
                                                  List<CharacterEntity> characters,
                                                  AiProviderRouter.ResolvedModel resolved,
                                                  List<String> accumulatedForeshadowing) {
        int chNum = chapter.getChapterNumber();
        String content = chapter.getContent();

        AtomicReference<String> plotSummaryRef = new AtomicReference<>("");
        AtomicReference<String> characterIssuesRef = new AtomicReference<>("[]");
        AtomicReference<String> consistencyIssuesRef = new AtomicReference<>("[]");
        AtomicReference<String> continuityIssuesRef = new AtomicReference<>("[]");
        AtomicReference<String> foreshadowingRef = new AtomicReference<>("[]");

        String proofSysPrompt = promptRegistry.getSystemPrompt(WorkflowStep.PROOFREADING, null);
        if (proofSysPrompt == null || proofSysPrompt.isBlank()) {
            proofSysPrompt = "你是一位专业的小说校对编辑，擅长发现前后文矛盾和人名错误。";
        }
        final String proofSystemPrompt = proofSysPrompt;

        // Sub-step 1: Plot Summary
        Flux<String> step1 = Flux.just("[[PROOFREAD:CHAPTER:" + chNum + ":PLOT_SUMMARY]]")
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

        // Sub-step 2: Character Check
        Flux<String> step2 = Flux.just("[[PROOFREAD:CHAPTER:" + chNum + ":CHARACTER_CHECK]]")
                .concatWith(Flux.defer(() -> {
                    long s2Start = System.currentTimeMillis();
                    String nameList = String.join("、", characterNames);
                    String s2Template = promptRegistry.getSubStepTemplate(WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_CHARACTER_CHECK, null);
                    String prompt = promptRegistry.resolveTemplate(s2Template, Map.of(
                            "characterNames", nameList,
                            "chapterContent", wrapContent(truncate(content, 6000))));
                    String s2Sys = promptRegistry.getSubStepSystemPrompt(WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_CHARACTER_CHECK, null);
                    if (s2Sys == null || s2Sys.isBlank()) s2Sys = proofSystemPrompt;
                    AiRequest req = AiRequest.builder()
                            .systemPrompt(s2Sys)
                            .userPrompt(prompt)
                            .maxTokens(1024)
                            .temperature(0.2)
                            .build();
                    applyResolvedConfig(req, resolved);
                    StringBuilder sb = new StringBuilder();
                    return resolved.provider().streamText(req)
                            .doOnNext(sb::append)
                            .doOnComplete(() -> {
                                characterIssuesRef.set(sb.toString().trim());
                                aiUsageTracker.record(chapter.getProjectId(), resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - s2Start);
                            });
                }));

        // Sub-step 3: Consistency Check
        Flux<String> step3 = Flux.just("[[PROOFREAD:CHAPTER:" + chNum + ":CONSISTENCY]]")
                .concatWith(Flux.defer(() -> {
                    long s3Start = System.currentTimeMillis();
                    StringBuilder charSummary = new StringBuilder();
                    for (CharacterEntity c : characters) {
                        if (c.getContent() != null) {
                            charSummary.append(c.getName()).append(": ")
                                    .append(truncate(c.getContent(), 200)).append("\n");
                        }
                    }
                    String prevSummary = plotSummaryRef.get();
                    String prevChapterSummary = "";
                    if (chNum > 1) {
                        for (ChapterEntity prev : allChapters) {
                            if (prev.getChapterNumber() == chNum - 1 && prev.getPlotSummary() != null) {
                                prevChapterSummary = prev.getPlotSummary();
                                break;
                            }
                        }
                    }
                    String s3Template = promptRegistry.getSubStepTemplate(WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_CONSISTENCY, null);
                    String prompt = promptRegistry.resolveTemplate(s3Template, Map.of(
                            "characterSummaries", wrapContent(charSummary.toString()),
                            "previousPlotSummary", prevChapterSummary,
                            "chapterContent", wrapContent(truncate(content, 5000))));
                    String s3Sys = promptRegistry.getSubStepSystemPrompt(WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_CONSISTENCY, null);
                    if (s3Sys == null || s3Sys.isBlank()) s3Sys = proofSystemPrompt;
                    AiRequest req = AiRequest.builder()
                            .systemPrompt(s3Sys)
                            .userPrompt(prompt)
                            .maxTokens(1024)
                            .temperature(0.3)
                            .build();
                    applyResolvedConfig(req, resolved);
                    StringBuilder sb = new StringBuilder();
                    return resolved.provider().streamText(req)
                            .doOnNext(sb::append)
                            .doOnComplete(() -> {
                                consistencyIssuesRef.set(sb.toString().trim());
                                aiUsageTracker.record(chapter.getProjectId(), resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - s3Start);
                            });
                }));

        // Sub-step 4: Continuity Check (skip for chapter 1)
        Flux<String> step4;
        if (chNum <= 1) {
            step4 = Flux.empty();
        } else {
            step4 = Flux.just("[[PROOFREAD:CHAPTER:" + chNum + ":CONTINUITY]]")
                    .concatWith(Flux.defer(() -> {
                        long s4Start = System.currentTimeMillis();
                        String prevEnd = "";
                        for (ChapterEntity prev : allChapters) {
                            if (prev.getChapterNumber() == chNum - 1 && prev.getContent() != null) {
                                String pc = prev.getContent();
                                prevEnd = pc.length() > 200 ? pc.substring(pc.length() - 200) : pc;
                                break;
                            }
                        }
                        String currStart = content.length() > 200 ? content.substring(0, 200) : content;
                        String s4Template = promptRegistry.getSubStepTemplate(WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_CONTINUITY, null);
                        String prompt = promptRegistry.resolveTemplate(s4Template, Map.of(
                                "previousEnd", wrapContent(prevEnd),
                                "currentStart", wrapContent(currStart)));
                        String s4Sys = promptRegistry.getSubStepSystemPrompt(WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_CONTINUITY, null);
                        if (s4Sys == null || s4Sys.isBlank()) s4Sys = proofSystemPrompt;
                        AiRequest req = AiRequest.builder()
                                .systemPrompt(s4Sys)
                                .userPrompt(prompt)
                                .maxTokens(512)
                                .temperature(0.3)
                                .build();
                        applyResolvedConfig(req, resolved);
                        StringBuilder sb = new StringBuilder();
                        return resolved.provider().streamText(req)
                                .doOnNext(sb::append)
                                .doOnComplete(() -> {
                                    continuityIssuesRef.set(sb.toString().trim());
                                    aiUsageTracker.record(chapter.getProjectId(), resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - s4Start);
                                });
                    }));
        }

        // Sub-step 5: Foreshadowing Tracking
        Flux<String> step5 = Flux.just("[[PROOFREAD:CHAPTER:" + chNum + ":FORESHADOWING]]")
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
            saveProofreadingResults(chapter, plotSummaryRef.get(), characterIssuesRef.get(),
                    consistencyIssuesRef.get(), continuityIssuesRef.get(), foreshadowingRef.get());
            return Flux.empty();
        });

        return step1.concatWith(step2).concatWith(step3).concatWith(step4).concatWith(step5).concatWith(saveStep);
    }

    private void saveProofreadingResults(ChapterEntity chapter, String plotSummary,
                                          String characterIssues, String consistencyIssues,
                                          String continuityIssues, String foreshadowing) {
        final String cleanPlotSummary = stripAiFormatting(plotSummary);
        final String cleanCharacterIssues = stripAiFormatting(characterIssues);
        final String cleanConsistencyIssues = stripAiFormatting(consistencyIssues);
        final String cleanContinuityIssues = stripAiFormatting(continuityIssues);
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
        report.setCharacterIssues(cleanCharacterIssues);
        report.setConsistencyIssues(cleanConsistencyIssues);
        report.setContinuityIssues(cleanContinuityIssues);
        report.setForeshadowing(cleanForeshadowing);
        proofreadingReportRepository.save(report);

        chapter.setPlotSummary(cleanPlotSummary != null && cleanPlotSummary.length() > 500 ? cleanPlotSummary.substring(0, 500) : cleanPlotSummary);
        chapter.setProofreadStatus(StepStatus.GENERATED);
        chapterRepository.save(chapter);

        chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chNum)
                .ifPresent(outline -> {
                    outline.setSummary(cleanPlotSummary);
                    chapterOutlineRepository.save(outline);
                });

        log.info("Saved proofreading results for project {} chapter {}", projectId, chNum);
    }
}
