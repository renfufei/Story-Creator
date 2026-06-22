package com.storycreator.ai.prompt;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;

public record BuiltinTemplate(
    String key,           // "STEP|SUBSTEP|GENRE" e.g. "CHARACTER_DESIGN|CHARACTER_CARD|"
    WorkflowStep step,
    PromptSubStep subStep,  // null for main-step
    Genre genre,            // null for generic
    String name,
    String systemPrompt,
    String template
) {}
