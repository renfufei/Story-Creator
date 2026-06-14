package com.storycreator.workflow.step;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.workflow.engine.WorkflowContext;
import org.springframework.stereotype.Component;

@Component
public class OutlineGenerationHandler implements WorkflowStepHandler {

    private final PromptTemplateRegistry promptRegistry;

    public OutlineGenerationHandler(PromptTemplateRegistry promptRegistry) {
        this.promptRegistry = promptRegistry;
    }

    @Override
    public WorkflowStep getStep() {
        return WorkflowStep.OUTLINE_GENERATION;
    }

    @Override
    public AiRequest buildRequest(WorkflowContext context) {
        String template = promptRegistry.getTemplate(WorkflowStep.OUTLINE_GENERATION, context.getGenre());
        String prompt = promptRegistry.resolveTemplate(template, context.toTemplateVariables());

        String systemPrompt = promptRegistry.getSystemPrompt(WorkflowStep.OUTLINE_GENERATION, context.getGenre());
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "你是一位经验丰富的网络小说策划，擅长构建紧凑的故事结构和引人入胜的剧情节奏。";
        }

        return AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(8192)
                .temperature(0.7)
                .build();
    }
}
