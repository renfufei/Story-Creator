package com.storycreator;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:integration_test;DB_CLOSE_DELAY=-1"
})
class PromptSubStepIntegrationTest {

    @Autowired
    private PromptTemplateRegistry registry;

    @Test
    void applicationStartsSuccessfully() {
        assertThat(registry).isNotNull();
    }

    @Test
    void allSubStepDefaultTemplatesAccessibleViaRegistry() {
        for (PromptSubStep subStep : PromptSubStep.values()) {
            WorkflowStep parentStep = subStep.getParentStep();
            String template = registry.getSubStepTemplate(parentStep, subStep, null);

            assertThat(template)
                    .as("Template for %s should be accessible via registry", subStep.name())
                    .isNotNull()
                    .isNotEmpty();
        }
    }

    @Test
    void subStepTemplatesContainExpectedPlaceholders() {
        // CHARACTER_CARD template should contain {{cardNumber}} and {{totalCards}}
        String characterCardTemplate = registry.getSubStepTemplate(
                WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD, null);
        assertThat(characterCardTemplate).contains("{{cardNumber}}");
        assertThat(characterCardTemplate).contains("{{totalCards}}");

        // PROOFREAD_FIX template should contain {{reportSummary}} and {{originalContent}}
        String proofreadFixTemplate = registry.getSubStepTemplate(
                WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_FIX, null);
        assertThat(proofreadFixTemplate).contains("{{reportSummary}}");
        assertThat(proofreadFixTemplate).contains("{{originalContent}}");

        // CHAPTER_TITLE template should contain {{contentPreview}}
        String titleTemplate = registry.getSubStepTemplate(
                WorkflowStep.POLISHING, PromptSubStep.CHAPTER_TITLE, null);
        assertThat(titleTemplate).contains("{{contentPreview}}");
    }

    @Test
    void resolveTemplateEndToEnd() {
        // Get a real template from DB and resolve it
        String template = registry.getSubStepTemplate(
                WorkflowStep.POLISHING, PromptSubStep.CHAPTER_TITLE, null);

        Map<String, String> vars = Map.of("contentPreview", "这是一段章节内容预览文本");

        String resolved = registry.resolveTemplate(template, vars);

        assertThat(resolved).doesNotContain("{{contentPreview}}");
        assertThat(resolved).contains("这是一段章节内容预览文本");
    }

    @Test
    void resolveTemplateWithLongVariable() {
        String template = registry.getSubStepTemplate(
                WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_FIX, null);

        String longContent = "这是一段非常长的原文内容，用于验证resolveTemplate对长文本变量的处理逻辑，" +
                "它应该被三反引号包裹起来，因为它的长度超过了五十个字符的阈值。";
        Map<String, String> vars = Map.of(
                "reportSummary", "短报告",
                "originalContent", longContent
        );

        String resolved = registry.resolveTemplate(template, vars);

        assertThat(resolved).doesNotContain("{{originalContent}}");
        assertThat(resolved).contains("```");
        assertThat(resolved).contains("短报告"); // short value not wrapped
    }

    @Test
    void subStepTemplatesContainAtLeastOneExpectedVariable() {
        // Verify that each sub-step template contains at least one of its declared variables.
        // Not all variables need to be in the DB template — some (e.g. stepGuidance) are optional
        // and appended at runtime by the engine.
        for (Map.Entry<PromptSubStep, List<String>> entry : PromptTemplateRegistry.SUB_STEP_VARIABLES.entrySet()) {
            PromptSubStep subStep = entry.getKey();
            List<String> expectedVars = entry.getValue();
            WorkflowStep parentStep = subStep.getParentStep();

            String template = registry.getSubStepTemplate(parentStep, subStep, null);
            assertThat(template)
                    .as("Template for %s should exist", subStep.name())
                    .isNotNull();

            boolean hasAtLeastOne = expectedVars.stream()
                    .anyMatch(varName -> template.contains("{{" + varName + "}}"));
            assertThat(hasAtLeastOne)
                    .as("Template for %s should contain at least one of its declared variables %s",
                            subStep.name(), expectedVars)
                    .isTrue();
        }
    }
}
