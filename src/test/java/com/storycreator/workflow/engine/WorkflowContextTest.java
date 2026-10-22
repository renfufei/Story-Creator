package com.storycreator.workflow.engine;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
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

    // --- 分卷：只做调用参数，不进提示词 ---

    @Test
    void stampScope_carriesVolumeIntoRequest() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setProjectId(26L);
        ctx.setCurrentChapter(7);
        ctx.setVolumeNumber(2);
        ctx.setVolumeTitle("风起云涌");

        AiRequest request = ctx.stampScope(AiRequest.builder().userPrompt("USER").build());

        assertThat(request.getProjectId()).isEqualTo(26L);
        assertThat(request.getChapterNumber()).isEqualTo(7);
        assertThat(request.getVolumeNumber()).isEqualTo(2);
        assertThat(request.getVolumeTitle()).isEqualTo("风起云涌");
        // 提示词一个字都不能变
        assertThat(request.getUserPrompt()).isEqualTo("USER");
    }

    @Test
    void toTemplateVariables_hasNoVolumeKeys() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setVolumeNumber(2);
        ctx.setVolumeTitle("风起云涌");

        Map<String, String> vars = ctx.toTemplateVariables();

        // 刻意的：卷号走 AiRequest 参数，模板变量里不出现，避免模型读到「第几卷」
        assertThat(vars).doesNotContainKeys("volumeNumber", "volumeTitle", "volumeRange", "volumeContext");
    }

    @Test
    void stampScope_toleratesNullRequest() {
        assertThat(new WorkflowContext().stampScope(null)).isNull();
    }
}
