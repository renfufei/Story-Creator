package com.storycreator.workflow.step;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.workflow.engine.WorkflowContext;
import org.springframework.stereotype.Component;

@Component
public class PolishingHandler implements WorkflowStepHandler {

    private final PromptTemplateRegistry promptRegistry;

    public PolishingHandler(PromptTemplateRegistry promptRegistry) {
        this.promptRegistry = promptRegistry;
    }

    @Override
    public WorkflowStep getStep() {
        return WorkflowStep.POLISHING;
    }

    @Override
    public AiRequest buildRequest(WorkflowContext context) {
        String template = promptRegistry.getTemplate(WorkflowStep.POLISHING, context.getGenre());
        String prompt = promptRegistry.resolveTemplate(template, context.toTemplateVariables());

        String systemPrompt = promptRegistry.getSystemPrompt(WorkflowStep.POLISHING, context.getGenre());
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "你是一位资深的网络小说编辑，擅长文字润色、情节优化和节奏把控。";
        }

        // Estimate tokens needed: Chinese ~2 tokens/char, add buffer for polish expansion
        int estimatedTokens = Math.max(8192, context.getChapterWordCountMax() * 2 + 2000);

        return AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(estimatedTokens)
                .temperature(0.6)
                .build();
    }
}
