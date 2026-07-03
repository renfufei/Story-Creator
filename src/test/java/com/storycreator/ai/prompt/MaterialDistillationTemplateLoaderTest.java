package com.storycreator.ai.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaterialDistillationTemplateLoaderTest {

    @Mock
    private ResourcePatternResolver resourcePatternResolver;

    private MaterialDistillationTemplateLoader loader;

    @BeforeEach
    void setUp() {
        loader = new MaterialDistillationTemplateLoader(resourcePatternResolver);
    }

    private Resource mockResource(String filename, String yamlContent) throws Exception {
        Resource resource = mock(Resource.class);
        when(resource.getInputStream()).thenReturn(
                new ByteArrayInputStream(yamlContent.getBytes(StandardCharsets.UTF_8)));
        when(resource.getFilename()).thenReturn(filename);
        return resource;
    }

    @Test
    void load_parsesSixTemplates() throws Exception {
        Resource r1 = mockResource("CHARACTER.yaml", "template: char tmpl\nsystemPrompt: char sys\n");
        Resource r2 = mockResource("WORLD.yaml", "template: world tmpl\nsystemPrompt: world sys\n");
        Resource r3 = mockResource("OUTLINE.yaml", "template: outline tmpl\nsystemPrompt: outline sys\n");
        Resource r4 = mockResource("SKILL.yaml", "template: skill tmpl\nsystemPrompt: skill sys\n");
        Resource r5 = mockResource("ITEM.yaml", "template: item tmpl\nsystemPrompt: item sys\n");
        Resource r6 = mockResource("OTHER.yaml", "template: other tmpl\nsystemPrompt: other sys\n");
        when(resourcePatternResolver.getResources(anyString()))
                .thenReturn(new Resource[]{r1, r2, r3, r4, r5, r6});

        loader.load();

        assertThat(loader.getTemplate("CHARACTER")).isEqualTo("char tmpl");
        assertThat(loader.getTemplate("WORLD")).isEqualTo("world tmpl");
        assertThat(loader.getTemplate("OUTLINE")).isEqualTo("outline tmpl");
        assertThat(loader.getTemplate("SKILL")).isEqualTo("skill tmpl");
        assertThat(loader.getTemplate("ITEM")).isEqualTo("item tmpl");
        assertThat(loader.getTemplate("OTHER")).isEqualTo("other tmpl");
    }

    @Test
    void load_skipsNullData() throws Exception {
        Resource resource = mock(Resource.class);
        when(resource.getInputStream()).thenReturn(
                new ByteArrayInputStream("---\n".getBytes(StandardCharsets.UTF_8)));
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});

        loader.load();

        assertThat(loader.getTemplate("CHARACTER")).isNull();
    }

    @Test
    void load_skipsNullFilename() throws Exception {
        Resource resource = mock(Resource.class);
        when(resource.getInputStream()).thenReturn(
                new ByteArrayInputStream("template: content\n".getBytes(StandardCharsets.UTF_8)));
        when(resource.getFilename()).thenReturn(null);
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});

        loader.load();

        assertThat(loader.getTemplate(null)).isNull();
    }

    @Test
    void getTemplate_returnsNullForUnknownCategory() throws Exception {
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{});
        loader.load();

        assertThat(loader.getTemplate("NONEXISTENT")).isNull();
    }

    @Test
    void getSystemPrompt_returnsCorrectContent() throws Exception {
        Resource resource = mockResource("WORLD.yaml", "template: tmpl\nsystemPrompt: 你是蒸馏助手\n");
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});

        loader.load();

        assertThat(loader.getSystemPrompt("WORLD")).isEqualTo("你是蒸馏助手");
    }

    @Test
    void getSystemPrompt_returnsNullForUnknownCategory() throws Exception {
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{});
        loader.load();

        assertThat(loader.getSystemPrompt("NONEXISTENT")).isNull();
    }

    @Test
    void load_trimsTrailingNewline() throws Exception {
        String yaml = "template: |\n  content line\nsystemPrompt: |\n  system line\n";
        Resource resource = mockResource("CHARACTER.yaml", yaml);
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});

        loader.load();

        assertThat(loader.getTemplate("CHARACTER")).doesNotEndWith("\n");
        assertThat(loader.getSystemPrompt("CHARACTER")).doesNotEndWith("\n");
    }

    @Test
    void load_handlesIOExceptionGracefully() throws Exception {
        when(resourcePatternResolver.getResources(anyString()))
                .thenThrow(new java.io.IOException("test error"));

        loader.load(); // should not throw

        assertThat(loader.getTemplate("CHARACTER")).isNull();
    }
}
