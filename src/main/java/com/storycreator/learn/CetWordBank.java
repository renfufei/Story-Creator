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
 * 大学词汇库（独立词源，与人教版词库 {@link WordMatchBank} 互不干涉）。
 *
 * <p>数据源为 classpath 下的 {@code learn/cet-words.json}，两册：
 * <ul>
 *   <li>{@code cet-4} 四级 —— 7508 条</li>
 *   <li>{@code cet-6} 六级 —— 5651 条</li>
 * </ul>
 *
 * <p><b>与人教版的三条边界</b>（按需求刻意为之）：
 * <ol>
 *   <li><b>不做比较</b>：不拿人教版的词去过滤大学，两套词表重叠多少都不管；</li>
 *   <li><b>不去重</b>：上游「乱序」词表是三段词表拼接，同一个词常出现 2~3 次（释义略有差异），
 *       这些记录<b>全部保留</b>（例：{@code access} 在四级里出现 3 次）。切关时同名记录被分散到
 *       同一语义域内的不同关卡，等于自带复习节奏；</li>
 *   <li><b>独立成册</b>：stage {@code 大学}，不掺进人教版的 24 册里。</li>
 * </ol>
 *
 * <p><b>主题即关卡</b>：这里不调用 {@link WordMatchBank#splitBalanced(int)}。词表本身是扁平的、
 * 没有课本单元可依，切关工作在离线生成 {@code cet-words.json} 时已经做完
 * （见 {@code scripts/learn/build_cet_words.py}：按 {@code cet-themes.tsv} 的 68 个<b>语义域</b>归类，
 * 域内再按源顺序每 6 条切一关），所以这里一个主题就是前端眼里的一关，<b>不再二次切分</b> ——
 * 否则会破坏「关内英文不重复」这条已生成好的性质。
 */
@Service
public class CetWordBank {

    private static final Logger log = LoggerFactory.getLogger(CetWordBank.class);

    /** 数据源文件；原始词表（唯一真相）在同名目录的测试资源 {@code learn/cet-words-source/} 下。 */
    static final String RESOURCE = "learn/cet-words.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<WordMatchBank.BookInfo> books = new ArrayList<>();
    private final Map<String, List<WordMatchBank.Level>> levelsByBook = new LinkedHashMap<>();

    public CetWordBank() {
        load();
    }

    /** 前端引导数据：册列表 + 每册的关卡（含全部单词对）。 */
    public Map<String, Object> bootstrapData() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("books", WordBankJson.booksNode(books));
        result.put("levels", WordBankJson.levelsNode(levelsByBook));
        return result;
    }

    /**
     * 只含册元信息、不含关卡 —— 首屏引导数据里带的就是它。
     *
     * <p>大学全部词条约 650KB（gzip 约 225KB），是首屏数据的两倍；页面是<b>同步 XHR</b> 取首屏数据的，
     * 塞进来会直接拖长白屏。故关卡等用户真的选到大学时再拉 {@code /learn/word-match/cet}。
     */
    public List<Map<String, Object>> bookIndex() {
        return WordBankJson.booksNode(books);
    }

    public List<WordMatchBank.BookInfo> getBooks() {
        return List.copyOf(books);
    }

    public List<WordMatchBank.Level> getLevels(String bookId) {
        return levelsByBook.getOrDefault(bookId, List.of());
    }

    private void load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            for (JsonNode bookNode : root.path("books")) {
                String bookId = bookNode.path("id").asText();
                String label = bookNode.path("label").asText();
                int grade = bookNode.path("grade").asInt();
                String semester = bookNode.path("semester").asText();
                String stage = bookNode.path("stage").asText("大学");

                List<WordMatchBank.Level> levels = new ArrayList<>();
                int wordCount = 0;
                for (JsonNode themeNode : bookNode.path("themes")) {
                    String themeName = themeNode.path("name").asText();
                    List<WordMatchBank.WordPair> pairs = new ArrayList<>();
                    for (JsonNode wordNode : themeNode.path("words")) {
                        if (!wordNode.isArray() || wordNode.size() < 2) {
                            continue;
                        }
                        String en = wordNode.get(0).asText().trim();
                        String zh = wordNode.get(1).asText().trim();
                        if (en.isEmpty() || zh.isEmpty()) {
                            continue;
                        }
                        pairs.add(new WordMatchBank.WordPair(bookId + ":" + levels.size() + ":" + pairs.size(), en, zh));
                    }
                    if (pairs.size() < WordMatchBank.MIN_PAIRS) {
                        // 生成脚本保证每关 5~7 对，走到这里说明 cet-words.json 被改坏了
                        log.warn("大学词库 {} / {} 只有 {} 对（少于 {} 对），已跳过",
                                label, themeName, pairs.size(), WordMatchBank.MIN_PAIRS);
                        continue;
                    }
                    wordCount += pairs.size();
                    levels.add(new WordMatchBank.Level(levels.size(), themeName, List.copyOf(pairs)));
                }
                books.add(new WordMatchBank.BookInfo(bookId, label, grade, semester, stage,
                        levels.size(), wordCount));
                levelsByBook.put(bookId, List.copyOf(levels));
            }
            log.info("大学词库加载完成：{} 册 / {} 关 / {} 词",
                    books.size(),
                    levelsByBook.values().stream().mapToInt(List::size).sum(),
                    books.stream().mapToInt(WordMatchBank.BookInfo::wordCount).sum());
        } catch (Exception e) {
            throw new IllegalStateException("加载大学词库失败：" + RESOURCE, e);
        }
    }
}
