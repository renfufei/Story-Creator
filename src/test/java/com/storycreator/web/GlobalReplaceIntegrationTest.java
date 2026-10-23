package com.storycreator.web;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.ProjectStatus;
import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局替换：「替换为」留空 = <b>删除</b>左边匹配到的内容（2026-10-23 用户要求）。
 *
 * <p>为什么要有这组测试：端点原来用 {@code @RequestParam List<String> newTexts}，
 * Spring 的「字符串 → 集合」转换器会把**空值转成空集合**（`newTexts=` ⇒ size=0）。
 * 后果不是「这一对丢了」这么简单 —— 后面的值会**前移错位**：
 * 「甲→（空）, 乙→X」实际执行成「甲→X」，而「乙→X」整对消失。
 *
 * <p>现在参数走 {@code request.getParameterValues} 读原始值，逐项归一：
 * null → 空串；newTexts 比 oldTexts 短 → 缺的补空串；oldTexts 为空/空白 → 跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:global_replace_test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none"
})
class GlobalReplaceIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ChapterRepository chapterRepository;

    private Long projectId;
    private Long chapterId;

    @BeforeEach
    void setUp() {
        projectId = createProject("全局替换测试项目");
    }

    private Long createProject(String title) {
        ProjectEntity project = new ProjectEntity();
        project.setTitle(title);
        project.setGenre(Genre.XUANHUAN);
        project.setStatus(ProjectStatus.IN_PROGRESS);
        project.setTotalChapters(3);
        project.setChapterWordCount(1000);
        project.setChapterWordCountMin(800);
        project.setChapterWordCountMax(1200);
        project.setCharacterCount(2);
        project.setCurrentStep(WorkflowStep.CHAPTER_WRITING);
        return projectRepository.save(project).getId();
    }

    /** 造一章正文，返回章节 id */
    private Long createChapter(String content) {
        ChapterEntity ch = new ChapterEntity();
        ch.setProjectId(projectId);
        ch.setChapterNumber(1);
        ch.setTitle("第1章");
        ch.setContent(content);
        ch.setWordCount(content.length());
        ch.setStatus(StepStatus.CONFIRMED);
        return chapterRepository.save(ch).getId();
    }

    private String content() {
        return chapterRepository.findById(chapterId).orElseThrow().getContent();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * 按前端真实顺序拼参数：oldTexts / newTexts **交替**出现（前端就是逐对 append 的）。
     * {@code news} 为 null 表示整个 newTexts 参数都不传。
     */
    private String pairQuery(List<String> olds, List<String> news) {
        StringBuilder sb = new StringBuilder("?");
        for (int i = 0; i < olds.size(); i++) {
            if (i > 0) sb.append('&');
            sb.append("oldTexts=").append(enc(olds.get(i)));
            if (news != null) {
                // 留空就发空值（`newTexts=`）—— 这正是原来被转换器吃掉的那种入参
                String n = i < news.size() ? news.get(i) : "";
                sb.append("&newTexts=").append(enc(n));
            }
        }
        return sb.toString();
    }

    /** 调 SSE 端点，返回整段流文本（emitter.complete() 后连接关闭，可同步读取） */
    private String call(String query) {
        /* ★必须用 URI 而不是 String：TestRestTemplate 把 String 当 **URI 模板**，
           会对我们已经百分号编码过的 `%E5%BC%A0`（中文）、`20%25`（百分号）再编码一次，
           服务端拿到的就是字面量 `%E5%BC%A0`，中文永远匹配不上。 */
        java.net.URI uri = java.net.URI.create(
                "http://localhost:" + port + "/projects/" + projectId + "/global-replace" + query);
        ResponseEntity<String> resp = restTemplate.getForEntity(uri, String.class);
        assertThat(resp.getStatusCode()).as("SSE 端点应 200 建流").isEqualTo(HttpStatus.OK);
        return resp.getBody() == null ? "" : resp.getBody();
    }

    // ==================== 核心：留空 = 删除 ====================

    @Test
    @DisplayName("「替换为」留空 ⇒ 删除左边匹配到的内容")
    void blankNewText_deletesMatches() {
        chapterId = createChapter("张三来了，张三走了");

        String body = call(pairQuery(List.of("张三"), List.of("")));

        assertThat(body).contains("全局替换完成");
        assertThat(content()).isEqualTo("来了，走了");
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getWordCount())
                .as("字数统计要跟着改（删除后变短）").isEqualTo("来了，走了".length());
    }

    @Test
    @DisplayName("整个 newTexts 参数都不传 ⇒ 同样按「替换为空」处理（null → 空串）")
    void missingNewTexts_treatedAsDelete() {
        chapterId = createChapter("第1章 引子");

        String body = call(pairQuery(List.of("第1章 "), null));

        assertThat(body).contains("全局替换完成");
        assertThat(content()).isEqualTo("引子");
    }

    @Test
    @DisplayName("★多对里中间那对留空：后面的对不能前移错位（旧实现会错配 + 尾对消失）")
    void blankNewTextInMiddle_keepsPairsAligned() {
        chapterId = createChapter("AAA-BBB-CCC");

        String body = call(pairQuery(List.of("AAA", "BBB", "CCC"), List.of("", "X", "Y")));

        assertThat(body).contains("全局替换完成");
        // AAA 删除、BBB→X、CCC→Y；若错位会得到 "X-Y"（AAA→X）之类
        assertThat(content()).isEqualTo("-X-Y");
    }

    @Test
    @DisplayName("newTexts 比 oldTexts 短 ⇒ 缺的那几对按「删除」处理，不静默丢弃")
    void shorterNewTexts_padsWithBlank() {
        chapterId = createChapter("赤橙黄绿青");

        // 只给第一对的替换值，后两对没有对应值 ⇒ 都删除
        String body = call(pairQuery(List.of("赤", "黄", "青"), List.of("红")));

        assertThat(body).contains("全局替换完成");
        assertThat(content()).isEqualTo("红橙绿");
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("左边全为空 / 纯空白 ⇒ 报「没有有效的替换对」，正文不动")
    void noValidOldTexts_reportsError() {
        chapterId = createChapter("正文不该被改");

        String body = call(pairQuery(List.of("", "   "), List.of("X", "Y")));

        assertThat(body).contains("没有有效的替换对");
        assertThat(content()).isEqualTo("正文不该被改");
    }

    @Test
    @DisplayName("正常成对替换：中文与空串混用也按字面量替换（不走正则）")
    void literalReplace_handlesRegexMetachars() {
        chapterId = createChapter("价格是 1.5 元，折扣 20%");

        // 「.」与「%」都是正则元字符：replace 是字面量替换，不该被当成通配
        String body = call(pairQuery(List.of("1.5", "20%"), List.of("2", "")));

        assertThat(body).contains("全局替换完成");
        assertThat(content()).isEqualTo("价格是 2 元，折扣 ");
    }
}
