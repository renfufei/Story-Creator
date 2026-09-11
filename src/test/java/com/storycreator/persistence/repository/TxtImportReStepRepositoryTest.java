package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.TxtImportReStepEntity;
import com.storycreator.txtimport.RePhase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 逆向工程流程控制表：迁移可用、唯一键生效、中断复位可用。
 * <p>复位是断点续跑的前提 —— 进程重启后必须能把「执行中」的步骤变回待执行。
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:re_step_test;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
class TxtImportReStepRepositoryTest {

    private static final long JOB = 1001L;
    private static final long OTHER_JOB = 1002L;

    @Autowired
    private TxtImportReStepRepository repository;

    private TxtImportReStepEntity step(long jobId, RePhase phase, String status, int done) {
        TxtImportReStepEntity s = new TxtImportReStepEntity();
        s.setJobId(jobId);
        s.setPhase(phase.name());
        s.setSortOrder(phase.getSortOrder());
        s.setTotalUnits(10);
        s.setCompletedUnits(done);
        s.setStatus(status);
        if (RePhase.Status.RUNNING.name().equals(status)) {
            s.setStartedAt(LocalDateTime.now());
        }
        return repository.save(s);
    }

    @Test
    void v65MigrationCreatesTableAndSortsBySortOrder() {
        step(JOB, RePhase.STORY_OUTLINE, "PENDING", 0);
        step(JOB, RePhase.CHAPTER_OUTLINE, "COMPLETED", 10);
        step(JOB, RePhase.STORY_ARC, "PENDING", 0);

        List<TxtImportReStepEntity> steps = repository.findByJobIdOrderBySortOrder(JOB);
        assertThat(steps).hasSize(3);
        assertThat(steps).extracting(TxtImportReStepEntity::getPhase)
                .containsExactly("CHAPTER_OUTLINE", "STORY_ARC", "STORY_OUTLINE");
    }

    @Test
    void jobAndPhasePairIsUnique() {
        step(JOB, RePhase.CHAPTER_OUTLINE, "PENDING", 0);
        assertThatThrownBy(() -> {
            step(JOB, RePhase.CHAPTER_OUTLINE, "PENDING", 0);
            repository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void resetRunningToPendingOnlyTouchesTargetJob() {
        step(JOB, RePhase.CHAPTER_OUTLINE, "RUNNING", 3);
        step(OTHER_JOB, RePhase.CHAPTER_OUTLINE, "RUNNING", 5);

        int reset = repository.resetRunningToPending(JOB);
        assertThat(reset).isEqualTo(1);

        assertThat(repository.findByJobIdAndPhase(JOB, RePhase.CHAPTER_OUTLINE.name())
                .orElseThrow().getStatus()).isEqualTo("PENDING");
        // 进度不能被抹掉：已完成的部分是断点续跑的依据
        assertThat(repository.findByJobIdAndPhase(JOB, RePhase.CHAPTER_OUTLINE.name())
                .orElseThrow().getCompletedUnits()).isEqualTo(3);
        assertThat(repository.findByJobIdAndPhase(OTHER_JOB, RePhase.CHAPTER_OUTLINE.name())
                .orElseThrow().getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void resetAllRunningToPendingCoversEveryJob() {
        step(JOB, RePhase.CHAPTER_OUTLINE, "RUNNING", 3);
        step(OTHER_JOB, RePhase.STORY_ARC, "RUNNING", 1);
        step(JOB, RePhase.WORLD, "COMPLETED", 1);

        assertThat(repository.resetAllRunningToPending()).isEqualTo(2);

        assertThat(repository.findByJobIdAndPhase(JOB, RePhase.CHAPTER_OUTLINE.name())
                .orElseThrow().getStatus()).isEqualTo("PENDING");
        assertThat(repository.findByJobIdAndPhase(OTHER_JOB, RePhase.STORY_ARC.name())
                .orElseThrow().getStatus()).isEqualTo("PENDING");
        // 已完成的不受影响
        assertThat(repository.findByJobIdAndPhase(JOB, RePhase.WORLD.name())
                .orElseThrow().getStatus()).isEqualTo("COMPLETED");
    }
}
