package com.storycreator.workflow.step;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.workflow.engine.WorkflowContext;
import org.springframework.stereotype.Component;

@Component
public class CharacterDesignHandler implements WorkflowStepHandler {

    private final PromptTemplateRegistry promptRegistry;

    public CharacterDesignHandler(PromptTemplateRegistry promptRegistry) {
        this.promptRegistry = promptRegistry;
    }

    @Override
    public WorkflowStep getStep() {
        return WorkflowStep.CHARACTER_DESIGN;
    }

    @Override
    public AiRequest buildRequest(WorkflowContext context) {
        String template = promptRegistry.getTemplate(WorkflowStep.CHARACTER_DESIGN, context.getGenre());
        String prompt = promptRegistry.resolveTemplate(template, context.toTemplateVariables());

        String systemPrompt = promptRegistry.getSystemPrompt(WorkflowStep.CHARACTER_DESIGN, context.getGenre());
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "你是一位擅长角色塑造的网络小说作家，善于创造有深度、有魅力的角色。";
        }

        return AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(4096)
                .temperature(0.8)
                .build();
    }
}
