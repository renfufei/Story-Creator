package com.storycreator.workflow.engine;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.storycreator.workflow.engine.TextProcessingUtils.*;

@Service
public class OutlineGenerationService {

    private static final Logger log = LoggerFactory.getLogger(OutlineGenerationService.class);

    private final ProjectRepository projectRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final VolumeOutlineRepository volumeOutlineRepository;
    private final StoryOutlineRepository storyOutlineRepository;
    private final StepGuidanceRepository stepGuidanceRepository;
    private final AiProviderRouter providerRouter;
    private final PromptTemplateRegistry promptRegistry;
    private final WorkflowContextBuilder contextBuilder;
    private final AiUsageTracker aiUsageTracker;

    public OutlineGenerationService(ProjectRepository projectRepository,
                                    ChapterOutlineRepository chapterOutlineRepository,
                                    VolumeOutlineRepository volumeOutlineRepository,
                                    StoryOutlineRepository storyOutlineRepository,
                                    StepGuidanceRepository stepGuidanceRepository,
                                    AiProviderRouter providerRouter,
                                    PromptTemplateRegistry promptRegistry,
                                    WorkflowContextBuilder contextBuilder,
                                    AiUsageTracker aiUsageTracker) {
        this.projectRepository = projectRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.volumeOutlineRepository = volumeOutlineRepository;
        this.storyOutlineRepository = storyOutlineRepository;
        this.stepGuidanceRepository = stepGuidanceRepository;
        this.providerRouter = providerRouter;
        this.promptRegistry = promptRegistry;
        this.contextBuilder = contextBuilder;
        this.aiUsageTracker = aiUsageTracker;
    }

    // --- Public API ---

    public Flux<String> generateOutlineByChapters(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        int totalChapters = project.getTotalChapters();

        WorkflowContext baseContext = contextBuilder.build(projectId, 0);
        baseContext.setTotalChapters(totalChapters);

        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.OUTLINE_GENERATION);

        String guidanceSuffix = stepGuidanceRepository.findByProjectIdAndStep(projectId, WorkflowStep.OUTLINE_GENERATION)
                .filter(sg -> sg.getGuidance() != null && !sg.getGuidance().isBlank())
                .map(sg -> "\n\n【创作指导】\n" + sg.getGuidance() + "\n请在生成时参考以上指导意见。")
                .orElse("");

        List<VolumeRange> volumes = computeVolumes(totalChapters, project.getChaptersPerVolume());

        // Phase 0: Pre-create all chapter outline records as PENDING
        preCreateChapterOutlines(projectId, totalChapters, volumes);

