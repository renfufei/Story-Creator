package com.storycreator.learn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「册 + 关卡」引导数据的 JSON 形状。
 *
 * <p>单词匹配小游戏现在有<b>两套独立词源</b>：人教版的 24 册（{@link WordMatchBank}）与
 * 大学（{@link CetWordBank}）。两套数据的产出结构必须一模一样，前端只有一份解析逻辑，
 * 所以形状集中放在这里 —— 改动只需改一处，不会出现一边加了字段另一边忘了加。
 */
final class WordBankJson {

    private WordBankJson() {
    }

    /** 册元信息列表（id / label / grade / semester / stage / levelCount / wordCount），字段顺序即前端契约。 */
    static List<Map<String, Object>> booksNode(List<WordMatchBank.BookInfo> books) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WordMatchBank.BookInfo b : books) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", b.id());
            node.put("label", b.label());
            node.put("grade", b.grade());
            node.put("semester", b.semester());
            node.put("stage", b.stage());
            node.put("levelCount", b.levelCount());
            node.put("wordCount", b.wordCount());
            out.add(node);
        }
        return out;
    }

    /** 每册的关卡（含全部单词对），key 为册 id。 */
    static Map<String, List<Map<String, Object>>> levelsNode(Map<String, List<WordMatchBank.Level>> levelsByBook) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        levelsByBook.forEach((bookId, levels) -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (WordMatchBank.Level lv : levels) {
                Map<String, Object> lvNode = new LinkedHashMap<>();
                lvNode.put("index", lv.index());
                lvNode.put("theme", lv.theme());
                List<Map<String, Object>> pairs = new ArrayList<>();
                for (WordMatchBank.WordPair p : lv.pairs()) {
                    Map<String, Object> pNode = new LinkedHashMap<>();
                    pNode.put("key", p.key());
                    pNode.put("en", p.en());
                    pNode.put("zh", p.zh());
                    pairs.add(pNode);
                }
                lvNode.put("pairs", pairs);
                list.add(lvNode);
            }
            out.put(bookId, list);
        });
        return out;
    }
}
