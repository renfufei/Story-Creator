package com.storycreator.txtimport;

import com.storycreator.persistence.entity.ChapterSplitConfigEntity;
import com.storycreator.persistence.repository.ChapterSplitConfigRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

@Component
public class ChapterSplitConfigBuiltinLoader {

    private static final Logger log = LoggerFactory.getLogger(ChapterSplitConfigBuiltinLoader.class);

    private final ResourcePatternResolver resourcePatternResolver;
    private final ChapterSplitConfigRepository repository;

    public ChapterSplitConfigBuiltinLoader(ResourcePatternResolver resourcePatternResolver,
                                           ChapterSplitConfigRepository repository) {
        this.resourcePatternResolver = resourcePatternResolver;
        this.repository = repository;
    }

    @PostConstruct
    public void load() {
        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:chapter-split-configs/*.yml");
            Yaml yaml = new Yaml();
            int loaded = 0;
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    Map<String, Object> data = yaml.load(is);
                    if (data == null) continue;

                    String name = (String) data.get("name");
                    if (repository.existsByNameAndBuiltinTrue(name)) {
                        continue;
                    }

                    ChapterSplitConfigEntity entity = new ChapterSplitConfigEntity();
                    entity.setName(name);
                    entity.setDescription((String) data.get("description"));
                    entity.setPattern((String) data.get("pattern"));
                    entity.setTitleGroup(data.get("titleGroup") != null ? ((Number) data.get("titleGroup")).intValue() : 0);
                    entity.setIncludeMatch(Boolean.TRUE.equals(data.get("includeMatch")));
                    entity.setBuiltin(true);
                    entity.setEnabled(true);
                    entity.setSortOrder(loaded);
                    repository.save(entity);
                    loaded++;
                } catch (Exception e) {
                    log.warn("Failed to load chapter split config: {}", resource.getFilename(), e);
                }
            }
            if (loaded > 0) {
                log.info("Loaded {} builtin chapter split configs", loaded);
            }
        } catch (Exception e) {
            log.error("Failed to scan builtin chapter split configs", e);
        }
    }
}
