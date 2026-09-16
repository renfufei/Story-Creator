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
                    ChapterSplitConfigEntity existing = repository.findByName(name).orElse(null);
                    if (existing != null) {
                        if (existing.isBuiltin()) {
                            // 内置配置以 YAML 为准：刷新其正则/标题分组等定义，
                            // 但保留用户在界面上调整的「启用状态」与「排序」。
                            existing.setPattern((String) data.get("pattern"));
                            existing.setTitleGroup(data.get("titleGroup") != null ? ((Number) data.get("titleGroup")).intValue() : 0);
                            existing.setIncludeMatch(Boolean.TRUE.equals(data.get("includeMatch")));
                            existing.setDescription((String) data.get("description"));
                            repository.save(existing);
                        }
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
                    // 优先级以 YAML 的 order 为准：精确的章节号匹配必须排在启发式（独立标题行 /
                    // 分隔线）之前，否则正文顶部一条「------」就会把整本书合成一章。
                    // 不用扫描顺序，保证加载顺序变化不会打乱优先级；未声明时回落到扫描序号。
                    Object order = data.get("order");
                    entity.setSortOrder(order instanceof Number ? ((Number) order).intValue() : loaded);
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
