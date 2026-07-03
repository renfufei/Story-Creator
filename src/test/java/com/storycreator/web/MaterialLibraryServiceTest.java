package com.storycreator.web;

import com.storycreator.ai.prompt.MaterialDistillationTemplateLoader;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.MaterialCategory;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiProvider;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.persistence.entity.MaterialLibraryEntity;
import com.storycreator.persistence.repository.MaterialLibraryRepository;
import com.storycreator.workflow.engine.AiUsageTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaterialLibraryServiceTest {

    @Mock private MaterialLibraryRepository repository;
    @Mock private AiProviderRouter providerRouter;
    @Mock private AiUsageTracker aiUsageTracker;
    @Mock private MaterialDistillationTemplateLoader templateLoader;
    @Mock private AiProvider aiProvider;

    private MaterialLibraryService service;

    @BeforeEach
    void setUp() {
        service = new MaterialLibraryService(repository, providerRouter, aiUsageTracker, templateLoader);
    }

    @Test
    void distill_callsAiAndSavesResult() {
        // Setup
        when(templateLoader.getTemplate("WORLD")).thenReturn("蒸馏模板 {{content}}");
        when(templateLoader.getSystemPrompt("WORLD")).thenReturn("你是蒸馏助手");

        AiProviderRouter.ResolvedModel resolved = new AiProviderRouter.ResolvedModel(
                aiProvider, "gpt-4", "http://localhost", "key123", null);
        when(providerRouter.resolveModel(eq(1L), eq(WorkflowStep.WORLD_BUILDING))).thenReturn(resolved);
        when(aiProvider.streamText(any(AiRequest.class))).thenReturn(Flux.just("蒸馏后的", "世界观"));

        MaterialLibraryEntity savedEntity = new MaterialLibraryEntity();
        savedEntity.setId(10L);
        savedEntity.setName("测试素材");
        savedEntity.setContent("蒸馏后的世界观");
        when(repository.save(any(MaterialLibraryEntity.class))).thenReturn(savedEntity);

        // Execute
        MaterialLibraryEntity result = service.distill(1L, "原始世界观内容", MaterialCategory.WORLD, "测试素材", "来源");

        // Verify
        assertThat(result.getContent()).isEqualTo("蒸馏后的世界观");
        assertThat(result.getName()).isEqualTo("测试素材");

        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiProvider).streamText(requestCaptor.capture());
        AiRequest request = requestCaptor.getValue();
        assertThat(request.getSystemPrompt()).isEqualTo("你是蒸馏助手");
        assertThat(request.getUserPrompt()).contains("原始世界观内容");
        assertThat(request.getModel()).isEqualTo("gpt-4");
    }

    @Test
    void distill_fallsBackToOtherTemplate() {
        // Category template not found, falls back to OTHER
        when(templateLoader.getTemplate("SKILL")).thenReturn(null);
        when(templateLoader.getTemplate("OTHER")).thenReturn("通用蒸馏 {{content}}");
        when(templateLoader.getSystemPrompt("SKILL")).thenReturn(null);
        when(templateLoader.getSystemPrompt("OTHER")).thenReturn("通用助手");

        AiProviderRouter.ResolvedModel resolved = new AiProviderRouter.ResolvedModel(
                aiProvider, "model1", null, null, null);
        when(providerRouter.resolveModel(eq(1L), eq(WorkflowStep.WORLD_BUILDING))).thenReturn(resolved);
        when(aiProvider.streamText(any(AiRequest.class))).thenReturn(Flux.just("蒸馏结果"));

        MaterialLibraryEntity savedEntity = new MaterialLibraryEntity();
        savedEntity.setContent("蒸馏结果");
        when(repository.save(any())).thenReturn(savedEntity);

        MaterialLibraryEntity result = service.distill(1L, "技能内容", MaterialCategory.SKILL, "技能素材", null);

        assertThat(result.getContent()).isEqualTo("蒸馏结果");
        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiProvider).streamText(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getSystemPrompt()).isEqualTo("通用助手");
    }

    @Test
    void distill_throwsRuntimeExceptionOnAiFailure() {
        when(templateLoader.getTemplate("WORLD")).thenReturn("模板 {{content}}");
        when(templateLoader.getSystemPrompt("WORLD")).thenReturn("sys");

        AiProviderRouter.ResolvedModel resolved = new AiProviderRouter.ResolvedModel(
                aiProvider, "model1", null, null, null);
        when(providerRouter.resolveModel(any(), any())).thenReturn(resolved);
        when(aiProvider.streamText(any())).thenReturn(Flux.error(new RuntimeException("AI超时")));

        assertThatThrownBy(() -> service.distill(1L, "内容", MaterialCategory.WORLD, "名称", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("AI蒸馏失败");
    }

    @Test
    void listForInjection_emptyIds_returnsEmpty() {
        assertThat(service.listForInjection(null)).isEmpty();
        assertThat(service.listForInjection(List.of())).isEmpty();
    }

    @Test
    void listForInjection_buildsSingleMaterial() {
        MaterialLibraryEntity entity = new MaterialLibraryEntity();
        entity.setId(1L);
        entity.setName("世界观素材");
        entity.setCategory(MaterialCategory.WORLD);
        entity.setContent("一段世界观描述");
        when(repository.findByIdIn(List.of(1L))).thenReturn(List.of(entity));

        String result = service.listForInjection(List.of(1L));

        assertThat(result).contains("【参考素材库】");
        assertThat(result).contains("〔世界观〕世界观素材");
        assertThat(result).contains("一段世界观描述");
        assertThat(result).contains("以上素材仅供参考借鉴");
    }

    @Test
    void listForInjection_buildsMultipleMaterials() {
        MaterialLibraryEntity e1 = new MaterialLibraryEntity();
        e1.setId(1L);
        e1.setName("角色A");
        e1.setCategory(MaterialCategory.CHARACTER);
        e1.setContent("角色描述A");

        MaterialLibraryEntity e2 = new MaterialLibraryEntity();
        e2.setId(2L);
        e2.setName("技能X");
        e2.setCategory(MaterialCategory.SKILL);
        e2.setContent("技能描述X");

        when(repository.findByIdIn(List.of(1L, 2L))).thenReturn(List.of(e1, e2));

        String result = service.listForInjection(List.of(1L, 2L));

        assertThat(result).contains("〔角色〕角色A");
        assertThat(result).contains("角色描述A");
        assertThat(result).contains("〔金手指/技能〕技能X");
        assertThat(result).contains("技能描述X");
    }

    @Test
    void listForInjection_noMatchingIds_returnsEmpty() {
        when(repository.findByIdIn(List.of(99L))).thenReturn(List.of());

        String result = service.listForInjection(List.of(99L));

        assertThat(result).isEmpty();
    }

    @Test
    void findAll_delegatesToRepository() {
        service.findAll();
        verify(repository).findAllByOrderByUpdatedAtDesc();
    }

    @Test
    void findByCategory_delegatesToRepository() {
        service.findByCategory(MaterialCategory.ITEM);
        verify(repository).findByCategoryOrderByUpdatedAtDesc(MaterialCategory.ITEM);
    }

    @Test
    void deleteById_delegatesToRepository() {
        service.deleteById(5L);
        verify(repository).deleteById(5L);
    }
}
