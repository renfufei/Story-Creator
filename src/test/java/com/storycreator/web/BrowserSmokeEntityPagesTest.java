package com.storycreator.web;

import com.storycreator.core.domain.MaterialCategory;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.GuidanceLibraryEntity;
import com.storycreator.persistence.entity.MaterialLibraryEntity;
import com.storycreator.persistence.entity.PromptTemplateEntity;
import com.storycreator.persistence.entity.SideStoryEntity;
import com.storycreator.persistence.entity.TtsExportTaskEntity;
import com.storycreator.persistence.entity.TtsReplacementTemplateEntity;
import com.storycreator.persistence.entity.TxtImportJobEntity;
import com.storycreator.persistence.repository.GuidanceLibraryRepository;
import com.storycreator.persistence.repository.MaterialLibraryRepository;
import com.storycreator.persistence.repository.PromptTemplateRepository;
import com.storycreator.persistence.repository.SideStoryRepository;
import com.storycreator.persistence.repository.TtsExportTaskRepository;
import com.storycreator.persistence.repository.TtsReplacementTemplateRepository;
import com.storycreator.persistence.repository.TxtImportJobRepository;
import com.storycreator.testsupport.BrowserSmokeSupport;
import com.storycreator.tts.TtsExportStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 需要<b>特定实体 id</b>才能打开的页面（编辑页 / 详情页）的浏览器冒烟测试。
 *
 * <p>这些页面在 {@code @BeforeEach} 里现造实体，测完即删；断言口径与
 * {@link BrowserSmokePagesTest} 一致（四类信号干净 + JS 真渲染出文案）。
 */
@Tag("browser")
@DisplayName("实体详情页冒烟")
class BrowserSmokeEntityPagesTest extends BrowserSmokeSupport {

    @Autowired private TtsReplacementTemplateRepository ttsTemplateRepository;
    @Autowired private GuidanceLibraryRepository guidanceRepository;
    @Autowired private MaterialLibraryRepository materialRepository;
    @Autowired private PromptTemplateRepository promptTemplateRepository;
    @Autowired private SideStoryRepository sideStoryRepository;
    @Autowired private TtsExportTaskRepository ttsTaskRepository;
    @Autowired private TxtImportJobRepository txtImportJobRepository;

    private Long ttsTemplateId;
    private Long guidanceId;
    private Long materialId;
    private Long promptId;
    private Long sideStoryId;
    private Long ttsTaskId;
    private Long txtJobId;

    @BeforeEach
    void createEntities() {
        TtsReplacementTemplateEntity tpl = new TtsReplacementTemplateEntity();
        tpl.setName("冒烟替换模板");
        tpl.setDescription("无头浏览器冒烟测试用");
        tpl.setEnabled(true);
        ttsTemplateId = ttsTemplateRepository.save(tpl).getId();

        GuidanceLibraryEntity guidance = new GuidanceLibraryEntity();
        guidance.setName("冒烟创作指导");
        guidance.setStep(WorkflowStep.WORLD_BUILDING);
        guidance.setGuidance("冒烟测试用的指导内容");
        guidanceId = guidanceRepository.save(guidance).getId();

        MaterialLibraryEntity material = new MaterialLibraryEntity();
        material.setName("冒烟素材");
        material.setCategory(MaterialCategory.WORLD);
        material.setContent("冒烟测试用的素材内容");
        materialId = materialRepository.save(material).getId();

        PromptTemplateEntity prompt = new PromptTemplateEntity();
        prompt.setStep(WorkflowStep.WORLD_BUILDING);
        prompt.setSubStep(PromptSubStep.WORLD_BUILDING_PRIMARY);
        prompt.setName("冒烟提示词模板");
        prompt.setTemplate("请写一段{{genre}}故事");
        promptId = promptTemplateRepository.save(prompt).getId();

        SideStoryEntity sideStory = new SideStoryEntity();
        sideStory.setProjectId(projectId);
        sideStory.setTitle("冒烟番外");
        sideStory.setDescription("冒烟测试用的番外");
        sideStory.setType("SUPPLEMENTARY");
        sideStory.setStatus("DRAFT");
        sideStoryId = sideStoryRepository.save(sideStory).getId();

        TtsExportTaskEntity task = new TtsExportTaskEntity();
        task.setProjectId(projectId);
        task.setConfigId(configId);
        task.setStatus(TtsExportStatus.PENDING);
        ttsTaskId = ttsTaskRepository.save(task).getId();

        // TXT 导入 job：逆向流程页（/projects/{id}/reverse/*）按项目 id 解析它
        TxtImportJobEntity job = new TxtImportJobEntity();
        job.setProjectId(projectId);
        job.setTitle("冒烟逆向项目");
        job.setStatus("DONE");
        job.setChapterCount(3);
        job.setTotalWordCount(600);
        txtJobId = txtImportJobRepository.save(job).getId();
    }

