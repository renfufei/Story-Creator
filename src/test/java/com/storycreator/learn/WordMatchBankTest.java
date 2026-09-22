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
 * 单词匹配词库 / 关卡切分单测。
 *
 * <p>这个游戏的正确性依赖几条硬性质，词库改动很容易悄悄破坏它们，故全部落成断言：
 * <ul>
 *   <li>每关 3~7 对、<b>围绕 6 对</b>（太少棋盘下方留大片空白，太多手机一屏放不下）；</li>
 *   <li>同一关的词来自同一个主题（「同类型或有关联的放同一关」）；</li>
 *   <li>同一关内英文不重复；</li>
 *   <li>中文释义重复<b>只允许</b>出现在「同义表达：」主题里 —— 那里的中文本来就一模一样，
 *       前端按释义判定，选哪个都算对，不会出现「两个都算对」的矛盾；</li>
 *   <li>意思相同的词必须在<b>同一关</b>（否则「选哪个都对」这条体验就不成立）；</li>
 *   <li><b>零丢失</b>：词库必须完整收录人教版分册词汇表的全部词条（源清单在测试资源里）；</li>
 *   <li>配对 key 全册唯一。</li>
 * </ul>
 */
@DisplayName("英语单词匹配 · 词库与关卡")
class WordMatchBankTest {

    private static final String SYN_PREFIX = "同义表达：";
    /** 源清单目录（人教分册词汇表 → 24 个 txt，`英文\t中文`）。 */
    private static final String SOURCE_DIR = "learn/pep-words-source/";

    private final WordMatchBank bank = new WordMatchBank();

    @Test
    @DisplayName("splitBalanced：每关 3~7 对、均分不丢不重、各关大小相差不超过 1")
    void splitBalanced_keepsEveryLevelWithinRange() {
        for (int n = 3; n <= 120; n++) {
            List<Integer> parts = WordMatchBank.splitBalanced(n);
            assertThat(parts).as("%d 个词应至少切出 1 关", n).isNotEmpty();
            assertThat(parts.stream().mapToInt(Integer::intValue).sum())
                    .as("%d 个词的切分必须不丢不重", n).isEqualTo(n);
            for (int size : parts) {
                assertThat(size)
                        .as("%d 个词切出的每关应在 %d~%d 对之间，实际 %s",
                                n, WordMatchBank.MIN_PAIRS, WordMatchBank.MAX_PAIRS, parts)
                        .isBetween(WordMatchBank.MIN_PAIRS, WordMatchBank.MAX_PAIRS);
            }
            int max = parts.stream().mapToInt(Integer::intValue).max().orElseThrow();
            int min = parts.stream().mapToInt(Integer::intValue).min().orElseThrow();
            assertThat(max - min)
                    .as("%d 个词应尽量均分（各关最多差 1 对），实际 %s", n, parts)
                    .isLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("每关词数围绕 6 对展开（3~7 硬边界，均值 ≥5.4，恰好 6 对的占多数）")
    void levels_centerAroundSixPairs() {
        int levels = 0;
        int pairs = 0;
        int exactlyTarget = 0;
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            for (WordMatchBank.Level level : bank.getLevels(book.id())) {
                int n = level.pairs().size();
                levels++;
                pairs += n;
                if (n == WordMatchBank.TARGET_PAIRS) {
                    exactlyTarget++;
                }
            }
        }
        assertThat(levels).as("关卡总数").isPositive();
        assertThat(pairs / (double) levels)
                .as("平均每关配对数（目标 %d）", WordMatchBank.TARGET_PAIRS)
                .isBetween(5.4, 6.2);
        assertThat(exactlyTarget * 100.0 / levels)
                .as("恰好 %d 对的关卡占比（碎片关不应成为主流）", WordMatchBank.TARGET_PAIRS)
                .isGreaterThanOrEqualTo(50.0);
    }

    @Test
    @DisplayName("词库加载：24 册（小学 8 + 初中 5 + 高中 11）、每册都有若干关")
    void bank_containsAllBooks() {
        assertThat(bank.getBooks())
                .as("人教版小学 3~6 年级 + 初中 7~9 年级 + 高中必修1-5/选修6-11 共 24 册")
                .hasSize(24);
        assertThat(bank.getBooks()).extracting(WordMatchBank.BookInfo::label)
                .containsExactly("三年级上册", "三年级下册", "四年级上册", "四年级下册",
                        "五年级上册", "五年级下册", "六年级上册", "六年级下册",
                        "七年级上册", "七年级下册", "八年级上册", "八年级下册", "九年级全一册",
                        "必修1", "必修2", "必修3", "必修4", "必修5",
                        "选修6", "选修7", "选修8", "选修9", "选修10", "选修11");
        // 学段：册次选择器按它分组，顺序必须是「小学 → 初中 → 高中」
        assertThat(bank.getBooks()).extracting(WordMatchBank.BookInfo::stage)
                .containsExactly("小学", "小学", "小学", "小学", "小学", "小学", "小学", "小学",
                        "初中", "初中", "初中", "初中", "初中",
                        "高中", "高中", "高中", "高中", "高中",
                        "高中", "高中", "高中", "高中", "高中", "高中");
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            assertThat(book.levelCount()).as("%s 应有至少 8 关", book.label()).isGreaterThanOrEqualTo(8);
            assertThat(book.wordCount()).as("%s 应有足够单词", book.label()).isGreaterThanOrEqualTo(60);
        }
    }

