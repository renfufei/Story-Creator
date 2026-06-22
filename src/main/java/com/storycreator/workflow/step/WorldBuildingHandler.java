package com.storycreator.workflow.step;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.workflow.engine.WorkflowContext;
import org.springframework.stereotype.Component;

@Component
public class WorldBuildingHandler implements WorkflowStepHandler {

    private final PromptTemplateRegistry promptRegistry;

    public WorldBuildingHandler(PromptTemplateRegistry promptRegistry) {
        this.promptRegistry = promptRegistry;
    }

    @Override
    public WorkflowStep getStep() {
        return WorkflowStep.WORLD_BUILDING;
    }

    @Override
    public AiRequest buildRequest(WorkflowContext context) {
        String template = promptRegistry.getTemplate(WorkflowStep.WORLD_BUILDING, context.getGenre());
        String prompt = promptRegistry.resolveTemplate(template, context.toTemplateVariables());

        String systemPrompt = promptRegistry.getSystemPrompt(WorkflowStep.WORLD_BUILDING, context.getGenre());

        return AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(4096)
                .temperature(0.8)
                .build();
    }
}
