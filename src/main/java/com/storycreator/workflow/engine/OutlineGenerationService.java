package com.storycreator.workflow.engine;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorldFacetKey;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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
    private final CharacterRepository characterRepository;
    private final AiProviderRouter providerRouter;
    private final PromptTemplateRegistry promptRegistry;
    private final WorkflowContextBuilder contextBuilder;
    private final AiUsageTracker aiUsageTracker;
    private final AutoRunStepConfigRepository autoRunStepConfigRepository;
    private final WorldFacetElaborationService worldFacetElaborationService;

    public OutlineGenerationService(ProjectRepository projectRepository,
                                    ChapterOutlineRepository chapterOutlineRepository,
                                    VolumeOutlineRepository volumeOutlineRepository,
                                    StoryOutlineRepository storyOutlineRepository,
                                    StepGuidanceRepository stepGuidanceRepository,
                                    CharacterRepository characterRepository,
                                    AiProviderRouter providerRouter,
                                    PromptTemplateRegistry promptRegistry,
                                    WorkflowContextBuilder contextBuilder,
                                    AiUsageTracker aiUsageTracker,
                                    AutoRunStepConfigRepository autoRunStepConfigRepository,
                                    WorldFacetElaborationService worldFacetElaborationService) {
        this.projectRepository = projectRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.volumeOutlineRepository = volumeOutlineRepository;
        this.storyOutlineRepository = storyOutlineRepository;
        this.stepGuidanceRepository = stepGuidanceRepository;
        this.characterRepository = characterRepository;
        this.providerRouter = providerRouter;
        this.promptRegistry = promptRegistry;
        this.contextBuilder = contextBuilder;
        this.aiUsageTracker = aiUsageTracker;
        this.autoRunStepConfigRepository = autoRunStepConfigRepository;
        this.worldFacetElaborationService = worldFacetElaborationService;
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
                    AiCallConfig aiConfig = new AiCallConfig(resolved, guidanceSuffix);
                    Flux<String> arcFlux = generateSingleVolumeArc(baseContext, vol, totalChapters, aiConfig, volumeArcSummaries)
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

        // Phase 1.5: Generate volume characters
        Flux<String> phase15 = Flux.defer(() -> {
            ProjectEntity freshProject = projectRepository.findById(projectId).orElseThrow();
            double recurringRate = freshProject.getRecurringCharacterRate();
            double tempRate = freshProject.getTempCharacterRate();
            int totalVolumes = volumes.size();

            // Count already introduced volume characters per type
            List<CharacterEntity> existingVolumeChars = characterRepository.findByProjectIdOrderBySortOrder(projectId)
                    .stream().filter(c -> c.getSortOrder() > 0).toList();
            int alreadyRecurring = (int) existingVolumeChars.stream()
                    .filter(c -> "VOLUME_RECURRING".equals(c.getCharacterType())).count();
            int alreadyTemp = (int) existingVolumeChars.stream()
                    .filter(c -> "VOLUME_TEMP".equals(c.getCharacterType())).count();

            return Flux.fromIterable(volumes)
                    .concatMap(vol -> {
                        int recurringCount = VolumeCharacterCalculator.calculateForVolume(
                                vol.volumeNumber(), totalVolumes, recurringRate, alreadyRecurring, true);
                        int tempCount = VolumeCharacterCalculator.calculateForVolume(
                                vol.volumeNumber(), totalVolumes, tempRate, alreadyTemp, false);

                        if (recurringCount <= 0 && tempCount <= 0) {
                            return Flux.empty();
                        }

                        String marker = "[[SECTION:VOLCHARS:" + vol.volumeNumber() + "]]";
                        String volumeArc = volumeArcSummaries.get(vol.volumeNumber() - 1);

                        log.info("[P{}] Volume {} generating {} recurring + {} temp characters",
                                projectId, vol.volumeNumber(), recurringCount, tempCount);

                        long vcStart = System.currentTimeMillis();
                        StringBuilder vcContent = new StringBuilder();
                        AiCallConfig aiConfig = new AiCallConfig(resolved, guidanceSuffix);

                        final int rc = recurringCount;
                        final int tc = tempCount;
                        Flux<String> vcFlux = generateVolumeCharacters(baseContext, vol, totalVolumes,
                                volumeArc, rc, tc, aiConfig)
                                .doOnNext(vcContent::append)
                                .doOnComplete(() -> {
                                    String text = vcContent.toString();
                                    parseAndSaveVolumeCharacters(projectId, vol.volumeNumber(), text, rc, tc);
                                    long vcElapsed = System.currentTimeMillis() - vcStart;
                                    log.info("[P{}] Volume {} characters done ({}s, {}chars)",
                                            projectId, vol.volumeNumber(), vcElapsed / 1000, text.length());
                                    aiUsageTracker.record(projectId, resolved.modelId(),
                                            resolved.provider().getProviderName(), vcElapsed);
                                });
                        return Flux.just(marker).concatWith(vcFlux);
                    });
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
                                int prevStart = Math.max(1, chapterNum - 2);
                                for (int i = prevStart; i < chapterNum; i++) {
                                    previousOutlines.add(outlineMap.getOrDefault(i, ""));
                                }
                                List<String> nextOutlines = List.of();

                                updateChapterOutlineStatus(projectId, chapterNum, "GENERATING");
                                log.info("[P{}] Chapter outline {} generating (vol{})", projectId, chapterNum, vol.volumeNumber());
                                long chStart = System.currentTimeMillis();
                                String volumeArc = volumeArcSummaries.get(vol.volumeNumber() - 1);
                                StringBuilder chContent = new StringBuilder();
                                AiCallConfig aiConfig = new AiCallConfig(resolved, guidanceSuffix);
                                ChapterOutlineContext outlineCtx = new ChapterOutlineContext();
                                outlineCtx.setChapterNum(chapterNum);
                                outlineCtx.setTotalChapters(totalChapters);
                                outlineCtx.setVol(vol);
                                outlineCtx.setVolumeArc(volumeArc);
                                outlineCtx.setPreviousOutlines(previousOutlines);
                                outlineCtx.setNextOutlines(nextOutlines);
                                Flux<String> chFlux = generateSingleChapterOutlineV2(baseContext, outlineCtx, aiConfig)
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

                                List<ChapterOutlineInfo> prevOutlinesForRefine = gatherPreviousOutlines(projectId, chapterNum, vol, volumes);
                                List<ChapterOutlineInfo> nextOutlinesForRefine = gatherNextOutlines(projectId, chapterNum, totalChapters, vol, volumes);

                                ChapterOutlineEntity currentEntity = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNum)
                                        .orElse(null);
                                String currentOutline = buildCurrentOutlineText(chapterNum, currentEntity);
                                String volumeArc = volumeArcSummaries.get(vol.volumeNumber() - 1);

                                updateChapterOutlineStatus(projectId, chapterNum, "REFINING");
                                log.info("[P{}] Chapter {} refining (vol{})", projectId, chapterNum, vol.volumeNumber());
                                long refineStart = System.currentTimeMillis();
                                StringBuilder refineContent = new StringBuilder();

                                AiCallConfig aiConfig = new AiCallConfig(resolved, guidanceSuffix);
                                ChapterRefineContext refineCtx = new ChapterRefineContext();
                                refineCtx.setChapterNum(chapterNum);
                                refineCtx.setTotalChapters(totalChapters);
                                refineCtx.setVolumeArc(volumeArc);
                                refineCtx.setCurrentChapterOutline(currentOutline);
                                refineCtx.setPreviousOutlines(prevOutlinesForRefine);
                                refineCtx.setNextOutlines(nextOutlinesForRefine);
                                refineCtx.setCurrentVolume(vol);
                                // Save original summary before refinement overwrites it
                                if (currentEntity != null && currentEntity.getSummary() != null && !currentEntity.getSummary().isBlank()) {
                                    currentEntity.setOriginalSummary(currentEntity.getSummary());
                                    chapterOutlineRepository.save(currentEntity);
                                }

                                Flux<String> refineFlux = generateSingleChapterRefine(baseContext, refineCtx, aiConfig)
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

        // Phase 3: Generate story summary (skip if already exists)
        Flux<String> phase3 = Flux.defer(() -> {
            String summaryMarker = "[[SECTION:SUMMARY]]";
            // Skip if story summary already exists
            Optional<StoryOutlineEntity> existingSummary = storyOutlineRepository.findByProjectId(projectId);
            if (existingSummary.isPresent() && existingSummary.get().getContent() != null
                    && !existingSummary.get().getContent().isBlank()) {
                log.info("[P{}] Story summary already exists, skipping regeneration", projectId);
                return Flux.just(summaryMarker).concatWith(Flux.just(existingSummary.get().getContent()));
            }
            long summaryStart = System.currentTimeMillis();
            StringBuilder summaryContent = new StringBuilder();
            AiCallConfig aiConfig = new AiCallConfig(resolved, guidanceSuffix);
            Flux<String> summaryFlux = generateStorySummary(baseContext, totalChapters, volumeArcSummaries, aiConfig)
                    .doOnNext(summaryContent::append)
                    .doOnComplete(() -> {
                        saveStorySummaryToDb(projectId, summaryContent.toString());
                        aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - summaryStart);
                    });
            return Flux.just(summaryMarker).concatWith(summaryFlux);
        });

        Flux<String> conditionalRefine = Flux.defer(() ->
            isChapterOutlineRefineEnabled(projectId) ? phase25 : Flux.empty()
        );
        return phase1.concatWith(phase15).concatWith(phase2).concatWith(conditionalRefine).concatWith(phase3);
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
        int prevStart = Math.max(1, chapterNumber - 2);
        for (int i = prevStart; i < chapterNumber; i++) {
            String outline = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, i)
                    .map(ChapterOutlineEntity::getSummary).orElse("");
            previousOutlines.add(outline);
        }

        List<String> nextOutlines = List.of();

        log.info("[P{}] Regenerating chapter outline {}", projectId, chapterNumber);
        long regenStart = System.currentTimeMillis();

        AiCallConfig aiConfig = new AiCallConfig(resolved, guidanceSuffix);
        ChapterOutlineContext outlineCtx = new ChapterOutlineContext();
        outlineCtx.setChapterNum(chapterNumber);
        outlineCtx.setTotalChapters(totalChapters);
        outlineCtx.setVol(vol);
        outlineCtx.setVolumeArc(volumeArc);
        outlineCtx.setPreviousOutlines(previousOutlines);
        outlineCtx.setNextOutlines(nextOutlines);
        StringBuilder chContent = new StringBuilder();
        return generateSingleChapterOutlineV2(baseContext, outlineCtx, aiConfig)
                .doOnNext(chContent::append)
                .doOnComplete(() -> {
                    String text = chContent.toString();
                    saveSingleChapterOutline(projectId, chapterNumber, vol.volumeNumber(), text);
                    long regenElapsed = System.currentTimeMillis() - regenStart;
                    log.info("[P{}] Chapter outline {} regenerated ({}chars, {}s)", projectId, chapterNumber, text.length(), regenElapsed / 1000);
                    aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), regenElapsed);
                });
    }

    // --- Public variable builders (for prompt explore) ---

    public Map<String, String> buildVolumeArcVariables(Long projectId, int volumeNumber) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        int totalChapters = project.getTotalChapters();
        WorkflowContext baseContext = contextBuilder.build(projectId, 0);
        baseContext.setTotalChapters(totalChapters);

        String guidanceSuffix = stepGuidanceRepository.findByProjectIdAndStep(projectId, WorkflowStep.OUTLINE_GENERATION)
                .filter(sg -> sg.getGuidance() != null && !sg.getGuidance().isBlank())
                .map(sg -> "\n\n【创作指导】\n" + sg.getGuidance() + "\n请在生成时参考以上指导意见。")
                .orElse("");

        List<VolumeRange> volumes = computeVolumes(totalChapters, project.getChaptersPerVolume());
        VolumeRange vol = volumes.stream()
                .filter(v -> v.volumeNumber() == volumeNumber)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Volume not found: " + volumeNumber));

        List<VolumeOutlineEntity> existingVolumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
        StringBuilder previousContext = new StringBuilder();
        for (int i = 0; i < volumeNumber - 1; i++) {
            int volIdx = i;
            existingVolumes.stream()
                    .filter(v -> v.getVolumeNumber() == volIdx + 1)
                    .findFirst()
                    .map(VolumeOutlineEntity::getArcSummary)
                    .filter(s -> s != null && !s.isBlank())
                    .ifPresent(arc -> previousContext.append("第").append(volIdx + 1).append("卷：")
                            .append(truncate(arc, 300)).append("\n\n"));
        }
        if (!previousContext.isEmpty()) {
            previousContext.insert(0, "\n【前文各卷弧线摘要】\n");
        }

        String allCharacters = buildAllCharactersInfo(projectId);
        String storySummary = loadStorySummary(projectId);

        // Use WORLD_BACKGROUND + CONFLICT_ROOTS facets for volume arc (fallback to truncate)
        String worldSettingForArc;
        String worldBackground = safeStr(worldFacetElaborationService.getFacet(projectId, WorldFacetKey.WORLD_BACKGROUND));
        String conflictRoots = safeStr(worldFacetElaborationService.getFacet(projectId, WorldFacetKey.CONFLICT_ROOTS));
        if (!worldBackground.isEmpty() || !conflictRoots.isEmpty()) {
            StringBuilder wsSb = new StringBuilder();
            if (!worldBackground.isEmpty()) wsSb.append("【世界背景】\n").append(worldBackground);
            if (!conflictRoots.isEmpty()) {
                if (!wsSb.isEmpty()) wsSb.append("\n\n");
                wsSb.append("【冲突根源】\n").append(conflictRoots);
            }
            worldSettingForArc = wrapContent(wsSb.toString());
        } else {
            worldSettingForArc = wrapContent(truncate(baseContext.getWorldSetting(), 400));
        }

        Genre genre = baseContext.getGenre();
        return Map.ofEntries(
                Map.entry("title", baseContext.getTitle() != null ? baseContext.getTitle() : ""),
                Map.entry("genre", genre != null ? genre.getDisplayName() : ""),
                Map.entry("description", baseContext.getDescription() != null ? baseContext.getDescription() : ""),
                Map.entry("worldSetting", worldSettingForArc),
                Map.entry("characters", wrapContent(truncate(baseContext.getCharacters(), 400))),
                Map.entry("allCharacters", allCharacters),
                Map.entry("storySummary", storySummary),
                Map.entry("totalChapters", String.valueOf(totalChapters)),
                Map.entry("volumeNumber", String.valueOf(vol.volumeNumber())),
                Map.entry("chapterStart", String.valueOf(vol.chapterStart())),
                Map.entry("chapterEnd", String.valueOf(vol.chapterEnd())),
                Map.entry("previousArcs", previousContext.toString()),
                Map.entry("stepGuidance", guidanceSuffix)
        );
    }

    public Map<String, String> buildChapterOutlineVariables(Long projectId, int chapterNumber) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        int totalChapters = project.getTotalChapters();
        WorkflowContext baseContext = contextBuilder.build(projectId, 0);
        baseContext.setTotalChapters(totalChapters);

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
                .map(VolumeOutlineEntity::getArcSummary).orElse("");

        List<String> previousOutlines = new ArrayList<>();
        int prevStart = Math.max(1, chapterNumber - 2);
        for (int i = prevStart; i < chapterNumber; i++) {
            previousOutlines.add(chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, i)
                    .map(ChapterOutlineEntity::getSummary).orElse(""));
        }

        String phaseHint;
        double progress = (double) chapterNumber / totalChapters;
        if (progress <= 0.2) phaseHint = "开篇引入阶段";
        else if (progress <= 0.4) phaseHint = "发展铺垫阶段";
        else if (progress <= 0.6) phaseHint = "中段高潮阶段";
        else if (progress <= 0.8) phaseHint = "深入发展阶段";
        else phaseHint = "收束结局阶段";

        StringBuilder contextInfo = new StringBuilder();
        if (volumeArc != null && !volumeArc.isBlank()) {
            contextInfo.append("\n【本卷故事弧线】").append(wrapContent(volumeArc));
        }
        if (!previousOutlines.isEmpty()) {
            contextInfo.append("\n===== 以下为相邻章节大纲（仅供了解前后脉络，严禁照搬内容） =====\n");
            contextInfo.append("【前文章节大纲】\n");
            int startChapter = chapterNumber - previousOutlines.size();
            for (int i = 0; i < previousOutlines.size(); i++) {
                String outline = previousOutlines.get(i);
                if (outline != null && !outline.isBlank()) {
                    boolean isLastPrevious = (i == previousOutlines.size() - 1);
                    contextInfo.append("第").append(startChapter + i).append("章：")
                            .append(isLastPrevious ? outline : truncate(outline, 300)).append("\n");
                }
            }
            contextInfo.append("===== 相邻章节大纲结束（以上仅供参考，你必须生成全新的独特内容） =====\n");
        }

        String allCharacters = buildAllCharactersInfo(projectId);
        String storySummary = loadStorySummary(projectId);

        Genre genre = baseContext.getGenre();
        return Map.ofEntries(
                Map.entry("title", baseContext.getTitle() != null ? baseContext.getTitle() : ""),
                Map.entry("genre", genre != null ? genre.getDisplayName() : ""),
                Map.entry("characters", wrapContent(truncate(baseContext.getCharacters(), 1000))),
                Map.entry("allCharacters", allCharacters),
                Map.entry("storySummary", storySummary),
                Map.entry("chapterNumber", String.valueOf(chapterNumber)),
                Map.entry("totalChapters", String.valueOf(totalChapters)),
                Map.entry("chapterStart", String.valueOf(vol.chapterStart())),
                Map.entry("chapterEnd", String.valueOf(vol.chapterEnd())),
                Map.entry("phaseHint", phaseHint),
                Map.entry("contextInfo", contextInfo.toString()),
                Map.entry("stepGuidance", guidanceSuffix)
        );
    }

    public Map<String, String> buildChapterOutlineRefineVariables(Long projectId, int chapterNumber) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        int totalChapters = project.getTotalChapters();
        WorkflowContext baseContext = contextBuilder.build(projectId, 0);
        baseContext.setTotalChapters(totalChapters);

        List<VolumeRange> volumes = computeVolumes(totalChapters, project.getChaptersPerVolume());
        VolumeRange vol = volumes.stream()
                .filter(v -> chapterNumber >= v.chapterStart() && chapterNumber <= v.chapterEnd())
                .findFirst()
                .orElse(new VolumeRange(1, 1, totalChapters));

        String volumeArc = volumeOutlineRepository.findByProjectIdAndVolumeNumber(projectId, vol.volumeNumber())
                .map(VolumeOutlineEntity::getArcSummary).orElse("");

        List<ChapterOutlineInfo> previousOutlines = gatherPreviousOutlines(projectId, chapterNumber, vol, volumes);
        List<ChapterOutlineInfo> nextOutlines = gatherNextOutlines(projectId, chapterNumber, totalChapters, vol, volumes);

        ChapterOutlineEntity currentEntity = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElse(null);
        String currentOutline = buildCurrentOutlineText(chapterNumber, currentEntity);

        String contextInfo = buildRefineContextInfo(volumeArc, previousOutlines, nextOutlines);

        String allCharacters = buildAllCharactersInfo(projectId);

        Genre genre = baseContext.getGenre();
        return Map.ofEntries(
                Map.entry("title", baseContext.getTitle() != null ? baseContext.getTitle() : ""),
                Map.entry("genre", genre != null ? genre.getDisplayName() : ""),
                Map.entry("chapterNumber", String.valueOf(chapterNumber)),
                Map.entry("totalChapters", String.valueOf(totalChapters)),
                Map.entry("contextInfo", contextInfo),
                Map.entry("currentOutline", currentOutline),
                Map.entry("allCharacters", allCharacters)
        );
    }

    public Map<String, String> buildStorySummaryVariables(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        int totalChapters = project.getTotalChapters();
        WorkflowContext baseContext = contextBuilder.build(projectId, 0);

        String guidanceSuffix = stepGuidanceRepository.findByProjectIdAndStep(projectId, WorkflowStep.OUTLINE_GENERATION)
                .filter(sg -> sg.getGuidance() != null && !sg.getGuidance().isBlank())
                .map(sg -> "\n\n【创作指导】\n" + sg.getGuidance() + "\n请在生成时参考以上指导意见。")
                .orElse("");

        List<VolumeOutlineEntity> existingVolumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
        StringBuilder arcsInfo = new StringBuilder();
        for (VolumeOutlineEntity v : existingVolumes) {
            if (v.getArcSummary() != null && !v.getArcSummary().isBlank()) {
                arcsInfo.append("第").append(v.getVolumeNumber()).append("卷：")
                        .append(wrapContent(truncate(v.getArcSummary(), 400))).append("\n");
            }
        }

        Genre genre = baseContext.getGenre();
        return Map.of(
                "title", baseContext.getTitle() != null ? baseContext.getTitle() : "",
                "genre", genre != null ? genre.getDisplayName() : "",
                "description", baseContext.getDescription() != null ? baseContext.getDescription() : "",
                "totalChapters", String.valueOf(totalChapters),
                "arcsInfo", arcsInfo.toString(),
                "stepGuidance", guidanceSuffix
        );
    }

    // --- Private helpers ---

    private boolean isChapterOutlineRefineEnabled(Long projectId) {
        return autoRunStepConfigRepository
                .findByProjectIdAndStep(projectId, "CHAPTER_OUTLINE_REFINE")
                .map(AutoRunStepConfigEntity::isEnabled)
                .orElse(true);
    }

    // VolumeRange extracted to package-level file

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
                                                   AiCallConfig aiConfig,
                                                   List<String> previousArcSummaries) {
        AiProviderRouter.ResolvedModel resolved = aiConfig.resolved();
        String guidanceSuffix = aiConfig.guidanceSuffix();
        StringBuilder previousContext = new StringBuilder();
        if (!previousArcSummaries.isEmpty()) {
            previousContext.append("\n【前文各卷弧线摘要】\n");
            for (int i = 0; i < previousArcSummaries.size(); i++) {
                previousContext.append("第").append(i + 1).append("卷：")
                        .append(truncate(previousArcSummaries.get(i), 300)).append("\n\n");
            }
        }

        Long projectId = baseContext.getProjectId();
        String allCharacters = buildAllCharactersInfo(projectId);
        String storySummary = loadStorySummary(projectId);

        Genre genre = baseContext.getGenre();
        String template = promptRegistry.getSubStepTemplate(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.VOLUME_ARC, genre);
        Map<String, String> vars = Map.ofEntries(
                Map.entry("title", baseContext.getTitle() != null ? baseContext.getTitle() : ""),
                Map.entry("genre", genre != null ? genre.getDisplayName() : ""),
                Map.entry("description", baseContext.getDescription() != null ? baseContext.getDescription() : ""),
                Map.entry("worldSetting", wrapContent(truncate(baseContext.getWorldSetting(), 400))),
                Map.entry("characters", wrapContent(truncate(baseContext.getCharacters(), 400))),
                Map.entry("allCharacters", allCharacters),
                Map.entry("storySummary", storySummary),
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
                .maxTokens(2048)
                .temperature(0.75)
                .build();
        applyResolvedConfig(request, resolved);

        return resolved.provider().streamText(request);
    }

    private Flux<String> generateSingleChapterOutlineV2(WorkflowContext baseContext,
                                                          ChapterOutlineContext ctx,
                                                          AiCallConfig aiConfig) {
        AiProviderRouter.ResolvedModel resolved = aiConfig.resolved();
        String guidanceSuffix = aiConfig.guidanceSuffix();
        int chapterNum = ctx.getChapterNum();
        int totalChapters = ctx.getTotalChapters();
        VolumeRange vol = ctx.getVol();
        String volumeArc = ctx.getVolumeArc();
        List<String> previousOutlines = ctx.getPreviousOutlines();
        List<String> nextOutlines = ctx.getNextOutlines();
        String phaseHint;
        double progress = (double) chapterNum / totalChapters;
        if (progress <= 0.2) phaseHint = "开篇引入阶段";
        else if (progress <= 0.4) phaseHint = "发展铺垫阶段";
        else if (progress <= 0.6) phaseHint = "中段高潮阶段";
        else if (progress <= 0.8) phaseHint = "深入发展阶段";
        else phaseHint = "收束结局阶段";

        StringBuilder contextInfo = new StringBuilder();
        if (volumeArc != null && !volumeArc.isBlank()) {
            contextInfo.append("\n【本卷故事弧线】").append(wrapContent(volumeArc));
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
                    boolean isLastPrevious = (i == previousOutlines.size() - 1);
                    contextInfo.append("第").append(startChapter + i).append("章：")
                            .append(isLastPrevious ? outline : truncate(outline, 300)).append("\n");
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

        Long projectId = baseContext.getProjectId();
        String allCharacters = buildAllCharactersInfo(projectId);
        String storySummary = loadStorySummary(projectId);

        Genre genre = baseContext.getGenre();
        String template = promptRegistry.getSubStepTemplate(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.CHAPTER_OUTLINE, genre);
        Map<String, String> vars = Map.ofEntries(
                Map.entry("title", baseContext.getTitle() != null ? baseContext.getTitle() : ""),
                Map.entry("genre", genre != null ? genre.getDisplayName() : ""),
                Map.entry("characters", wrapContent(truncate(baseContext.getCharacters(), 1000))),
                Map.entry("allCharacters", allCharacters),
                Map.entry("storySummary", storySummary),
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
                .maxTokens(1536)
                .temperature(0.7)
                .build();
        applyResolvedConfig(request, resolved);

        return resolved.provider().streamText(request);
    }

    private Flux<String> generateSingleChapterRefine(WorkflowContext baseContext,
                                                       ChapterRefineContext ctx,
                                                       AiCallConfig aiConfig) {
        AiProviderRouter.ResolvedModel resolved = aiConfig.resolved();
        int chapterNum = ctx.getChapterNum();
        int totalChapters = ctx.getTotalChapters();
        String volumeArc = ctx.getVolumeArc();
        List<ChapterOutlineInfo> previousOutlines = ctx.getPreviousOutlines();
        String currentChapterOutline = ctx.getCurrentChapterOutline();
        List<ChapterOutlineInfo> nextOutlines = ctx.getNextOutlines();

        String contextInfo = buildRefineContextInfo(volumeArc, previousOutlines, nextOutlines);

        Long projectId = baseContext.getProjectId();
        String allCharacters = buildAllCharactersInfo(projectId);

        Genre genre = baseContext.getGenre();
        String template = promptRegistry.getSubStepTemplate(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.CHAPTER_OUTLINE_REFINE, genre);
        Map<String, String> vars = Map.ofEntries(
                Map.entry("title", baseContext.getTitle() != null ? baseContext.getTitle() : ""),
                Map.entry("genre", genre != null ? genre.getDisplayName() : ""),
                Map.entry("chapterNumber", String.valueOf(chapterNum)),
                Map.entry("totalChapters", String.valueOf(totalChapters)),
                Map.entry("contextInfo", contextInfo),
                Map.entry("currentOutline", currentChapterOutline),
                Map.entry("allCharacters", allCharacters)
        );
        String prompt = promptRegistry.resolveTemplate(template, vars);
        String systemPrompt = promptRegistry.getSubStepSystemPrompt(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.CHAPTER_OUTLINE_REFINE, genre);
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "你是一位网络小说策划，正在对章节大纲进行精修校对。直接输出精修后的大纲内容，禁止输出任何分析过程、修改说明或策划笔记。";
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(1536)
                .temperature(0.65)
                .build();
        applyResolvedConfig(request, resolved);

        return resolved.provider().streamText(request);
    }

    private Flux<String> generateStorySummary(WorkflowContext baseContext, int totalChapters,
                                               List<String> volumeArcSummaries,
                                               AiCallConfig aiConfig) {
        AiProviderRouter.ResolvedModel resolved = aiConfig.resolved();
        String guidanceSuffix = aiConfig.guidanceSuffix();
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

        return resolved.provider().streamText(request);
    }

    // --- Refine context helpers ---

    private List<ChapterOutlineInfo> gatherPreviousOutlines(Long projectId, int chapterNumber,
                                                             VolumeRange currentVol, List<VolumeRange> volumes) {
        int prevCount = 10;
        int prevFullCount = 2;
        List<ChapterOutlineInfo> result = new ArrayList<>();

        // Gather chapters within current volume before this chapter
        int volStart = currentVol.chapterStart();
        for (int ch = chapterNumber - 1; ch >= volStart && result.size() < prevCount; ch--) {
            result.add(0, loadChapterOutlineInfo(projectId, ch));
        }

        // If current chapter is first in volume, try to get 1 chapter from previous volume
        if (result.isEmpty()) {
            VolumeRange prevVol = volumes.stream()
                    .filter(v -> v.volumeNumber() == currentVol.volumeNumber() - 1)
                    .findFirst().orElse(null);
            if (prevVol != null) {
                result.add(0, loadChapterOutlineInfo(projectId, prevVol.chapterEnd()));
            }
        }

        // Mark fullContent: last prevFullCount items get full content
        List<ChapterOutlineInfo> marked = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            ChapterOutlineInfo info = result.get(i);
            boolean full = (i >= result.size() - prevFullCount);
            marked.add(new ChapterOutlineInfo(info.chapterNumber(), info.title(), info.characterNames(), info.summary(), full));
        }
        return marked;
    }

    private List<ChapterOutlineInfo> gatherNextOutlines(Long projectId, int chapterNumber, int totalChapters,
                                                          VolumeRange currentVol, List<VolumeRange> volumes) {
        int nextCount = 3;
        int nextFullCount = 1;
        List<ChapterOutlineInfo> result = new ArrayList<>();

        // Gather chapters within current volume after this chapter
        int volEnd = currentVol.chapterEnd();
        for (int ch = chapterNumber + 1; ch <= volEnd && result.size() < nextCount; ch++) {
            result.add(loadChapterOutlineInfo(projectId, ch));
        }

        // If current chapter is last in volume, try to get 1 chapter from next volume
        if (result.isEmpty()) {
            VolumeRange nextVol = volumes.stream()
                    .filter(v -> v.volumeNumber() == currentVol.volumeNumber() + 1)
                    .findFirst().orElse(null);
            if (nextVol != null) {
                result.add(loadChapterOutlineInfo(projectId, nextVol.chapterStart()));
            }
        }

        // Mark fullContent: first nextFullCount items get full content
        List<ChapterOutlineInfo> marked = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            ChapterOutlineInfo info = result.get(i);
            boolean full = (i < nextFullCount);
            marked.add(new ChapterOutlineInfo(info.chapterNumber(), info.title(), info.characterNames(), info.summary(), full));
        }
        return marked;
    }

    private ChapterOutlineInfo loadChapterOutlineInfo(Long projectId, int chapterNumber) {
        ChapterOutlineEntity entity = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElse(null);
        if (entity == null) {
            return new ChapterOutlineInfo(chapterNumber, "", "", "", false);
        }
        return new ChapterOutlineInfo(
                chapterNumber,
                entity.getTitle() != null ? entity.getTitle() : "",
                entity.getCharacterNames() != null ? entity.getCharacterNames() : "",
                entity.getSummary() != null ? entity.getSummary() : "",
                false
        );
    }

    private String buildCurrentOutlineText(int chapterNumber, ChapterOutlineEntity entity) {
        if (entity == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("第").append(chapterNumber).append("章");
        if (entity.getTitle() != null && !entity.getTitle().isBlank()) {
            sb.append("：").append(entity.getTitle());
        }
        sb.append("\n");
        if (entity.getCharacterNames() != null && !entity.getCharacterNames().isBlank()) {
            sb.append("出场角色：").append(entity.getCharacterNames()).append("\n");
        }
        if (entity.getSummary() != null && !entity.getSummary().isBlank()) {
            sb.append(entity.getSummary());
        }
        return sb.toString();
    }

    private String buildRefineContextInfo(String volumeArc, List<ChapterOutlineInfo> previousOutlines,
                                            List<ChapterOutlineInfo> nextOutlines) {
        StringBuilder contextInfo = new StringBuilder();
        if (volumeArc != null && !volumeArc.isBlank()) {
            contextInfo.append("【本卷故事弧线】\n").append(volumeArc).append("\n");
        }
        boolean hasAdjacentContext = (previousOutlines != null && !previousOutlines.isEmpty())
                || (nextOutlines != null && !nextOutlines.isEmpty());
        if (hasAdjacentContext) {
            contextInfo.append("\n===== 以下为相邻章节大纲（仅供了解前后脉络，严禁照搬内容） =====\n");
        }
        if (previousOutlines != null && !previousOutlines.isEmpty()) {
            contextInfo.append("【前文章节大纲】\n");
            for (ChapterOutlineInfo info : previousOutlines) {
                if (info.fullContent()) {
                    contextInfo.append("第").append(info.chapterNumber()).append("章：").append(info.title()).append("\n");
                    if (info.characterNames() != null && !info.characterNames().isBlank()) {
                        contextInfo.append("出场角色：").append(info.characterNames()).append("\n");
                    }
                    if (info.summary() != null && !info.summary().isBlank()) {
                        contextInfo.append(info.summary()).append("\n");
                    }
                } else {
                    contextInfo.append("第").append(info.chapterNumber()).append("章：").append(info.title()).append("\n");
                }
            }
        }
        if (nextOutlines != null && !nextOutlines.isEmpty()) {
            contextInfo.append("【后续章节大纲】\n");
            for (ChapterOutlineInfo info : nextOutlines) {
                if (info.fullContent()) {
                    contextInfo.append("第").append(info.chapterNumber()).append("章：").append(info.title()).append("\n");
                    if (info.characterNames() != null && !info.characterNames().isBlank()) {
                        contextInfo.append("出场角色：").append(info.characterNames()).append("\n");
                    }
                    if (info.summary() != null && !info.summary().isBlank()) {
                        contextInfo.append(info.summary()).append("\n");
                    }
                } else {
                    contextInfo.append("第").append(info.chapterNumber()).append("章：").append(info.title()).append("\n");
                }
            }
        }
        if (hasAdjacentContext) {
            contextInfo.append("===== 相邻章节大纲结束 =====\n");
        }
        return contextInfo.toString();
    }

    // --- Volume character generation helpers ---

    private Flux<String> generateVolumeCharacters(WorkflowContext baseContext, VolumeRange vol,
                                                    int totalVolumes, String volumeArc,
                                                    int recurringCount, int tempCount,
                                                    AiCallConfig aiConfig) {
        AiProviderRouter.ResolvedModel resolved = aiConfig.resolved();
        String guidanceSuffix = aiConfig.guidanceSuffix();
        Long projectId = baseContext.getProjectId();

        String allCharacters = buildAllCharactersInfo(projectId);
        String worldSetting = baseContext.getWorldSetting() != null ? baseContext.getWorldSetting() : "";

        Genre genre = baseContext.getGenre();
        String template = promptRegistry.getSubStepTemplate(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.VOLUME_CHARACTERS, genre);
        Map<String, String> vars = Map.ofEntries(
                Map.entry("title", baseContext.getTitle() != null ? baseContext.getTitle() : ""),
                Map.entry("genre", genre != null ? genre.getDisplayName() : ""),
                Map.entry("worldSetting", wrapContent(truncate(worldSetting, 400))),
                Map.entry("volumeArc", wrapContent(volumeArc != null ? volumeArc : "")),
                Map.entry("existingCharacters", allCharacters),
                Map.entry("volumeNumber", String.valueOf(vol.volumeNumber())),
                Map.entry("totalVolumes", String.valueOf(totalVolumes)),
                Map.entry("recurringCount", String.valueOf(recurringCount)),
                Map.entry("tempCount", String.valueOf(tempCount)),
                Map.entry("stepGuidance", guidanceSuffix)
        );
        String prompt = promptRegistry.resolveTemplate(template, vars);
        String systemPrompt = promptRegistry.getSubStepSystemPrompt(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.VOLUME_CHARACTERS, genre);
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "你是一位经验丰富的网络小说策划，擅长设计鲜明有特色的配角。";
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(2048)
                .temperature(0.8)
                .build();
        applyResolvedConfig(request, resolved);

        return resolved.provider().streamText(request);
    }

    private void parseAndSaveVolumeCharacters(Long projectId, int volumeNumber, String content, int recurringCount, int tempCount) {
        content = stripAiFormatting(content);
        // Parse character blocks delimited by 【角色名】
        String[] blocks = content.split("(?=【角色名】)");
        int maxSort = characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0)
                .stream().mapToInt(CharacterEntity::getSortOrder).max().orElse(0);

        int recurringParsed = 0;
        int tempParsed = 0;

        for (String block : blocks) {
            block = block.trim();
            if (block.isEmpty() || !block.startsWith("【角色名】")) continue;

            String name = extractField(block, "角色名");
            if (name == null || name.isBlank()) continue;

            String gender = extractField(block, "性别");
            String role = extractField(block, "身份/职业");
            String personality = extractField(block, "性格特点");
            String appearance = extractField(block, "外貌特征");
            String narrativeFunction = extractField(block, "叙事功能");
            String typeStr = extractField(block, "角色类型");

            boolean isRecurring = typeStr != null && typeStr.contains("长期");

            // Determine character type based on parsed type and remaining counts
            String characterType;
            if (isRecurring && recurringParsed < recurringCount) {
                characterType = "VOLUME_RECURRING";
                recurringParsed++;
            } else {
                characterType = "VOLUME_TEMP";
                tempParsed++;
            }

            CharacterEntity entity = new CharacterEntity();
            entity.setProjectId(projectId);
            entity.setName(name);
            entity.setGender(gender);
            entity.setRole(role);
            entity.setPersonality(personality);
            entity.setAppearance(appearance);
            entity.setDescription(narrativeFunction);
            entity.setStatus("GENERATED");
            entity.setSortOrder(++maxSort);
            entity.setCharacterType(characterType);
            entity.setStartVolume(volumeNumber);
            entity.setEndVolume("VOLUME_TEMP".equals(characterType) ? volumeNumber : null);

            // Build summary from parsed fields
            StringBuilder summary = new StringBuilder();
            if (role != null) summary.append("身份：").append(role).append("；");
            if (personality != null) summary.append("性格：").append(personality).append("；");
            if (narrativeFunction != null) summary.append("作用：").append(narrativeFunction);
            entity.setSummary(summary.toString());

            characterRepository.save(entity);
        }
        log.info("[P{}] Volume {} saved {} recurring + {} temp characters",
                projectId, volumeNumber, recurringParsed, tempParsed);
    }

    private String extractField(String block, String fieldName) {
        Pattern p = Pattern.compile("【" + Pattern.quote(fieldName) + "】(.+?)(?=【|$)", Pattern.DOTALL);
        Matcher m = p.matcher(block);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    // --- Public variable builder for volume characters (prompt explore) ---

    public Map<String, String> buildVolumeCharactersVariables(Long projectId, int volumeNumber) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        int totalChapters = project.getTotalChapters();
        WorkflowContext baseContext = contextBuilder.build(projectId, 0);

        String guidanceSuffix = stepGuidanceRepository.findByProjectIdAndStep(projectId, WorkflowStep.OUTLINE_GENERATION)
                .filter(sg -> sg.getGuidance() != null && !sg.getGuidance().isBlank())
                .map(sg -> "\n\n【创作指导】\n" + sg.getGuidance() + "\n请在生成时参考以上指导意见。")
                .orElse("");

        List<VolumeRange> volumes = computeVolumes(totalChapters, project.getChaptersPerVolume());
        int totalVolumes = volumes.size();

        String volumeArc = volumeOutlineRepository.findByProjectIdAndVolumeNumber(projectId, volumeNumber)
                .map(VolumeOutlineEntity::getArcSummary).orElse("");

        String allCharacters = buildAllCharactersInfo(projectId);

        // Calculate counts for display
        List<CharacterEntity> existingVolumeChars = characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        int alreadyRecurring = (int) existingVolumeChars.stream()
                .filter(c -> "VOLUME_RECURRING".equals(c.getCharacterType())
                        && c.getStartVolume() != null && c.getStartVolume() < volumeNumber).count();
        int alreadyTemp = (int) existingVolumeChars.stream()
                .filter(c -> "VOLUME_TEMP".equals(c.getCharacterType())
                        && c.getStartVolume() != null && c.getStartVolume() < volumeNumber).count();

        int recurringCount = VolumeCharacterCalculator.calculateForVolume(
                volumeNumber, totalVolumes, project.getRecurringCharacterRate(), alreadyRecurring, true);
        int tempCount = VolumeCharacterCalculator.calculateForVolume(
                volumeNumber, totalVolumes, project.getTempCharacterRate(), alreadyTemp, false);

        Genre genre = baseContext.getGenre();
        return Map.ofEntries(
                Map.entry("title", baseContext.getTitle() != null ? baseContext.getTitle() : ""),
                Map.entry("genre", genre != null ? genre.getDisplayName() : ""),
                Map.entry("worldSetting", wrapContent(truncate(baseContext.getWorldSetting() != null ? baseContext.getWorldSetting() : "", 400))),
                Map.entry("volumeArc", wrapContent(volumeArc)),
                Map.entry("existingCharacters", allCharacters),
                Map.entry("volumeNumber", String.valueOf(volumeNumber)),
                Map.entry("totalVolumes", String.valueOf(totalVolumes)),
                Map.entry("recurringCount", String.valueOf(recurringCount)),
                Map.entry("tempCount", String.valueOf(tempCount)),
                Map.entry("stepGuidance", guidanceSuffix)
        );
    }

    // --- Character & story summary helpers ---

    private String buildAllCharactersInfo(Long projectId) {
        List<CharacterEntity> allCards = characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        StringBuilder sb = new StringBuilder();
        for (CharacterEntity card : allCards) {
            sb.append("【").append(card.getName()).append("】");
            String cardText = card.getSummary() != null && !card.getSummary().isBlank()
                    ? card.getSummary()
                    : (card.getContent() != null ? truncate(card.getContent(), 300) : "");
            sb.append(cardText).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String loadStorySummary(Long projectId) {
        return storyOutlineRepository.findByProjectId(projectId)
                .map(StoryOutlineEntity::getContent)
                .filter(c -> c != null && !c.isBlank())
                .orElse("");
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

        // Parse arc name from first line prefix "故事弧线：XXX"
        String arcName = null;
        String firstLine = content.split("\\R", 2)[0].strip();
        if (firstLine.startsWith("故事弧线：")) {
            arcName = firstLine.substring("故事弧线：".length()).strip();
            if (arcName.length() > 200) arcName = arcName.substring(0, 200);
            if (arcName.isBlank()) arcName = null;
        }
        entity.setArcName(arcName);

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

        // 清除AI常见前缀标签
        String[] summaryPrefixes = {"大纲内容：", "大纲内容:", "章节大纲：", "章节大纲:", "精修内容：", "精修内容:", "正文内容：", "正文内容:"};
        for (String prefix : summaryPrefixes) {
            if (summary.startsWith(prefix)) {
                summary = summary.substring(prefix.length()).stripLeading();
                break;
            }
        }

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

    private static String safeStr(String s) {
        return s != null ? s : "";
    }
}
