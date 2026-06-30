package com.storycreator.ai.prompt;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContextSummaryTemplateLoaderTest {

    @Mock
    private ResourcePatternResolver resourcePatternResolver;

    private ContextSummaryTemplateLoader loader;

    @BeforeEach
    void setUp() {
        loader = new ContextSummaryTemplateLoader(resourcePatternResolver);
    }

    private Resource mockResource(String filename, String yamlContent) throws Exception {
        Resource resource = mock(Resource.class);
        when(resource.getInputStream()).thenReturn(
                new ByteArrayInputStream(yamlContent.getBytes(StandardCharsets.UTF_8)));
        when(resource.getFilename()).thenReturn(filename);
        return resource;
    }

    @Test
    void load_parsesAllFourTemplates() throws Exception {
        Resource r1 = mockResource("world.yaml", "template: world tmpl\nsystemPrompt: world sys\n");
        Resource r2 = mockResource("character.yaml", "template: char tmpl\nsystemPrompt: char sys\n");
        Resource r3 = mockResource("outline.yaml", "template: out tmpl\nsystemPrompt: out sys\n");
        Resource r4 = mockResource("chapter.yaml", "template: chap tmpl\nsystemPrompt: chap sys\n");
        when(resourcePatternResolver.getResources(anyString()))
                .thenReturn(new Resource[]{r1, r2, r3, r4});

        loader.load();

        assertThat(loader.size()).isEqualTo(4);
    }

    @Test
    void load_skipsNullData() throws Exception {
        Resource resource = mock(Resource.class);
        // Empty YAML doc parses to null
        when(resource.getInputStream()).thenReturn(
                new ByteArrayInputStream("---\n".getBytes(StandardCharsets.UTF_8)));
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});

        loader.load();

        assertThat(loader.size()).isEqualTo(0);
    }

    @Test
    void load_skipsNullFilename() throws Exception {
        Resource resource = mock(Resource.class);
        when(resource.getInputStream()).thenReturn(
                new ByteArrayInputStream("template: content\n".getBytes(StandardCharsets.UTF_8)));
        when(resource.getFilename()).thenReturn(null);
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});

        loader.load();

        assertThat(loader.size()).isEqualTo(0);
    }

    @Test
    void getTemplate_returnsCorrectContent() throws Exception {
        Resource resource = mockResource("world.yaml", "template: 世界观摘要模板\nsystemPrompt: sys\n");
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});

        loader.load();

        assertThat(loader.getTemplate("world")).isEqualTo("世界观摘要模板");
    }

    @Test
    void getTemplate_returnsNullForUnknownName() throws Exception {
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{});
        loader.load();

        assertThat(loader.getTemplate("nonexistent")).isNull();
    }

    @Test
    void getSystemPrompt_returnsCorrectContent() throws Exception {
        Resource resource = mockResource("world.yaml", "template: tmpl\nsystemPrompt: 你是摘要助手\n");
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});

        loader.load();

        assertThat(loader.getSystemPrompt("world")).isEqualTo("你是摘要助手");
    }

    @Test
    void getSystemPrompt_returnsNullForUnknownName() throws Exception {
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{});
        loader.load();

        assertThat(loader.getSystemPrompt("nonexistent")).isNull();
    }

    @Test
    void load_trimsTrailingNewline() throws Exception {
        String yaml = "template: |\n  content line\nsystemPrompt: |\n  system line\n";
        Resource resource = mockResource("test.yaml", yaml);
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[]{resource});

        loader.load();

        assertThat(loader.getTemplate("test")).doesNotEndWith("\n");
        assertThat(loader.getSystemPrompt("test")).doesNotEndWith("\n");
    }
}