    @AfterEach
    void deleteEntities() {
        // 子类 @AfterEach 先于父类执行：先删从表，父类再删 project / config
        transactionTemplate.executeWithoutResult(status -> {
            if (ttsTaskId != null) ttsTaskRepository.deleteById(ttsTaskId);
            if (txtJobId != null) txtImportJobRepository.deleteById(txtJobId);
            if (sideStoryId != null) sideStoryRepository.deleteById(sideStoryId);
            if (promptId != null) promptTemplateRepository.deleteById(promptId);
            if (materialId != null) materialRepository.deleteById(materialId);
            if (guidanceId != null) guidanceRepository.deleteById(guidanceId);
            if (ttsTemplateId != null) ttsTemplateRepository.deleteById(ttsTemplateId);
        });
    }

    @Test
    void ttsTemplateEditPage_rendersForm() {
        smoke("/settings/tts-templates/" + ttsTemplateId + "/edit", "#tplName", "编辑模板", true,
                page -> assertThat(
                                page.evaluate("document.getElementById('tplName').value"))
                        .as("编辑表单应回填模板名")
                        .contains("冒烟替换模板"));
    }

    @Test
    void ttsBuiltinTemplateViewPage_rendersRules() {
        // control-chars 是内置模板的固定 id（见 tts-templates 资源）
        smoke("/settings/tts-templates/builtin/control-chars/view", "#bName", "查看模板", true,
                page -> assertThat(page.count("#rulesArea tr, #rulesArea .row"))
                        .as("内置模板查看页应渲染出规则列表")
                        .isGreaterThanOrEqualTo(1));
    }

    @Test
    void ttsTemplateBindingsPage_rendersBoundList() {
        smoke("/settings/tts-templates/bindings/" + configId, "#bindingsArea", "模板绑定管理", true, null);
    }

    @Test
    void guidanceEditPage_rendersForm() {
        smoke("/settings/guidances/" + guidanceId + "/edit", "#fName", "编辑创作指导", true,
                page -> assertThat(
                                page.evaluate("document.getElementById('fName').value"))
                        .as("编辑表单应回填指导名称")
                        .contains("冒烟创作指导"));
    }

    @Test
    void materialEditPage_rendersForm() {
        smoke("/settings/materials/" + materialId + "/edit", "#fName", "编辑素材", true,
                page -> assertThat(
                                page.evaluate("document.getElementById('fName').value"))
                        .as("编辑表单应回填素材名称")
                        .contains("冒烟素材"));
    }

    @Test
    void promptEditPage_rendersCustomForm() {
        smoke("/prompts/" + promptId + "/edit", "#customForm", "编辑Prompt模板", true, null);
    }

    @Test
    void promptBuiltinViewPage_rendersBuiltinContent() {
        // key 格式为 "STEP|SUB_STEP|GENRE"，尾部 | 不能省（genre 为空），否则后端 404
        smoke("/prompts/builtin/WORLD_BUILDING%7CWORLD_BUILDING_PRIMARY%7C", "#builtinView", "查看内置Prompt模板", true,
                page -> assertThat(
                                page.evaluate("document.getElementById('bName').value"))
                        .as("内置模板查看页应回填模板名")
                        .contains("通用世界观模板"));
    }

    @Test
    void sideStoryWorkflowPage_rendersPanels() {
        smoke("/projects/" + projectId + "/side-stories/" + sideStoryId, "#charEdit-all", "番外故事线", true, null);
    }

    @Test
    void ttsFullPlayPage_rendersPlayer() {
        smoke("/tts-fullplay?taskId=" + ttsTaskId, null, "全文收听", true, null);
    }

    // ==================== Workflow (split hub + 6 step pages) ====================

    @Test
    void workflowHubPage_rendersStepCards() {
        smoke("/projects/" + projectId + "/workflow", null, "世界观设定", true, null);
    }

    @Test
    void workflowStepPage_worldBuilding_renders() {
        smoke("/projects/" + projectId + "/workflow/world-building", null, "世界观设定", true, null);
    }

    @Test
    void workflowStepPage_characters_renders() {
        smoke("/projects/" + projectId + "/workflow/characters", null, "世界观设定", true, null);
    }

    @Test
    void workflowStepPage_outline_renders() {
        smoke("/projects/" + projectId + "/workflow/outline", null, "世界观设定", true, null);
    }

    @Test
    void workflowStepPage_chapters_renders() {
        smoke("/projects/" + projectId + "/workflow/chapters", null, "世界观设定", true, null);
    }

    @Test
    void workflowStepPage_polishing_renders() {
        smoke("/projects/" + projectId + "/workflow/polishing", null, "世界观设定", true, null);
    }

    @Test
    void workflowStepPage_proofreading_renders() {
        smoke("/projects/" + projectId + "/workflow/proofreading", null, "世界观设定", true, null);
    }

    // ==================== Reverse engineering pages (split from /import/txt step 3/4) ====================

    @Test
    void reverseOptionsPage_rendersSwitches() {
        smoke("/projects/" + projectId + "/reverse/options", "#sw-world", "逆向工程 · 选项", true,
                page -> assertThat(page.evaluate("document.getElementById('sw-world') !== null"))
                        .as("选项页应渲染出反推开关组").isEqualTo("true"));
    }

    @Test
    void reverseProgressPage_rendersMonitor() {
        smoke("/projects/" + projectId + "/reverse/progress", "#reOutput", "执行监控", true, null);
    }
}
