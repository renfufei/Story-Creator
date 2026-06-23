package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.TtsExportChapterEntity;
import com.storycreator.tts.TtsExportChapterStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface TtsExportChapterRepository extends JpaRepository<TtsExportChapterEntity, Long> {

    List<TtsExportChapterEntity> findByTaskIdOrderByChapterNumber(Long taskId);

    Optional<TtsExportChapterEntity> findByTaskIdAndChapterNumber(Long taskId, int chapterNumber);

    List<TtsExportChapterEntity> findByProjectIdAndStatus(Long projectId, TtsExportChapterStatus status);

    @Transactional
    void deleteByTaskId(Long taskId);
}
