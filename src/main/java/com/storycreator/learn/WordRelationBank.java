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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 近义词 / 反义词词库（小学 / 初中 / 高中），供「近义词匹配」「反义词匹配」两个小游戏共用。
 *
 * <p>数据源是两个 classpath 资源：{@code learn/synonyms.json} 与 {@code learn/antonyms.json}，
 * 结构同为「学段 → 主题 → 词对」，每对含 {@code word}（左列题面）、{@code match}（右列答案）、
 * {@code group}（所属语义组）与 {@code note}（辨析 / 释义）。
 * 三个学段各 10 个主题、每主题 18 对，两个词库各 540 对（90 关）。
 *
 * <p><b>★ 为什么每对都要带 group</b>：配对游戏的致命伤是「两个字都说得通」。
 * 如果一关里同时出现「注视/凝视」和「端详/打量」，右列就会有两个都能配「注视」的答案，
 * 玩家选哪个都算对、也都不算对。所以词库里把互相为近义词（反义词则是同一条语义轴）的词
 * 归到同一个 group，并在加载时强校验：<b>同一关里同一个 group 最多只能出现一对</b>
 * （即同一关最多 2 个同义词，绝不会出现 3 个及以上）。违反就直接启动失败。
 *
 * <p><b>切关规则</b>：每个主题内按 {@link #splitBalanced(int)} 均分，每关
 * {@value #MIN_PAIRS}~{@value #MAX_PAIRS} 对、围绕 {@value #TARGET_PAIRS} 对。
 * 当前每个主题正好 18 对 ⇒ 一主题切三关（6 + 6 + 6）。
 *
 * <p><b>数据校验</b>（任一违例抛 {@link IllegalStateException} 让启动失败，宁可起不来，
 * 也不要让某个学段悄悄少一半题）：词与答案非空且不相等、辨析非空、词长 1~6、
 * group 必须存在且两个词都在成员表里、同关不重词、全库同一个词只出现一次。
 */
@Service
public class WordRelationBank {

    private static final Logger log = LoggerFactory.getLogger(WordRelationBank.class);

    /** 每关最少 / 最多词对数。 */
    public static final int MIN_PAIRS = 4;
    public static final int MAX_PAIRS = 8;

    /** 每关词对数目标值：6 对时左 6 行、右 2 列 3 行，两列高度最协调。 */
    public static final int TARGET_PAIRS = 6;

    /** 词长上限（四字成语是 4，留一点余量但不允许整句）。 */
    public static final int MAX_WORD_LENGTH = 6;

    /** 两种模式：近义词 / 反义词。 */
    public static final String MODE_SYNONYM = "synonym";
    public static final String MODE_ANTONYM = "antonym";

    /** 学段元信息（前端顶部三个页签用）。 */
    public record StageInfo(String id, String label, String gradeLabel, String source,
                            int themeCount, int levelCount, int pairCount) {
    }

    /**
     * 一对词：左列题面 + 右列答案 + 所属语义组 + 辨析。
     *
     * <p>{@code group} 只在服务端用于防歧义校验与测试复算，<b>不下发前端</b>
     * （{@link #levelsNode} 只输出 word / match / note）——前端按「词相等」判对，
     * 拿到组名反而能作弊。
     */
    public record PairItem(String word, String match, String group, String note) {
    }

    /** 一个关卡：主题名 + {@value #MIN_PAIRS}~{@value #MAX_PAIRS} 对词。 */
    public record Level(int index, String theme, List<PairItem> items) {
    }

    /** 一个模式的完整词库。 */
    private record Dataset(String mode, String label, List<StageInfo> stages,
                           Map<String, List<Level>> levelsByStage, int groupCount) {
    }

    private static final Map<String, String> RESOURCE_BY_MODE = new LinkedHashMap<>();
    private static final Map<String, String> LABEL_BY_MODE = new LinkedHashMap<>();

    static {
        RESOURCE_BY_MODE.put(MODE_SYNONYM, "learn/synonyms.json");
        RESOURCE_BY_MODE.put(MODE_ANTONYM, "learn/antonyms.json");
        LABEL_BY_MODE.put(MODE_SYNONYM, "近义词");
        LABEL_BY_MODE.put(MODE_ANTONYM, "反义词");
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Dataset> datasets = new LinkedHashMap<>();

    /** 每个模式的「语义组 → 成员词」，校验用，也开放给测试复算。 */
    private final Map<String, Map<String, List<String>>> groupMembers = new LinkedHashMap<>();

    public WordRelationBank() {
        for (String mode : RESOURCE_BY_MODE.keySet()) {
            load(mode, RESOURCE_BY_MODE.get(mode));
        }
    }

    /** 支持的模式（顺序固定：近义词在前）。 */
    public static List<String> modes() {
        return List.copyOf(RESOURCE_BY_MODE.keySet());
    }

    /** 规范化模式名；不认识时返回 {@code null}（控制器据此返回 400）。 */
    public static String normalizeMode(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase();
        return RESOURCE_BY_MODE.containsKey(v) ? v : null;
    }

    public static String labelOfMode(String mode) {
        return LABEL_BY_MODE.getOrDefault(mode, mode);
    }

    /** 前端引导数据：模式 + 学段列表 + 每学段关卡（含全部词对与辨析）。 */
    public Map<String, Object> bootstrapData(String mode) {
        Dataset ds = require(mode);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", ds.mode());
        result.put("label", ds.label());
        result.put("stages", stagesNode(ds));
        result.put("levels", levelsNode(ds));
        result.put("groupCount", ds.groupCount());
        return result;
    }

    public List<StageInfo> getStages(String mode) {
        return List.copyOf(require(mode).stages());
    }

    public List<Level> getLevels(String mode, String stageId) {
        return require(mode).levelsByStage().getOrDefault(stageId, List.of());
    }

    public String getLabel(String mode) {
        return require(mode).label();
    }

    public int getGroupCount(String mode) {
        return require(mode).groupCount();
    }

    /** 语义组 → 成员词（只读副本），供测试独立复算「同一关不会出现 3 个同义词」。 */
    public Map<String, List<String>> getGroupMembers(String mode) {
        return Map.copyOf(groupMembers.getOrDefault(mode, Map.of()));
    }

    private Dataset require(String mode) {
        Dataset ds = datasets.get(mode);
        if (ds == null) {
            throw new IllegalArgumentException("不支持的词库模式：" + mode);
        }
        return ds;
    }

    private List<Map<String, Object>> stagesNode(Dataset ds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StageInfo s : ds.stages()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", s.id());
            node.put("label", s.label());
            node.put("gradeLabel", s.gradeLabel());
            node.put("source", s.source());
            node.put("themeCount", s.themeCount());
            node.put("levelCount", s.levelCount());
            node.put("pairCount", s.pairCount());
            out.add(node);
        }
        return out;
    }

    private Map<String, List<Map<String, Object>>> levelsNode(Dataset ds) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        ds.levelsByStage().forEach((stageId, levels) -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Level lv : levels) {
                Map<String, Object> lvNode = new LinkedHashMap<>();
                lvNode.put("index", lv.index());
                lvNode.put("theme", lv.theme());
                List<Map<String, Object>> items = new ArrayList<>();
                for (PairItem it : lv.items()) {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("word", it.word());
                    node.put("match", it.match());
                    node.put("note", it.note());
                    items.add(node);
                }
                lvNode.put("items", items);
                list.add(lvNode);
            }
            out.put(stageId, list);
        });
        return out;
    }

    /**
     * 把 n 对词均分成若干关，每关 {@value #MIN_PAIRS}~{@value #MAX_PAIRS} 对、尽量贴近
     * {@value #TARGET_PAIRS} 对。与 {@code WordMatchBank#splitBalanced(int)} 同源。
     * 例：6→[6]；7→[7]；12→[6,6]；14→[7,7]；18→[6,6,6]。
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

    private void load(String mode, String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode root = objectMapper.readTree(in);

            // ① 语义组：组 id → 成员表。同义词是「近义簇」，反义词是「语义轴」。
            Map<String, List<String>> groups = new LinkedHashMap<>();
            JsonNode groupsNode = root.path("groups");
            if (!groupsNode.isObject()) {
                throw new IllegalStateException(resource + " 缺少 groups 段（语义组是防歧义的前提）");
            }
            groupsNode.fields().forEachRemaining(e -> {
                List<String> members = new ArrayList<>();
                for (JsonNode m : e.getValue()) {
                    members.add(m.asText().trim());
                }
                if (members.size() < 2) {
                    throw new IllegalStateException(resource + " 语义组 " + e.getKey()
                            + " 成员少于 2 个，约束形同虚设");
                }
                groups.put(e.getKey(), List.copyOf(members));
            });
            groupMembers.put(mode, Map.copyOf(groups));

            List<StageInfo> stages = new ArrayList<>();
            Map<String, List<Level>> levelsByStage = new LinkedHashMap<>();
            Set<String> seenWords = new LinkedHashSet<>();
            Set<String> usedGroups = new LinkedHashSet<>();

            for (JsonNode stageNode : root.path("stages")) {
                String stageId = stageNode.path("id").asText("").trim();
                String label = stageNode.path("label").asText("").trim();
                if (stageId.isEmpty() || label.isEmpty()) {
                    throw new IllegalStateException(resource + " 学段缺少 id 或 label：" + stageNode);
                }
                if (levelsByStage.containsKey(stageId)) {
                    throw new IllegalStateException(resource + " 学段 id 重复：" + stageId);
                }
                String gradeLabel = stageNode.path("gradeLabel").asText("").trim();
                String source = stageNode.path("source").asText("").trim();

                List<Level> levels = new ArrayList<>();
                int themeCount = 0;
                int pairCount = 0;
                for (JsonNode themeNode : stageNode.path("themes")) {
                    String themeName = themeNode.path("name").asText("").trim();
                    List<PairItem> items = new ArrayList<>();
                    for (JsonNode itemNode : themeNode.path("items")) {
                        String word = itemNode.path("word").asText("").trim();
                        String match = itemNode.path("match").asText("").trim();
                        String group = itemNode.path("group").asText("").trim();
                        String note = itemNode.path("note").asText("").trim();
                        validatePair(resource, stageId, themeName, word, match, group, note,
                                groups, seenWords);
                        usedGroups.add(group);
                        items.add(new PairItem(word, match, group, note));
                    }
                    if (items.isEmpty()) {
                        continue;
                    }
                    if (items.size() < MIN_PAIRS) {
                        log.warn("{} {}/{} 只有 {} 对，不足 {} 对，该主题不成关",
                                resource, label, themeName, items.size(), MIN_PAIRS);
                        continue;
                    }
                    themeCount++;
                    pairCount += items.size();

                    int cursor = 0;
                    for (int size : splitBalanced(items.size())) {
                        List<PairItem> slice = List.copyOf(items.subList(cursor, cursor + size));
                        cursor += size;
                        assertNoAmbiguity(resource, label, themeName, levels.size(), slice);
                        levels.add(new Level(levels.size(), themeName, slice));
                    }
                }
                stages.add(new StageInfo(stageId, label, gradeLabel, source,
                        themeCount, levels.size(), pairCount));
                levelsByStage.put(stageId, List.copyOf(levels));
            }
            if (stages.isEmpty()) {
                throw new IllegalStateException(resource + " 学段为空");
            }
            List<String> idle = new ArrayList<>();
            for (String g : groups.keySet()) {
                if (!usedGroups.contains(g)) {
                    idle.add(g);
                }
            }
            if (!idle.isEmpty()) {
                log.warn("{} 有 {} 个语义组没有被任何词对使用：{}", resource, idle.size(), idle);
            }

            datasets.put(mode, new Dataset(mode, LABEL_BY_MODE.get(mode), List.copyOf(stages),
                    levelsByStage, groups.size()));
            log.info("{}词库加载完成：{} 个学段 / {} 关 / {} 对（每关 {}~{} 对，目标 {}，{} 个语义组）",
                    LABEL_BY_MODE.get(mode), stages.size(),
                    levelsByStage.values().stream().mapToInt(List::size).sum(),
                    stages.stream().mapToInt(StageInfo::pairCount).sum(),
                    MIN_PAIRS, MAX_PAIRS, TARGET_PAIRS, groups.size());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("加载" + LABEL_BY_MODE.get(mode) + "词库失败：" + resource, e);
        }
    }

    /** 单对词的硬校验。 */
    private static void validatePair(String resource, String stageId, String theme,
                                     String word, String match, String group, String note,
                                     Map<String, List<String>> groups, Set<String> seenWords) {
        String where = "（" + stageId + "/" + theme + "）";
        if (word.isEmpty() || match.isEmpty()) {
            throw new IllegalStateException(resource + " 有空词条" + where);
        }
        if (word.equals(match)) {
            throw new IllegalStateException(resource + "「" + word + "」的答案与自身相同" + where);
        }
        if (word.length() > MAX_WORD_LENGTH || match.length() > MAX_WORD_LENGTH) {
            throw new IllegalStateException(resource + "「" + word + "/" + match + "」超过 "
                    + MAX_WORD_LENGTH + " 个字，右列格子放不下" + where);
        }
        if (note.isEmpty()) {
            throw new IllegalStateException(resource + "「" + word + "」缺少辨析" + where);
        }
        List<String> members = groups.get(group);
        if (members == null) {
            throw new IllegalStateException(resource + "「" + word + "」引用了不存在的语义组 "
                    + group + where);
        }
        if (!members.contains(word) || !members.contains(match)) {
            throw new IllegalStateException(resource + "「" + word + "/" + match
                    + "」不在语义组 " + group + " 的成员表里" + where);
        }
        if (!seenWords.add(word)) {
            throw new IllegalStateException(resource + "「" + word + "」在词库中重复出现" + where);
        }
        if (!seenWords.add(match)) {
            throw new IllegalStateException(resource + "「" + match
                    + "」既作过答案又作题面，会造成跨关歧义" + where);
        }
    }

    /**
     * ★ 防歧义：同一关里，同一个语义组只能出现<b>一对</b>。
     *
     * <p>等价于「同一关最多出现 2 个同义词」。一旦出现第二对，右列就会有 2 个都能配上同一题面的
     * 答案，玩家无从判断，游戏性直接被破坏 —— 这是本轮需求里点名的红线，所以做成启动期硬校验。
     */
    private static void assertNoAmbiguity(String resource, String stageLabel, String theme,
                                          int levelIndex, List<PairItem> items) {
        Map<String, Integer> perGroup = new LinkedHashMap<>();
        Set<String> words = new LinkedHashSet<>();
        for (PairItem it : items) {
            words.add(it.word());
            words.add(it.match());
        }
        if (words.size() != items.size() * 2) {
            throw new IllegalStateException(resource + " " + stageLabel + "/" + theme
                    + " 第 " + (levelIndex + 1) + " 关存在重复词，左右两列会出现同字块");
        }
        // 逐对按 group 计数
        for (PairItem it : items) {
            String g = it.group();
            int n = perGroup.merge(g, 1, Integer::sum);
            if (n > 1) {
                throw new IllegalStateException(resource + " " + stageLabel + "/" + theme
                        + " 第 " + (levelIndex + 1) + " 关里语义组 " + g + " 出现了 " + n
                        + " 对 ⇒ 这一关会有 3 个以上近义词互相干扰");
            }
        }
    }
}
