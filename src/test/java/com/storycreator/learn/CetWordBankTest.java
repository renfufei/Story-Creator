package com.storycreator.learn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 大学词库（独立词源）单测。
 *
 * <p>刻意与人教版那套（{@link WordMatchBankTest}）分开写 —— 两套词源的规则本来就不一样：
 * <ul>
 *   <li>人教版的词表按课本主题组织、要求「意思相同的词必须同一关」；</li>
 *   <li>大学来自扁平考试词表，<b>不去重</b>（同一个词在册内可能出现 2~3 次、释义略有差异），
 *       所以中文释义允许跨关重复，硬性质只剩「每关 3~7 对」+「关内英文不重复」+「零丢失」。</li>
 * </ul>
 *
 * <p><b>主题 = 语义域，而且粒度必须够细</b>：不再按词性（名词/动词/…）粗分，而是按语义域归类
 * （逐条归属见源清单同目录的 {@code cet-themes.tsv}，域清单与展示顺序见 {@code cet-domain-tree.tsv}），
 * 使<b>同一关的词语义相关</b>。域分两级：68 个粗域里有 35 个装得太多 —— 最大的「性质与特征」有 818 条，
 * 从 able 到 awkward 什么形容词都有，6 条随手抽出来彼此毫无关系，做起来记不住。这些大域已按语义
 * 细分成 98 个小分类，加 33 个没超阈值的原域，共 <b>131 个细域、规模 30~149 条</b>。
 * 域内仍按源顺序切关。粒度上限由 {@link #domainsAreFinelyGrained()} 把关。
 *
 * <p><b>册内关卡顺序是交错过的</b>：同一语义域最多连排 5 关（{@code MAX_SAME_DOMAIN_RUN}），
 * 其余互相穿插 —— 不这么排，一册开头会连着做 51 关「人物与身份」、接着 84 关「性质与特征」，
 * 做久了很疲劳。见 {@link #sameDomainNeverRunsLongerThanFiveLevels()}。
 *
 * <p>源清单 {@code learn/cet-words-source/cet-4.txt / cet-6.txt} 是唯一真相，
 * 由 {@code scripts/learn/build_cet_words.py} 从上游词表逐行照抄（不排序、不去重）。
 */
@DisplayName("英语单词匹配 · 大学词库（独立词源）")
class CetWordBankTest {

    private static final String SOURCE_DIR = "learn/cet-words-source/";

    /** 细域树（源清单同目录）：`父域<TAB>细域<TAB>说明`，行序即域在册内的排列顺序。 */
    private static final String DOMAIN_TREE = SOURCE_DIR + "cet-domain-tree.tsv";

    /** 逐条语义域归属：`英文<TAB>释义<TAB>细域`。 */
    private static final String THEME_SOURCE = SOURCE_DIR + "cet-themes.tsv";

    /** 每个细域在册内最多允许多少条词 —— 超过就说明粒度不够，大域没拆开。 */
    private static final int MAX_WORDS_PER_DOMAIN = 150;

    /** 兜底父域：装的是没归好类的杂项，语义本就发散，单独放行。 */
    private static final String FALLBACK_PARENT = "特殊类别";

    /** 细域 → 父域。**从细域树读**，不在测试里再抄一遍 131 个名字（抄一遍就会漂）。 */
    private static final Map<String, String> DOMAIN_PARENT = loadDomainTree();

    /** 树里登记的全部细域。 */
    private static final Set<String> DOMAINS = DOMAIN_PARENT.keySet();

    /** 同一语义域在册内最多能连排几关（与 {@code build_cet_words.py} 的 MAX_SAME_DOMAIN_RUN 一致）。 */
    private static final int MAX_SAME_DOMAIN_RUN = 5;

    /** 主题名同名域多关时带「 · 序号」后缀，取基名比对。 */
    private static String baseTheme(String theme) {
        int i = theme.indexOf(" · ");
        return i < 0 ? theme : theme.substring(0, i);
    }

    private static Map<String, String> loadDomainTree() {
        Map<String, String> parent = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(DOMAIN_TREE).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t");
                assertThat(parts).as("细域树每行都是「父域<TAB>细域<TAB>说明」").hasSize(3);
                assertThat(parent.put(parts[1], parts[0]))
                        .as("细域树里细域重复定义：%s", parts[1]).isNull();
            }
        } catch (Exception e) {
            throw new IllegalStateException("读不到细域树 " + DOMAIN_TREE, e);
        }
        assertThat(parent).as("细域树不能为空").isNotEmpty();
        assertThat(parent.values()).as("兜底父域必须在树里").contains(FALLBACK_PARENT);
        return parent;
    }

    /** 读逐条语义域映射 → {@code 英文\0释义} 到细域。 */
    private static Map<String, String> loadThemes() {
        Map<String, String> themeOf = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(THEME_SOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t");
                assertThat(parts).as("语义域映射每行都是「英文<TAB>释义<TAB>细域」").hasSize(3);
                String previous = themeOf.put(parts[0].strip() + "\u0000" + parts[1].strip(), parts[2].strip());
                assertThat(previous).as("语义域映射里 (英文,释义) 重复：%s / %s", parts[0], parts[1]).isNull();
            }
        } catch (Exception e) {
            throw new IllegalStateException("读不到语义域映射 " + THEME_SOURCE, e);
        }
        return themeOf;
    }

    /** 源清单逐行 → 二维数组 {@code [英文, 释义]}（首尾空白已去，与生成脚本一致）。 */
    private static List<String[]> readSourceRows(String bookId) throws Exception {
        List<String[]> rows = new ArrayList<>();
        ClassPathResource res = new ClassPathResource(SOURCE_DIR + bookId + ".txt");
        assertThat(res.exists()).as("源清单 %s 必须存在", SOURCE_DIR + bookId + ".txt").isTrue();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", 2);
                assertThat(parts).as("源清单每行都是「英文<TAB>释义」").hasSize(2);
                rows.add(new String[]{parts[0].strip(), parts[1].strip()});
            }
        }
        return rows;
    }

    private final CetWordBank bank = new CetWordBank();

    @Test
    @DisplayName("两册：四级 / 六级，学段为「大学」，与人教版那 24 册各自独立")
    void twoBooksWithOwnStage() {
        assertThat(bank.getBooks()).extracting(WordMatchBank.BookInfo::id)
                .containsExactly("cet-4", "cet-6");
        assertThat(bank.getBooks()).extracting(WordMatchBank.BookInfo::label)
                .containsExactly("四级", "六级");
        assertThat(bank.getBooks()).extracting(WordMatchBank.BookInfo::stage)
                .as("学段用于册次选择器分组，大学要单独成组")
                .containsOnly("大学");
        assertThat(bank.getBooks()).extracting(WordMatchBank.BookInfo::levelCount)
                .as("四级 7508 条 / 六级 5651 条，按 131 个细域归类后每关 6 条切")
                .containsExactly(1257, 946);
        assertThat(bank.getBooks()).extracting(WordMatchBank.BookInfo::wordCount)
                .containsExactly(7508, 5651);
    }

    @Test
    @DisplayName("每关 3~7 对（实际 5~7），且关内英文不重复 —— 棋盘上不会出现两张同样的卡片")
    void everyLevel_hasThreeToSevenPairsAndDistinctEnglish() {
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            List<WordMatchBank.Level> levels = bank.getLevels(book.id());
            assertThat(levels).as("%s 关卡数应与元信息一致", book.label())
                    .hasSize(book.levelCount());
            assertThat(levels).allSatisfy(level -> {
                assertThat(level.pairs())
                        .as("%s 第 %d 关（%s）配对数", book.label(), level.index() + 1, level.theme())
                        .hasSizeBetween(WordMatchBank.MIN_PAIRS, WordMatchBank.MAX_PAIRS);
                Set<String> en = new HashSet<>();
                for (WordMatchBank.WordPair pair : level.pairs()) {
                    assertThat(pair.en()).as("英文非空").isNotBlank();
                    assertThat(pair.zh()).as("中文非空").isNotBlank();
                    assertThat(en.add(pair.en().toLowerCase()))
                            .as("%s 第 %d 关英文重复：%s", book.label(), level.index() + 1, pair.en())
                            .isTrue();
                }
            });
        }
    }

    @Test
    @DisplayName("主题只来自细域树里登记的域；用到的域恰好是「册内词条数 >= MIN_PAIRS」的那些")
    void levels_areGroupedBySemanticDomain() throws Exception {
        Map<String, String> themeOf = loadThemes();
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            // 期望值不写死：从源清单 ∩ 语义域映射现算 —— 词库改了这里跟着走，不用再抄一遍域名
            Map<String, Integer> expected = new LinkedHashMap<>();
            for (String[] row : readSourceRows(book.id())) {
                String domain = themeOf.get(row[0] + "\u0000" + row[1]);
                assertThat(domain).as("源清单里的 (%s, %s) 必须有语义域归属", row[0], row[1]).isNotNull();
                expected.merge(domain, 1, Integer::sum);
            }
            // 不足 MIN_PAIRS 条的碎域会被并进同父域的兄弟细域（找不到兄弟才落兜底），因此不成关
            expected.values().removeIf(n -> n < WordMatchBank.MIN_PAIRS);

            Set<String> used = new LinkedHashSet<>();
            for (WordMatchBank.Level level : bank.getLevels(book.id())) {
                String base = baseTheme(level.theme());
                assertThat(DOMAINS).as("%s 出现未登记的语义域：%s", book.label(), level.theme())
                        .contains(base);
                used.add(base);
            }
            assertThat(used).as("%s 用到的语义域应与源清单现算的一致", book.label())
                    .containsExactlyInAnyOrderElementsOf(expected.keySet());
        }
    }

    @Test
    @DisplayName("粒度：每个细域在册内不超过 150 条词（「性质与特征」818 条那种粗域必须拆开）")
    void domainsAreFinelyGrained() {
        Set<String> all = new LinkedHashSet<>();
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            Map<String, Integer> wordsOf = new LinkedHashMap<>();
            for (WordMatchBank.Level level : bank.getLevels(book.id())) {
                String base = baseTheme(level.theme());
                all.add(base);
                wordsOf.merge(base, level.pairs().size(), Integer::sum);
            }
            assertThat(wordsOf).as("%s 的语义域个数（拆细后应是上百个，而不是原来的 67 个）", book.label())
                    .hasSizeGreaterThanOrEqualTo(120);
            wordsOf.forEach((domain, words) -> assertThat(words)
                    .as("%s 的细域「%s」有 %d 条词，粒度太粗（上限 %d 条）",
                            book.label(), domain, words, MAX_WORDS_PER_DOMAIN)
                    .isLessThanOrEqualTo(MAX_WORDS_PER_DOMAIN));
        }
        assertThat(all).as("两册合计用到的细域个数").hasSizeGreaterThanOrEqualTo(120);
    }

    @Test
    @DisplayName("兜底域不能变成杂物堆：特殊类别系（父域）占比低于 3%")
    void fallbackBucketStaysSmall() {
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            int total = 0;
            int fallback = 0;
            for (WordMatchBank.Level level : bank.getLevels(book.id())) {
                int n = level.pairs().size();
                total += n;
                if (FALLBACK_PARENT.equals(DOMAIN_PARENT.get(baseTheme(level.theme())))) {
                    fallback += n;
                }
            }
            /* 兜底域是「没归好类」的杂物桶。它一旦变大，说明前面的分类在失效（词丢进来没地方去），
               细分成多少个小分类都没意义 —— 所以给它一个占比上限当告警。实测两册都在 1.6% 上下。 */
            assertThat(fallback * 100.0 / total)
                    .as("%s 落到兜底域「%s」的词条占比", book.label(), FALLBACK_PARENT)
                    .isLessThan(3.0);
        }
    }

    @Test
    @DisplayName("关卡交错：同一语义域在册内最多连排 5 关（不然一册开头要连着做几十关同类词）")
    void sameDomainNeverRunsLongerThanFiveLevels() {
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            String prev = null;
            int run = 0;
            for (WordMatchBank.Level level : bank.getLevels(book.id())) {
                String base = baseTheme(level.theme());
                run = base.equals(prev) ? run + 1 : 1;
                assertThat(run)
                        .as("%s 第 %d 关（%s）同类连排了 %d 关，超过 5 关上限",
                                book.label(), level.index() + 1, level.theme(), run)
                        .isLessThanOrEqualTo(MAX_SAME_DOMAIN_RUN);
                prev = base;
            }
            /* 光有「不超过 5」还不够：整段按域排也是一堆 5 连排，一样过。真正的交错标志是
               「做一册的过程中反复回头做同一类」—— 即域的出现段数明显多于域数。实测：
               整段排时两者相等；细分 + 交错后 四级 303 段 / 130 域、六级 243 段 / 130 域。
               阈值取域数的 1.5 倍：整段排（=1.0）过不了，也不会因为「域变小、每域只切一两块」
               这种数据形态误报。 */
            int segments = 0;
            prev = null;
            for (WordMatchBank.Level level : bank.getLevels(book.id())) {
                String base = baseTheme(level.theme());
                if (!base.equals(prev)) {
                    segments++;
                }
                prev = base;
            }
            int domains = (int) bank.getLevels(book.id()).stream()
                    .map(l -> baseTheme(l.theme())).distinct().count();
            assertThat(segments).as("%s 的语义域出现段数（应明显多于域数 %d，说明真的交错开了）", book.label(), domains)
                    .isGreaterThan(domains * 3 / 2);
        }
    }

    @Test
    @DisplayName("零丢失 · 不去重：词库与源清单逐条相等（含同一个词的多次出现）")
    void bank_coversSourceVocabularyExactly() throws Exception {
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            ClassPathResource res = new ClassPathResource(SOURCE_DIR + book.id() + ".txt");
            assertThat(res.exists()).as("源清单 %s 必须存在", SOURCE_DIR + book.id() + ".txt").isTrue();

            Map<String, Integer> expected = new LinkedHashMap<>();
            int lineCount = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] parts = line.split("\t", 2);
                    assertThat(parts).as("源清单每行都是「英文<TAB>释义」").hasSize(2);
                    /* 上游释义字段大量带首尾空格（四级 7508 行里 4565 行有），
                       生成脚本与词库加载都会 strip，所以比对前统一去掉首尾空白 ——
                       去的是空白，不是词条：重复词条照样按次数一一对上。 */
                    expected.merge(parts[0].strip() + "\u0000" + parts[1].strip(), 1, Integer::sum);
                    lineCount++;
                }
            }

            Map<String, Integer> actual = new LinkedHashMap<>();
            for (WordMatchBank.Level level : bank.getLevels(book.id())) {
                for (WordMatchBank.WordPair pair : level.pairs()) {
                    actual.merge(pair.en() + "\u0000" + pair.zh(), 1, Integer::sum);
                }
            }
            // 逐条比对（含次数）：既证明零丢失，也证明**没有做任何去重**
            assertThat(actual).as("%s 词库必须与源清单逐条一致（不多不少、不合并同名词条）", book.label())
                    .isEqualTo(expected);
            assertThat(book.wordCount()).as("%s 词数应等于源清单行数", book.label()).isEqualTo(lineCount);
        }
    }

    @Test
    @DisplayName("刻意保留重复：册内同词多条、四级六级之间同词共存")
    void duplicatesAreKeptOnPurpose() {
        Map<String, Integer> cet4 = englishCounts("cet-4");
        Map<String, Integer> cet6 = englishCounts("cet-6");

        assertThat(cet4.get("access")).as("access 在四级原表出现 3 次，必须全都留着").isEqualTo(3);
        int repeatedInCet4 = (int) cet4.values().stream().filter(n -> n > 1).count();
        int repeatedInCet6 = (int) cet6.values().stream().filter(n -> n > 1).count();
        assertThat(repeatedInCet4).as("四级里重复出现的词不应被去重").isGreaterThan(1000);
        assertThat(repeatedInCet6).as("六级里重复出现的词不应被去重").isGreaterThan(1000);

        Set<String> shared = new HashSet<>(cet4.keySet());
        shared.retainAll(cet6.keySet());
        assertThat(shared).as("四级与六级之间也不去重，共享词应保留在两边")
                .hasSizeGreaterThan(1000);
    }

    @Test
    @DisplayName("重复的词被分散到不同关卡（域内顺序切关带来的复习节奏）")
    void repeatedWords_areSpreadAcrossLevels() {
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            Map<String, List<Integer>> levelOf = new HashMap<>();
            for (WordMatchBank.Level level : bank.getLevels(book.id())) {
                for (WordMatchBank.WordPair pair : level.pairs()) {
                    levelOf.computeIfAbsent(pair.en(), k -> new ArrayList<>()).add(level.index());
                }
            }
            List<Integer> gaps = new ArrayList<>();
            levelOf.values().forEach(list -> {
                for (int i = 1; i < list.size(); i++) {
                    gaps.add(list.get(i) - list.get(i - 1));
                }
            });
            assertThat(gaps).as("%s 应存在大量重复词", book.label()).isNotEmpty();
            gaps.sort(Integer::compareTo);
            int median = gaps.get(gaps.size() / 2);
            /* 阈值说明：按语义域归类后，一册被拆成 130 个子序列，重复词的间距随「域规模」缩小
               （细分前实测中位数 四级 10 / 六级 8 关；域间交错 + 细分后摊得更开，实测 427 / 288）。
               这里要防的是「把重复词摊到相邻关」那种贪心分配 —— 那样中位数会掉到 1~2。 */
            assertThat(median).as("%s 同词两次出现的关卡间距中位数（不应挤在相邻关）", book.label())
                    .isGreaterThanOrEqualTo(50);
        }
    }

    @Test
    @DisplayName("配对 key 全册唯一；关卡内单词总数等于元信息词数")
    void pairKeys_areUniqueWithinBook() {
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            Set<String> keys = new HashSet<>();
            int total = 0;
            for (WordMatchBank.Level level : bank.getLevels(book.id())) {
                for (WordMatchBank.WordPair pair : level.pairs()) {
                    total++;
                    assertThat(keys.add(pair.key()))
                            .as("%s 中配对 key 重复：%s", book.label(), pair.key())
                            .isTrue();
                }
            }
            assertThat(total).as("%s 单词总数应等于元信息词数", book.label())
                    .isEqualTo(book.wordCount());
        }
    }

    @Test
    @DisplayName("引导数据：与人教版同一份契约（books + levels），前端可无差别合并")
    void bootstrapData_shapesMatchFrontendContract() {
        var data = bank.bootstrapData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> books = (List<Map<String, Object>>) data.get("books");
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> levels =
                (Map<String, List<Map<String, Object>>>) data.get("levels");

        assertThat(books).hasSize(2);
        assertThat(levels.keySet()).containsExactly("cet-4", "cet-6");
        for (Map<String, Object> book : books) {
            String id = String.valueOf(book.get("id"));
            assertThat(String.valueOf(book.get("stage"))).as("%s 的学段", id).isEqualTo("大学");
            List<Map<String, Object>> bookLevels = levels.get(id);
            assertThat((Integer) book.get("levelCount")).as("%s levelCount", id).isEqualTo(bookLevels.size());
            for (Map<String, Object> level : bookLevels) {
                assertThat(level).containsKeys("index", "theme", "pairs");
                assertThat((List<?>) level.get("pairs"))
                        .hasSizeBetween(WordMatchBank.MIN_PAIRS, WordMatchBank.MAX_PAIRS);
            }
        }
    }

    @Test
    @DisplayName("册元信息索引（bookIndex）：首屏只下发这个，不含任何关卡")
    void bookIndex_carriesMetadataWithoutLevels() {
        List<Map<String, Object>> index = bank.bookIndex();
        assertThat(index).hasSize(2);
        assertThat(index.get(0)).containsKeys("id", "label", "grade", "semester", "stage",
                "levelCount", "wordCount");
        assertThat(index.get(0)).doesNotContainKeys("levels", "themes");
        assertThat(index).extracting(m -> String.valueOf(m.get("id")))
                .containsExactly("cet-4", "cet-6");
    }

    private Map<String, Integer> englishCounts(String bookId) {
        Map<String, Integer> counts = new HashMap<>();
        for (WordMatchBank.Level level : bank.getLevels(bookId)) {
            for (WordMatchBank.WordPair pair : level.pairs()) {
                counts.merge(pair.en(), 1, Integer::sum);
            }
        }
        return counts;
    }
}
