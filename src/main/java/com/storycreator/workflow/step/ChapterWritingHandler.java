package com.storycreator.workflow.step;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.workflow.engine.WorkflowContext;
import org.springframework.stereotype.Component;

@Component
public class ChapterWritingHandler implements WorkflowStepHandler {

    private final PromptTemplateRegistry promptRegistry;

    public ChapterWritingHandler(PromptTemplateRegistry promptRegistry) {
        this.promptRegistry = promptRegistry;
    }

    @Override
    public WorkflowStep getStep() {
        return WorkflowStep.CHAPTER_WRITING;
    }

    @Override
    public AiRequest buildRequest(WorkflowContext context) {
        String template = promptRegistry.getTemplate(WorkflowStep.CHAPTER_WRITING, context.getGenre());
        String prompt = promptRegistry.resolveTemplate(template, context.toTemplateVariables());

        String systemPrompt = promptRegistry.getSystemPrompt(WorkflowStep.CHAPTER_WRITING, context.getGenre());

        // Estimate tokens needed: Chinese ~2 tokens/char, add buffer
        int estimatedTokens = Math.max(8192, context.getChapterWordCountMax() * 2 + 2000);

        return AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(estimatedTokens)
                .temperature(0.85)
                .build();
    }
}