    @Test
    @DisplayName("每个关卡：3~7 对、单词配对完整")
    void everyLevel_hasThreeToSevenPairs() {
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            List<WordMatchBank.Level> levels = bank.getLevels(book.id());
            assertThat(levels).as("%s 关卡数应与元信息一致", book.label())
                    .hasSize(book.levelCount());
            for (WordMatchBank.Level level : levels) {
                assertThat(level.pairs())
                        .as("%s 第 %d 关（%s）配对数", book.label(), level.index() + 1, level.theme())
                        .hasSizeBetween(WordMatchBank.MIN_PAIRS, WordMatchBank.MAX_PAIRS);
                for (WordMatchBank.WordPair pair : level.pairs()) {
                    assertThat(pair.en()).as("英文非空").isNotBlank();
                    assertThat(pair.zh()).as("中文非空").isNotBlank();
                }
            }
        }
    }

    @Test
    @DisplayName("同一关的词来自同一个主题（主题不跨关混排）")
    void everyLevel_belongsToExactlyOneTheme() {
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            List<WordMatchBank.Level> levels = bank.getLevels(book.id());
            List<String> runs = new ArrayList<>();
            for (WordMatchBank.Level level : levels) {
                if (runs.isEmpty() || !runs.get(runs.size() - 1).equals(level.theme())) {
                    runs.add(level.theme());
                }
            }
            Set<String> themes = new LinkedHashSet<>();
            levels.forEach(l -> themes.add(l.theme()));
            assertThat(runs)
                    .as("%s 的关卡顺序里同一主题必须连续（否则同一主题被拆散）", book.label())
                    .hasSameSizeAs(themes);
        }
    }

    @Test
    @DisplayName("同一关英文不重复；中文重复只允许出现在「同义表达：」主题")
    void everyLevel_hasDistinctEnglishAndMeaningOnlyInSynonymTheme() {
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            for (WordMatchBank.Level level : bank.getLevels(book.id())) {
                Set<String> en = new HashSet<>();
                Set<String> zh = new HashSet<>();
                for (WordMatchBank.WordPair pair : level.pairs()) {
                    assertThat(en.add(pair.en().toLowerCase()))
                            .as("%s 第 %d 关英文重复：%s", book.label(), level.index() + 1, pair.en())
                            .isTrue();
                    if (!zh.add(pair.zh())) {
                        assertThat(level.theme())
                                .as("%s 第 %d 关出现重复中文「%s」，但主题不是「%s」",
                                        book.label(), level.index() + 1, pair.zh(), SYN_PREFIX)
                                .startsWith(SYN_PREFIX);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("意思相同的词必须在同一关（「选哪个都算对」的前提）")
    void sameMeaningPairs_stayInOneLevel() {
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            Map<String, Integer> zhLevel = new HashMap<>();
            Map<String, List<String>> zhWords = new LinkedHashMap<>();
            for (WordMatchBank.Level level : bank.getLevels(book.id())) {
                for (WordMatchBank.WordPair pair : level.pairs()) {
                    Integer prev = zhLevel.put(pair.zh(), level.index());
                    zhWords.computeIfAbsent(pair.zh(), k -> new ArrayList<>()).add(pair.en());
                    if (prev != null && prev != level.index()) {
                        assertThat(level.index())
                                .as("%s 中文「%s」被拆到第 %d 关和第 %d 关（%s）",
                                        book.label(), pair.zh(), prev + 1, level.index() + 1, zhWords.get(pair.zh()))
                                .isEqualTo(prev);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("零丢失：词库完整收录源清单（人教分册词汇表）的全部词条")
    void bank_coversSourceVocabularyExactly() throws Exception {
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            ClassPathResource res = new ClassPathResource(SOURCE_DIR + book.id() + ".txt");
            assertThat(res.exists()).as("源清单 %s 必须存在", SOURCE_DIR + book.id() + ".txt").isTrue();

            Map<String, Integer> expected = new LinkedHashMap<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] parts = line.split("\t");
                    if (parts.length >= 2) {
                        expected.merge(parts[0], 1, Integer::sum);
                    }
                }
            }
            Map<String, Integer> actual = new LinkedHashMap<>();
            for (WordMatchBank.Level level : bank.getLevels(book.id())) {
                for (WordMatchBank.WordPair pair : level.pairs()) {
                    actual.merge(pair.en(), 1, Integer::sum);
                }
            }
            assertThat(actual.keySet())
                    .as("%s 词库与源清单必须完全一致（不多不少）", book.label())
                    .containsExactlyInAnyOrderElementsOf(expected.keySet());
            assertThat(actual).as("%s 没有重复词条", book.label()).hasSameSizeAs(expected);
        }
    }

    @Test
    @DisplayName("特殊/不规则词汇单独成主题（不规则变化 / 专有名词 / 问候语 / 功能词 / 句型框架）")
    void specialThemes_existForIrregularAndSpecialWords() {
        // 六年级下册整本在讲过去式：不规则动词必须单独成关，否则会散落在各主题里
        List<String> g6b = themeWords("pep-6-2", "不规则变化");
        assertThat(g6b).as("六年级下册的不规则动词明显成组")
                .contains("went", "ate", "took", "bought", "thought", "drank")
                .hasSizeGreaterThanOrEqualTo(12);

        // 九年级的专有名词（人名/地名）曾是「无主题可去」的散词，现在单独成关
        assertThat(themeWords("pep-9-1", "专有名词"))
                .as("九年级专有名词关卡").hasSizeGreaterThanOrEqualTo(20);

        // 七年级上册的问候语与句型框架（含旧版被丢掉的 Good morning / See you 等）
        assertThat(themeWords("pep-7-1", "问候与日常用语"))
                .contains("Good morning", "See you", "Happy birthday");
        assertThat(themeWords("pep-7-1", "句型框架与常用搭配")).isNotEmpty();

        // 高中：课文的人物/地名很多，必须集中成专有名词关，而不是散落在单元主题里
        assertThat(themeWords("pep-h-1", "专有名词"))
                .as("必修1 的专有名词关卡").hasSizeGreaterThanOrEqualTo(15);
        assertThat(themeWords("pep-h-11", "专有名词"))
                .as("选修11 的专有名词关卡").isNotEmpty();

        // 同义表达主题：全库覆盖所有「同义组」（每组自成一关，中文一模一样）
        int synThemes = 0;
        int synWords = 0;
        for (WordMatchBank.BookInfo book : bank.getBooks()) {
            for (WordMatchBank.Level level : bank.getLevels(book.id())) {
                if (level.theme().startsWith(SYN_PREFIX)) {
                    synThemes++;
                    synWords += level.pairs().size();
                }
            }
        }
        assertThat(synThemes).as("同义表达关卡数").isGreaterThanOrEqualTo(40);
        assertThat(synWords).as("同义表达关卡覆盖的词条数").isGreaterThanOrEqualTo(120);
    }

    @Test
    @DisplayName("词库规模：覆盖人教版 3~12 年级词汇表（小学 / 初中 / 高中三段）")
    void bank_coversTextbookVocabulary() {
        int words = bank.getBooks().stream().mapToInt(WordMatchBank.BookInfo::wordCount).sum();
        int levels = bank.getBooks().stream().mapToInt(WordMatchBank.BookInfo::levelCount).sum();
        assertThat(words).as("人教版 3~12 年级词汇总量（教材分册词汇表口径，零丢失）")
                .isGreaterThanOrEqualTo(7000);
        // 每关 6 对左右 ⇒ 关数明显少于「每关 4.5 对」时代；下限定在 1200 防回退
        assertThat(levels).as("按主题切出的关卡总量").isGreaterThanOrEqualTo(1200);
        // 初中是原先缺口最大的学段（曾只覆盖教材三成），单独设下限防回退
        assertThat(bank.getBooks()).filteredOn(b -> "初中".equals(b.stage()))
                .allSatisfy(b -> assertThat(b.wordCount())
                        .as("%s 的词量", b.label()).isGreaterThanOrEqualTo(380));
        assertThat(bank.getBooks()).filteredOn(b -> "小学".equals(b.stage()))
                .allSatisfy(b -> assertThat(b.wordCount())
                        .as("%s 的词量", b.label()).isGreaterThanOrEqualTo(60));
        // 高中：必修1-5 / 选修6-11 共 11 册，每册 ≥300 词
        assertThat(bank.getBooks()).filteredOn(b -> "高中".equals(b.stage()))
                .as("高中 11 册（必修1-5 + 选修6-11）").hasSize(11)
                .allSatisfy(b -> {
                    assertThat(b.wordCount()).as("%s 的词量", b.label()).isGreaterThanOrEqualTo(300);
                    assertThat(b.levelCount()).as("%s 的关数", b.label()).isGreaterThanOrEqualTo(45);
                });
    }

    @Test
    @DisplayName("配对 key 全册唯一")
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
            assertThat(total).as("%s 关卡内单词总数应等于元信息词数", book.label())
                    .isEqualTo(book.wordCount());
        }
    }

    @Test
    @DisplayName("引导数据：册元信息 + 每册关卡结构完整")
    void bootstrapData_shapesMatchFrontendContract() {
        var data = bank.bootstrapData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> books = (List<Map<String, Object>>) data.get("books");
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> levels =
                (Map<String, List<Map<String, Object>>>) data.get("levels");

        assertThat(books).hasSize(24);
        assertThat(levels.keySet()).containsExactlyElementsOf(
                books.stream().map(b -> String.valueOf(b.get("id"))).toList());
        for (Map<String, Object> book : books) {
            String id = String.valueOf(book.get("id"));
            assertThat(String.valueOf(book.get("stage")))
                    .as("%s 的学段（前端分组依据）", id).isIn("小学", "初中", "高中");
            List<Map<String, Object>> bookLevels = levels.get(id);
            assertThat((Integer) book.get("levelCount")).as("%s levelCount", id).isEqualTo(bookLevels.size());
            for (Map<String, Object> level : bookLevels) {
                assertThat(level).containsKeys("index", "theme", "pairs");
                assertThat((List<?>) level.get("pairs"))
                        .hasSizeBetween(WordMatchBank.MIN_PAIRS, WordMatchBank.MAX_PAIRS);
            }
        }
    }

    /** 某册里主题名以 prefix 开头的所有主题的英文词（扁平）。 */
    private List<String> themeWords(String bookId, String prefix) {
        List<String> out = new ArrayList<>();
        for (WordMatchBank.Level level : bank.getLevels(bookId)) {
            if (level.theme().startsWith(prefix)) {
                level.pairs().forEach(p -> out.add(p.en()));
            }
        }
        return out;
    }
}
