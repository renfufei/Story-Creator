package com.storycreator.volume;

import com.storycreator.core.domain.Genre;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.VolumeOutlineEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.VolumeOutlineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 章节 ↔ 分卷 绑定：核心是「新能力」与「老数据零变化」必须同时成立。
 *
 * <p>覆盖：老项目按整除公式分组（开关关闭）、重建写绑定、单章/批量移动、
 * 增删卷、以及之后新增的未绑定章节如何兜底。</p>
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:volume_binding_test;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
class VolumeServiceTest {

    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ChapterRepository chapterRepository;
    @Autowired
    private VolumeOutlineRepository volumeOutlineRepository;

    private VolumeService service;
    private Long projectId;

    @BeforeEach
    void setUp() {
        service = new VolumeService();
        service.setProjectRepository(projectRepository);
        service.setChapterRepository(chapterRepository);
        service.setVolumeOutlineRepository(volumeOutlineRepository);

        ProjectEntity project = new ProjectEntity();
        project.setTitle("分卷绑定测试");
        project.setGenre(Genre.XUANHUAN);
        project.setChaptersPerVolume(4);
        projectId = projectRepository.save(project).getId();
    }

    private void chapters(int count) {
        for (int n = 1; n <= count; n++) {
            ChapterEntity c = new ChapterEntity();
            c.setProjectId(projectId);
            c.setChapterNumber(n);
            c.setTitle("第" + n + "章");
            c.setContent("内容" + n);
            chapterRepository.save(c);
        }
    }

    private VolumeOutlineEntity volume(int number, int start, int end) {
        VolumeOutlineEntity v = new VolumeOutlineEntity();
        v.setProjectId(projectId);
        v.setVolumeNumber(number);
        v.setTitle("第" + number + "卷");
        v.setChapterStart(start);
        v.setChapterEnd(end);
        return volumeOutlineRepository.save(v);
    }

    /** 取某卷解析出的章节号列表。 */
    private List<Integer> numbersOf(int volumeNumber) {
        return service.resolveGroups(projectId).stream()
                .filter(g -> g.volumeNumber() == volumeNumber)
                .map(VolumeChapterGroup::chapterNumbers)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到第" + volumeNumber + "卷"));
    }

    @Test
    void legacyProject_groupsByChaptersPerVolumeFormula() {
        chapters(10);
        volume(1, 1, 4);
        volume(2, 5, 8);
        volume(3, 9, 10);

        assertThat(service.isBindingEnabled(projectId)).isFalse();
        assertThat(numbersOf(1)).containsExactly(1, 2, 3, 4);
        assertThat(numbersOf(2)).containsExactly(5, 6, 7, 8);
        assertThat(numbersOf(3)).containsExactly(9, 10);
        // 老项目不得被写出显式绑定
        assertThat(chapterRepository.findByProjectIdOrderByChapterNumber(projectId))
                .allSatisfy(c -> assertThat(c.getVolumeId()).isNull());
    }

    @Test
    void noVolumes_returnsEmptyGroups() {
        chapters(5);
        assertThat(service.resolveGroups(projectId)).isEmpty();
    }

