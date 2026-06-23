package com.storycreator.persistence.repository;

import com.storycreator.core.domain.StepStatus;
import com.storycreator.persistence.entity.ChapterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

public interface ChapterRepository extends JpaRepository<ChapterEntity, Long> {
    List<ChapterEntity> findByProjectIdOrderByChapterNumber(Long projectId);
    Optional<ChapterEntity> findByProjectIdAndChapterNumber(Long projectId, int chapterNumber);
    List<ChapterEntity> findByStatus(StepStatus status);

    @Query("SELECT c.projectId, COUNT(c), COALESCE(SUM(LENGTH(c.content)), 0) FROM ChapterEntity c WHERE c.content IS NOT NULL AND c.content <> '' GROUP BY c.projectId")
    List<Object[]> countAndWordCountByProject();

    @Modifying
    @Query("UPDATE ChapterEntity c SET c.status = :newStatus WHERE c.status = :oldStatus")
    int updateStatusByStatus(StepStatus oldStatus, StepStatus newStatus);

    @Modifying
    @Query("UPDATE ChapterEntity c SET c.proofreadFixStatus = :newStatus WHERE c.proofreadFixStatus = :oldStatus")
    int updateProofreadFixStatusByStatus(StepStatus oldStatus, StepStatus newStatus);

    @Modifying
    @Transactional
    void deleteByProjectId(Long projectId);
}
