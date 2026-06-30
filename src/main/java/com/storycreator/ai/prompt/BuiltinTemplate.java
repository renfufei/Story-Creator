package com.storycreator.ai.prompt;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;

import java.util.Set;

public record BuiltinTemplate(
    String key,           // "STEP|SUBSTEP|GENRE" e.g. "CHARACTER_DESIGN|CHARACTER_CARD|"
    WorkflowStep step,
    PromptSubStep subStep,  // null for main-step → now uses _PRIMARY sub-step instead
    Genre genre,            // null for generic
    String name,
    String systemPrompt,
    String template
) {
    public int sortOrder() {
        return subStep != null ? subStep.getSortOrder() : step.getOrder() * 10;
    }

    public Set<TemplateWorkflowTag> workflowTags() {
        return subStep != null ? TemplateWorkflowUsage.getTagsFor(subStep) : Set.of();
    }
}
