package com.storycreator.learn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人教版（PEP）英语单词库（小学 3~6 年级 + 初中 7~9 年级 + 高中必修1-5 / 选修6-11），
 * 供「英语单词匹配」小游戏使用，共 24 册。
 *
 * <p>数据源为 classpath 下的 {@code learn/pep-words.json}，结构为「册 → 主题 → 单词」。
 * 同一主题内的单词语义相关，因此<b>按主题切关</b>（而不是把整册单词顺序切）——
 * 这样同一关里的词天然成套（都是颜色 / 都是动物……），符合「同类型或有关联的放同一关」的要求。
 * 高中册的主题即<b>课本单元</b>（每册 5 个单元）。
 *
 * <p>切关规则：每个主题内按 {@link #splitBalanced(int)} 均分成若干关，每关
 * {@value #MIN_PAIRS}~{@value #MAX_PAIRS} 对、<b>围绕 {@value #TARGET_PAIRS} 对</b>为目标，
 * 保证不出现只有 1~2 对的边角关，也不出现一关超过 {@value #MAX_PAIRS} 对。
 */
@Service
public class WordMatchBank {

    private static final Logger log = LoggerFactory.getLogger(WordMatchBank.class);

    /** 每关最少 / 最多配对数。 */
    public static final int MIN_PAIRS = 3;
    public static final int MAX_PAIRS = 7;

    /**
     * 每关配对数目标值。切关时优先让每关落在 6 对左右——
     * 卡片太少会让棋盘下方留出大片空白，太多则手机一屏放不下。
     * 3~7 是硬边界，6 是软目标。
     */
    public static final int TARGET_PAIRS = 6;

    private static final String RESOURCE = "learn/pep-words.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 册元信息（前端年级选择器用）。stage 为学段（小学 / 初中 / 高中），选择器按它分组。 */
    public record BookInfo(String id, String label, int grade, String semester, String stage,
                           int levelCount, int wordCount) {
    }

    /** 一对单词（key 在整册内唯一，前端用它判断配对成功）。 */
    public record WordPair(String key, String en, String zh) {
    }

    /** 一个关卡：主题名 + {@value #MIN_PAIRS}~{@value #MAX_PAIRS} 对单词。 */
    public record Level(int index, String theme, List<WordPair> pairs) {
    }

    private final List<BookInfo> books = new ArrayList<>();
    private final Map<String, List<Level>> levelsByBook = new LinkedHashMap<>();
    private final Map<String, String> bookLabels = new LinkedHashMap<>();

    public WordMatchBank() {
        load();
    }

    /** 前端引导数据：册列表 + 每册的关卡（含全部单词）。形状与大学词库共用（见 {@link WordBankJson}）。 */
    public Map<String, Object> bootstrapData() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("books", WordBankJson.booksNode(books));
        result.put("levels", WordBankJson.levelsNode(levelsByBook));
        return result;
    }

    public List<BookInfo> getBooks() {
        return List.copyOf(books);
    }

    public List<Level> getLevels(String bookId) {
        return levelsByBook.getOrDefault(bookId, List.of());
    }

    public String labelOf(String bookId) {
        return bookLabels.getOrDefault(bookId, bookId);
    }

    /**
     * 把 n 个词均分成若干关，每关 {@value #MIN_PAIRS}~{@value #MAX_PAIRS} 对、尽量贴近 {@value #TARGET_PAIRS} 对。
     *
     * <p>关卡数取两个约束的较大者：
     * <ul>
     *   <li>{@code ceil(n / MAX_PAIRS)} —— 保证没有任何一关超过 {@value #MAX_PAIRS} 对；
     *       只有 n=8 时需要它（只按目标值算会得到 1 关 8 对）。</li>
     *   <li>{@code round(n / TARGET_PAIRS)} —— 让每关尽量落在 {@value #TARGET_PAIRS} 对附近。</li>
     * </ul>
     * 再把余数摊到前面的关，使各关大小尽量均匀。
     * 例：12 个词切成 6+6（而不是 7+5）；13 个词切成 7+6；15 个词切成 5+5+5。
     */
    static List<Integer> splitBalanced(int n) {
        List<Integer> out = new ArrayList<>();
        if (n <= 0) {
            return out;
        }
        if (n <= MAX_PAIRS) {
            out.add(n);
            return out;
        }
        int byMax = (int) Math.ceil(n / (double) MAX_PAIRS);
        int byTarget = (int) Math.round(n / (double) TARGET_PAIRS);
        int groups = Math.min(Math.max(byMax, byTarget), n);
        int base = n / groups;
        int remainder = n % groups;
        for (int i = 0; i < groups; i++) {
            out.add(base + (i < remainder ? 1 : 0));
        }
        return out;
    }

    private void load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            for (JsonNode bookNode : root.path("books")) {
                String bookId = bookNode.path("id").asText();
                String label = bookNode.path("label").asText();
                int grade = bookNode.path("grade").asInt();
                String semester = bookNode.path("semester").asText();
                // 学段（小学 / 初中 / 高中）：册次选择器按它分组；数据缺字段时按年级推导兜底
                String stage = bookNode.path("stage").asText("").trim();
                if (stage.isEmpty()) {
                    stage = grade >= 10 ? "高中" : (grade >= 7 ? "初中" : "小学");
                }

                List<Level> levels = new ArrayList<>();
                int wordCount = 0;
                for (JsonNode themeNode : bookNode.path("themes")) {
                    String themeName = themeNode.path("name").asText();
                    List<String[]> words = new ArrayList<>();
                    for (JsonNode wordNode : themeNode.path("words")) {
                        if (!wordNode.isArray() || wordNode.size() < 2) {
                            continue;
                        }
                        String en = wordNode.get(0).asText().trim();
                        String zh = wordNode.get(1).asText().trim();
                        if (en.isEmpty() || zh.isEmpty()) {
                            continue;
                        }
                        words.add(new String[]{en, zh});
                    }
                    if (words.size() < MIN_PAIRS) {
                        log.warn("单词库主题 {} / {} 只有 {} 个词，不足 {} 对，该主题不成关",
                                label, themeName, words.size(), MIN_PAIRS);
                        continue;
                    }
                    wordCount += words.size();

                    int cursor = 0;
                    for (int size : splitBalanced(words.size())) {
                        List<WordPair> pairs = new ArrayList<>();
                        for (int i = 0; i < size; i++) {
                            String[] w = words.get(cursor + i);
                            pairs.add(new WordPair(bookId + ":" + levels.size() + ":" + i, w[0], w[1]));
                        }
                        cursor += size;
                        levels.add(new Level(levels.size(), themeName, List.copyOf(pairs)));
                    }
                }
                books.add(new BookInfo(bookId, label, grade, semester, stage, levels.size(), wordCount));
                bookLabels.put(bookId, label);
                levelsByBook.put(bookId, List.copyOf(levels));
            }
            log.info("单词库加载完成：{} 册 / {} 关 / {} 词（每关 {}~{} 对，目标 {}）",
                    books.size(),
                    levelsByBook.values().stream().mapToInt(List::size).sum(),
                    books.stream().mapToInt(BookInfo::wordCount).sum(),
                    MIN_PAIRS, MAX_PAIRS, TARGET_PAIRS);
        } catch (Exception e) {
            throw new IllegalStateException("加载单词库失败：" + RESOURCE, e);
        }
    }
}
