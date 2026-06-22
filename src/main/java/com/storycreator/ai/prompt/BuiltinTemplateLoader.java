package com.storycreator.ai.prompt;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

@Component
public class BuiltinTemplateLoader {

    private static final Logger log = LoggerFactory.getLogger(BuiltinTemplateLoader.class);

    private final ResourcePatternResolver resourcePatternResolver;
    private final List<BuiltinTemplate> templates = new ArrayList<>();

    public BuiltinTemplateLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    @PostConstruct
    public void load() {
        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:prompts/**/*.yaml");
            Yaml yaml = new Yaml();
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    Map<String, Object> data = yaml.load(is);
                    if (data == null) continue;

                    // Parse step from directory name
                    String path = resource.getURL().getPath();
                    // path like: .../prompts/CHARACTER_DESIGN/CHARACTER_CARD.yaml
                    String[] parts = path.split("/prompts/");
                    if (parts.length < 2) continue;
                    String relativePath = parts[1]; // "CHARACTER_DESIGN/CHARACTER_CARD.yaml"
                    String[] segments = relativePath.split("/");
                    if (segments.length != 2) continue;

                    String stepName = segments[0];
                    String fileName = segments[1].replace(".yaml", "");

                    WorkflowStep step;
                    try {
                        step = WorkflowStep.valueOf(stepName);
                    } catch (IllegalArgumentException e) {
                        continue; // skip non-workflow-step directories (e.g. context-summary)
                    }
                    PromptSubStep subStep = null;
                    if (!"default".equals(fileName)) {
                        subStep = PromptSubStep.valueOf(fileName);
                    }

                    String name = (String) data.get("name");
                    String systemPrompt = trimTrailingNewline((String) data.get("systemPrompt"));
                    String template = trimTrailingNewline((String) data.get("template"));

                    // Key format: "STEP|SUBSTEP|GENRE" - genre is empty for generic
                    String key = step.name() + "|" + (subStep != null ? subStep.name() : "") + "|";

                    templates.add(new BuiltinTemplate(key, step, subStep, null, name, systemPrompt, template));
                } catch (Exception e) {
                    log.warn("Failed to load builtin template: {}", resource.getFilename(), e);
                }
            }
            // Sort by step order
            templates.sort(Comparator.comparingInt(t -> t.step().getOrder()));
            log.info("Loaded {} builtin prompt templates", templates.size());
        } catch (Exception e) {
            log.error("Failed to scan builtin prompt templates", e);
        }
    }

    private String trimTrailingNewline(String s) {
        if (s == null) return null;
        // YAML block scalars add a trailing newline; trim it
        if (s.endsWith("\n")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public List<BuiltinTemplate> getAll() {
        return Collections.unmodifiableList(templates);
    }

    /**
     * Find main-step builtin template (subStep == null).
     * Falls back from genre-specific to generic (null genre).
     */
    public Optional<BuiltinTemplate> findMainStep(WorkflowStep step, Genre genre) {
        if (genre != null) {
            Optional<BuiltinTemplate> genreMatch = templates.stream()
                    .filter(t -> t.step() == step && t.subStep() == null && t.genre() == genre)
                    .findFirst();
            if (genreMatch.isPresent()) return genreMatch;
        }
        return templates.stream()
                .filter(t -> t.step() == step && t.subStep() == null && t.genre() == null)
                .findFirst();
    }

    /**
     * Find sub-step builtin template.
     * Falls back from genre-specific to generic (null genre).
     */
    public Optional<BuiltinTemplate> findSubStep(WorkflowStep step, PromptSubStep subStep, Genre genre) {
        if (subStep == null) return Optional.empty();
        if (genre != null) {
            Optional<BuiltinTemplate> genreMatch = templates.stream()
                    .filter(t -> t.step() == step && t.subStep() == subStep && t.genre() == genre)
                    .findFirst();
            if (genreMatch.isPresent()) return genreMatch;
        }
        return templates.stream()
                .filter(t -> t.step() == step && t.subStep() == subStep && t.genre() == null)
                .findFirst();
    }
}
