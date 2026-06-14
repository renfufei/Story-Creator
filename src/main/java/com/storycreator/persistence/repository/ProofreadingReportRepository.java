package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.ProofreadingReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProofreadingReportRepository extends JpaRepository<ProofreadingReportEntity, Long> {
    List<ProofreadingReportEntity> findByProjectIdOrderByChapterNumber(Long projectId);
    Optional<ProofreadingReportEntity> findByProjectIdAndChapterNumber(Long projectId, int chapterNumber);
    void deleteByProjectId(Long projectId);
}
