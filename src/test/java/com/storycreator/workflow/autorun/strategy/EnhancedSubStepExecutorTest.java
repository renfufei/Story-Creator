package com.storycreator.workflow.autorun.strategy;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiProvider;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.workflow.engine.AiUsageTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EnhancedSubStepExecutorTest {

    private EnhancedSubStepExecutor executor;

    @Mock private PromptTemplateRegistry promptRegistry;
    @Mock private AiProviderRouter providerRouter;
    @Mock private AiUsageTracker aiUsageTracker;
    @Mock private GlobalSettingService globalSettingService;
    @Mock private AiProvider aiProvider;

    private static final Long PROJECT_ID = 1L;
    private static final Genre GENRE = Genre.XUANHUAN;

    @BeforeEach
    void setUp() {
        executor = new EnhancedSubStepExecutor(promptRegistry, providerRouter, aiUsageTracker, globalSettingService);
    }

    private void setupMocks(String templateContent, String resolvedPrompt, String aiResult) {
        when(promptRegistry.getSubStepTemplate(any(), any(), any())).thenReturn(templateContent);
        when(promptRegistry.getSubStepSystemPrompt(any(), any(), any())).thenReturn("系统提示");
        when(promptRegistry.resolveTemplate(eq(templateContent), any())).thenReturn(resolvedPrompt);

        AiProviderRouter.ResolvedModel resolved = new AiProviderRouter.ResolvedModel(
                aiProvider, "test-model", "http://localhost", "key123", null);
        when(providerRouter.resolveModel(eq(PROJECT_ID), any())).thenReturn(resolved);
        when(globalSettingService.getAiTimeoutSeconds()).thenReturn(300);
        when(aiProvider.streamText(any())).thenReturn(Flux.just(aiResult));
        when(aiProvider.getProviderName()).thenReturn("test");
    }

    @Test
    void executeSubStep_resolvesTemplateAndCallsAi() {
        setupMocks("模板{{title}}", "已解析的提示", "AI生成结果");

        Map<String, String> vars = Map.of("title", "测试标题");
        String result = executor.executeSubStep(PROJECT_ID, WorkflowStep.WORLD_BUILDING,
                PromptSubStep.WRITING_RULES, vars, 2048, 0.5, GENRE);

        assertThat(result).isEqualTo("AI生成结果");
        verify(promptRegistry).getSubStepTemplate(WorkflowStep.WORLD_BUILDING, PromptSubStep.WRITING_RULES, GENRE);
        verify(promptRegistry).resolveTemplate(eq("模板{{title}}"), eq(vars));
        verify(aiProvider).streamText(any());
        verify(aiUsageTracker).record(eq(PROJECT_ID), eq("test-model"), eq("test"), anyLong());
    }

    @Test
    void executeSubStep_handlesEmptyResult() {
        setupMocks("模板", "提示", "");

        String result = executor.executeSubStep(PROJECT_ID, WorkflowStep.WORLD_BUILDING,
                PromptSubStep.WRITING_RULES, Map.of(), 2048, 0.5, GENRE);

        assertThat(result).isEmpty();
    }

    @Test
    void executeSubStep_usesCorrectMaxTokensAndTemp() {
        setupMocks("模板", "提示", "结果");

        executor.executeSubStep(PROJECT_ID, WorkflowStep.CHAPTER_WRITING,
                PromptSubStep.CHAPTER_INSTANT_REVIEW, Map.of(), 4096, 0.7, GENRE);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiProvider).streamText(captor.capture());
        AiRequest captured = captor.getValue();
        assertThat(captured.getMaxTokens()).isEqualTo(4096);
        assertThat(captured.getTemperature()).isEqualTo(0.7);
    }

    @Test
    void executeSubStep_throwsOnNullTokenList() {
        when(promptRegistry.getSubStepTemplate(any(), any(), any())).thenReturn("模板");
        when(promptRegistry.getSubStepSystemPrompt(any(), any(), any())).thenReturn("系统");
        when(promptRegistry.resolveTemplate(any(), any())).thenReturn("提示");

        AiProviderRouter.ResolvedModel resolved = new AiProviderRouter.ResolvedModel(
                aiProvider, "test-model", "http://localhost", "key123", null);
        when(providerRouter.resolveModel(eq(PROJECT_ID), any())).thenReturn(resolved);
        when(globalSettingService.getAiTimeoutSeconds()).thenReturn(1);
        // Simulate timeout: Flux that never completes -> block returns null after timeout
        when(aiProvider.streamText(any())).thenReturn(Flux.never());

        assertThatThrownBy(() -> executor.executeSubStep(PROJECT_ID, WorkflowStep.WORLD_BUILDING,
                PromptSubStep.WRITING_RULES, Map.of(), 2048, 0.5, GENRE))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void generateWritingRules_delegatesCorrectly() {
        setupMocks("规则模板", "规则提示", "写作规则内容");

        String result = executor.generateWritingRules(PROJECT_ID, Map.of("title", "x"), GENRE);

        assertThat(result).isEqualTo("写作规则内容");
        verify(promptRegistry).getSubStepTemplate(WorkflowStep.WORLD_BUILDING, PromptSubStep.WRITING_RULES, GENRE);
    }

    @Test
    void generateBehaviorBoundaries_delegatesCorrectly() {
        setupMocks("边界模板", "边界提示", "行为边界内容");

        String result = executor.generateBehaviorBoundaries(PROJECT_ID, Map.of(), GENRE);

        assertThat(result).isEqualTo("行为边界内容");
        verify(promptRegistry).getSubStepTemplate(WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_BEHAVIOR_BOUNDARIES, GENRE);
    }

    @Test
    void generateEventPlan_delegatesCorrectly() {
        setupMocks("事件模板", "事件提示", "事件计划内容");

        String result = executor.generateEventPlan(PROJECT_ID, Map.of(), GENRE);

        assertThat(result).isEqualTo("事件计划内容");
        verify(promptRegistry).getSubStepTemplate(WorkflowStep.OUTLINE_GENERATION, PromptSubStep.CHAPTER_EVENT_PLAN, GENRE);
    }
}
