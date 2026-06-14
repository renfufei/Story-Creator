package com.storycreator.workflow.engine;

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

    public ContextSummaryService(AiProviderRouter providerRouter, AiUsageTracker aiUsageTracker) {
        this.providerRouter = providerRouter;
        this.aiUsageTracker = aiUsageTracker;
    }

    /**
     * Summarize world setting content to ~500 chars.
     * Returns null on failure (caller falls back to truncation).
     */
    public String summarizeWorldSetting(Long projectId, String content) {
        if (content == null || content.length() < 500) return content;
        try {
            AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.WORLD_BUILDING);
            String prompt = "请将以下世界观设定压缩为一段简洁摘要（约300-500字），保留关键信息（时代背景、力量体系、核心地理、重要势力），去除细节和修饰语。只输出摘要，不要任何前缀。\n\n" + content;
            AiRequest request = AiRequest.builder()
                    .systemPrompt("你是一位小说编辑，擅长提炼核心信息。")
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
            String prompt = "请将以下角色信息卡压缩为一段简洁摘要（约150-300字），保留：姓名、身份、核心性格、关键能力、主要关系。去除外貌细节和冗余描述。只输出摘要。\n\n" + content;
            AiRequest request = AiRequest.builder()
                    .systemPrompt("你是一位小说编辑，擅长提炼角色核心信息。")
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
            String prompt = "请将以下角色总览压缩为一段简洁摘要（约150-300字），保留每个角色的名字、身份和核心关系网络。只输出摘要。\n\n" + content;
            AiRequest request = AiRequest.builder()
                    .systemPrompt("你是一位小说编辑，擅长提炼角色核心信息。")
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

    private void applyConfig(AiRequest request, AiProviderRouter.ResolvedModel resolved) {
        if (resolved.modelId() != null) request.setModel(resolved.modelId());
        if (resolved.baseUrl() != null) request.setBaseUrl(resolved.baseUrl());
        if (resolved.apiKey() != null) request.setApiKey(resolved.apiKey());
        if (resolved.extraParams() != null) request.setExtraParams(resolved.extraParams());
    }
}
