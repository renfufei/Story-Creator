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

        return AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(8192)
                .temperature(0.7)
                .build();
    }
}
