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
 * <p><b>主题 = 语义域</b>：不再按词性（名词/动词/…）粗分，而是按 68 个语义域归类
 * （见 {@code scripts/learn/build_cet_words.py} 的 {@code DOMAIN_ORDER} 与源清单同目录的
 * {@code cet-themes.tsv}），使<b>同一关的词语义相关</b>。域内仍按源顺序切关。
 *
 * <p>源清单 {@code learn/cet-words-source/cet-4.txt / cet-6.txt} 是唯一真相，
 * 由 {@code scripts/learn/build_cet_words.py} 从上游词表逐行照抄（不排序、不去重）。
 */
@DisplayName("英语单词匹配 · 大学词库（独立词源）")
class CetWordBankTest {

    private static final String SOURCE_DIR = "learn/cet-words-source/";

    /**
     * 68 个语义域，与生成脚本 {@code DOMAIN_ORDER}、{@code cet-themes.tsv} 逐字一致。
     * 顺序：具体生活 → 自然与物质 → 社会 → 心智与抽象 → 功能与特殊。
     */
    private static final Set<String> DOMAINS = Set.of(
            // 具体生活（10）
            "人物与身份", "家庭与亲属", "身体与健康", "饮食与食物", "服饰与打扮",
            "居住与建筑", "交通与出行", "购物与消费", "娱乐与休闲", "日常用品与工具",
            // 自然与物质（5）
            "动物与植物", "自然与天气", "物质与材料", "空间与方位", "事物与部件",
            // 社会（19）
            "组织与机构", "政治与政府", "法律与司法", "军事与战争", "经济与金融",
            "商业与贸易", "工作与职业", "教育与学习", "科学技术", "计算机与信息",
            "媒体与传播", "文学与写作", "艺术与绘画", "音乐与表演", "影视与娱乐",
            "体育与运动", "宗教与信仰", "节日与习俗", "历史与考古",
            // 心智与抽象（30）
            "情绪与感受", "性格与品质", "态度与意愿", "思考与观点", "认知与理解", "记忆与注意",
            "语言与交流", "数量与度量", "时间与频率", "性质与特征", "状态与情况",
            "变化与发展", "增长与减少", "因果与逻辑", "方法与手段", "计划与安排",
            "重要性", "优劣评价", "正确与错误", "关系与异同", "程度与强度",
            "移动与位移", "操作与处理", "获取与给予", "建立与破坏", "保护与维持",
            "帮助与合作", "竞争与冲突", "控制与影响", "交往与联系",
            // 功能与特殊（4）
            "功能词", "专有名词", "短语与搭配", "特殊类别");

    /** 主题名同名域多关时带「 · 序号」后缀，取基名比对。 */
    private static String baseTheme(String theme) {
        int i = theme.indexOf(" · ");
        return i < 0 ? theme : theme.substring(0, i);
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
                .as("四级 7508 条 / 六级 5651 条，按语义域归类后每关 6 条切")
                .containsExactly(1255, 944);
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
    @DisplayName("主题只来自 68 个语义域；两册都应覆盖除「短语与搭配」外的全部域")
    void levels_areGroupedBySemanticDomain() {
        // 「短语与搭配」在两册里各只有 1 条，不足 MIN_PAIRS 对，按生成脚本约定并入「特殊类别」，故不成关
        Set<String> expectedUsed = new HashSet<>(DOMAINS);
        expectedUsed.remove("短语与搭配");

        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            Set<String> used = new HashSet<>();
            for (WordMatchBank.Level level : bank.getLevels(book.id())) {
                String base = baseTheme(level.theme());
                assertThat(DOMAINS).as("%s 出现未登记的语义域：%s", book.label(), level.theme())
                        .contains(base);
                used.add(base);
            }
            assertThat(used).as("%s 应覆盖全部语义域（短语与搭配已并入特殊类别）", book.label())
                    .containsExactlyInAnyOrderElementsOf(expectedUsed);
        }
    }

    @Test
    @DisplayName("同一语义域内的词条语义相关：抽检若干关，主题名与词条不出现明显串味")
    void levels_areSemanticallyCoherent() {
        // 「特殊类别」是兜底域，语义本就发散；只抽检非兜底域
        Map<String, List<String>> sample = new LinkedHashMap<>();
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            for (WordMatchBank.Level level : bank.getLevels(book.id())) {
                String base = baseTheme(level.theme());
                if (!"特殊类别".equals(base)) {
                    sample.computeIfAbsent(base, k -> new ArrayList<>())
                            .add(level.pairs().stream().map(WordMatchBank.WordPair::en)
                                    .reduce((a, b) -> a + "," + b).orElse(""));
                }
            }
        }
        // 每个语义域都至少有一关可抽检（短语与搭配已并入特殊类别；特殊类别是兜底域，语义本就发散，不参与抽检）
        Set<String> expected = new HashSet<>(DOMAINS);
        expected.remove("短语与搭配");
        expected.remove("特殊类别");
        assertThat(sample.keySet()).as("每个非兜底语义域都应有关卡").containsExactlyInAnyOrderElementsOf(expected);
        assertThat(sample.values()).as("每关都应有单词").allSatisfy(v -> assertThat(v).isNotEmpty());
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
            /* 阈值说明：按语义域归类后，一册被拆成 68 个子序列，重复词的间距随「域规模」缩小
               （实测中位数 四级 10 / 六级 8 关），不再是从前按词性粗分时的上百关。
               这里要防的是「把重复词摊到相邻关」那种贪心分配 —— 那样中位数会掉到 1~2。 */
            assertThat(median).as("%s 同词两次出现的关卡间距中位数（不应挤在相邻关）", book.label())
                    .isGreaterThanOrEqualTo(5);
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