        // Load existing data for resume support
        List<VolumeOutlineEntity> existingVolumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
        List<ChapterOutlineEntity> existingChapters = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);

        Set<Integer> completedVolumeNums = existingVolumes.stream()
                .filter(v -> v.getArcSummary() != null && !v.getArcSummary().isBlank())
                .map(VolumeOutlineEntity::getVolumeNumber)
                .collect(Collectors.toSet());

        Set<Integer> completedChapterNums = existingChapters.stream()
                .filter(ch -> "COMPLETED".equals(ch.getStatus()) || "REFINED".equals(ch.getStatus()) || "REFINING".equals(ch.getStatus()))
                .map(ChapterOutlineEntity::getChapterNumber)
                .collect(Collectors.toSet());

        log.info("[P{}] Outline generation: totalChapters={} volumes={} completedVolumes={} completedChapters={}",
                projectId, totalChapters, volumes.size(), completedVolumeNums.size(), completedChapterNums.size());

        // Pre-fill volumeArcSummaries from existing data
        List<String> volumeArcSummaries = new ArrayList<>();
        for (VolumeRange vol : volumes) {
            existingVolumes.stream()
                    .filter(v -> v.getVolumeNumber() == vol.volumeNumber())
                    .findFirst()
                    .map(VolumeOutlineEntity::getArcSummary)
                    .filter(s -> s != null && !s.isBlank())
                    .ifPresentOrElse(volumeArcSummaries::add, () -> volumeArcSummaries.add(""));
        }

        // Phase 1: Generate volume arcs
        Flux<String> phase1 = Flux.fromIterable(volumes)
                .concatMap(vol -> {
                    String marker = "[[SECTION:VOLUME:" + vol.volumeNumber() + ":" + vol.chapterStart() + ":" + vol.chapterEnd() + "]]";
                    if (completedVolumeNums.contains(vol.volumeNumber())) {
                        log.debug("[P{}] Volume {} skipped (already exists)", projectId, vol.volumeNumber());
                        String existing = volumeArcSummaries.get(vol.volumeNumber() - 1);
                        return Flux.just(marker, existing);
                    }
                    log.info("[P{}] Volume {} generating (ch{}-{})", projectId, vol.volumeNumber(), vol.chapterStart(), vol.chapterEnd());
                    long volStart = System.currentTimeMillis();
                    StringBuilder arcContent = new StringBuilder();
                    Flux<String> arcFlux = generateSingleVolumeArc(baseContext, vol, totalChapters, resolved, guidanceSuffix, volumeArcSummaries)
                            .doOnNext(arcContent::append)
                            .doOnComplete(() -> {
                                String text = arcContent.toString();
                                volumeArcSummaries.set(vol.volumeNumber() - 1, text);
                                saveSingleVolumeArc(projectId, vol, text);
                                long volElapsed = System.currentTimeMillis() - volStart;
                                log.info("[P{}] Volume {} done ({}s, {}chars)", projectId, vol.volumeNumber(),
                                        volElapsed / 1000, text.length());
                                aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), volElapsed);
                            });
                    return Flux.just(marker).concatWith(arcFlux);
                });

        // Phase 2: Generate chapter outlines
        Map<Integer, String> outlineMap = new java.util.concurrent.ConcurrentHashMap<>();
        existingChapters.forEach(ch -> {
            if (ch.getSummary() != null && !ch.getSummary().isBlank()) {
                outlineMap.put(ch.getChapterNumber(), ch.getSummary());
            }
        });

        Flux<String> phase2 = Flux.fromIterable(volumes)
                .concatMap(vol -> {
                    return Flux.range(vol.chapterStart(), vol.chapterEnd() - vol.chapterStart() + 1)
                            .concatMap(chapterNum -> {
                                String chMarker = "[[SECTION:CHAPTER:" + chapterNum + ":" + vol.volumeNumber() + "]]";
                                if (completedChapterNums.contains(chapterNum)) {
                                    log.debug("[P{}] Chapter outline {} skipped (already exists)", projectId, chapterNum);
                                    String existingSummary = existingChapters.stream()
                                            .filter(ch -> ch.getChapterNumber() == chapterNum)
                                            .findFirst()
                                            .map(ChapterOutlineEntity::getSummary)
                                            .orElse("");
                                    return Flux.just(chMarker, existingSummary);
                                }
                                List<String> previousOutlines = new ArrayList<>();
                                int prevStart = Math.max(1, chapterNum - 5);
                                for (int i = prevStart; i < chapterNum; i++) {
                                    previousOutlines.add(outlineMap.getOrDefault(i, ""));
                                }
                                List<String> nextOutlines = List.of();

                                updateChapterOutlineStatus(projectId, chapterNum, "GENERATING");
                                log.info("[P{}] Chapter outline {} generating (vol{})", projectId, chapterNum, vol.volumeNumber());
                                long chStart = System.currentTimeMillis();
                                String volumeArc = volumeArcSummaries.get(vol.volumeNumber() - 1);
                                StringBuilder chContent = new StringBuilder();
                                Flux<String> chFlux = generateSingleChapterOutlineV2(
                                        baseContext, chapterNum, totalChapters, vol, volumeArc, previousOutlines, nextOutlines, resolved, guidanceSuffix)
                                        .doOnNext(chContent::append)
                                        .doOnComplete(() -> {
                                            String text = chContent.toString();
                                            outlineMap.put(chapterNum, text);
                                            saveSingleChapterOutline(projectId, chapterNum, vol.volumeNumber(), text);
                                            updateChapterOutlineStatus(projectId, chapterNum, "COMPLETED");
                                            long chElapsed = System.currentTimeMillis() - chStart;
                                            log.info("[P{}] Chapter outline {} done ({}s, {}chars)", projectId, chapterNum,
                                                    chElapsed / 1000, text.length());
                                            aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), chElapsed);
                                        })
                                        .doOnError(e -> updateChapterOutlineStatus(projectId, chapterNum, "FAILED"));
                                return Flux.just(chMarker).concatWith(chFlux);
                            });
                });

        // Phase 2.5: Refine chapter outlines
        Flux<String> phase25 = Flux.defer(() -> {
            List<ChapterOutlineEntity> allOutlines = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);
            Set<Integer> alreadyRefined = allOutlines.stream()
                    .filter(ChapterOutlineEntity::isRefined)
                    .map(ChapterOutlineEntity::getChapterNumber)
                    .collect(Collectors.toSet());

            log.info("[P{}] Phase 2.5 refine: total={} alreadyRefined={}", projectId, allOutlines.size(), alreadyRefined.size());

            return Flux.fromIterable(volumes)
                    .concatMap(vol -> Flux.range(vol.chapterStart(), vol.chapterEnd() - vol.chapterStart() + 1)
                            .concatMap(chapterNum -> {
                                if (alreadyRefined.contains(chapterNum)) {
                                    log.debug("[P{}] Chapter {} refine skipped (already refined)", projectId, chapterNum);
                                    return Flux.empty();
                                }

                                String refineMarker = "[[SECTION:REFINE:" + chapterNum + ":" + vol.volumeNumber() + "]]";

                                List<String> prevOutlinesForRefine = new ArrayList<>();
                                int refPrevStart = Math.max(1, chapterNum - 3);
                                for (int pi = refPrevStart; pi < chapterNum; pi++) {
                                    prevOutlinesForRefine.add(chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, pi)
                                            .map(ChapterOutlineEntity::getSummary).orElse(""));
                                }
                                String currentOutline = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNum)
                                        .map(ChapterOutlineEntity::getSummary).orElse("");
                                List<String> nextOutlinesForRefine = new ArrayList<>();
                                for (int ni = chapterNum + 1; ni <= Math.min(totalChapters, chapterNum + 2); ni++) {
                                    nextOutlinesForRefine.add(chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, ni)
                                            .map(ChapterOutlineEntity::getSummary).orElse(""));
                                }
                                String volumeArc = volumeArcSummaries.get(vol.volumeNumber() - 1);

                                updateChapterOutlineStatus(projectId, chapterNum, "REFINING");
                                log.info("[P{}] Chapter {} refining (vol{})", projectId, chapterNum, vol.volumeNumber());
                                long refineStart = System.currentTimeMillis();
                                StringBuilder refineContent = new StringBuilder();

                                Flux<String> refineFlux = generateSingleChapterRefine(
                                        baseContext, chapterNum, totalChapters, volumeArc,
                                        prevOutlinesForRefine, currentOutline, nextOutlinesForRefine, resolved, guidanceSuffix)
                                        .doOnNext(refineContent::append)
                                        .doOnComplete(() -> {
                                            String text = refineContent.toString();
                                            saveSingleChapterOutline(projectId, chapterNum, vol.volumeNumber(), text);
                                            chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNum)
                                                    .ifPresent(entity -> {
                                                        entity.setRefined(true);
                                                        entity.setStatus("REFINED");
                                                        chapterOutlineRepository.save(entity);
                                                    });
                                            long refineElapsed = System.currentTimeMillis() - refineStart;
                                            log.info("[P{}] Chapter {} refined ({}s, {}chars)", projectId, chapterNum,
                                                    refineElapsed / 1000, text.length());
                                            aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), refineElapsed);
                                        })
                                        .doOnError(e -> updateChapterOutlineStatus(projectId, chapterNum, "FAILED"));

                                return Flux.just(refineMarker).concatWith(refineFlux);
                            }));
        });

        // Phase 3: Generate story summary
        Flux<String> phase3 = Flux.defer(() -> {
            String summaryMarker = "[[SECTION:SUMMARY]]";
            long summaryStart = System.currentTimeMillis();
            StringBuilder summaryContent = new StringBuilder();
            Flux<String> summaryFlux = generateStorySummary(baseContext, totalChapters, volumeArcSummaries, resolved, guidanceSuffix)
                    .doOnNext(summaryContent::append)
                    .doOnComplete(() -> {
                        saveStorySummaryToDb(projectId, summaryContent.toString());
                        aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - summaryStart);
                    });
            return Flux.just(summaryMarker).concatWith(summaryFlux);
        });

        return phase1.concatWith(phase2).concatWith(phase25).concatWith(phase3);
    }

    public Flux<String> regenerateChapterOutline(Long projectId, int chapterNumber) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        int totalChapters = project.getTotalChapters();
        WorkflowContext baseContext = contextBuilder.build(projectId, 0);
        baseContext.setTotalChapters(totalChapters);

        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.OUTLINE_GENERATION);
        String guidanceSuffix = stepGuidanceRepository.findByProjectIdAndStep(projectId, WorkflowStep.OUTLINE_GENERATION)
                .filter(sg -> sg.getGuidance() != null && !sg.getGuidance().isBlank())
                .map(sg -> "\n\n【创作指导】\n" + sg.getGuidance() + "\n请在生成时参考以上指导意见。")
                .orElse("");

        List<VolumeRange> volumes = computeVolumes(totalChapters, project.getChaptersPerVolume());
        VolumeRange vol = volumes.stream()
                .filter(v -> chapterNumber >= v.chapterStart() && chapterNumber <= v.chapterEnd())
                .findFirst()
                .orElse(new VolumeRange(1, 1, totalChapters));

        String volumeArc = volumeOutlineRepository.findByProjectIdAndVolumeNumber(projectId, vol.volumeNumber())
                .map(VolumeOutlineEntity::getArcSummary)
                .orElse("");

        List<String> previousOutlines = new ArrayList<>();
        int prevStart = Math.max(1, chapterNumber - 5);
        for (int i = prevStart; i < chapterNumber; i++) {
            String outline = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, i)
                    .map(ChapterOutlineEntity::getSummary).orElse("");
            previousOutlines.add(outline);
        }

        List<String> nextOutlines = new ArrayList<>();
        for (int i = chapterNumber + 1; i <= Math.min(totalChapters, chapterNumber + 2); i++) {
            String outline = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, i)
                    .map(ChapterOutlineEntity::getSummary).orElse("");
            nextOutlines.add(outline);
        }

        log.info("[P{}] Regenerating chapter outline {}", projectId, chapterNumber);
        long regenStart = System.currentTimeMillis();

        StringBuilder chContent = new StringBuilder();
        return generateSingleChapterOutlineV2(baseContext, chapterNumber, totalChapters, vol, volumeArc, previousOutlines, nextOutlines, resolved, guidanceSuffix)
                .doOnNext(chContent::append)
                .doOnComplete(() -> {
                    String text = chContent.toString();
                    saveSingleChapterOutline(projectId, chapterNumber, vol.volumeNumber(), text);
                    long regenElapsed = System.currentTimeMillis() - regenStart;
                    log.info("[P{}] Chapter outline {} regenerated ({}chars, {}s)", projectId, chapterNumber, text.length(), regenElapsed / 1000);
                    aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), regenElapsed);
                });
    }

    // --- Private helpers ---

    record VolumeRange(int volumeNumber, int chapterStart, int chapterEnd) {}

    private List<VolumeRange> computeVolumes(int totalChapters, int volumeSize) {
        if (volumeSize <= 0) volumeSize = 10;
        List<VolumeRange> volumes = new ArrayList<>();
        int vol = 1;
        for (int start = 1; start <= totalChapters; start += volumeSize) {
            int end = Math.min(start + volumeSize - 1, totalChapters);
            volumes.add(new VolumeRange(vol++, start, end));
        }
        return volumes;
    }

    private Flux<String> generateSingleVolumeArc(WorkflowContext baseContext,
                                                   VolumeRange vol, int totalChapters,
                                                   AiProviderRouter.ResolvedModel resolved,
                                                   String guidanceSuffix,
                                                   List<String> previousArcSummaries) {
        StringBuilder previousContext = new StringBuilder();
        if (!previousArcSummaries.isEmpty()) {
            previousContext.append("\n【前文各卷弧线摘要】\n");
            for (int i = 0; i < previousArcSummaries.size(); i++) {
                previousContext.append("第").append(i + 1).append("卷：")
                        .append(truncate(previousArcSummaries.get(i), 300)).append("\n\n");
            }
        }

        Genre genre = baseContext.getGenre();
        String template = promptRegistry.getSubStepTemplate(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.VOLUME_ARC, genre);
        Map<String, String> vars = Map.ofEntries(
                Map.entry("title", baseContext.getTitle() != null ? baseContext.getTitle() : ""),
                Map.entry("genre", genre != null ? genre.getDisplayName() : ""),
                Map.entry("description", baseContext.getDescription() != null ? baseContext.getDescription() : ""),
                Map.entry("worldSetting", wrapContent(truncate(baseContext.getWorldSetting(), 400))),
                Map.entry("characters", wrapContent(truncate(baseContext.getCharacters(), 400))),
                Map.entry("totalChapters", String.valueOf(totalChapters)),
                Map.entry("volumeNumber", String.valueOf(vol.volumeNumber())),
                Map.entry("chapterStart", String.valueOf(vol.chapterStart())),
                Map.entry("chapterEnd", String.valueOf(vol.chapterEnd())),
                Map.entry("previousArcs", previousContext.toString()),
                Map.entry("stepGuidance", guidanceSuffix)
        );
        String prompt = promptRegistry.resolveTemplate(template, vars);
        String systemPrompt = promptRegistry.getSubStepSystemPrompt(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.VOLUME_ARC, genre);
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "你是一位经验丰富的网络小说策划，擅长设计故事弧线和节奏控制。";
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(1024)
                .temperature(0.75)
                .build();
        applyResolvedConfig(request, resolved);

        return Flux.defer(() -> resolved.provider().streamText(request))
                .retryWhen(retryOnConnectionReset("VolumeArc-" + vol.volumeNumber()));
    }

    private Flux<String> generateSingleChapterOutlineV2(WorkflowContext baseContext,
                                                          int chapterNum, int totalChapters,
                                                          VolumeRange vol, String volumeArc,
                                                          List<String> previousOutlines,
                                                          List<String> nextOutlines,
                                                          AiProviderRouter.ResolvedModel resolved,
                                                          String guidanceSuffix) {
        String phaseHint;
        double progress = (double) chapterNum / totalChapters;
        if (progress <= 0.2) phaseHint = "开篇引入阶段";
        else if (progress <= 0.4) phaseHint = "发展铺垫阶段";
        else if (progress <= 0.6) phaseHint = "中段高潮阶段";
        else if (progress <= 0.8) phaseHint = "深入发展阶段";
        else phaseHint = "收束结局阶段";

        StringBuilder contextInfo = new StringBuilder();
        if (volumeArc != null && !volumeArc.isBlank()) {
            contextInfo.append("\n【本卷故事弧线】").append(wrapContent(truncate(volumeArc, 500)));
        }
        boolean hasAdjacentContext = (previousOutlines != null && !previousOutlines.isEmpty())
                || (nextOutlines != null && !nextOutlines.isEmpty());
        if (hasAdjacentContext) {
            contextInfo.append("\n===== 以下为相邻章节大纲（仅供了解前后脉络，严禁照搬内容） =====\n");
        }
        if (previousOutlines != null && !previousOutlines.isEmpty()) {
            contextInfo.append("【前文章节大纲】\n");
            int startChapter = chapterNum - previousOutlines.size();
            for (int i = 0; i < previousOutlines.size(); i++) {
                String outline = previousOutlines.get(i);
                if (outline != null && !outline.isBlank()) {
                    contextInfo.append("第").append(startChapter + i).append("章：")
                            .append(truncate(outline, 300)).append("\n");
                }
            }
        }
        if (nextOutlines != null && !nextOutlines.isEmpty()) {
            contextInfo.append("【后续章节大纲】\n");
            for (int i = 0; i < nextOutlines.size(); i++) {
                String outline = nextOutlines.get(i);
                if (outline != null && !outline.isBlank()) {
                    contextInfo.append("第").append(chapterNum + 1 + i).append("章：")
                            .append(truncate(outline, 300)).append("\n");
                }
            }
        }
        if (hasAdjacentContext) {
            contextInfo.append("===== 相邻章节大纲结束（以上仅供参考，你必须生成全新的独特内容） =====\n");
        }

        Genre genre = baseContext.getGenre();
        String template = promptRegistry.getSubStepTemplate(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.CHAPTER_OUTLINE, genre);
        Map<String, String> vars = Map.ofEntries(
                Map.entry("title", baseContext.getTitle() != null ? baseContext.getTitle() : ""),
                Map.entry("genre", genre != null ? genre.getDisplayName() : ""),
                Map.entry("worldSetting", wrapContent(truncate(baseContext.getWorldSetting(), 300))),
                Map.entry("characters", wrapContent(truncate(baseContext.getCharacters(), 300))),
                Map.entry("chapterNumber", String.valueOf(chapterNum)),
                Map.entry("totalChapters", String.valueOf(totalChapters)),
                Map.entry("chapterStart", String.valueOf(vol.chapterStart())),
                Map.entry("chapterEnd", String.valueOf(vol.chapterEnd())),
                Map.entry("phaseHint", phaseHint),
                Map.entry("contextInfo", contextInfo.toString()),
                Map.entry("stepGuidance", guidanceSuffix)
        );
        String prompt = promptRegistry.resolveTemplate(template, vars);
        String systemPrompt = promptRegistry.getSubStepSystemPrompt(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.CHAPTER_OUTLINE, genre);
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "你是一位网络小说策划，请简洁地生成单章大纲。直接输出大纲内容，禁止输出任何分析、评论或解释说明。";
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(768)
                .temperature(0.7)
                .build();
        applyResolvedConfig(request, resolved);

        return Flux.defer(() -> resolved.provider().streamText(request))
                .retryWhen(retryOnConnectionReset("ChapterOutline-" + chapterNum));
    }

    private Flux<String> generateSingleChapterRefine(WorkflowContext baseContext,
                                                       int chapterNum, int totalChapters,
                                                       String volumeArc,
                                                       List<String> previousOutlines,
                                                       String currentChapterOutline,
                                                       List<String> nextOutlines,
                                                       AiProviderRouter.ResolvedModel resolved,
                                                       String guidanceSuffix) {
        StringBuilder contextInfo = new StringBuilder();
        if (volumeArc != null && !volumeArc.isBlank()) {
            contextInfo.append("\n【本卷故事弧线】").append(wrapContent(truncate(volumeArc, 500)));
        }
        boolean hasAdjacentContext = (previousOutlines != null && !previousOutlines.isEmpty())
                || (nextOutlines != null && !nextOutlines.isEmpty());
        if (hasAdjacentContext) {
            contextInfo.append("\n===== 以下为相邻章节大纲（仅供了解前后脉络，严禁照搬内容） =====\n");
        }
        if (previousOutlines != null && !previousOutlines.isEmpty()) {
            contextInfo.append("【前文章节大纲】\n");
            int startChapter = chapterNum - previousOutlines.size();
            for (int i = 0; i < previousOutlines.size(); i++) {
                String outline = previousOutlines.get(i);
                if (outline != null && !outline.isBlank()) {
                    contextInfo.append("第").append(startChapter + i).append("章：")
                            .append(truncate(outline, 300)).append("\n");
                }
            }
        }
        if (nextOutlines != null && !nextOutlines.isEmpty()) {
            contextInfo.append("【后续章节大纲】\n");
            for (int i = 0; i < nextOutlines.size(); i++) {
                String outline = nextOutlines.get(i);
                if (outline != null && !outline.isBlank()) {
                    contextInfo.append("第").append(chapterNum + 1 + i).append("章：")
                            .append(truncate(outline, 300)).append("\n");
                }
            }
        }
        if (hasAdjacentContext) {
            contextInfo.append("===== 相邻章节大纲结束（以上仅供参考，你必须生成全新的独特内容） =====\n");
        }

        Genre genre = baseContext.getGenre();
        String template = promptRegistry.getSubStepTemplate(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.CHAPTER_OUTLINE_REFINE, genre);
        Map<String, String> vars = Map.of(
                "title", baseContext.getTitle() != null ? baseContext.getTitle() : "",
                "genre", genre != null ? genre.getDisplayName() : "",
                "worldSetting", wrapContent(truncate(baseContext.getWorldSetting(), 300)),
                "characters", wrapContent(truncate(baseContext.getCharacters(), 300)),
                "chapterNumber", String.valueOf(chapterNum),
                "totalChapters", String.valueOf(totalChapters),
                "contextInfo", contextInfo.toString(),
                "currentOutline", currentChapterOutline,
                "stepGuidance", guidanceSuffix
        );
        String prompt = promptRegistry.resolveTemplate(template, vars);
        String systemPrompt = promptRegistry.getSubStepSystemPrompt(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.CHAPTER_OUTLINE_REFINE, genre);
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "你是一位网络小说策划，正在对章节大纲进行精修校对。直接输出精修后的大纲内容，禁止输出任何分析过程、修改说明或策划笔记。";
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(768)
                .temperature(0.65)
                .build();
        applyResolvedConfig(request, resolved);

        return Flux.defer(() -> resolved.provider().streamText(request))
                .retryWhen(retryOnConnectionReset("ChapterRefine-" + chapterNum));
    }

    private Flux<String> generateStorySummary(WorkflowContext baseContext, int totalChapters,
                                               List<String> volumeArcSummaries,
                                               AiProviderRouter.ResolvedModel resolved,
                                               String guidanceSuffix) {
        StringBuilder arcsInfo = new StringBuilder();
        for (int i = 0; i < volumeArcSummaries.size(); i++) {
            arcsInfo.append("第").append(i + 1).append("卷：")
                    .append(wrapContent(truncate(volumeArcSummaries.get(i), 400))).append("\n");
        }

        Genre genre = baseContext.getGenre();
        String template = promptRegistry.getSubStepTemplate(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.STORY_SUMMARY, genre);
        Map<String, String> vars = Map.of(
                "title", baseContext.getTitle() != null ? baseContext.getTitle() : "",
                "genre", genre != null ? genre.getDisplayName() : "",
                "description", baseContext.getDescription() != null ? baseContext.getDescription() : "",
                "totalChapters", String.valueOf(totalChapters),
                "arcsInfo", arcsInfo.toString(),
                "stepGuidance", guidanceSuffix
        );
        String prompt = promptRegistry.resolveTemplate(template, vars);
        String systemPrompt = promptRegistry.getSubStepSystemPrompt(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.STORY_SUMMARY, genre);
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "你是一位经验丰富的网络小说策划，请生成完整的故事总纲。";
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(1536)
                .temperature(0.7)
                .build();
        applyResolvedConfig(request, resolved);

        return Flux.defer(() -> resolved.provider().streamText(request))
                .retryWhen(retryOnConnectionReset("StorySummary"));
    }

    // --- Retry helper ---

    private Retry retryOnConnectionReset(String context) {
        return Retry.backoff(3, Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(10))
                .filter(this::isRetryableError)
                .doBeforeRetry(signal -> log.warn("[{}] Retrying due to transient error (attempt {}): {}",
                        context, signal.totalRetries() + 1, signal.failure().getMessage()));
    }

    private boolean isRetryableError(Throwable e) {
        if (e instanceof IOException) return true;
        String msg = e.getMessage();
        if (msg == null) {
            return e.getCause() instanceof IOException;
        }
        return msg.contains("Connection reset")
                || msg.contains("connection reset")
                || msg.contains("Connection refused")
                || msg.contains("Connection timed out")
                || msg.contains("Connection prematurely closed")
                || msg.contains("premature close")
                || msg.contains("GOAWAY")
                || msg.contains("connection was aborted");
    }

    // --- Persistence helpers ---

    private void saveSingleVolumeArc(Long projectId, VolumeRange vol, String content) {
        content = stripAiFormatting(content);
        VolumeOutlineEntity entity = volumeOutlineRepository
                .findByProjectIdAndVolumeNumber(projectId, vol.volumeNumber())
                .orElseGet(() -> {
                    VolumeOutlineEntity v = new VolumeOutlineEntity();
                    v.setProjectId(projectId);
                    v.setVolumeNumber(vol.volumeNumber());
                    return v;
                });
        entity.setChapterStart(vol.chapterStart());
        entity.setChapterEnd(vol.chapterEnd());
        entity.setArcSummary(content);
        entity.setTitle("第" + vol.volumeNumber() + "卷");
        volumeOutlineRepository.save(entity);
    }

    private void preCreateChapterOutlines(Long projectId, int totalChapters, List<VolumeRange> volumes) {
        for (VolumeRange vol : volumes) {
            for (int chNum = vol.chapterStart(); chNum <= vol.chapterEnd(); chNum++) {
                int chapterNum = chNum;
                ChapterOutlineEntity existing = chapterOutlineRepository
                        .findByProjectIdAndChapterNumber(projectId, chapterNum)
                        .orElse(null);
                if (existing == null) {
                    ChapterOutlineEntity entity = new ChapterOutlineEntity();
                    entity.setProjectId(projectId);
                    entity.setChapterNumber(chapterNum);
                    entity.setVolumeNumber(vol.volumeNumber());
                    entity.setStatus("PENDING");
                    chapterOutlineRepository.save(entity);
                } else if ("GENERATING".equals(existing.getStatus())) {
                    existing.setStatus("PENDING");
                    chapterOutlineRepository.save(existing);
                }
            }
        }
        log.info("[P{}] Pre-created/verified {} chapter outline records", projectId, totalChapters);
    }

    private void updateChapterOutlineStatus(Long projectId, int chapterNum, String status) {
        chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNum)
                .ifPresent(entity -> {
                    entity.setStatus(status);
                    chapterOutlineRepository.save(entity);
                });
    }

    private void saveSingleChapterOutline(Long projectId, int chapterNum, int volumeNum, String content) {
        String text = content.strip();

        int titleIdx = text.indexOf("**标题：**");
        if (titleIdx < 0) titleIdx = text.indexOf("**标题:**");
        if (titleIdx > 0) {
            text = text.substring(titleIdx);
        }

        String[] trailingPatterns = {"---", "策划笔记", "修改说明", "精修逻辑", "【备注】", "【说明】", "注：", "注意："};
        for (String pattern : trailingPatterns) {
            int idx = text.lastIndexOf(pattern);
            if (idx > 0) {
                int newlineIdx = text.lastIndexOf('\n', idx);
                if (newlineIdx >= 0 && text.substring(newlineIdx, idx).isBlank()) {
                    text = text.substring(0, newlineIdx).stripTrailing();
                }
            }
        }

        content = text;

        Pattern titlePattern = Pattern.compile("\\*\\*标题[：:]\\*\\*\\s*(.+)");
        Pattern characterPattern = Pattern.compile("\\*\\*出场角色[：:]\\*\\*\\s*(.+)");

        String title = null;
        Matcher tMatcher = titlePattern.matcher(content);
        if (tMatcher.find()) {
            title = tMatcher.group(1).trim();
            if (title.length() > 200) title = title.substring(0, 200);
        }

        String characterNames = null;
        Matcher cMatcher = characterPattern.matcher(content);
        if (cMatcher.find()) {
            characterNames = cMatcher.group(1).trim();
            if (characterNames.length() > 500) characterNames = characterNames.substring(0, 500);
        }

        String summary = content
                .replaceFirst("\\*\\*标题[：:]\\*\\*[^\\n]*\\n?", "")
                .replaceFirst("\\*\\*出场角色[：:]\\*\\*[^\\n]*\\n?", "")
                .strip();

        ChapterOutlineEntity entity = chapterOutlineRepository
                .findByProjectIdAndChapterNumber(projectId, chapterNum)
                .orElseGet(() -> {
                    ChapterOutlineEntity e = new ChapterOutlineEntity();
                    e.setProjectId(projectId);
                    e.setChapterNumber(chapterNum);
                    return e;
                });
        entity.setVolumeNumber(volumeNum);
        entity.setTitle(stripAiFormatting(title != null ? title : "第" + chapterNum + "章"));
        entity.setSummary(stripAiFormatting(summary));
        entity.setCharacterNames(stripAiFormatting(characterNames));
        entity.setStatus("COMPLETED");
        entity.setRefined(false);
        chapterOutlineRepository.save(entity);
    }

    private void saveStorySummaryToDb(Long projectId, String content) {
        content = stripAiFormatting(content);
        StoryOutlineEntity outline = storyOutlineRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    StoryOutlineEntity o = new StoryOutlineEntity();
                    o.setProjectId(projectId);
                    return o;
                });
        outline.setContent(content);
        storyOutlineRepository.save(outline);
    }
}
