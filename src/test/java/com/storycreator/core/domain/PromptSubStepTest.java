package com.storycreator.core.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class PromptSubStepTest {

    @Test
    void allSubStepsHaveNonNullParentStep() {
        for (PromptSubStep subStep : PromptSubStep.values()) {
            assertThat(subStep.getParentStep())
                    .as("Parent step for %s should not be null", subStep.name())
                    .isNotNull();
        }
    }

    @ParameterizedTest
    @EnumSource(PromptSubStep.class)
    void allSubStepsHaveNonEmptyDisplayName(PromptSubStep subStep) {
        assertThat(subStep.getDisplayName())
                .isNotNull()
                .isNotEmpty();
    }

    @Test
    void totalSubStepCountIs15() {
        assertThat(PromptSubStep.values()).hasSize(15);
    }

    @Test
    void characterDesignSubStepsMapCorrectly() {
        assertThat(PromptSubStep.CHARACTER_CARD.getParentStep()).isEqualTo(WorkflowStep.CHARACTER_DESIGN);
        assertThat(PromptSubStep.CHARACTER_OVERVIEW.getParentStep()).isEqualTo(WorkflowStep.CHARACTER_DESIGN);
        assertThat(PromptSubStep.CHARACTER_REFINE.getParentStep()).isEqualTo(WorkflowStep.CHARACTER_DESIGN);
    }

    @Test
    void outlineGenerationSubStepsMapCorrectly() {
        assertThat(PromptSubStep.VOLUME_ARC.getParentStep()).isEqualTo(WorkflowStep.OUTLINE_GENERATION);
        assertThat(PromptSubStep.CHAPTER_OUTLINE.getParentStep()).isEqualTo(WorkflowStep.OUTLINE_GENERATION);
        assertThat(PromptSubStep.CHAPTER_OUTLINE_REFINE.getParentStep()).isEqualTo(WorkflowStep.OUTLINE_GENERATION);
        assertThat(PromptSubStep.STORY_SUMMARY.getParentStep()).isEqualTo(WorkflowStep.OUTLINE_GENERATION);
    }

    @Test
    void proofreadingSubStepsMapCorrectly() {
        assertThat(PromptSubStep.PROOFREAD_PLOT_SUMMARY.getParentStep()).isEqualTo(WorkflowStep.PROOFREADING);
        assertThat(PromptSubStep.PROOFREAD_CHARACTER_CHECK.getParentStep()).isEqualTo(WorkflowStep.PROOFREADING);
        assertThat(PromptSubStep.PROOFREAD_CONSISTENCY.getParentStep()).isEqualTo(WorkflowStep.PROOFREADING);
        assertThat(PromptSubStep.PROOFREAD_CONTINUITY.getParentStep()).isEqualTo(WorkflowStep.PROOFREADING);
        assertThat(PromptSubStep.PROOFREAD_FORESHADOWING.getParentStep()).isEqualTo(WorkflowStep.PROOFREADING);
        assertThat(PromptSubStep.PROOFREAD_FIX.getParentStep()).isEqualTo(WorkflowStep.PROOFREADING);
    }

    @Test
    void polishingSubStepsMapCorrectly() {
        assertThat(PromptSubStep.CHAPTER_TITLE.getParentStep()).isEqualTo(WorkflowStep.POLISHING);
        assertThat(PromptSubStep.CHARACTER_STATES.getParentStep()).isEqualTo(WorkflowStep.POLISHING);
    }
}
