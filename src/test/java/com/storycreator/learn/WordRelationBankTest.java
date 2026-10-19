package com.storycreator.learn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * 近义词 / 反义词词库单测。
 *
 * <p>配对游戏的正确性几乎全压在数据上（页面逻辑很薄），而数据最容易在「补几个词」时悄悄退化，
 * 所以把几条硬性质钉成断言：
 * <ul>
 *   <li>三学段（小学 / 初中 / 高中）齐全、顺序固定 —— 前端页签直接按数组顺序渲染；</li>
 *   <li>每关 {@value WordRelationBank#MIN_PAIRS}~{@value WordRelationBank#MAX_PAIRS} 对，词与答案不相等、辨析非空；</li>
 *   <li>★ <b>同一关不会出现 3 个及以上同义词</b>：任一半语组在任一关里最多贡献 2 个词
 *       （也就是最多一对）。这条是需求里点名的红线，词库改坏必须让构建变红；</li>
 *   <li>★ <b>每关都是唯一解</b>：对任意题面，右列恰好只有一个候选与它同组 ——
 *       否则玩家选哪个都说得通，「配对」就变成了猜出题人心思；</li>
 *   <li>同一个词在整个词库里只出现一次（既当题面又当答案会造成跨关歧义）。</li>
 * </ul>
 */
@DisplayName("近义词 / 反义词 · 词库与关卡")
class WordRelationBankTest {

    private final WordRelationBank bank = new WordRelationBank();

    private static final List<String> STAGE_IDS = List.of("primary", "junior", "senior");
    private static final List<String> STAGE_LABELS = List.of("小学", "初中", "高中");

    @Test
    @DisplayName("两个模式都能加载，三学段顺序固定")
    void loads_threeStagesInOrder() {
        assertThat(WordRelationBank.modes())
                .as("模式顺序固定：近义词在前，前端卡片和默认值都按它来")
                .containsExactly(WordRelationBank.MODE_SYNONYM, WordRelationBank.MODE_ANTONYM);
        for (String mode : WordRelationBank.modes()) {
            List<WordRelationBank.StageInfo> stages = bank.getStages(mode);
            assertThat(stages).as("%s 应有三个学段", mode).hasSize(3);
            assertThat(stages.stream().map(WordRelationBank.StageInfo::id).toList())
                    .as("%s 学段 id 顺序", mode).containsExactlyElementsOf(STAGE_IDS);
            assertThat(stages.stream().map(WordRelationBank.StageInfo::label).toList())
                    .as("%s 学段名称", mode).containsExactlyElementsOf(STAGE_LABELS);
            for (WordRelationBank.StageInfo s : stages) {
                assertThat(s.gradeLabel()).as("%s 应有年级说明", s.id()).isNotBlank();
                assertThat(s.source()).as("%s 应标注取材来源", s.id()).contains("人教");
                assertThat(s.levelCount()).as("%s 每主题 18 对切三关", s.id())
                        .isEqualTo(s.themeCount() * 3);
                assertThat(s.pairCount()).as("%s 每关 6 对 × %d 关", s.id(), s.levelCount())
                        .isEqualTo(s.levelCount() * 6);
            }
        }
    }

    @Test
    @DisplayName("每关 4~8 对、词与答案不相等、辨析非空且词长可控")
    void everyLevel_isWellFormed() {
        for (String mode : WordRelationBank.modes()) {
            for (WordRelationBank.StageInfo stage : bank.getStages(mode)) {
                List<WordRelationBank.Level> levels = bank.getLevels(mode, stage.id());
                assertThat(levels).as("%s/%s 应有 %d 关", mode, stage.id(), stage.levelCount())
                        .hasSize(stage.levelCount());
                for (int i = 0; i < levels.size(); i++) {
                    WordRelationBank.Level lv = levels.get(i);
                    assertThat(lv.index()).as("关卡序号应连续").isEqualTo(i);
                    assertThat(lv.theme()).as("%s/%s 第 %d 关应有主题名", mode, stage.id(), i + 1).isNotBlank();
                    assertThat(lv.items()).as("%s/%s 第 %d 关的对数", mode, stage.id(), i + 1)
                            .hasSizeBetween(WordRelationBank.MIN_PAIRS, WordRelationBank.MAX_PAIRS);
                    for (WordRelationBank.PairItem it : lv.items()) {
                        assertThat(it.word()).as("题面不得为空").isNotBlank();
                        assertThat(it.match()).as("答案不得为空").isNotBlank();
                        assertThat(it.word()).as("「%s」的答案不能是它自己", it.word()).isNotEqualTo(it.match());
                        assertThat(it.note()).as("「%s」缺少辨析", it.word())
                                .isNotBlank().hasSizeLessThanOrEqualTo(60);
                        assertThat(it.word().length()).as("「%s」词长", it.word())
                                .isBetween(1, WordRelationBank.MAX_WORD_LENGTH);
                        assertThat(it.match().length()).as("「%s」答案词长", it.match())
                                .isBetween(1, WordRelationBank.MAX_WORD_LENGTH);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("★ 同一关最多 2 个同义词：任一语义组在一关里最多贡献 1 对")
    void noLevel_containsThreeSynonyms() {
        for (String mode : WordRelationBank.modes()) {
            Map<String, List<String>> groups = bank.getGroupMembers(mode);
            for (WordRelationBank.StageInfo stage : bank.getStages(mode)) {
                for (WordRelationBank.Level lv : bank.getLevels(mode, stage.id())) {
                    Map<String, Integer> perGroup = new LinkedHashMap<>();
                    for (WordRelationBank.PairItem it : lv.items()) {
                        perGroup.merge(it.group(), 1, Integer::sum);
                    }
                    perGroup.forEach((group, pairs) -> assertThat(pairs)
                            .as("%s/%s 第 %d 关（%s）里语义组 %s 出现了 %d 对 ⇒ 该关会有 3 个以上同义词互相干扰",
                                    mode, stage.label(), lv.index() + 1, lv.theme(), group, pairs)
                            .isEqualTo(1));

                    // 换个说法再验一遍（对应需求原文）：同组词在这一关里的总数 ≤ 2
                    Set<String> levelWords = new LinkedHashSet<>();
                    lv.items().forEach(it -> {
                        levelWords.add(it.word());
                        levelWords.add(it.match());
                    });
                    for (Map.Entry<String, List<String>> e : groups.entrySet()) {
                        long n = e.getValue().stream().filter(levelWords::contains).count();
                        assertThat(n)
                                .as("%s 第 %d 关里语义组 %s 有 %d 个词（应 ≤ 2），实际：%s",
                                        mode, lv.index() + 1, e.getKey(), n,
                                        e.getValue().stream().filter(levelWords::contains).toList())
                                .isLessThanOrEqualTo(2);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("★ 每关唯一解：对任一题面，右列恰好只有一个候选与它同组")
    void everyLevel_hasExactlyOneCandidatePerWord() {
        for (String mode : WordRelationBank.modes()) {
            for (WordRelationBank.StageInfo stage : bank.getStages(mode)) {
                for (WordRelationBank.Level lv : bank.getLevels(mode, stage.id())) {
                    for (WordRelationBank.PairItem item : lv.items()) {
                        List<String> candidates = new ArrayList<>();
                        for (WordRelationBank.PairItem other : lv.items()) {
                            if (other.group().equals(item.group())) {
                                candidates.add(other.match());
                            }
                        }
                        assertThat(candidates)
                                .as("%s %s 里「%s」的候选答案应恰好 1 个，实际 %s",
                                        mode, lv.theme(), item.word(), candidates)
                                .containsExactly(item.match());
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("同一关不重词：左右两列共 12 个词两两不同")
    void everyLevel_hasNoDuplicateWords() {
        for (String mode : WordRelationBank.modes()) {
            for (WordRelationBank.StageInfo stage : bank.getStages(mode)) {
                for (WordRelationBank.Level lv : bank.getLevels(mode, stage.id())) {
                    Set<String> words = new HashSet<>();
                    int total = 0;
                    for (WordRelationBank.PairItem it : lv.items()) {
                        words.add(it.word());
                        words.add(it.match());
                        total += 2;
                    }
                    assertThat(words).as("%s/%s/%s 一关内不得出现重复词",
                            mode, stage.id(), lv.theme()).hasSize(total);
                }
            }
        }
    }

    @Test
    @DisplayName("全库同一个词只出现一次（既当题面又当答案会造成跨关歧义）")
    void wordsAreGloballyUnique() {
        for (String mode : WordRelationBank.modes()) {
            Map<String, String> seen = new HashMap<>();
            for (WordRelationBank.StageInfo stage : bank.getStages(mode)) {
                for (WordRelationBank.Level lv : bank.getLevels(mode, stage.id())) {
                    for (WordRelationBank.PairItem it : lv.items()) {
                        assertThat(seen.put(it.word(), stage.id() + "/" + lv.theme()))
                                .as("%s 里「%s」重复出现（先出现在 %s）",
                                        mode, it.word(), seen.get(it.word()))
                                .isNull();
                        assertThat(seen.put(it.match(), stage.id() + "/" + lv.theme()))
                                .as("%s 里「%s」既作答案又作题面（先出现在 %s）",
                                        mode, it.match(), seen.get(it.match()))
                                .isNull();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("规模：每模式 3 学段 × 30 关 × 6 对 = 540 对")
    void scale_isStable() {
        for (String mode : WordRelationBank.modes()) {
            int levels = 0;
            int pairs = 0;
            for (WordRelationBank.StageInfo stage : bank.getStages(mode)) {
                levels += bank.getLevels(mode, stage.id()).size();
                pairs += stage.pairCount();
            }
            assertThat(levels).as("%s 总关数", mode).isEqualTo(90);
            assertThat(pairs).as("%s 总对数", mode).isEqualTo(540);
            assertThat(bank.getGroupCount(mode)).as("%s 语义组数应与对数同量级", mode)
                    .isBetween(50, 640);
        }
    }

    @Test
    @DisplayName("语义组本身：成员 ≥ 2、无重复成员")
    void groups_areSane() {
        for (String mode : WordRelationBank.modes()) {
            bank.getGroupMembers(mode).forEach((group, members) -> {
                assertThat(members).as("%s 语义组 %s 成员数", mode, group).hasSizeGreaterThanOrEqualTo(2);
                assertThat(new HashSet<>(members)).as("%s 语义组 %s 有重复成员", mode, group)
                        .hasSameSizeAs(members);
            });
        }
    }

    @Test
    @DisplayName("bootstrapData：契约齐全，且不下发 group（否则前端能直接作弊）")
    void bootstrapData_hasExpectedShape() {
        for (String mode : WordRelationBank.modes()) {
            Map<String, Object> data = bank.bootstrapData(mode);
            assertThat(data).containsKeys("mode", "label", "stages", "levels", "groupCount");
            assertThat(data.get("mode")).isEqualTo(mode);
            assertThat(data.get("label")).isEqualTo(WordRelationBank.labelOfMode(mode));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stages = (List<Map<String, Object>>) data.get("stages");
            assertThat(stages).hasSize(3);
            assertThat(stages.get(0)).containsKeys("id", "label", "gradeLabel", "source",
                    "themeCount", "levelCount", "pairCount");

            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> levels =
                    (Map<String, List<Map<String, Object>>>) data.get("levels");
            assertThat(levels).containsOnlyKeys(STAGE_IDS);
            for (String stageId : STAGE_IDS) {
                List<Map<String, Object>> list = levels.get(stageId);
                assertThat(list).isNotEmpty();
                assertThat(list.get(0)).containsKeys("index", "theme", "items");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) list.get(0).get("items");
                assertThat(items).isNotEmpty();
                assertThat(items.get(0))
                        .as("前端只需要 word / match / note")
                        .containsOnlyKeys("word", "match", "note");
            }
        }
    }

    @Test
    @DisplayName("splitBalanced：每关 4~8 对、不丢不重、各关最多差 1")
    void splitBalanced_keepsEveryLevelWithinRange() {
        for (int n = 4; n <= 120; n++) {
            List<Integer> parts = WordRelationBank.splitBalanced(n);
            assertThat(parts).as("%d 对应至少切出 1 关", n).isNotEmpty();
            assertThat(parts.stream().mapToInt(Integer::intValue).sum())
                    .as("%d 对切分不丢不重", n).isEqualTo(n);
            for (int size : parts) {
                assertThat(size).as("%d 对切出的每关应在 %d~%d 对之间，实际 %s",
                                n, WordRelationBank.MIN_PAIRS, WordRelationBank.MAX_PAIRS, parts)
                        .isBetween(WordRelationBank.MIN_PAIRS, WordRelationBank.MAX_PAIRS);
            }
            int max = parts.stream().mapToInt(Integer::intValue).max().orElseThrow();
            int min = parts.stream().mapToInt(Integer::intValue).min().orElseThrow();
            assertThat(max - min).as("%d 对应尽量均分，实际 %s", n, parts).isLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("normalizeMode：只认 synonym / antonym，大小写与空格容错，其余返回 null")
    void normalizeMode_rejectsUnknown() {
        assertThat(WordRelationBank.normalizeMode("synonym")).isEqualTo("synonym");
        assertThat(WordRelationBank.normalizeMode(" ANTONYM ")).isEqualTo("antonym");
        assertThat(WordRelationBank.normalizeMode(null)).isNull();
        assertThat(WordRelationBank.normalizeMode("")).isNull();
        assertThat(WordRelationBank.normalizeMode("synonyms")).isNull();
    }
}
