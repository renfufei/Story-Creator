package com.storycreator.ai.prompt;

import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证所有内置提示词模板 YAML 文件能正常解析，字段完整、格式正确。
 */
class BuiltinTemplateYamlValidationTest {

    private static final List<ParsedTemplate> ALL_TEMPLATES = new ArrayList<>();

    record ParsedTemplate(
            String fileName,
            String stepDir,
            WorkflowStep step,
            PromptSubStep subStep,
            String name,
            String systemPrompt,
            String template
    ) {}

    @BeforeAll
    static void loadAllTemplates() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:prompts/**/*.yaml");
        Yaml yaml = new Yaml();

        for (Resource resource : resources) {
            String path = resource.getURL().getPath();
            String[] parts = path.split("/prompts/");
            if (parts.length < 2) continue;
            String relativePath = parts[1];
            String[] segments = relativePath.split("/");
            if (segments.length != 2) continue;

            String stepDir = segments[0];
            String fileName = segments[1].replace(".yaml", "");

            WorkflowStep step;
            try {
                step = WorkflowStep.valueOf(stepDir);
            } catch (IllegalArgumentException e) {
                continue; // skip non-workflow directories (context-summary, distillation)
            }

            PromptSubStep subStep;
            try {
                subStep = PromptSubStep.valueOf(fileName);
            } catch (IllegalArgumentException e) {
                continue;
            }

            try (InputStream is = resource.getInputStream()) {
                Map<String, Object> data = yaml.load(is);
                assertThat(data).as("YAML should not parse to null: %s", relativePath).isNotNull();

                String name = (String) data.get("name");
                String systemPrompt = (String) data.get("systemPrompt");
                String template = (String) data.get("template");

                ALL_TEMPLATES.add(new ParsedTemplate(relativePath, stepDir, step, subStep, name, systemPrompt, template));
            }
        }
    }

    static Stream<ParsedTemplate> allTemplates() {
        return ALL_TEMPLATES.stream();
    }

    @Test
    void allExpectedTemplatesAreLoaded() {
        // All PromptSubSteps that have a parent WorkflowStep should have a corresponding YAML file
        Set<PromptSubStep> loadedSubSteps = new HashSet<>();
        for (ParsedTemplate t : ALL_TEMPLATES) {
            loadedSubSteps.add(t.subStep());
        }

        for (PromptSubStep subStep : PromptSubStep.values()) {
            WorkflowStep parent = subStep.getParentStep();
            if (parent != null) {
                assertThat(loadedSubSteps)
                        .as("Missing builtin template YAML for %s/%s.yaml", parent.name(), subStep.name())
                        .contains(subStep);
            }
        }
    }

    @Test
    void templateCountMatchesExpectedRange() {
        // Sanity check: we expect at least 20 templates (current count: ~30+)
        assertThat(ALL_TEMPLATES).hasSizeGreaterThanOrEqualTo(20);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    void eachTemplateHasSystemPrompt(ParsedTemplate t) {
        assertThat(t.systemPrompt())
                .as("[%s] systemPrompt must not be blank", t.fileName())
                .isNotBlank();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    void eachTemplateHasTemplateContent(ParsedTemplate t) {
        assertThat(t.template())
                .as("[%s] template must not be blank", t.fileName())
                .isNotBlank();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    void eachTemplateHasName(ParsedTemplate t) {
        assertThat(t.name())
                .as("[%s] name must not be blank", t.fileName())
                .isNotBlank();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    void subStepBelongsToCorrectParentStep(ParsedTemplate t) {
        WorkflowStep expectedParent = t.subStep().getParentStep();
        assertThat(t.step())
                .as("[%s] subStep %s should belong to step %s but file is under %s",
                        t.fileName(), t.subStep(), expectedParent, t.step())
                .isEqualTo(expectedParent);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    void systemPromptDoesNotContainPlaceholders(ParsedTemplate t) {
        // systemPrompt should not contain {{variable}} placeholders — those belong in template
        assertThat(t.systemPrompt())
                .as("[%s] systemPrompt should not contain {{placeholders}}", t.fileName())
                .doesNotContainPattern("\\{\\{\\w+\\}\\}");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    void templateDoesNotContainYamlArtifacts(ParsedTemplate t) {
        // Template content should not contain raw YAML markers that indicate parse errors
        assertThat(t.template())
                .as("[%s] template should not contain YAML document markers", t.fileName())
                .doesNotContain("---\n")
                .doesNotContain("...\n");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    void systemPromptIsReasonableLength(ParsedTemplate t) {
        // systemPrompt should have substantial content (>20 chars) to be meaningful
        assertThat(t.systemPrompt().trim().length())
                .as("[%s] systemPrompt too short to be meaningful", t.fileName())
                .isGreaterThan(20);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    void templateIsReasonableLength(ParsedTemplate t) {
        // template should have substantial content (>10 chars)
        assertThat(t.template().trim().length())
                .as("[%s] template too short to be meaningful", t.fileName())
                .isGreaterThan(10);
    }

    @Test
    void noDuplicateSubSteps() {
        // Each subStep should appear exactly once
        Map<PromptSubStep, List<String>> subStepFiles = new HashMap<>();
        for (ParsedTemplate t : ALL_TEMPLATES) {
            subStepFiles.computeIfAbsent(t.subStep(), k -> new ArrayList<>()).add(t.fileName());
        }
        for (Map.Entry<PromptSubStep, List<String>> entry : subStepFiles.entrySet()) {
            assertThat(entry.getValue())
                    .as("Duplicate YAML files for subStep %s: %s", entry.getKey(), entry.getValue())
                    .hasSize(1);
        }
    }

    @Test
    void loaderProducesIdenticalResults() throws Exception {
        // Verify BuiltinTemplateLoader produces the same count when using real classpath
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        BuiltinTemplateLoader loader = new BuiltinTemplateLoader(resolver);
        loader.load();

        assertThat(loader.getAll())
                .as("BuiltinTemplateLoader should load same count as our manual parse")
                .hasSameSizeAs(ALL_TEMPLATES);
    }
}
