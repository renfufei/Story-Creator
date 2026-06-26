package com.storycreator.tts.template;

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
public class TtsReplacementBuiltinLoader {

    private static final Logger log = LoggerFactory.getLogger(TtsReplacementBuiltinLoader.class);

    private final ResourcePatternResolver resourcePatternResolver;
    private final List<BuiltinTtsTemplate> templates = new ArrayList<>();

    public TtsReplacementBuiltinLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    @PostConstruct
    public void load() {
        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:tts-templates/*.yml");
            Yaml yaml = new Yaml();
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    Map<String, Object> data = yaml.load(is);
                    if (data == null) continue;

                    String id = (String) data.get("id");
                    String name = (String) data.get("name");
                    String description = (String) data.get("description");

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rulesData = (List<Map<String, Object>>) data.get("rules");
                    List<TtsReplacementRule> rules = new ArrayList<>();
                    if (rulesData != null) {
                        for (Map<String, Object> ruleData : rulesData) {
                            String pattern = (String) ruleData.get("pattern");
                            String replacement = ruleData.get("replacement") != null ? (String) ruleData.get("replacement") : "";
                            boolean isRegex = Boolean.TRUE.equals(ruleData.get("isRegex"));
                            String ruleDesc = (String) ruleData.get("description");
                            rules.add(new TtsReplacementRule(pattern, replacement, isRegex, ruleDesc));
                        }
                    }

                    templates.add(new BuiltinTtsTemplate(id, name, description, rules));
                } catch (Exception e) {
                    log.warn("Failed to load TTS template: {}", resource.getFilename(), e);
                }
            }
            log.info("Loaded {} builtin TTS replacement templates", templates.size());
        } catch (Exception e) {
            log.error("Failed to scan builtin TTS replacement templates", e);
        }
    }

    public List<BuiltinTtsTemplate> getAll() {
        return Collections.unmodifiableList(templates);
    }

    public Optional<BuiltinTtsTemplate> findById(String id) {
        return templates.stream().filter(t -> t.id().equals(id)).findFirst();
    }
}
