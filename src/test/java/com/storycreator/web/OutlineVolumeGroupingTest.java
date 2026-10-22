package com.storycreator.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.ProjectStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.ChapterOutlineEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.VolumeOutlineEntity;
import com.storycreator.persistence.repository.ChapterOutlineRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.VolumeOutlineRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /projects/{id}/outline/data} 的「章节大纲归卷」口径（2026-10-22）。
 *
 * <p>线上真实故障：项目 26 的 {@code volume_outlines} 里唯一那条 vol1 区间停在 1-6
 * （那是按<b>正文</b>算出来的 —— 正文章节只有 6 条），而大纲章节已经生成到 30 条、
 * 每条 {@code volume_number} 都写着 1。旧的按区间过滤把 7-30 全挡掉，
 * 页面上就表现为「新生成的大纲不显示在已有分卷下面」，可库里明明有记录。
 *
 * <p>现在的口径：<b>章节自己记录的 {@code volume_number} 是权威</b>，区间只在
 * 老数据缺失卷号 / 卷号指向已删卷时兜底，且任何章节都不允许被静默丢弃。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:outline_volume_grouping_test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none"
})
class OutlineVolumeGroupingTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private VolumeOutlineRepository volumeOutlineRepository;

    @Autowired
    private ChapterOutlineRepository chapterOutlineRepository;

    private final ObjectMapper mapper = new ObjectMapper();

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    /** 分卷区间陈旧（1-6），但章节记录都写着 vol1 —— 30 章必须全部显示。 */
    @Test
    void staleVolumeRange_keepsAllGeneratedChapters() throws Exception {
        Long pid = newProject("陈旧区间", 30, 30);
        volume(pid, 1, "青梅与校花的初体验争夺战", 1, 6, "第一卷第1-6章的弧线");
        for (int n = 1; n <= 30; n++) {
            chapterOutline(pid, n, 1, "COMPLETED");
        }

        JsonNode data = outlineData(pid);
        JsonNode vols = data.get("volumes");
        assertThat(vols).hasSize(1);

        JsonNode vol = vols.get(0);
        assertThat(vol.get("chapters")).hasSize(30);
        // 卷的章范围按实际归入的章节现算，不再显示过期的 1-6
        assertThat(vol.get("chapterStart").asInt()).isEqualTo(1);
        assertThat(vol.get("chapterEnd").asInt()).isEqualTo(30);
        // 弧线/标题不能被归卷逻辑动到
        assertThat(vol.get("title").asText()).isEqualTo("青梅与校花的初体验争夺战");
        assertThat(vol.get("arcSummary").asText()).contains("第一卷第1-6章的弧线");
        assertThat(chapterNumbers(vol)).containsExactlyElementsOf(range(1, 30));
    }

    /** 多卷：各章按自己的 volumeNumber 落到对应卷，不串卷。 */
    @Test
    void multipleVolumes_eachChapterUnderItsOwnVolume() throws Exception {
        Long pid = newProject("多卷", 20, 10);
        volume(pid, 1, "第一卷", 1, 10, "卷1弧线");
        volume(pid, 2, "第二卷", 11, 20, "卷2弧线");
        for (int n = 1; n <= 20; n++) {
            chapterOutline(pid, n, n <= 10 ? 1 : 2, "COMPLETED");
        }

        JsonNode vols = outlineData(pid).get("volumes");
        assertThat(vols).hasSize(2);
        assertThat(chapterNumbers(vols.get(0))).containsExactlyElementsOf(range(1, 10));
        assertThat(chapterNumbers(vols.get(1))).containsExactlyElementsOf(range(11, 20));
    }

    /** 老数据没有 volume_number —— 退回区间匹配，仍然可见。 */
    @Test
    void legacyChaptersWithoutVolumeNumber_fallBackToRange() throws Exception {
        Long pid = newProject("老数据", 20, 10);
        volume(pid, 1, "第一卷", 1, 10, "卷1弧线");
        volume(pid, 2, "第二卷", 11, 20, "卷2弧线");
        for (int n = 1; n <= 20; n++) {
            chapterOutline(pid, n, null, "COMPLETED");
        }

        JsonNode vols = outlineData(pid).get("volumes");
        assertThat(chapterNumbers(vols.get(0))).containsExactlyElementsOf(range(1, 10));
        assertThat(chapterNumbers(vols.get(1))).containsExactlyElementsOf(range(11, 20));
    }

    /** 章节号超出所有卷区间 —— 挂到最后一卷，绝不能凭空消失。 */
    @Test
    void chapterOutsideAllRanges_isNotDropped() throws Exception {
        Long pid = newProject("超出区间", 12, 10);
        volume(pid, 1, "唯一卷", 1, 10, "弧线");
        for (int n = 1; n <= 12; n++) {
            chapterOutline(pid, n, null, "COMPLETED");
        }

        JsonNode vols = outlineData(pid).get("volumes");
        assertThat(vols).hasSize(1);
        assertThat(chapterNumbers(vols.get(0))).containsExactlyElementsOf(range(1, 12));
        assertThat(vols.get(0).get("chapterEnd").asInt()).isEqualTo(12);
    }

    /** 卷号指向一个已被删除的卷 —— 按区间兜底，不许丢。 */
    @Test
    void chapterWithDanglingVolumeNumber_matchedByRange() throws Exception {
        Long pid = newProject("悬空卷号", 10, 10);
        volume(pid, 1, "唯一卷", 1, 10, "弧线");
        chapterOutline(pid, 3, 99, "COMPLETED");
        chapterOutline(pid, 4, null, "COMPLETED");

        JsonNode vols = outlineData(pid).get("volumes");
        assertThat(chapterNumbers(vols.get(0))).containsExactly(3, 4);
    }

    /** 没有任何分卷记录时保持老行为：平铺 legacyChapters。 */
    @Test
    void noVolumes_returnsLegacyChapters() throws Exception {
        Long pid = newProject("无分卷", 3, 10);
        for (int n = 1; n <= 3; n++) {
            chapterOutline(pid, n, null, "COMPLETED");
        }

        JsonNode data = outlineData(pid);
        assertThat(data.get("volumes")).isEmpty();
        assertThat(data.get("legacyChapters")).hasSize(3);
    }

    // ------------------------------------------------------------------
    // 造数据
    // ------------------------------------------------------------------

    private Long newProject(String title, int totalChapters, int perVolume) {
        ProjectEntity project = new ProjectEntity();
        project.setTitle(title);
        project.setGenre(Genre.XUANHUAN);
        project.setStatus(ProjectStatus.IN_PROGRESS);
        project.setTotalChapters(totalChapters);
        project.setChaptersPerVolume(perVolume);
        project.setChapterWordCount(1000);
        project.setChapterWordCountMin(800);
        project.setChapterWordCountMax(1200);
        project.setCharacterCount(2);
        project.setCurrentStep(WorkflowStep.OUTLINE_GENERATION);
        return projectRepository.save(project).getId();
    }

    private void volume(Long projectId, int number, String title, int start, int end, String arcSummary) {
        VolumeOutlineEntity v = new VolumeOutlineEntity();
        v.setProjectId(projectId);
        v.setVolumeNumber(number);
        v.setTitle(title);
        v.setChapterStart(start);
        v.setChapterEnd(end);
        v.setArcSummary(arcSummary);
        volumeOutlineRepository.save(v);
    }

    private void chapterOutline(Long projectId, int chapterNumber, Integer volumeNumber, String status) {
        ChapterOutlineEntity e = new ChapterOutlineEntity();
        e.setProjectId(projectId);
        e.setChapterNumber(chapterNumber);
        e.setVolumeNumber(volumeNumber);
        e.setTitle("第" + chapterNumber + "章");
        e.setSummary("第" + chapterNumber + "章大纲");
        e.setStatus(status);
        chapterOutlineRepository.save(e);
    }

    private JsonNode outlineData(Long projectId) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/projects/" + projectId + "/outline/data", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody());
    }

    private List<Integer> chapterNumbers(JsonNode vol) {
        List<Integer> numbers = new ArrayList<>();
        vol.get("chapters").forEach(ch -> numbers.add(ch.get("chapterNumber").asInt()));
        return numbers;
    }

    private List<Integer> range(int from, int to) {
        List<Integer> list = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            list.add(i);
        }
        return list;
    }
}
