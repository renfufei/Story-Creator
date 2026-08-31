package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.TxtImportChapterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TxtImportChapterRepository extends JpaRepository<TxtImportChapterEntity, Long> {

    List<TxtImportChapterEntity> findByJobIdOrderByChapterNumber(Long jobId);

    Optional<TxtImportChapterEntity> findByJobIdAndChapterNumber(Long jobId, int chapterNumber);

    void deleteByJobId(Long jobId);

    int countByJobId(Long jobId);
}
