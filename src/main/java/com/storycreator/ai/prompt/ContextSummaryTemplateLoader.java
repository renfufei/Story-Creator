package com.storycreator.ai.prompt;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@Component
public class ContextSummaryTemplateLoader {

    private static final Logger log = LoggerFactory.getLogger(ContextSummaryTemplateLoader.class);

    private final ResourcePatternResolver resourcePatternResolver;
    private final Map<String, String> templates = new HashMap<>();
    private final Map<String, String> systemPrompts = new HashMap<>();

    public ContextSummaryTemplateLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    @PostConstruct
    public void load() {
        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:prompts/context-summary/*.yaml");
            Yaml yaml = new Yaml();
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    Map<String, Object> data = yaml.load(is);
                    if (data == null) continue;

                    String fileName = resource.getFilename();
                    if (fileName == null) continue;
                    String name = fileName.replace(".yaml", "");

                    String systemPrompt = trimTrailingNewline((String) data.get("systemPrompt"));
                    String template = trimTrailingNewline((String) data.get("template"));

                    if (template != null) templates.put(name, template);
                    if (systemPrompt != null) systemPrompts.put(name, systemPrompt);
                } catch (Exception e) {
                    log.warn("Failed to load context-summary template: {}", resource.getFilename(), e);
                }
            }
            log.info("Loaded {} context-summary prompt templates", templates.size());
        } catch (Exception e) {
            log.error("Failed to scan context-summary prompt templates", e);
        }
    }

    private String trimTrailingNewline(String s) {
        if (s == null) return null;
        if (s.endsWith("\n")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public String getTemplate(String name) {
        return templates.get(name);
    }

    public String getSystemPrompt(String name) {
        return systemPrompts.get(name);
    }

    public int size() {
        return templates.size();
    }
}
