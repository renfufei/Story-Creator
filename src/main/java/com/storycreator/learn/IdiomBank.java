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
 * 成语词库（小学 / 初中 / 高中三学段），供「成语填空」小游戏使用。
 *
 * <p>数据源为 classpath 下的 {@code learn/idioms.json}，结构是「学段 → 主题 → 成语」，
 * 每条成语带释义与（可选的）出处。三个学段各 10 个主题、每主题 6 条，共 180 条四字成语，
 * 全部取自人教版（部编版）语文课本：小学 1~6 年级课文与「语文园地·日积月累」，
 * 初中 7~9 年级课文与课后积累，高中必修 1~5 的文言诗文与现代文。
 *
 * <p><b>为什么按主题切关</b>：同一主题的成语语义相近（都是寓言 / 都是写景 / 都是勤学），
 * 一关里的字义色彩成套，玩家填完能形成联想，比按顺序硬切有意义 —— 与英语单词匹配同一套思路。
 *
 * <p><b>切关规则</b>：每个主题内按 {@link #splitBalanced(int)} 均分成若干关，每关
 * {@value #MIN_IDIOMS}~{@value #MAX_IDIOMS} 条、围绕 {@value #TARGET_IDIOMS} 条为目标。
 * 主题只有 6 条时正好一关；12 条会切成 6+6，不会出现 11 条一关或者 1 条一关的边角关。
 *
 * <p><b>数据校验</b>：加载时强校验「四字」「释义非空」「全库不重复」，有违例直接抛
 * {@link IllegalStateException} 让启动失败 —— 宁可起不来，也不要让某个学段悄悄少一半题。
 * 前端按「四字一格」排版，三字/五字以上的词会破版，所以长度是硬约束。
 */
@Service
public class IdiomBank {

    private static final Logger log = LoggerFactory.getLogger(IdiomBank.class);

    /** 每关最少 / 最多成语条数。 */
    public static final int MIN_IDIOMS = 4;
    public static final int MAX_IDIOMS = 8;

    /** 每关条数目标值：6 条时棋盘左右两列高度最协调（左 6 行 × 右 3 行 × 2 列）。 */
    public static final int TARGET_IDIOMS = 6;

    /** 一条成语固定四个字（前端四格排版 + 随机拆其中一格）。 */
    public static final int CHAR_COUNT = 4;

    private static final String RESOURCE = "learn/idioms.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 学段元信息（前端顶部三个页签用）。 */
    public record StageInfo(String id, String label, String gradeLabel, String source,
                            int themeCount, int levelCount, int idiomCount) {
    }

    /** 一条成语：本体 + 释义 + 出处（无出处时为空串，前端不渲染那一段）。 */
    public record Idiom(String idiom, String meaning, String source) {
    }

    /** 一个关卡：主题名 + {@value #MIN_IDIOMS}~{@value #MAX_IDIOMS} 条成语。 */
    public record Level(int index, String theme, List<Idiom> items) {
    }

    private final List<StageInfo> stages = new ArrayList<>();
    private final Map<String, List<Level>> levelsByStage = new LinkedHashMap<>();
    private final Map<String, String> stageLabels = new LinkedHashMap<>();

    public IdiomBank() {
        load();
    }

    /** 前端引导数据：学段列表 + 每学段的关卡（含全部成语、释义、出处）。 */
    public Map<String, Object> bootstrapData() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stages", stagesNode());
        result.put("levels", levelsNode());
        return result;
    }

    public List<StageInfo> getStages() {
        return List.copyOf(stages);
    }

    public List<Level> getLevels(String stageId) {
        return levelsByStage.getOrDefault(stageId, List.of());
    }

    public String labelOf(String stageId) {
        return stageLabels.getOrDefault(stageId, stageId);
    }

    private List<Map<String, Object>> stagesNode() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StageInfo s : stages) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", s.id());
            node.put("label", s.label());
            node.put("gradeLabel", s.gradeLabel());
            node.put("source", s.source());
            node.put("themeCount", s.themeCount());
            node.put("levelCount", s.levelCount());
            node.put("idiomCount", s.idiomCount());
            out.add(node);
        }
        return out;
    }

    private Map<String, List<Map<String, Object>>> levelsNode() {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        levelsByStage.forEach((stageId, levels) -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Level lv : levels) {
                Map<String, Object> lvNode = new LinkedHashMap<>();
                lvNode.put("index", lv.index());
                lvNode.put("theme", lv.theme());
                List<Map<String, Object>> items = new ArrayList<>();
                for (Idiom it : lv.items()) {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("idiom", it.idiom());
                    node.put("meaning", it.meaning());
                    node.put("source", it.source());
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
     * 把 n 条成语均分成若干关，每关 {@value #MIN_IDIOMS}~{@value #MAX_IDIOMS} 条、尽量贴近
     * {@value #TARGET_IDIOMS} 条。算法与英语单词匹配的 {@code WordMatchBank#splitBalanced(int)} 同源：
     * 关数取 ceil(n/MAX) 与 round(n/TARGET) 的较大者，余数摊到前面几关，各关大小尽量均匀。
     * 例：6→[6]；7→[7]；12→[6,6]；14→[7,7]；18→[6,6,6]。
     */
    static List<Integer> splitBalanced(int n) {
        List<Integer> out = new ArrayList<>();
        if (n <= 0) {
            return out;
        }
        if (n <= MAX_IDIOMS) {
            out.add(n);
            return out;
        }
        int byMax = (int) Math.ceil(n / (double) MAX_IDIOMS);
        int byTarget = (int) Math.round(n / (double) TARGET_IDIOMS);
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
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode stageNode : root.path("stages")) {
                String stageId = stageNode.path("id").asText().trim();
                String label = stageNode.path("label").asText().trim();
                if (stageId.isEmpty() || label.isEmpty()) {
                    throw new IllegalStateException("成语词库学段缺少 id 或 label：" + stageNode);
                }
                if (levelsByStage.containsKey(stageId)) {
                    throw new IllegalStateException("成语词库学段 id 重复：" + stageId);
                }
                String gradeLabel = stageNode.path("gradeLabel").asText("").trim();
                String source = stageNode.path("source").asText("").trim();

                List<Level> levels = new ArrayList<>();
                int themeCount = 0;
                int idiomCount = 0;
                for (JsonNode themeNode : stageNode.path("themes")) {
                    String themeName = themeNode.path("name").asText("").trim();
                    List<Idiom> items = new ArrayList<>();
                    for (JsonNode itemNode : themeNode.path("items")) {
                        String idiom = itemNode.path("idiom").asText("").trim();
                        String meaning = itemNode.path("meaning").asText("").trim();
                        String from = itemNode.path("source").asText("").trim();
                        validate(stageId, themeName, idiom, meaning, seen);
                        items.add(new Idiom(idiom, meaning, from));
                    }
                    if (items.isEmpty()) {
                        continue;
                    }
                    if (items.size() < MIN_IDIOMS) {
                        log.warn("成语库 {}/{} 只有 {} 条，不足 {} 条，该主题不成关",
                                label, themeName, items.size(), MIN_IDIOMS);
                        continue;
                    }
                    themeCount++;
                    idiomCount += items.size();

                    int cursor = 0;
                    for (int size : splitBalanced(items.size())) {
                        levels.add(new Level(levels.size(), themeName,
                                List.copyOf(items.subList(cursor, cursor + size))));
                        cursor += size;
                    }
                }
                stages.add(new StageInfo(stageId, label, gradeLabel, source,
                        themeCount, levels.size(), idiomCount));
                stageLabels.put(stageId, label);
                levelsByStage.put(stageId, List.copyOf(levels));
            }
            if (stages.isEmpty()) {
                throw new IllegalStateException("成语词库为空：" + RESOURCE);
            }
            log.info("成语词库加载完成：{} 个学段 / {} 关 / {} 条成语（每关 {}~{} 条，目标 {}）",
                    stages.size(),
                    levelsByStage.values().stream().mapToInt(List::size).sum(),
                    stages.stream().mapToInt(StageInfo::idiomCount).sum(),
                    MIN_IDIOMS, MAX_IDIOMS, TARGET_IDIOMS);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("加载成语词库失败：" + RESOURCE, e);
        }
    }

    /** 单条成语的硬校验：必须四字、释义不得为空、全库不得重复。 */
    private static void validate(String stageId, String theme, String idiom, String meaning, Set<String> seen) {
        if (idiom.length() != CHAR_COUNT) {
            throw new IllegalStateException("成语「" + idiom + "」（" + stageId + "/" + theme
                    + "）不是四字，前端按四格排版会破版");
        }
        if (meaning.isEmpty()) {
            throw new IllegalStateException("成语「" + idiom + "」（" + stageId + "/" + theme + "）缺少释义");
        }
        if (!seen.add(idiom)) {
            throw new IllegalStateException("成语「" + idiom + "」在词库中重复出现（" + stageId + "/" + theme + "）");
        }
    }
}
