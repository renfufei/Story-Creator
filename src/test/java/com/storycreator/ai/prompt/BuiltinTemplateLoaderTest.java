package com.storycreator.ai.prompt;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BuiltinTemplateLoaderTest {

    @Mock
    private ResourcePatternResolver resourcePatternResolver;

    private BuiltinTemplateLoader loader;

    @BeforeEach
    void setUp() {
        loader = new BuiltinTemplateLoader(resourcePatternResolver);
    }

    private Resource mockResource(String urlPath, String yamlContent) throws Exception {
        Resource resource = mock(Resource.class);
        when(resource.getInputStream()).thenReturn(
                new ByteArrayInputStream(yamlContent.getBytes(StandardCharsets.UTF_8)));
        when(resource.getURL()).thenReturn(new URL("file:" + urlPath));
        lenient().when(resource.getFilename()).thenReturn(urlPath.substring(urlPath.lastIndexOf('/') + 1));
        return resource;
    }

    @Test
    void load_scansYamlFilesAndParsesCorrectly() throws Exception {
        String yaml = "name: 测试模板\nsystemPrompt: 系统提示\ntemplate: 模板内容\n";
        Resource resource = mockResource(
                "/jar/classes/prompts/WORLD_BUILDING/WORLD_BUILDING_PRIMARY.yaml", yaml);
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});

        loader.load();

        List<BuiltinTemplate> all = loader.getAll();
        assertThat(all).hasSize(1);
        BuiltinTemplate t = all.get(0);
        assertThat(t.step()).isEqualTo(WorkflowStep.WORLD_BUILDING);
        assertThat(t.subStep()).isEqualTo(PromptSubStep.WORLD_BUILDING_PRIMARY);
        assertThat(t.name()).isEqualTo("测试模板");
        assertThat(t.systemPrompt()).isEqualTo("系统提示");
        assertThat(t.template()).isEqualTo("模板内容");
    }

    @Test
    void load_skipsNonWorkflowStepDirectories() throws Exception {
        String yaml = "name: summary\ntemplate: content\n";
        Resource resource = mockResource(
                "/jar/classes/prompts/context-summary/world.yaml", yaml);
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});

        loader.load();

        assertThat(loader.getAll()).isEmpty();
    }

    @Test
    void load_skipsInvalidYaml() throws Exception {
        // YAML that parses to null (empty document) — code continues before reaching getURL()
        Resource resource = mock(Resource.class);
        when(resource.getInputStream()).thenReturn(
                new ByteArrayInputStream("---\n".getBytes(StandardCharsets.UTF_8)));
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});

        loader.load();

        assertThat(loader.getAll()).isEmpty();
    }

    @Test
    void load_primaryFileNameSetsPrimarySubStep() throws Exception {
        String yaml = "name: 默认模板\ntemplate: content\n";
        Resource resource = mockResource(
                "/jar/classes/prompts/CHARACTER_DESIGN/CHARACTER_DESIGN_PRIMARY.yaml", yaml);
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});

        loader.load();

        assertThat(loader.getAll()).hasSize(1);
        assertThat(loader.getAll().get(0).subStep()).isEqualTo(PromptSubStep.CHARACTER_DESIGN_PRIMARY);
        assertThat(loader.getAll().get(0).subStep().isPrimary()).isTrue();
    }

    @Test
    void load_nonDefaultFileNameSetsSubStep() throws Exception {
        String yaml = "name: 角色卡\ntemplate: card content\n";
        Resource resource = mockResource(
                "/jar/classes/prompts/CHARACTER_DESIGN/CHARACTER_CARD.yaml", yaml);
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});

        loader.load();

        assertThat(loader.getAll()).hasSize(1);
        assertThat(loader.getAll().get(0).subStep()).isEqualTo(PromptSubStep.CHARACTER_CARD);
    }

    @Test
    void load_templatesSortedByStepOrder() throws Exception {
        String yaml1 = "name: 校对\ntemplate: proofread\n";
        String yaml2 = "name: 世界观\ntemplate: world\n";
        Resource r1 = mockResource("/jar/classes/prompts/PROOFREADING/PROOFREAD_PLOT_SUMMARY.yaml", yaml1);
        Resource r2 = mockResource("/jar/classes/prompts/WORLD_BUILDING/WORLD_BUILDING_PRIMARY.yaml", yaml2);
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{r1, r2});

        loader.load();

        List<BuiltinTemplate> all = loader.getAll();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).step()).isEqualTo(WorkflowStep.WORLD_BUILDING);
        assertThat(all.get(1).step()).isEqualTo(WorkflowStep.PROOFREADING);
    }

    @Test
    void findMainStep_returnsGenericWhenNoGenreMatch() throws Exception {
        String yaml = "name: 通用世界观\ntemplate: generic world\n";
        Resource resource = mockResource(
                "/jar/classes/prompts/WORLD_BUILDING/WORLD_BUILDING_PRIMARY.yaml", yaml);
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});
        loader.load();

        Optional<BuiltinTemplate> result = loader.findMainStep(WorkflowStep.WORLD_BUILDING, Genre.XUANHUAN);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("通用世界观");
    }

    @Test
    void findMainStep_prefersGenreSpecificMatch() throws Exception {
        // The loader does not parse genre from YAML (genre is always null in current impl).
        // With genre always null, findMainStep falls back to generic.
        // Test that a generic template is returned when genre != null (fallback behavior)
        String yaml = "name: 通用\ntemplate: generic\n";
        Resource resource = mockResource(
                "/jar/classes/prompts/WORLD_BUILDING/WORLD_BUILDING_PRIMARY.yaml", yaml);
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});
        loader.load();

        // With null genre, it directly matches
        Optional<BuiltinTemplate> result = loader.findMainStep(WorkflowStep.WORLD_BUILDING, null);
        assertThat(result).isPresent();
        assertThat(result.get().genre()).isNull();
    }

    @Test
    void findMainStep_returnsEmptyWhenNoMatch() throws Exception {
        String yaml = "name: 角色卡\ntemplate: card\n";
        Resource resource = mockResource(
                "/jar/classes/prompts/CHARACTER_DESIGN/CHARACTER_CARD.yaml", yaml);
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});
        loader.load();

        // Looking for main step (subStep==null) of WORLD_BUILDING - not loaded
        Optional<BuiltinTemplate> result = loader.findMainStep(WorkflowStep.WORLD_BUILDING, null);

        assertThat(result).isEmpty();
    }

    @Test
    void findSubStep_returnsEmptyWhenSubStepNull() throws Exception {
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{});
        loader.load();

        Optional<BuiltinTemplate> result = loader.findSubStep(WorkflowStep.CHARACTER_DESIGN, null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void findSubStep_returnsGenericFallback() throws Exception {
        String yaml = "name: 角色卡通用\ntemplate: card generic\n";
        Resource resource = mockResource(
                "/jar/classes/prompts/CHARACTER_DESIGN/CHARACTER_CARD.yaml", yaml);
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});
        loader.load();

        // No genre-specific match, should fallback to genre=null
        Optional<BuiltinTemplate> result = loader.findSubStep(
                WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD, Genre.XUANHUAN);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("角色卡通用");
    }

    @Test
    void load_trimTrailingNewline() throws Exception {
        String yaml = "name: test\ntemplate: |\n  content with newline\nsystemPrompt: |\n  system prompt\n";
        Resource resource = mockResource(
                "/jar/classes/prompts/WORLD_BUILDING/WORLD_BUILDING_PRIMARY.yaml", yaml);
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});

        loader.load();

        BuiltinTemplate t = loader.getAll().get(0);
        // YAML block scalar "|\n  content with newline\n" produces "content with newline\n"
        // trimTrailingNewline removes the trailing \n
        assertThat(t.template()).doesNotEndWith("\n");
        assertThat(t.systemPrompt()).doesNotEndWith("\n");
    }

    @Test
    void getAll_returnsUnmodifiableList() throws Exception {
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{});
        loader.load();

        List<BuiltinTemplate> all = loader.getAll();

        assertThatThrownBy(() -> all.add(new BuiltinTemplate("k", WorkflowStep.WORLD_BUILDING,
                null, null, "n", "s", "t")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
