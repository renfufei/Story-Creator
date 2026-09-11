package com.storycreator.txtimport;

import com.storycreator.persistence.entity.ChapterSplitConfigEntity;
import com.storycreator.persistence.repository.ChapterSplitConfigRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于「正则匹配 + 标题分组」的通用分割 Handler 抽象基类。
 *
 * 具体 Handler 只需在构造时提供：
 *  - 绑定的配置名称（configName，对应 chapter_split_configs.name）
 *  - 默认正则 / 标题分组 / 是否包含命中行（当数据库没有该配置时作为兜底）
 *
 * 若数据库中已存在对应配置，则优先使用数据库中的 pattern / titleGroup / includeMatch，
 * 这样用户在前端调整配置时无需改动代码。
 *
 * 注意：标题清洗（去除首尾中文/半角冒号）统一在此处完成，覆盖所有基于正则的分割方式。
 */
public abstract class AbstractRegexChapterHandler implements ChapterSplitHandler {

    private final ChapterSplitConfigRepository configRepository;
    private final String configName;
    private final String defaultPattern;
    private final int defaultTitleGroup;
    private final boolean defaultIncludeMatch;
    private ChapterSplitConfigEntity cachedConfig;

    protected AbstractRegexChapterHandler(ChapterSplitConfigRepository configRepository,
                                          String configName,
                                          String defaultPattern,
                                          int defaultTitleGroup,
                                          boolean defaultIncludeMatch) {
        this.configRepository = configRepository;
        this.configName = configName;
        this.defaultPattern = defaultPattern;
        this.defaultTitleGroup = defaultTitleGroup;
        this.defaultIncludeMatch = defaultIncludeMatch;
    }

    @Override
    public String getConfigName() {
        return configName;
    }

    /** 读取绑定的数据库配置（带缓存）。 */
    protected ChapterSplitConfigEntity getConfig() {
        if (cachedConfig == null) {
            cachedConfig = configRepository.findByName(configName).orElse(null);
        }
        return cachedConfig;
    }

    protected String getPattern() {
        ChapterSplitConfigEntity c = getConfig();
        return (c != null && c.getPattern() != null && !c.getPattern().isBlank()) ? c.getPattern() : defaultPattern;
    }

    protected int getTitleGroup() {
        ChapterSplitConfigEntity c = getConfig();
        return (c != null) ? c.getTitleGroup() : defaultTitleGroup;
    }

    protected boolean isIncludeMatch() {
        ChapterSplitConfigEntity c = getConfig();
        return (c != null) ? c.isIncludeMatch() : defaultIncludeMatch;
    }

    @Override
    public boolean canHandle(String text) {
        ChapterSplitConfigEntity c = getConfig();
        if (c != null && !c.isEnabled()) {
            return false;
        }
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            return Pattern.compile(getPattern()).matcher(text).find();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<SplitChapter> split(String text) {
        return splitRegex(text, getPattern(), getTitleGroup(), isIncludeMatch());
    }

    /**
     * 通用正则分割（静态方法，供本类与 GenericConfigSplitHandler 复用）。
     *
     * @param titleGroup < 0 表示自动编号（标题取「第N章」），0 表示使用整段匹配，>0 表示使用对应分组
     */
    public static List<SplitChapter> splitRegex(String text, String pattern, int titleGroup, boolean includeMatch) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Pattern p = Pattern.compile(pattern);
        Matcher matcher = p.matcher(text);

        List<int[]> positions = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (matcher.find()) {
            positions.add(new int[]{matcher.start(), matcher.end()});
            titles.add(extractTitle(matcher, titleGroup));
        }
        if (positions.isEmpty()) {
            return List.of();
        }

        List<SplitChapter> chapters = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            int contentStart = includeMatch ? positions.get(i)[0] : positions.get(i)[1];
            int contentEnd = (i + 1 < positions.size()) ? positions.get(i + 1)[0] : text.length();

            String content = text.substring(contentStart, contentEnd).trim();
            if (content.isEmpty()) {
                continue;
            }

            String rawTitle = titles.get(i);
            String title = cleanTitle(rawTitle);
            if (title == null || title.isBlank()) {
                title = "第" + (chapters.size() + 1) + "章";
            }

            chapters.add(new SplitChapter(chapters.size() + 1, title, content, content.length()));
        }
        return chapters;
    }

    private static String extractTitle(Matcher matcher, int titleGroup) {
        if (titleGroup < 0) {
            return null; // 自动编号
        }
        try {
            if (titleGroup <= matcher.groupCount()) {
                String title = matcher.group(titleGroup);
                return title != null ? title.trim() : null;
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * 清洗章节标题：去除最前面 / 最后面的中文冒号「：」或半角冒号「:」及其周围空白
     * （含全角空格 \u3000）。例如「：开端」→「开端」，
     * 「第一章：开端」经 titleGroup 提取为「：开端」后同样清洗为「开端」。
     * 仅处理首尾冒号，标题中间的冒号予以保留。
     */
    public static String cleanTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        // 去除开头的中文/半角冒号及周围空白
        t = t.replaceAll("^[\\s\\u3000]*[\\uff1a:][\\s\\u3000]*", "");
        // 去除结尾的冒号及周围空白
        t = t.replaceAll("[\\s\\u3000]*[\\uff1a:][\\s\\u3000]*$", "");
        return t;
    }
}
