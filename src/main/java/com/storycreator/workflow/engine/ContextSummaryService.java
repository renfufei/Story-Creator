package com.storycreator.workflow.engine;

import com.storycreator.ai.prompt.ContextSummaryTemplateLoader;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ContextSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ContextSummaryService.class);

    private final AiProviderRouter providerRouter;
    private final AiUsageTracker aiUsageTracker;
    private final ContextSummaryTemplateLoader templateLoader;

    public ContextSummaryService(AiProviderRouter providerRouter, AiUsageTracker aiUsageTracker,
                                 ContextSummaryTemplateLoader templateLoader) {
        this.providerRouter = providerRouter;
        this.aiUsageTracker = aiUsageTracker;
        this.templateLoader = templateLoader;
    }

    /**
     * Summarize world setting content to ~500 chars.
     * Returns null on failure (caller falls back to truncation).
     */
    public String summarizeWorldSetting(Long projectId, String content) {
        if (content == null || content.length() < 500) return content;
        try {
            AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.WORLD_BUILDING);
            String prompt = resolveTemplate("SUMMARIZE_WORLD", content);
            String systemPrompt = templateLoader.getSystemPrompt("SUMMARIZE_WORLD");
            AiRequest request = AiRequest.builder()
                    .systemPrompt(systemPrompt)
                    .userPrompt(prompt)
                    .maxTokens(512)
                    .temperature(0.3)
                    .build();
            applyConfig(request, resolved);

            StringBuilder sb = new StringBuilder();
            long startTime = System.currentTimeMillis();
            resolved.provider().streamText(request)
                    .doOnNext(sb::append)
                    .blockLast();
            aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - startTime);
            String summary = sb.toString().trim();
            log.info("[P{}] World setting summary generated ({}→{} chars)", projectId, content.length(), summary.length());
            return summary;
        } catch (Exception e) {
            log.warn("[P{}] Failed to generate world setting summary: {}", projectId, e.getMessage());
            return null;
        }
    }

    /**
     * Summarize a character card to ~300 chars.
     * Returns null on failure.
     */
    public String summarizeCharacterCard(Long projectId, String content) {
        if (content == null || content.length() < 300) return content;
        try {
            AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.CHARACTER_DESIGN);
            String prompt = resolveTemplate("SUMMARIZE_CHARACTER_CARD", content);
            String systemPrompt = templateLoader.getSystemPrompt("SUMMARIZE_CHARACTER_CARD");
            AiRequest request = AiRequest.builder()
                    .systemPrompt(systemPrompt)
                    .userPrompt(prompt)
                    .maxTokens(384)
                    .temperature(0.3)
                    .build();
            applyConfig(request, resolved);

            StringBuilder sb = new StringBuilder();
            long startTime = System.currentTimeMillis();
            resolved.provider().streamText(request)
                    .doOnNext(sb::append)
                    .blockLast();
            aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - startTime);
            String summary = sb.toString().trim();
            log.info("[P{}] Character card summary generated ({}→{} chars)", projectId, content.length(), summary.length());
            return summary;
        } catch (Exception e) {
            log.warn("[P{}] Failed to generate character card summary: {}", projectId, e.getMessage());
            return null;
        }
    }

    /**
     * Summarize character overview to ~300 chars.
     * Returns null on failure.
     */
    public String summarizeCharacterOverview(Long projectId, String content) {
        if (content == null || content.length() < 300) return content;
        try {
            AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.CHARACTER_DESIGN);
            String prompt = resolveTemplate("SUMMARIZE_CHARACTER_OVERVIEW", content);
            String systemPrompt = templateLoader.getSystemPrompt("SUMMARIZE_CHARACTER_OVERVIEW");
            AiRequest request = AiRequest.builder()
                    .systemPrompt(systemPrompt)
                    .userPrompt(prompt)
                    .maxTokens(384)
                    .temperature(0.3)
                    .build();
            applyConfig(request, resolved);

            StringBuilder sb = new StringBuilder();
            long startTime = System.currentTimeMillis();
            resolved.provider().streamText(request)
                    .doOnNext(sb::append)
                    .blockLast();
            aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - startTime);
            String summary = sb.toString().trim();
            log.info("[P{}] Character overview summary generated ({}→{} chars)", projectId, content.length(), summary.length());
            return summary;
        } catch (Exception e) {
            log.warn("[P{}] Failed to generate character overview summary: {}", projectId, e.getMessage());
            return null;
        }
    }

    /**
     * Summarize chapter content to 200-400 chars for use as previous chapter context.
     * Short chapters (<500 chars) return content directly.
     * Returns null on failure (caller falls back to tail truncation).
     */
    public String summarizeChapterContent(Long projectId, int chapterNumber, String content) {
        if (content == null || content.isBlank()) return null;
        if (content.length() < 500) return content;
        try {
            AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.CHAPTER_WRITING);
            String truncated = content.length() > 6000 ? content.substring(0, 6000) : content;
            String prompt = resolveTemplate("SUMMARIZE_CHAPTER", truncated);
            String systemPrompt = templateLoader.getSystemPrompt("SUMMARIZE_CHAPTER");
            AiRequest request = AiRequest.builder()
                    .systemPrompt(systemPrompt)
                    .userPrompt(prompt)
                    .maxTokens(512)
                    .temperature(0.3)
                    .build();
            applyConfig(request, resolved);

            StringBuilder sb = new StringBuilder();
            long startTime = System.currentTimeMillis();
            resolved.provider().streamText(request)
                    .doOnNext(sb::append)
                    .blockLast();
            aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - startTime);
            String summary = sb.toString().trim();
            log.info("[P{}] Chapter {} content summary generated ({}→{} chars)", projectId, chapterNumber, content.length(), summary.length());
            return summary;
        } catch (Exception e) {
            log.warn("[P{}] Failed to generate chapter {} content summary: {}", projectId, chapterNumber, e.getMessage());
            return null;
        }
    }

    private String resolveTemplate(String name, String content) {
        String template = templateLoader.getTemplate(name);
        if (template == null) {
            throw new IllegalStateException("No context-summary template found: " + name);
        }
        return template.replace("{{content}}", content);
    }

    private void applyConfig(AiRequest request, AiProviderRouter.ResolvedModel resolved) {
        if (resolved.modelId() != null) request.setModel(resolved.modelId());
        if (resolved.baseUrl() != null) request.setBaseUrl(resolved.baseUrl());
        if (resolved.apiKey() != null) request.setApiKey(resolved.apiKey());
        if (resolved.extraParams() != null) request.setExtraParams(resolved.extraParams());
    }
}
