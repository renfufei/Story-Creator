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
public class MaterialDistillationTemplateLoader {

    private static final Logger log = LoggerFactory.getLogger(MaterialDistillationTemplateLoader.class);

    private final ResourcePatternResolver resourcePatternResolver;
    private final Map<String, String> templates = new HashMap<>();
    private final Map<String, String> systemPrompts = new HashMap<>();

    public MaterialDistillationTemplateLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    @PostConstruct
    public void load() {
        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:prompts/distillation/*.yaml");
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
                    log.warn("Failed to load distillation template: {}", resource.getFilename(), e);
                }
            }
            log.info("Loaded {} distillation prompt templates", templates.size());
        } catch (Exception e) {
            log.error("Failed to scan distillation prompt templates", e);
        }
    }

    private String trimTrailingNewline(String s) {
        if (s == null) return null;
        if (s.endsWith("\n")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public String getTemplate(String category) {
        return templates.get(category);
    }

    public String getSystemPrompt(String category) {
        return systemPrompts.get(category);
    }
}
