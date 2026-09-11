package com.storycreator.txtimport;

import com.storycreator.persistence.entity.ChapterSplitConfigEntity;
import com.storycreator.persistence.repository.ChapterSplitConfigRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TXT 章节分割调度器。
 *
 * 采用「多 Handler」策略：注入所有 {@link ChapterSplitHandler} 实现，按配置（选中项 / 启用且排序）遍历，
 * 由每个 Handler 自行通过 {@link ChapterSplitHandler#canHandle(String)} 判断是否可处理；
 * 第一个返回 true 的 Handler 通过 {@link ChapterSplitHandler#split(String)} 完成分割。
 *
 * 若某条配置没有对应的专属 Handler（例如用户自定义配置），则使用 {@link GenericConfigSplitHandler} 兜底，
 * 保证既有自定义分割能力不丢失。
 */
@Component
public class TxtChapterSplitter {

    private final List<ChapterSplitHandler> handlers;
    private final ChapterSplitConfigRepository configRepository;
    private final GenericConfigSplitHandler genericHandler;

    public TxtChapterSplitter(List<ChapterSplitHandler> handlers,
                             ChapterSplitConfigRepository configRepository,
                             GenericConfigSplitHandler genericHandler) {
        this.handlers = handlers;
        this.configRepository = configRepository;
        this.genericHandler = genericHandler;
    }

    public List<SplitChapter> split(String text) {
        return split(text, null);
    }

    public List<SplitChapter> split(String text, List<ChapterSplitConfigEntity> configs) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<ChapterSplitConfigEntity> cfgs = (configs != null && !configs.isEmpty())
                ? configs
                : configRepository.findByEnabledTrueOrderBySortOrder();

        Map<String, ChapterSplitHandler> byName = new LinkedHashMap<>();
        for (ChapterSplitHandler h : handlers) {
            byName.putIfAbsent(h.getConfigName(), h);
        }

        for (ChapterSplitConfigEntity config : cfgs) {
            if (config == null || !config.isEnabled()) {
                continue;
            }
            ChapterSplitHandler handler = byName.get(config.getName());
            List<SplitChapter> result;
            if (handler != null) {
                if (!handler.canHandle(text)) {
                    continue;
                }
                result = handler.split(text);
            } else {
                result = genericHandler.split(text, config);
            }
            if (!result.isEmpty()) {
                return result;
            }
        }

        return List.of(new SplitChapter(1, "全文", text.trim(), text.trim().length()));
    }
}
