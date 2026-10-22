package com.storycreator.web;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.ProjectStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「已完本 / 已废弃」项目的写入闸门（2026-10-21）。
 *
 * <p>产品口径：
 * <ul>
 *   <li>这两类项目不能再启动<b>会改正文</b>的流程（AI 生成、扩写、校对修复、角色精炼、
 *       大纲重生成、全局替换、分面重展开、番外生成、自动创作）；</li>
 *   <li><b>TTS 导出 / 生成 MP3 不拦</b> —— 只读加工，不改文本；</li>
 *   <li>只读端点（章节列表、工作流引导数据、后台任务状态查询）一律不拦，
 *       否则正在跑的任务连进度都查不到。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:project_write_guard_test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none"
})
class ProjectWriteGuardIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProjectRepository projectRepository;

    private Long completedId;
    private Long abandonedId;
    private Long runningId;

    @BeforeEach
    void setUp() {
        completedId = createProject("已完本项目", ProjectStatus.COMPLETED);
        abandonedId = createProject("已废弃项目", ProjectStatus.ABANDONED);
        runningId = createProject("创作中项目", ProjectStatus.IN_PROGRESS);
    }

    private Long createProject(String title, ProjectStatus status) {
        ProjectEntity project = new ProjectEntity();
        project.setTitle(title);
        project.setGenre(Genre.XUANHUAN);
        project.setStatus(status);
        project.setTotalChapters(3);
        project.setChapterWordCount(1000);
        project.setChapterWordCountMin(800);
        project.setChapterWordCountMax(1200);
        project.setCharacterCount(2);
        project.setCurrentStep(WorkflowStep.CHAPTER_WRITING);
        return projectRepository.save(project).getId();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** SSE 端点被拒时返回的是一段 `event: error / data: <原因>` 流 */
    private String sseBody(String path) {
        ResponseEntity<String> resp = restTemplate.getForEntity(url(path), String.class);
        assertThat(resp.getStatusCode()).as("SSE 端点应正常建立连接再报错（不是 4xx/5xx）：" + path)
                .isEqualTo(HttpStatus.OK);
        return resp.getBody() == null ? "" : resp.getBody();
    }

    // ==================== 纯函数 ====================

    @Test
    void denyMessage_blocksOnlySealedStatuses() {
        String completed = com.storycreator.core.service.ProjectWriteGuard
                .denyMessage(ProjectStatus.COMPLETED, "进行AI生成");
        String abandoned = com.storycreator.core.service.ProjectWriteGuard
                .denyMessage(ProjectStatus.ABANDONED, "进行AI生成");
        assertThat(completed).contains("已完本").contains("无法进行AI生成");
        assertThat(abandoned).contains("已废弃").contains("无法进行AI生成");

        assertThat(com.storycreator.core.service.ProjectWriteGuard
                .denyMessage(ProjectStatus.IN_PROGRESS, "进行AI生成")).isNull();
        assertThat(com.storycreator.core.service.ProjectWriteGuard
                .denyMessage(ProjectStatus.NOT_STARTED, "进行AI生成")).isNull();

        // 动作名会拼进文案，不同入口给出的提示不一样，用户才知道是哪一步被挡
        assertThat(com.storycreator.core.service.ProjectWriteGuard
                .denyMessage(ProjectStatus.COMPLETED, "启动扩写")).contains("无法启动扩写");
    }

    // ==================== 已完本 ====================

    @Test
    void completedProject_blocksAiGenerateStream() {
        assertThat(sseBody("/projects/" + completedId + "/generate?step=WORLD_BUILDING"))
                .contains("已完本");
    }

    @Test
    void completedProject_blocksBackgroundGenerate() {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                url("/projects/" + completedId + "/bg-gen/start?step=CHAPTER_WRITING&chapter=1"),
                null, Map.class);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("status")).isEqualTo("error");
        assertThat(String.valueOf(resp.getBody().get("message"))).contains("已完本");
    }

    @Test
    void completedProject_blocksExpansionWithBadRequest() {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                url("/projects/" + completedId + "/expansion/start"), null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(resp.getBody().get("error"))).contains("已完本");
    }

    @Test
    void completedProject_blocksProofreadFixAndGlobalReplace() {
        assertThat(sseBody("/projects/" + completedId + "/proofread-fix/1")).contains("已完本");
        assertThat(sseBody("/projects/" + completedId + "/global-replace?oldTexts=a&newTexts=b"))
                .contains("已完本");
    }

    @Test
    void completedProject_blocksSideStoryGeneration() {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                url("/projects/" + completedId + "/side-stories/api/999/generate/writing/1"),
                null, Map.class);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("status")).isEqualTo("error");
        assertThat(String.valueOf(resp.getBody().get("message"))).contains("已完本");
    }

    // ==================== 已废弃 ====================

    @Test
    void abandonedProject_blocksAiGenerateStream() {
        assertThat(sseBody("/projects/" + abandonedId + "/generate?step=WORLD_BUILDING"))
                .contains("已废弃");
    }

    @Test
    void abandonedProject_blocksBackgroundGenerate() {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                url("/projects/" + abandonedId + "/bg-gen/start?step=CHAPTER_WRITING&chapter=1"),
                null, Map.class);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("status")).isEqualTo("error");
        assertThat(String.valueOf(resp.getBody().get("message"))).contains("已废弃");
    }

    // ==================== 不该被拦的 ====================

    @Test
    void ttsExport_isNotBlocked() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                url("/api/tts-export/projects/" + completedId + "/chapters"), String.class);
        assertThat(resp.getStatusCode())
                .as("生成 MP3 / TTS 导出只是读正文，不产生文本改动，必须放行")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void readOnlyEndpoints_areNotBlocked() {
        // 章节列表（前端要从这里拿正文做导出）
        assertThat(restTemplate.getForEntity(
                url("/projects/" + completedId + "/chapters/list"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // 后台任务状态查询：正在跑的任务必须还能查进度
        assertThat(restTemplate.getForEntity(
                url("/projects/" + completedId + "/bg-gen/status?step=CHAPTER_WRITING"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void inProgressProject_isNotBlockedByGuard() {
        // 创作中的项目不应该被闸门挡住。这里只看「只读入口」——
        // 真去调 /generate 会真的启动 AI 生成（缺模型配置也要跑一遍），测试不该有这种副作用；
        // 「创作中放行」这件事由 denyMessage 的纯函数用例覆盖。
        assertThat(restTemplate.getForEntity(
                url("/projects/" + runningId + "/workflow/data"), String.class).getStatusCode())
                .as("创作中的项目：引导数据正常返回，说明闸门没有误伤").isEqualTo(HttpStatus.OK);
    }
}
