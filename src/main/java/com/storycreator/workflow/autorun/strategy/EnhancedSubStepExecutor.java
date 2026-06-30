package com.storycreator.workflow.autorun.strategy;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.workflow.engine.AiUsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import static com.storycreator.workflow.engine.TextProcessingUtils.applyResolvedConfig;

@Service
public class EnhancedSubStepExecutor {

    private static final Logger log = LoggerFactory.getLogger(EnhancedSubStepExecutor.class);

    private final PromptTemplateRegistry promptRegistry;
    private final AiProviderRouter providerRouter;
    private final AiUsageTracker aiUsageTracker;
    private final GlobalSettingService globalSettingService;

    public EnhancedSubStepExecutor(PromptTemplateRegistry promptRegistry,
                                   AiProviderRouter providerRouter,
                                   AiUsageTracker aiUsageTracker,
                                   GlobalSettingService globalSettingService) {
        this.promptRegistry = promptRegistry;
        this.providerRouter = providerRouter;
        this.aiUsageTracker = aiUsageTracker;
        this.globalSettingService = globalSettingService;
    }

    /**
     * Execute a sub-step: resolve template, call AI, return result string.
     * Blocks the calling (virtual) thread until completion or timeout.
     */
    public String executeSubStep(Long projectId, WorkflowStep parentStep,
                                 PromptSubStep subStep, Map<String, String> variables,
                                 int maxTokens, double temperature, Genre genre) {
        return executeSubStep(projectId, parentStep, subStep, variables, maxTokens, temperature, genre, null);
    }

    public String executeSubStep(Long projectId, WorkflowStep parentStep,
                                 PromptSubStep subStep, Map<String, String> variables,
                                 int maxTokens, double temperature, Genre genre,
                                 Consumer<String> tokenSink) {
        // 1. Resolve template and system prompt
        String template = promptRegistry.getSubStepTemplate(parentStep, subStep, genre);
        String systemPrompt = promptRegistry.getSubStepSystemPrompt(parentStep, subStep, genre);
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "你是一位专业的网络小说创作助手。请按要求完成任务。";
        }

        // 2. Fill variables into template
        String userPrompt = promptRegistry.resolveTemplate(template, variables);

