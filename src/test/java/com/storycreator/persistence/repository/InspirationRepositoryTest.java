package com.storycreator.persistence.repository;

import com.storycreator.core.domain.Genre;
import com.storycreator.persistence.entity.InspirationEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 灵感表：迁移可用、按项目隔离、最新在前、按项目清理。
 * <p>投影到项目维度是安全边界 —— 不能从 A 项目的页面读到 B 项目的灵感。
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:inspiration_repo_test;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
class InspirationRepositoryTest {

    @Autowired
    private InspirationRepository inspirationRepository;

    @Autowired
    private ProjectRepository projectRepository;

    private Long projectA;
    private Long projectB;

    @BeforeEach
    void setUp() {
        projectA = newProject("灵感来源项目A");
        projectB = newProject("灵感来源项目B");
    }

    private Long newProject(String title) {
        ProjectEntity project = new ProjectEntity();
        project.setTitle(title);
        project.setGenre(Genre.XUANHUAN);
        return projectRepository.save(project).getId();
    }

    private InspirationEntity inspiration(Long projectId, String title, String content) {
        InspirationEntity entity = new InspirationEntity();
        entity.setProjectId(projectId);
        entity.setTitle(title);
        entity.setContent(content);
        return inspirationRepository.save(entity);
    }

    @Test
    void v66MigrationCreatesTableAndListsNewestFirst() {
        inspiration(projectA, "第一条", "内容一");
        inspiration(projectA, "第二条", "内容二");
        inspiration(projectA, "第三条", "内容三");

        List<InspirationEntity> items = inspirationRepository.findByProjectIdOrderByCreatedAtDescIdDesc(projectA);
        assertThat(items).hasSize(3);
        assertThat(items.get(0).getTitle()).isEqualTo("第三条");
        assertThat(items.get(2).getTitle()).isEqualTo("第一条");
    }

    @Test
    void listIsIsolatedPerProject() {
        inspiration(projectA, "A 的灵感", "只属于 A");
        inspiration(projectB, "B 的灵感", "只属于 B");

        assertThat(inspirationRepository.findByProjectIdOrderByCreatedAtDescIdDesc(projectA))
                .extracting(InspirationEntity::getTitle)
                .containsExactly("A 的灵感");
        assertThat(inspirationRepository.findByProjectIdOrderByCreatedAtDescIdDesc(projectB))
                .extracting(InspirationEntity::getTitle)
                .containsExactly("B 的灵感");
    }

    @Test
    void findByIdAndProjectIdRejectsCrossProjectAccess() {
        Long id = inspiration(projectA, "A 的灵感", "内容").getId();

        assertThat(inspirationRepository.findByIdAndProjectId(id, projectA)).isPresent();
        // 换一个项目 id 查同一条记录必须查不到
        assertThat(inspirationRepository.findByIdAndProjectId(id, projectB)).isEmpty();
    }

    @Test
    void countByProjectIdOnlyCountsOwnProject() {
        inspiration(projectA, "一", null);
        inspiration(projectA, "二", null);
        inspiration(projectB, "三", null);

        assertThat(inspirationRepository.countByProjectId(projectA)).isEqualTo(2);
        assertThat(inspirationRepository.countByProjectId(projectB)).isEqualTo(1);
    }

    @Test
    void deleteByProjectIdRemovesOnlyTargetProjectAndFlushes() {
        inspiration(projectA, "一", null);
        inspiration(projectA, "二", null);
        inspiration(projectB, "三", null);

        int removed = inspirationRepository.deleteByProjectId(projectA);

        assertThat(removed).isEqualTo(2);
        assertThat(inspirationRepository.findByProjectIdOrderByCreatedAtDescIdDesc(projectA)).isEmpty();
        assertThat(inspirationRepository.findByProjectIdOrderByCreatedAtDescIdDesc(projectB)).hasSize(1);
        // 删除必须真正落库：紧接着的查询（走同一持久化上下文之外）不应再看到旧行
        assertThat(inspirationRepository.count()).isEqualTo(1);
    }

    @Test
    void contentPreviewFlattensAndTruncates() {
        InspirationEntity longOne = inspiration(projectA, "长内容",
                "第一行\n第二行\n" + "长".repeat(200));
        assertThat(longOne.getContentPreview()).doesNotContain("\n");
        assertThat(longOne.getContentPreview()).endsWith("…");

        InspirationEntity empty = inspiration(projectA, "空内容", "  ");
        assertThat(empty.getContentPreview()).isEmpty();
    }
}
