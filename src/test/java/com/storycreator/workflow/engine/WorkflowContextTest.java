package com.storycreator.workflow.engine;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.WorkflowStep;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowContextTest {

    @Test
    void toTemplateVariables_includesReferenceMaterials_whenSet() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setReferenceMaterials("【参考素材库】\n〔角色〕角色素材\n描述内容\n\n以上素材仅供参考借鉴。");

        Map<String, String> vars = ctx.toTemplateVariables();

        assertThat(vars.get("referenceMaterials")).isEqualTo(
                "【参考素材库】\n〔角色〕角色素材\n描述内容\n\n以上素材仅供参考借鉴。");
    }

    @Test
    void toTemplateVariables_referenceMaterials_emptyWhenNull() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setReferenceMaterials(null);

        Map<String, String> vars = ctx.toTemplateVariables();

        assertThat(vars.get("referenceMaterials")).isEmpty();
    }

    @Test
    void toTemplateVariables_referenceMaterials_emptyWhenBlank() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setReferenceMaterials("   ");

        Map<String, String> vars = ctx.toTemplateVariables();

        assertThat(vars.get("referenceMaterials")).isEmpty();
    }

    @Test
    void toTemplateVariables_containsAllExpectedKeys() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setTitle("测试小说");
        ctx.setGenre(Genre.XUANHUAN);
        ctx.setDescription("描述");

        Map<String, String> vars = ctx.toTemplateVariables();

        assertThat(vars).containsKeys(
                "title", "genre", "description", "worldSetting", "characters",
                "outline", "chapterNumber", "totalChapters", "chapterTitle",
                "chapterSummary", "chapterWordCount", "chapterWordCountMin",
                "chapterWordCountMax", "previousContext", "nextChapterTitle",
                "nextChapterSummary", "overallOutline", "content", "polishNote",
                "characterCards", "stepGuidance", "previousCharacterStates",
                "referenceMaterials"
        );
    }

    @Test
    void toTemplateVariables_characterCards_hasPrefix() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setCharacterCards("角色卡片内容");

        Map<String, String> vars = ctx.toTemplateVariables();

        assertThat(vars.get("characterCards")).startsWith("【本章涉及角色详情】\n");
    }

    @Test
    void toTemplateVariables_stepGuidance_hasPrefix() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setStepGuidance("要写得有趣");

        Map<String, String> vars = ctx.toTemplateVariables();

        assertThat(vars.get("stepGuidance")).startsWith("【创作指导】\n");
        assertThat(vars.get("stepGuidance")).contains("请在生成时参考以上指导意见");
    }

    @Test
    void getterSetterRoundtrip_referenceMaterials() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setReferenceMaterials("素材内容");
        assertThat(ctx.getReferenceMaterials()).isEqualTo("素材内容");
    }
}
