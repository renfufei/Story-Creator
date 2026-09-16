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
 * 首个切出 ≥2 章（即真正完成切分）的 Handler 胜出——只切出 1 章的命中仅作兜底候选，
 * 不会抢占后续 Handler，详见 {@link #split(String, List)}。
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

    /**
     * 章节分割。遍历 {@code configs}（为空则回退到全部启用配置）：内置 Handler 始终参与（不受 enabled 开关影响，因为
     * 它由 {@code canHandle} 自检测），仅自定义配置受 enabled 控制；第一个 {@code canHandle} 命中的 Handler 胜出。
     *
     * <p>进入正则匹配之前会先做一次空白归一化（见 {@link TxtNormalizer#normalizeSpaces}）：
     * 中文 TXT 常用全角空格缩进章节标题，而 Java 的 {@code \s} 不含 U+3000，不归一化就会漏切。</p>
     */
    public List<SplitChapter> split(String text, List<ChapterSplitConfigEntity> configs) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // 归一化是全角→半角的一对一替换，长度与下标与原文一致，可直接用做切片来源
        String normalized = TxtNormalizer.normalizeSpaces(text);

        List<ChapterSplitConfigEntity> cfgs = (configs != null && !configs.isEmpty())
                ? configs
                : configRepository.findByEnabledTrueOrderBySortOrder();

        Map<String, ChapterSplitHandler> byName = new LinkedHashMap<>();
        for (ChapterSplitHandler h : handlers) {
            byName.putIfAbsent(h.getConfigName(), h);
        }

        // 只切出 1 章的命中不算「真正切分」：典型如正文顶部的一条「------」被分隔线 Handler
        // 命中，若直接采纳就会把整本书合成一章（真实案例：一条分隔线压掉 6 个「第X章」）。
        // 因此 1 章结果仅留作兜底候选，继续尝试后续 Handler；首个切出 ≥2 章的 Handler 胜出。
        // 兜底顺序：首个非空结果（保留标题）→ 全文单章。用户在界面上只勾选一个配置时，
        // 该配置即使只切出 1 章也会被兜底返回，行为与改造前一致。
        List<SplitChapter> singleChapterFallback = null;

        for (ChapterSplitConfigEntity config : cfgs) {
            // 内置配置始终参与（自检测），自定义配置才受 enabled 控制
            if (config == null || (!config.isBuiltin() && !config.isEnabled())) {
                continue;
            }
            ChapterSplitHandler handler = byName.get(config.getName());
            List<SplitChapter> result;
            if (handler != null) {
                if (!handler.canHandle(normalized)) {
                    continue;
                }
                result = handler.split(normalized);
            } else {
                result = genericHandler.split(normalized, config);
            }
            if (result.isEmpty()) {
                continue;
            }
            if (result.size() > 1) {
                return result;
            }
            if (singleChapterFallback == null) {
                singleChapterFallback = result;
            }
        }

        // 严格模式（标题行 ≤50 字符）一章都没切出来时，去掉长度守卫再放宽重试一次：
        // 少数书籍的标题与正文挤在同一行长行里，此时宁可拿到「长标题」也不要整本合成一章。
        List<SplitChapter> relaxed = splitRelaxed(normalized, cfgs);
        if (relaxed != null) {
            return relaxed;
        }

        if (singleChapterFallback != null) {
            return singleChapterFallback;
        }
        return List.of(new SplitChapter(1, "全文", normalized.trim(), normalized.trim().length()));
    }

    /**
     * 放宽重试：把每条配置正则里的 {@link AbstractRegexChapterHandler#HEADING_LINE_LIMIT_GUARD}
     * 去掉后重新分割，首个切出 ≥2 章的结果胜出。
     *
     * @return 放宽后的分割结果（至少 1 章）；一条都没切出时返回 null
     */
    private List<SplitChapter> splitRelaxed(String text, List<ChapterSplitConfigEntity> cfgs) {
        List<SplitChapter> single = null;
        for (ChapterSplitConfigEntity config : cfgs) {
            if (config == null || (!config.isBuiltin() && !config.isEnabled())) {
                continue;
            }
            String pattern = AbstractRegexChapterHandler.withoutHeadingLineLimit(config.getPattern());
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            List<SplitChapter> result = AbstractRegexChapterHandler.splitRegex(
                    text, pattern, config.getTitleGroup(), config.isIncludeMatch());
            if (result.isEmpty()) {
                continue;
            }
            if (result.size() > 1) {
                return result;
            }
            if (single == null) {
                single = result;
            }
        }
        return single;
    }
}
