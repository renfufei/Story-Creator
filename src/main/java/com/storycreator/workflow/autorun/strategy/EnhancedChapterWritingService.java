package com.storycreator.workflow.autorun.strategy;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ChapterOutlineEntity;
import com.storycreator.persistence.repository.ChapterOutlineRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.workflow.autorun.AutoRunContext;
import com.storycreator.workflow.engine.ContextSummaryService;
import com.storycreator.workflow.engine.WorkflowContextBuilder;
import com.storycreator.workflow.engine.WorkflowContext;
import com.storycreator.workflow.engine.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class EnhancedChapterWritingService {

    private static final Logger log = LoggerFactory.getLogger(EnhancedChapterWritingService.class);

    // Ordered status values for breakpoint resume
    private static final List<String> STATUS_ORDER = List.of(
            "NOT_STARTED", "BRIEFING_DONE", "REASONING_DONE", "CONTENT_DONE",
            "REVIEWED", "OPTIMIZED", "STORYLINE_DONE", "DONE");

    private final EnhancedSubStepExecutor executor;
    private final ChapterRepository chapterRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final WorkflowEngine workflowEngine;
    private final WorkflowContextBuilder contextBuilder;
    private final ContextSummaryService contextSummaryService;

    public EnhancedChapterWritingService(EnhancedSubStepExecutor executor,
                                         ChapterRepository chapterRepository,
                                         ChapterOutlineRepository chapterOutlineRepository,
                                         WorkflowEngine workflowEngine,
                                         WorkflowContextBuilder contextBuilder,
                                         ContextSummaryService contextSummaryService) {
        this.executor = executor;
        this.chapterRepository = chapterRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.workflowEngine = workflowEngine;
        this.contextBuilder = contextBuilder;
        this.contextSummaryService = contextSummaryService;
    }

    /**
     * Execute the 7-step enhanced chapter writing cycle with breakpoint resume support.
     */
    public void writeChapterEnhanced(AutoRunContext ctx, int chapterNumber,
                                     String writingRules, String styleFingerprint,
                                     int deepReviewInterval) {
        Long projectId = ctx.getProjectId();
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseGet(() -> {
                    ChapterEntity c = new ChapterEntity();
                    c.setProjectId(projectId);
                    c.setChapterNumber(chapterNumber);
                    return chapterRepository.save(c);
                });

        String status = chapter.getWritingCycleStatus();
        if ("DONE".equals(status)) {
            log.info("[P{}] Chapter {} already DONE, skipping", projectId, chapterNumber);
            return;
        }

        // Build context for variable maps
        WorkflowContext wfCtx = contextBuilder.build(projectId, chapterNumber);
        Genre genre = wfCtx.getGenre();

        // Load chapter outline for event plan
        String eventPlan = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .map(ChapterOutlineEntity::getEventPlan)
                .orElse("");

        String chapterSummary = wfCtx.getChapterSummary() != null ? wfCtx.getChapterSummary() : "";
        String chapterCards = wfCtx.getCharacterCards() != null ? wfCtx.getCharacterCards() : "";
        String stepGuidance = wfCtx.getStepGuidance() != null ? wfCtx.getStepGuidance() : "";
        String title = wfCtx.getTitle() != null ? wfCtx.getTitle() : "";
        String genreDisplay = genre != null ? genre.getDisplayName() : "";
        String prevContent = wfCtx.getPreviousChapterContent() != null ? wfCtx.getPreviousChapterContent() : "";

        // Token sink for forwarding to observation
        Consumer<String> tokenSink = ctx::forwardTokenToObservation;

        // Step 1 — 前文梳理 (Context Briefing)
        if (isBeforeOrAt(status, "NOT_STARTED")) {
            if (ctx.shouldStop()) return;
            log.info("[P{}] Chapter {} — Step 1: Context Briefing", projectId, chapterNumber);
            ctx.emitSubStep("CONTEXT_BRIEFING", chapterNumber);

            Map<String, String> vars = new HashMap<>();
            vars.put("title", title);
            vars.put("genre", genreDisplay);
            vars.put("chapterNumber", String.valueOf(chapterNumber));
            vars.put("previousChapterContent", prevContent);
            vars.put("chapterSummary", chapterSummary);
            vars.put("writingRules", writingRules);
            vars.put("characterCards", chapterCards);
            vars.put("stepGuidance", stepGuidance);

            String briefing = executor.generateContextBriefing(projectId, vars, genre, tokenSink);
            chapter.setWritingBriefing(briefing);
            chapter.setWritingCycleStatus("BRIEFING_DONE");
            chapterRepository.save(chapter);
        }

        // Step 2 — 剧情推演 (Plot Reasoning)
        if (isBeforeOrAt(status, "BRIEFING_DONE")) {
            if (ctx.shouldStop()) return;
            log.info("[P{}] Chapter {} — Step 2: Plot Reasoning", projectId, chapterNumber);
            ctx.emitSubStep("PLOT_REASONING", chapterNumber);

            // Reload briefing in case we're resuming
            chapter = reloadChapter(projectId, chapterNumber);
            String briefing = chapter.getWritingBriefing() != null ? chapter.getWritingBriefing() : "";

            Map<String, String> vars = new HashMap<>();
            vars.put("title", title);
            vars.put("genre", genreDisplay);
            vars.put("chapterNumber", String.valueOf(chapterNumber));
            vars.put("chapterSummary", chapterSummary);
            vars.put("eventPlan", eventPlan);
            vars.put("writingBriefing", briefing);
            vars.put("characterCards", chapterCards);
            vars.put("stepGuidance", stepGuidance);

            String reasoning = executor.generatePlotReasoning(projectId, vars, genre, tokenSink);
            chapter.setWritingReasoning(reasoning);
            chapter.setWritingCycleStatus("REASONING_DONE");
            chapterRepository.save(chapter);
        }

        // Step 3 — 正文生成 (Content Generation — reuses existing logic)
        if (isBeforeOrAt(status, "REASONING_DONE")) {
            if (ctx.shouldStop()) return;
            log.info("[P{}] Chapter {} — Step 3: Content Generation", projectId, chapterNumber);

            ctx.generateAndSave(WorkflowStep.CHAPTER_WRITING, chapterNumber,
                    "精品模式：第 " + chapterNumber + " 章");

            chapter = reloadChapter(projectId, chapterNumber);
            chapter.setWritingCycleStatus("CONTENT_DONE");
            chapterRepository.save(chapter);
        }

        // Step 4 — 即时审查 (Instant Review)
        if (isBeforeOrAt(status, "CONTENT_DONE")) {
            if (ctx.shouldStop()) return;
            log.info("[P{}] Chapter {} — Step 4: Instant Review", projectId, chapterNumber);
            ctx.emitSubStep("INSTANT_REVIEW", chapterNumber);

            chapter = reloadChapter(projectId, chapterNumber);
            String reasoning = chapter.getWritingReasoning() != null ? chapter.getWritingReasoning() : "";
            String contentDraft = chapter.getContent() != null ? chapter.getContent() : "";

            Map<String, String> vars = new HashMap<>();
            vars.put("chapterNumber", String.valueOf(chapterNumber));
            vars.put("writingReasoning", reasoning);
            vars.put("contentDraft", contentDraft);

            String review = executor.runInstantReview(projectId, vars, genre, tokenSink);
            chapter.setInstantReview(review);
            chapter.setWritingCycleStatus("REVIEWED");
            chapterRepository.save(chapter);
        }

        // Step 5 — 内容优化 (Content Optimization — conditional)
        if (isBeforeOrAt(status, "REVIEWED")) {
            if (ctx.shouldStop()) return;

            chapter = reloadChapter(projectId, chapterNumber);
            if (needsOptimization(chapter.getInstantReview())) {
                log.info("[P{}] Chapter {} — Step 5: Content Optimization", projectId, chapterNumber);
                ctx.emitSubStep("CONTENT_OPTIMIZATION", chapterNumber);

                String contentDraft = chapter.getContent() != null ? chapter.getContent() : "";
                String review = chapter.getInstantReview() != null ? chapter.getInstantReview() : "";

                Map<String, String> vars = new HashMap<>();
                vars.put("chapterNumber", String.valueOf(chapterNumber));
                vars.put("contentDraft", contentDraft);
                vars.put("instantReview", review);
                vars.put("writingRules", writingRules);
                vars.put("styleFingerprint", styleFingerprint);

                String optimized = executor.runContentOptimization(projectId, vars, genre, tokenSink);
                chapter.setContent(optimized);
                chapter.setWordCount(optimized.length());
                // Regenerate content summary for optimized content
                String newSummary = contextSummaryService.summarizeChapterContent(projectId, chapterNumber, optimized);
                if (newSummary != null) {
                    chapter.setContentSummary(newSummary);
                }
            } else {
                log.info("[P{}] Chapter {} — Step 5: Skipped (review passed)", projectId, chapterNumber);
            }
            chapter.setWritingCycleStatus("OPTIMIZED");
            chapterRepository.save(chapter);
        }

        // Step 6 — 故事线更新 (Storyline Update)
        if (isBeforeOrAt(status, "OPTIMIZED")) {
            if (ctx.shouldStop()) return;
            log.info("[P{}] Chapter {} — Step 6: Storyline Update", projectId, chapterNumber);
            ctx.emitSubStep("STORYLINE_UPDATE", chapterNumber);

            chapter = reloadChapter(projectId, chapterNumber);
            String content = chapter.getContent() != null ? chapter.getContent() : "";

            // Load previous chapter's storyline snapshot
            String prevSnapshot = "";
            if (chapterNumber > 1) {
                prevSnapshot = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber - 1)
                        .map(ChapterEntity::getStorylineSnapshot)
                        .orElse("");
                if (prevSnapshot == null) prevSnapshot = "";
            }

            Map<String, String> vars = new HashMap<>();
            vars.put("chapterNumber", String.valueOf(chapterNumber));
            vars.put("optimizedContent", content);
            vars.put("previousStorylineSnapshot", prevSnapshot);

            String storyline = executor.updateStoryline(projectId, vars, genre, tokenSink);
            chapter.setStorylineSnapshot(storyline);
            chapter.setWritingCycleStatus("STORYLINE_DONE");
            chapterRepository.save(chapter);
        }

        // Step 7 — 深度审查 (Deep Review — periodic)
        if (isBeforeOrAt(status, "STORYLINE_DONE")) {
            if (ctx.shouldStop()) return;

            chapter = reloadChapter(projectId, chapterNumber);
            if (chapterNumber > 0 && chapterNumber % deepReviewInterval == 0) {
                log.info("[P{}] Chapter {} — Step 7: Deep Review", projectId, chapterNumber);
                ctx.emitSubStep("DEEP_REVIEW", chapterNumber);

                String content = chapter.getContent() != null ? chapter.getContent() : "";

                // Load previous plot summary for cross-chapter review
                String prevPlotSummary = "";
                if (chapterNumber > 1) {
                    prevPlotSummary = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber - 1)
                            .map(ChapterEntity::getPlotSummary)
                            .orElse("");
                    if (prevPlotSummary == null) prevPlotSummary = "";
                }

                Map<String, String> vars = new HashMap<>();
                vars.put("chapterNumber", String.valueOf(chapterNumber));
                vars.put("finalContent", content);
                vars.put("writingRules", writingRules);
                vars.put("styleFingerprint", styleFingerprint);
                vars.put("previousPlotSummary", prevPlotSummary);

                String deepReviewResult = executor.runDeepReview(projectId, vars, genre, tokenSink);
                chapter.setDeepReview(deepReviewResult);
            } else {
                log.info("[P{}] Chapter {} — Step 7: Skipped (not at interval {})", projectId, chapterNumber, deepReviewInterval);
            }
            chapter.setWritingCycleStatus("DONE");
            chapterRepository.save(chapter);
        }

        // Post-cycle: Generate character states
        try {
            ctx.emitSubStep("CHARACTER_STATES", chapterNumber);
            workflowEngine.generateCharacterStates(projectId, chapterNumber, tokenSink);
        } catch (Exception e) {
            log.warn("[P{}] Character state generation failed for chapter {}: {}", projectId, chapterNumber, e.getMessage());
        }

        log.info("[P{}] Chapter {} enhanced writing cycle complete", projectId, chapterNumber);
    }

    private boolean needsOptimization(String reviewResult) {
        return reviewResult != null && reviewResult.contains("需优化");
    }

    private boolean isBeforeOrAt(String currentStatus, String threshold) {
        int currentIdx = STATUS_ORDER.indexOf(currentStatus);
        int thresholdIdx = STATUS_ORDER.indexOf(threshold);
        // If status not recognized, treat as NOT_STARTED
        if (currentIdx < 0) currentIdx = 0;
        return currentIdx <= thresholdIdx;
    }

    private ChapterEntity reloadChapter(Long projectId, int chapterNumber) {
        return chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterNumber));
    }
}