    @Test
    void rebuild_writesBindingsAndEnablesFlag() {
        chapters(10);
        service.rebuildBindings(projectId, 4);

        assertThat(projectRepository.findById(projectId).orElseThrow().isVolumeBindingEnabled()).isTrue();
        assertThat(service.resolveGroups(projectId)).hasSize(3);
        assertThat(numbersOf(1)).containsExactly(1, 2, 3, 4);
        assertThat(numbersOf(2)).containsExactly(5, 6, 7, 8);
        assertThat(numbersOf(3)).containsExactly(9, 10);
        // 冗余的 chapter_start/chapter_end 同步刷新
        assertThat(volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId))
                .extracting(VolumeOutlineEntity::getChapterStart, VolumeOutlineEntity::getChapterEnd)
                .containsExactly(org.assertj.core.api.Assertions.tuple(1, 4),
                        org.assertj.core.api.Assertions.tuple(5, 8),
                        org.assertj.core.api.Assertions.tuple(9, 10));
    }

    @Test
    void rebuild_doesNotTouchChaptersPerVolume() {
        chapters(8);
        service.rebuildBindings(projectId, 2);
        assertThat(projectRepository.findById(projectId).orElseThrow().getChaptersPerVolume())
                .as("chaptersPerVolume 是自动创作的默认值，不得被重建覆盖").isEqualTo(4);
        assertThat(numbersOf(1)).containsExactly(1, 2);
        assertThat(numbersOf(2)).containsExactly(3, 4);
    }

    @Test
    void assignChapters_movesSingleChapterAcrossVolumes() {
        chapters(10);
        service.rebuildBindings(projectId, 4);

        Long volume1Id = service.resolveGroups(projectId).get(0).volumeId();
        service.assignChapters(projectId, volume1Id, List.of(5));

        assertThat(numbersOf(1)).containsExactly(1, 2, 3, 4, 5);
        assertThat(numbersOf(2)).containsExactly(6, 7, 8);
        assertThat(numbersOf(3)).containsExactly(9, 10);
    }

    @Test
    void assignChapters_addsFiveChaptersFromNextVolume() {
        chapters(10);
        service.rebuildBindings(projectId, 4);

        // 用户的典型诉求：「某个分卷再增加 5 章」
        Long volume1Id = service.resolveGroups(projectId).get(0).volumeId();
        service.assignChapters(projectId, volume1Id, List.of(5, 6, 7, 8, 9));

        assertThat(numbersOf(1)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(numbersOf(2)).isEmpty();
        assertThat(numbersOf(3)).containsExactly(10);
    }

    @Test
    void unboundNewChapterFallsBackToFormulaInsteadOfDisappearing() {
        chapters(8);
        service.rebuildBindings(projectId, 4);

        // 之后新写出来的章节没有绑定（模拟写作流程新增），必须仍能落到推算出的卷里
        ChapterEntity extra = new ChapterEntity();
        extra.setProjectId(projectId);
        extra.setChapterNumber(9);
        extra.setTitle("第9章");
        chapterRepository.save(extra);

        // 第 9 章按公式算出第 3 卷，但实际只有 2 卷 → 必须落到最后一卷，而不是凭空消失
        assertThat(numbersOf(2)).contains(9);
        assertThat(service.resolveGroups(projectId).stream().mapToInt(g -> g.chapterNumbers().size()).sum())
                .isEqualTo(9);
    }

    @Test
    void createAndDeleteVolume() {
        chapters(8);
        service.rebuildBindings(projectId, 4);

        Long newVolumeId = service.createVolume(projectId, null);
        assertThat(numbersOf(3)).isEmpty();

        service.assignChapters(projectId, newVolumeId, List.of(7, 8));
        assertThat(numbersOf(3)).containsExactly(7, 8);
        assertThat(numbersOf(2)).containsExactly(5, 6);

        service.deleteVolume(projectId, newVolumeId);
        assertThat(service.resolveGroups(projectId)).hasSize(2);
        assertThat(numbersOf(2)).as("被删卷的章节应迁到相邻分卷，不得丢失").containsExactly(5, 6, 7, 8);
    }

    @Test
    void renameVolume_fallsBackToDefaultTitleWhenBlank() {
        chapters(4);
        service.rebuildBindings(projectId, 4);
        Long id = service.resolveGroups(projectId).get(0).volumeId();

        service.renameVolume(projectId, id, "开篇之卷");
        assertThat(volumeOutlineRepository.findById(id).orElseThrow().getTitle()).isEqualTo("开篇之卷");

        service.renameVolume(projectId, id, "  ");
        assertThat(volumeOutlineRepository.findById(id).orElseThrow().getTitle()).isEqualTo("第1卷");
    }

    @Test
    void foreignChapterOrVolumeIsRejected() {
        chapters(4);
        service.rebuildBindings(projectId, 4);

        assertThatThrownBy(() -> service.assignChapters(projectId, 999999L, List.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
        ProjectEntity other = new ProjectEntity();
        other.setTitle("另一个项目");
        other.setGenre(Genre.XUANHUAN);
        Long otherId = projectRepository.save(other).getId();
        Long myVolumeId = service.resolveGroups(projectId).get(0).volumeId();
        assertThatThrownBy(() -> service.assignChapters(otherId, myVolumeId, List.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groupMapsIncludeDerivedRange() {
        chapters(6);
        service.rebuildBindings(projectId, 4);

        var maps = service.resolveGroupMaps(projectId);
        assertThat(maps).hasSize(2);
        assertThat(maps.get(0)).containsEntry("chapterStart", 1).containsEntry("chapterEnd", 4)
                .containsEntry("chapterCount", 4);
        assertThat(maps.get(0).get("chapterNumbers")).isEqualTo(List.of(1, 2, 3, 4));
    }

    @Test
    void volumeNumberOfIsEmptyWhenNoVolumes() {
        chapters(4);
        assertThat(service.volumeNumberOf(projectId, 1))
                .as("没有分卷记录时应返回 empty，由调用方自行回落旧公式").isEmpty();
        assertThat(service.volumeNumberByChapter(projectId)).isEmpty();
    }

    @Test
    void volumeNumberByChapterReflectsManualBinding() {
        chapters(8);
        service.rebuildBindings(projectId, 4);

        // 按每卷 4 章：1-4 属第 1 卷，5-8 属第 2 卷
        assertThat(service.volumeNumberOf(projectId, 3)).contains(1);
        assertThat(service.volumeNumberOf(projectId, 6)).contains(2);

        // 手工把第 5 章挪进第 1 卷 —— 所有「第 N 章属于第几卷」的读取点都必须跟着变
        Long volume1Id = service.resolveGroups(projectId).get(0).volumeId();
        service.assignChapters(projectId, volume1Id, List.of(5));

        assertThat(service.volumeNumberOf(projectId, 5)).contains(1);
        assertThat(service.volumeNumberByChapter(projectId))
                .containsEntry(1, 1).containsEntry(5, 1).containsEntry(6, 2).containsEntry(8, 2);
    }
}
