package com.storycreator.workflow.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TextProcessingUtilsTest {

    /**
     * Full character card with multiline fields and content containing 【brackets】.
     */
    private static final String FULL_CARD = """
            【姓名】李尘安
            【性别】男
            【年龄】外表二十余岁，实际穿越时间未知
            【身份】新任仙帝 / 清虚道宗宗主 / 零债之躯
            【性格】务实精明、慵懒随性、护短、对资源有着本能的渴望
            【外貌】身形修长挺拔，肌肤胜雪，自带一股清冷而诱人的幽香，眉眼间总带着几分刚睡醒般的慵懒与无害，看似温润如玉，实则眼底藏着对因果算计的清醒
            【背景】现代社畜穿越者，因不沾因果成为唯一能入住前任仙帝大罗金仙躯体的容器。前任仙帝将亿万因果债甩锅给众生后超脱，留下这具无债的极品容器。李尘安苏醒于仙帝遗迹，发现只要呼吸都在"免费"使用天地灵气，当即决定苟住发育，用最低成本换取最大长生利益。
            【动机】积累足够的灵石与资源，享受长生不老的逍遥生活，同时利用"零债"优势在各大势力间左右逢源，通过"化债"或"转移债务"来巩固自身地位，最终实现真正的超脱或统治。
            【能力】
            1. 零息借贷：呼吸、修炼、使用高阶资源几乎不产生因果债，或利息极低。
            2. 完美重置：依托【还原符】，可随时将身体状态、灵力状态、伤势恢复至巅峰，相当于无限复活与读档。
            3. 因果吞噬：可吞噬他人未还的因果债转化为自身纯净修为。
            4. 魅惑体质：因仙帝残韵与零债纯净之躯，自带天然幽香，极易吸引异性修士（尤其是女修）的亲近与保护欲。
            【关系】
            - 与云清婉（清虚道宗掌门）：她是主角在清虚道宗的"靠山"与"伴侣"。她看穿了主角"零债之躯"的价值，主动提出合作，以宗门资源扶持主角，并视主角为需要呵护的"无债圣体"，后续发展为双向奔赴的道侣。
            - 与前仙帝：继承其衣钵但打法完全不同，前任是"甩锅流"，主角是"吸血流"兼"清道夫"。
            【概要】李尘安，现任仙帝，外表二十余岁的慵懒青年。作为唯一的"零债之躯"，他呼吸皆免费，修炼无利息，是天地间的最大"白嫖"者。性格务实精明，看似温润无害，实则精打细算。
            """;

    @Test
    void extractField_multilineAbilities_withBracketsInContent() {
        String abilities = TextProcessingUtils.extractField(FULL_CARD, "能力");
        assertThat(abilities).isNotNull();
        assertThat(abilities).contains("零息借贷");
        assertThat(abilities).contains("完美重置");
        assertThat(abilities).contains("因果吞噬");
        assertThat(abilities).contains("魅惑体质");
        // Content contains 【还原符】 — must NOT be treated as a field separator
        assertThat(abilities).contains("【还原符】");
        assertThat(abilities).contains("1.");
        assertThat(abilities).contains("2.");
        assertThat(abilities).contains("3.");
        assertThat(abilities).contains("4.");
    }

    @Test
    void extractField_multilineRelationships() {
        String relationships = TextProcessingUtils.extractField(FULL_CARD, "关系");
        assertThat(relationships).isNotNull();
        assertThat(relationships).contains("云清婉");
        assertThat(relationships).contains("前仙帝");
        assertThat(relationships).contains("靠山");
        assertThat(relationships).contains("甩锅流");
    }

    @Test
    void extractField_singleLineFields() {
        assertThat(TextProcessingUtils.extractField(FULL_CARD, "姓名")).isEqualTo("李尘安");
        assertThat(TextProcessingUtils.extractField(FULL_CARD, "性别")).isEqualTo("男");
        assertThat(TextProcessingUtils.extractField(FULL_CARD, "年龄")).isEqualTo("外表二十余岁，实际穿越时间未知");
        assertThat(TextProcessingUtils.extractField(FULL_CARD, "身份")).isEqualTo("新任仙帝 / 清虚道宗宗主 / 零债之躯");
    }

    @Test
    void extractField_lastField_概要() {
        String summary = TextProcessingUtils.extractField(FULL_CARD, "概要");
        assertThat(summary).isNotNull();
        assertThat(summary).contains("李尘安，现任仙帝");
        assertThat(summary).contains("精打细算");
    }

    @Test
    void extractField_colonFormat_multiline() {
        String colonCard = """
                姓名：李尘安
                性别：男
                能力：
                1. 零息借贷：呼吸不产生因果债
                2. 完美重置：依托【还原符】恢复巅峰
                3. 因果吞噬：吞噬他人因果债
                关系：
                - 与云清婉：她是主角的靠山
                - 与前仙帝：继承其衣钵
                """;
        String abilities = TextProcessingUtils.extractField(colonCard, "能力");
        assertThat(abilities).isNotNull();
        assertThat(abilities).contains("零息借贷");
        assertThat(abilities).contains("完美重置");
        assertThat(abilities).contains("因果吞噬");
        assertThat(abilities).contains("【还原符】");

        String relationships = TextProcessingUtils.extractField(colonCard, "关系");
        assertThat(relationships).contains("云清婉");
        assertThat(relationships).contains("前仙帝");
    }

    @Test
    void extractField_colonFormat_singleLineFields() {
        String text = "姓名：张三\n性别：男\n身份：剑修\n能力：剑术精湛";
        assertThat(TextProcessingUtils.extractField(text, "姓名")).isEqualTo("张三");
        assertThat(TextProcessingUtils.extractField(text, "性别")).isEqualTo("男");
        assertThat(TextProcessingUtils.extractField(text, "能力")).isEqualTo("剑术精湛");
    }

    @Test
    void extractField_fieldValueOnSameLineAsBracketHeader() {
        String text = "【姓名】张三\n【能力】剑术精湛\n【关系】与李四为师徒";
        assertThat(TextProcessingUtils.extractField(text, "姓名")).isEqualTo("张三");
        assertThat(TextProcessingUtils.extractField(text, "能力")).isEqualTo("剑术精湛");
        assertThat(TextProcessingUtils.extractField(text, "关系")).isEqualTo("与李四为师徒");
    }

    @Test
    void extractField_abilitiesStartOnHeaderLine_thenContinue() {
        String text = """
                【姓名】王五
                【能力】1. 火系法术
                2. 水系法术
                3. 风系法术
                【关系】与李四为敌
                """;
        String abilities = TextProcessingUtils.extractField(text, "能力");
        assertThat(abilities).contains("火系法术");
        assertThat(abilities).contains("水系法术");
        assertThat(abilities).contains("风系法术");
    }

    @Test
    void extractField_contentWithInlineBrackets_notSplit() {
        // 【还原符】 and 【天道法则】 appear in content — must NOT be treated as field headers
        String text = """
                【能力】
                1. 依托【还原符】恢复巅峰状态
                2. 突破【天道法则】限制，获得无限修为
                【关系】与云清婉为道侣
                """;
        String abilities = TextProcessingUtils.extractField(text, "能力");
        assertThat(abilities).contains("【还原符】");
        assertThat(abilities).contains("【天道法则】");
        assertThat(abilities).contains("1.");
        assertThat(abilities).contains("2.");
        // 关系 should not contain ability content
        String relationships = TextProcessingUtils.extractField(text, "关系");
        assertThat(relationships).isEqualTo("与云清婉为道侣");
    }

    @Test
    void extractField_nullInput_returnsNull() {
        assertThat(TextProcessingUtils.extractField(null, "姓名")).isNull();
    }

    @Test
    void extractField_fieldNotPresent_returnsNull() {
        assertThat(TextProcessingUtils.extractField("【姓名】张三", "能力")).isNull();
    }

    @Test
    void extractField_withMarkdownFormatting_stripped() {
        String text = "**【姓名】**张三\n**【能力】**\n1. **剑术**精湛\n2. 内功深厚";
        assertThat(TextProcessingUtils.extractField(text, "姓名")).isEqualTo("张三");
        String abilities = TextProcessingUtils.extractField(text, "能力");
        assertThat(abilities).contains("剑术");
        assertThat(abilities).contains("内功深厚");
    }

    @Test
    void parseAllFields_returnsCompleteMap() {
        Map<String, String> fields = TextProcessingUtils.parseAllFields(FULL_CARD);
        assertThat(fields).containsKeys("姓名", "性别", "年龄", "身份", "性格", "外貌", "背景", "动机", "能力", "关系", "概要");
        assertThat(fields.get("姓名")).isEqualTo("李尘安");
        assertThat(fields.get("能力")).contains("零息借贷");
        assertThat(fields.get("能力")).contains("魅惑体质");
        assertThat(fields.get("能力")).contains("【还原符】");
    }

    @Test
    void extractField_allFieldsOnOneLine_bracketFormat() {
        // AI put all fields on one line separated only by 【】
        String text = "【姓名】张三【性别】男【能力】剑术【关系】与李四为友";
        assertThat(TextProcessingUtils.extractField(text, "姓名")).isEqualTo("张三");
        assertThat(TextProcessingUtils.extractField(text, "性别")).isEqualTo("男");
        assertThat(TextProcessingUtils.extractField(text, "能力")).isEqualTo("剑术");
        assertThat(TextProcessingUtils.extractField(text, "关系")).isEqualTo("与李四为友");
    }
}
