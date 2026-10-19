package com.storycreator.learn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成语填空词库 / 关卡切分单测。
 *
 * <p>这个玩法的正确性依赖几条硬性质，改词库时很容易悄悄破坏，所以逐条落成断言：
 * <ul>
 *   <li>三学段（小学 / 初中 / 高中）齐全、顺序固定 —— 前端顶部页签直接按数组顺序渲染；</li>
 *   <li>每条必须<b>四字</b>（前端是「四格等宽」排版，三字/五字会破版）；</li>
 *   <li>每关 {@value IdiomBank#MIN_IDIOMS}~{@value IdiomBank#MAX_IDIOMS} 条、同一关同一主题；</li>
 *   <li>★ <b>同一关能拆出两两不同的字</b> —— 否则右列出现两个一样的字块，玩家没法判断；
 *       这条用二分图完美匹配（Kuhn）验证，而不是贪心，因为前端用的就是增广路匹配；</li>
 *   <li>全库成语不重复（同一个成语在两个学段各出现一次，会让人怀疑自己背错了）；</li>
 *   <li>释义非空且长度可控（提示条只给一行到两行）。</li>
 * </ul>
 */
@DisplayName("成语填空 · 词库与关卡")
class IdiomBankTest {

    private final IdiomBank bank = new IdiomBank();

    @Test
    @DisplayName("splitBalanced：每关 4~8 条、均分不丢不重、各关大小相差不超过 1")
    void splitBalanced_keepsEveryLevelWithinRange() {
        for (int n = 4; n <= 120; n++) {
            List<Integer> parts = IdiomBank.splitBalanced(n);
            assertThat(parts).as("%d 条至少切出 1 关", n).isNotEmpty();
            assertThat(parts.stream().mapToInt(Integer::intValue).sum())
                    .as("%d 条切分必须不丢不重", n).isEqualTo(n);
            for (int size : parts) {
                assertThat(size).as("%d 条切出的每关应在 %d~%d 条之间，实际 %s",
                                n, IdiomBank.MIN_IDIOMS, IdiomBank.MAX_IDIOMS, parts)
                        .isBetween(IdiomBank.MIN_IDIOMS, IdiomBank.MAX_IDIOMS);
            }
            int max = parts.stream().mapToInt(Integer::intValue).max().orElseThrow();
            int min = parts.stream().mapToInt(Integer::intValue).min().orElseThrow();
            assertThat(max - min).as("%d 条应尽量均分（各关最多差 1 条），实际 %s", n, parts)
                    .isLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("三学段齐全且顺序固定：小学 → 初中 → 高中")
    void bank_hasThreeStagesInOrder() {
        assertThat(bank.getStages()).extracting(IdiomBank.StageInfo::label)
                .containsExactly("小学", "初中", "高中");
        assertThat(bank.getStages()).extracting(IdiomBank.StageInfo::id)
                .containsExactly("primary", "junior", "senior");
        for (IdiomBank.StageInfo s : bank.getStages()) {
            assertThat(s.gradeLabel()).as("%s 的年级说明（页签副标题）", s.label()).isNotBlank();
            assertThat(s.source()).as("%s 的取材说明", s.label()).isNotBlank();
            assertThat(s.themeCount()).as("%s 的主题数", s.label()).isGreaterThanOrEqualTo(8);
            assertThat(s.levelCount()).as("%s 的关卡数", s.label()).isGreaterThanOrEqualTo(8);
            assertThat(s.idiomCount()).as("%s 的成语数", s.label()).isGreaterThanOrEqualTo(48);
        }
    }

    @Test
    @DisplayName("每个关卡：4~8 条、全部四字、释义非空、成语不重复")
    void everyLevel_isWellFormed() {
        for (IdiomBank.StageInfo s : bank.getStages()) {
            List<IdiomBank.Level> levels = bank.getLevels(s.id());
            assertThat(levels).as("%s 关卡数应与元信息一致", s.label()).hasSize(s.levelCount());
            for (IdiomBank.Level level : levels) {
                assertThat(level.items()).as("%s 第 %d 关（%s）条数",
                                s.label(), level.index() + 1, level.theme())
                        .hasSizeBetween(IdiomBank.MIN_IDIOMS, IdiomBank.MAX_IDIOMS);
                Set<String> seen = new HashSet<>();
                for (IdiomBank.Idiom it : level.items()) {
                    assertThat(it.idiom()).as("%s 第 %d 关的成语长度（前端四格排版）",
                                    s.label(), level.index() + 1)
                            .hasSize(IdiomBank.CHAR_COUNT);
                    assertThat(it.meaning()).as("「%s」的释义", it.idiom()).isNotBlank();
                    assertThat(it.meaning().length()).as("「%s」的释义长度（提示条 1~2 行）", it.idiom())
                            .isBetween(4, 60);
                    assertThat(seen.add(it.idiom())).as("%s 第 %d 关内成语重复：%s",
                            s.label(), level.index() + 1, it.idiom()).isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("★ 同一关可以拆出两两不同的字（二分图完美匹配存在）")
    void everyLevel_admitsDistinctBlankCharacters() {
        for (IdiomBank.StageInfo s : bank.getStages()) {
            for (IdiomBank.Level level : bank.getLevels(s.id())) {
                List<String> idioms = level.items().stream().map(IdiomBank.Idiom::idiom).toList();
                assertThat(canPickDistinct(idioms))
                        .as("%s 第 %d 关（%s）无法给每条成语挑到互不相同的拆字位置：%s —— "
                                        + "右列会出现重复字块，玩家无从判断该填哪一格",
                                s.label(), level.index() + 1, level.theme(), idioms)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("全库成语不重复（跨学段也不允许）")
    void bank_hasNoDuplicateIdiom() {
        List<String> all = new ArrayList<>();
        for (IdiomBank.StageInfo s : bank.getStages()) {
            for (IdiomBank.Level level : bank.getLevels(s.id())) {
                level.items().forEach(it -> all.add(it.idiom()));
            }
        }
        Set<String> unique = new LinkedHashSet<>(all);
        assertThat(unique).as("词库里的成语应两两不同").hasSameSizeAs(all);
        // 规模防回退：三学段各 10 主题 × 6 条 = 180
        assertThat(all.size()).as("成语总量").isGreaterThanOrEqualTo(150);
    }

    @Test
    @DisplayName("每个主题的关卡连续（同一主题不被别的主题从中间插开）")
    void everyStage_keepsThemeRunsContiguous() {
        for (IdiomBank.StageInfo s : bank.getStages()) {
            List<String> runs = new ArrayList<>();
            for (IdiomBank.Level level : bank.getLevels(s.id())) {
                if (runs.isEmpty() || !runs.get(runs.size() - 1).equals(level.theme())) {
                    runs.add(level.theme());
                }
            }
            Set<String> themes = new LinkedHashSet<>();
            bank.getLevels(s.id()).forEach(l -> themes.add(l.theme()));
            assertThat(runs).as("%s 的关卡顺序里同一主题必须连续", s.label())
                    .hasSameSizeAs(themes);
        }
    }

    @Test
    @DisplayName("引导数据：学段元信息 + 每段关卡结构完整（前端契约）")
    void bootstrapData_shapesMatchFrontendContract() {
        Map<String, Object> data = bank.bootstrapData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages = (List<Map<String, Object>>) data.get("stages");
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> levels =
                (Map<String, List<Map<String, Object>>>) data.get("levels");

        assertThat(stages).hasSize(3);
        assertThat(levels.keySet()).containsExactlyElementsOf(
                stages.stream().map(x -> String.valueOf(x.get("id"))).toList());
        for (Map<String, Object> stage : stages) {
            String id = String.valueOf(stage.get("id"));
            assertThat(stage).containsKeys("id", "label", "gradeLabel", "source",
                    "themeCount", "levelCount", "idiomCount");
            List<Map<String, Object>> stageLevels = levels.get(id);
            assertThat((Integer) stage.get("levelCount")).as("%s levelCount", id)
                    .isEqualTo(stageLevels.size());
            int items = 0;
            for (Map<String, Object> level : stageLevels) {
                assertThat(level).containsKeys("index", "theme", "items");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) level.get("items");
                assertThat(list).hasSizeBetween(IdiomBank.MIN_IDIOMS, IdiomBank.MAX_IDIOMS);
                for (Map<String, Object> it : list) {
                    // 出处可能为空串，但键必须存在 —— 前端用 x-show 判断，缺键会渲染出 undefined
                    assertThat(it).containsKeys("idiom", "meaning", "source");
                    items++;
                }
            }
            assertThat((Integer) stage.get("idiomCount")).as("%s idiomCount", id).isEqualTo(items);
        }
    }

    /**
     * 二分图完美匹配：每条成语（左）可挑四个位置（右），判断能否给每条都挑到互不相同的字。
     *
     * <p>用 Kuhn 增广路而不是贪心 —— 贪心会漏掉「先让一条、后面才能都排开」的解，
     * 这样断言就会误报；而前端选字用的正是同一套增广路算法，这里等于把它的前提钉死。
     */
    private static boolean canPickDistinct(List<String> idioms) {
        Map<String, Integer> owner = new HashMap<>();   // 字 -> 占用它的成语下标
        for (int i = 0; i < idioms.size(); i++) {
            if (!augment(i, idioms, owner, new HashSet<>())) {
                return false;
            }
        }
        return true;
    }

    private static boolean augment(int i, List<String> idioms, Map<String, Integer> owner, Set<String> visited) {
        String idiom = idioms.get(i);
        for (int p = 0; p < idiom.length(); p++) {
            String ch = String.valueOf(idiom.charAt(p));
            if (!visited.add(ch)) {
                continue;
            }
            Integer holder = owner.get(ch);
            if (holder == null || augment(holder, idioms, owner, visited)) {
                owner.put(ch, i);
                return true;
            }
        }
        return false;
    }
}
