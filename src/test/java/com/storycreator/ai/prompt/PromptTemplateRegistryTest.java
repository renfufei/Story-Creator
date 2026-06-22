package com.storycreator.ai.prompt;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.PromptTemplateEntity;
import com.storycreator.persistence.repository.PromptTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromptTemplateRegistryTest {

    @Mock
    private PromptTemplateRepository repository;

    @Mock
    private BuiltinTemplateLoader builtinLoader;

    private PromptTemplateRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PromptTemplateRegistry(repository, builtinLoader);
    }

    // --- resolveTemplate tests ---

    @Test
    void resolveTemplate_shortVariablesReplacedDirectly() {
        String template = "标题：{{title}}，题材：{{genre}}";
        Map<String, String> vars = Map.of("title", "测试小说", "genre", "玄幻");

        String result = registry.resolveTemplate(template, vars);

        assertThat(result).isEqualTo("标题：测试小说，题材：玄幻");
    }

    @Test
    void resolveTemplate_longVariableWrappedWithBackticks() {
        String longValue = "这是一段很长的世界观设定文本，超过五十个字符，用于测试是否会被三反引号包裹起来，确认包裹逻辑正常工作。";
        String template = "世界观：{{worldSetting}}";
        Map<String, String> vars = Map.of("worldSetting", longValue);

        String result = registry.resolveTemplate(template, vars);

        assertThat(result).contains("```");
        assertThat(result).contains(longValue);
    }

    @Test
    void resolveTemplate_shortVariableNotWrappedEvenIfLong() {
        // "title" is in SHORT_VARIABLES, so even if > 50 chars it should NOT be wrapped
        String longTitle = "这是一个超级超级超级超级超级超级超级长的标题名称用来测试短变量不会被包裹的逻辑";
        String template = "标题：{{title}}";
        Map<String, String> vars = Map.of("title", longTitle);

        String result = registry.resolveTemplate(template, vars);

        assertThat(result).doesNotContain("```");
        assertThat(result).isEqualTo("标题：" + longTitle);
    }

    @Test
    void resolveTemplate_nullValueTreatedAsEmptyString() {
        String template = "内容：{{content}}结束";
        Map<String, String> vars = new java.util.HashMap<>();
        vars.put("content", null);

        String result = registry.resolveTemplate(template, vars);

        assertThat(result).isEqualTo("内容：结束");
    }

    @Test
    void resolveTemplate_unmatchedPlaceholderRemainsUnchanged() {
        String template = "标题：{{title}}，未知：{{unknown}}";
        Map<String, String> vars = Map.of("title", "测试");

        String result = registry.resolveTemplate(template, vars);

        assertThat(result).contains("{{unknown}}");
        assertThat(result).contains("测试");
    }

    @Test
    void resolveTemplate_backtickInLongValueReplacedWithFullwidth() {
        String valueWithBacktick = "这段文本包含反引号`测试`的内容，而且长度超过五十个字符，所以会被包裹起来进行替换处理。这里再加一些文字确保超过五十个字符的阈值要求。";
        String template = "{{content}}";
        Map<String, String> vars = Map.of("content", valueWithBacktick);

        String result = registry.resolveTemplate(template, vars);

        assertThat(result).doesNotContain("`测试`");
        assertThat(result).contains("\uff40"); // fullwidth backtick
        assertThat(result).contains("```"); // wrapped with triple backticks
    }

    // --- getSubStepTemplate tests ---

    @Test
    void getSubStepTemplate_nullSubStepReturnsNull() {
        String result = registry.getSubStepTemplate(WorkflowStep.CHARACTER_DESIGN, null, Genre.XUANHUAN);

        assertThat(result).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    void getSubStepTemplate_genreMatchPreferred() {
        PromptTemplateEntity genreTemplate = new PromptTemplateEntity();
        genreTemplate.setTemplate("玄幻角色卡模板");

        when(repository.findByStepAndSubStepAndGenreAndIsDefaultTrue(
                WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD, Genre.XUANHUAN))
                .thenReturn(Optional.of(genreTemplate));

        String result = registry.getSubStepTemplate(
                WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD, Genre.XUANHUAN);

        assertThat(result).isEqualTo("玄幻角色卡模板");
        verify(repository, never()).findByStepAndSubStepAndGenreIsNullAndIsDefaultTrue(any(), any());
    }

    @Test
    void getSubStepTemplate_fallbackToGenericWhenNoGenreMatch() {
        PromptTemplateEntity genericTemplate = new PromptTemplateEntity();
        genericTemplate.setTemplate("通用角色卡模板");

        when(repository.findByStepAndSubStepAndGenreAndIsDefaultTrue(
                WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD, Genre.XUANHUAN))
                .thenReturn(Optional.empty());
        when(repository.findByStepAndSubStepAndGenreIsNullAndIsDefaultTrue(
                WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD))
                .thenReturn(Optional.of(genericTemplate));

        String result = registry.getSubStepTemplate(
                WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD, Genre.XUANHUAN);

        assertThat(result).isEqualTo("通用角色卡模板");
    }

    @Test
    void getSubStepTemplate_returnsNullWhenNoTemplateFound() {
        when(repository.findByStepAndSubStepAndGenreAndIsDefaultTrue(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findByStepAndSubStepAndGenreIsNullAndIsDefaultTrue(any(), any()))
                .thenReturn(Optional.empty());
        when(builtinLoader.findSubStep(any(), any(), any()))
                .thenReturn(Optional.empty());

        String result = registry.getSubStepTemplate(
                WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_FIX, Genre.DUSHI);

        assertThat(result).isNull();
    }

    // --- SUB_STEP_VARIABLES coverage ---

    @Test
    void subStepVariablesCoversAllSubSteps() {
        // SUB_STEP_VARIABLES should have an entry for each of the 15 sub-steps
        // Note: The map has 14 entries because CHARACTER_STATES uses "dimList" which is also a short variable
        // Actually let's just verify all PromptSubStep values are present
        for (PromptSubStep subStep : PromptSubStep.values()) {
            assertThat(PromptTemplateRegistry.SUB_STEP_VARIABLES)
                    .as("SUB_STEP_VARIABLES should contain entry for %s", subStep.name())
                    .containsKey(subStep);
        }
    }

    @Test
    void subStepVariablesHasNonEmptyListsForEachEntry() {
        PromptTemplateRegistry.SUB_STEP_VARIABLES.forEach((subStep, variables) -> {
            assertThat(variables)
                    .as("Variables for %s should not be empty", subStep.name())
                    .isNotEmpty();
        });
    }
}