        // 3. Resolve model config
        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, parentStep);

        // 4. Build AI request
        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .build();
        applyResolvedConfig(request, resolved);

        // 5. Stream and collect (blocking), forwarding tokens if sink provided
        long timeoutSeconds = globalSettingService.getAiTimeoutSeconds() * 3L;
        long startTime = System.currentTimeMillis();

        var flux = resolved.provider().streamText(request);
        if (tokenSink != null) {
            flux = flux.doOnNext(tokenSink::accept);
        }
        var tokens = flux.collectList()
                .block(Duration.ofSeconds(timeoutSeconds));
        if (tokens == null) {
            throw new RuntimeException("[P" + projectId + "] Sub-step " + subStep
                    + " returned null (timeout after " + timeoutSeconds + "s)");
        }
        String result = tokens.stream().reduce("", String::concat);

        // 6. Record usage
        long duration = System.currentTimeMillis() - startTime;
        aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), duration);

        if (result.isBlank()) {
            log.warn("[P{}] Sub-step {} returned blank result after {}ms", projectId, subStep, duration);
        } else {
            log.debug("[P{}] Sub-step {} completed in {}ms, result length={}", projectId, subStep, duration, result.length());
        }
        return result;
    }

    // --- Convenience methods ---

    public String generateWritingRules(Long projectId, Map<String, String> variables, Genre genre) {
        return executeSubStep(projectId, WorkflowStep.WORLD_BUILDING,
                PromptSubStep.WRITING_RULES, variables, 2048, 0.5, genre);
    }

    public String generateWritingRules(Long projectId, Map<String, String> variables, Genre genre, Consumer<String> tokenSink) {
        return executeSubStep(projectId, WorkflowStep.WORLD_BUILDING,
                PromptSubStep.WRITING_RULES, variables, 2048, 0.5, genre, tokenSink);
    }

    public String generateStyleFingerprint(Long projectId, Map<String, String> variables, Genre genre) {
        return executeSubStep(projectId, WorkflowStep.WORLD_BUILDING,
                PromptSubStep.STYLE_FINGERPRINT, variables, 1024, 0.5, genre);
    }

    public String generateStyleFingerprint(Long projectId, Map<String, String> variables, Genre genre, Consumer<String> tokenSink) {
        return executeSubStep(projectId, WorkflowStep.WORLD_BUILDING,
                PromptSubStep.STYLE_FINGERPRINT, variables, 1024, 0.5, genre, tokenSink);
    }

    public String generateBehaviorBoundaries(Long projectId, Map<String, String> variables, Genre genre) {
        return executeSubStep(projectId, WorkflowStep.CHARACTER_DESIGN,
                PromptSubStep.CHARACTER_BEHAVIOR_BOUNDARIES, variables, 1024, 0.5, genre);
    }

    public String generateBehaviorBoundaries(Long projectId, Map<String, String> variables, Genre genre, Consumer<String> tokenSink) {
        return executeSubStep(projectId, WorkflowStep.CHARACTER_DESIGN,
                PromptSubStep.CHARACTER_BEHAVIOR_BOUNDARIES, variables, 1024, 0.5, genre, tokenSink);
    }

    public String generateEventPlan(Long projectId, Map<String, String> variables, Genre genre) {
        return executeSubStep(projectId, WorkflowStep.OUTLINE_GENERATION,
                PromptSubStep.CHAPTER_EVENT_PLAN, variables, 1024, 0.6, genre);
    }

    public String generateEventPlan(Long projectId, Map<String, String> variables, Genre genre, Consumer<String> tokenSink) {
        return executeSubStep(projectId, WorkflowStep.OUTLINE_GENERATION,
                PromptSubStep.CHAPTER_EVENT_PLAN, variables, 1024, 0.6, genre, tokenSink);
    }

    public String generateContextBriefing(Long projectId, Map<String, String> variables, Genre genre) {
        return executeSubStep(projectId, WorkflowStep.CHAPTER_WRITING,
                PromptSubStep.CHAPTER_CONTEXT_BRIEFING, variables, 1024, 0.3, genre);
    }

    public String generateContextBriefing(Long projectId, Map<String, String> variables, Genre genre, Consumer<String> tokenSink) {
        return executeSubStep(projectId, WorkflowStep.CHAPTER_WRITING,
                PromptSubStep.CHAPTER_CONTEXT_BRIEFING, variables, 1024, 0.3, genre, tokenSink);
    }

    public String generatePlotReasoning(Long projectId, Map<String, String> variables, Genre genre) {
        return executeSubStep(projectId, WorkflowStep.CHAPTER_WRITING,
                PromptSubStep.CHAPTER_PLOT_REASONING, variables, 1536, 0.7, genre);
    }

    public String generatePlotReasoning(Long projectId, Map<String, String> variables, Genre genre, Consumer<String> tokenSink) {
        return executeSubStep(projectId, WorkflowStep.CHAPTER_WRITING,
                PromptSubStep.CHAPTER_PLOT_REASONING, variables, 1536, 0.7, genre, tokenSink);
    }

    public String runInstantReview(Long projectId, Map<String, String> variables, Genre genre) {
        return executeSubStep(projectId, WorkflowStep.CHAPTER_WRITING,
                PromptSubStep.CHAPTER_INSTANT_REVIEW, variables, 1536, 0.2, genre);
    }

    public String runInstantReview(Long projectId, Map<String, String> variables, Genre genre, Consumer<String> tokenSink) {
        return executeSubStep(projectId, WorkflowStep.CHAPTER_WRITING,
                PromptSubStep.CHAPTER_INSTANT_REVIEW, variables, 1536, 0.2, genre, tokenSink);
    }

    public String runContentOptimization(Long projectId, Map<String, String> variables, Genre genre) {
        return executeSubStep(projectId, WorkflowStep.CHAPTER_WRITING,
                PromptSubStep.CHAPTER_CONTENT_OPTIMIZATION, variables, 8192, 0.5, genre);
    }

    public String runContentOptimization(Long projectId, Map<String, String> variables, Genre genre, Consumer<String> tokenSink) {
        return executeSubStep(projectId, WorkflowStep.CHAPTER_WRITING,
                PromptSubStep.CHAPTER_CONTENT_OPTIMIZATION, variables, 8192, 0.5, genre, tokenSink);
    }

    public String updateStoryline(Long projectId, Map<String, String> variables, Genre genre) {
        return executeSubStep(projectId, WorkflowStep.CHAPTER_WRITING,
                PromptSubStep.CHAPTER_STORYLINE_UPDATE, variables, 1024, 0.3, genre);
    }

    public String updateStoryline(Long projectId, Map<String, String> variables, Genre genre, Consumer<String> tokenSink) {
        return executeSubStep(projectId, WorkflowStep.CHAPTER_WRITING,
                PromptSubStep.CHAPTER_STORYLINE_UPDATE, variables, 1024, 0.3, genre, tokenSink);
    }

    public String runDeepReview(Long projectId, Map<String, String> variables, Genre genre) {
        return executeSubStep(projectId, WorkflowStep.CHAPTER_WRITING,
                PromptSubStep.CHAPTER_DEEP_REVIEW, variables, 2048, 0.4, genre);
    }

    public String runDeepReview(Long projectId, Map<String, String> variables, Genre genre, Consumer<String> tokenSink) {
        return executeSubStep(projectId, WorkflowStep.CHAPTER_WRITING,
                PromptSubStep.CHAPTER_DEEP_REVIEW, variables, 2048, 0.4, genre, tokenSink);
    }
}
