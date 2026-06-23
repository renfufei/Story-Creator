package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.TtsExportTaskEntity;
import com.storycreator.tts.TtsExportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface TtsExportTaskRepository extends JpaRepository<TtsExportTaskEntity, Long> {

    List<TtsExportTaskEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<TtsExportTaskEntity> findAllByOrderByCreatedAtDesc();

    List<TtsExportTaskEntity> findByStatus(TtsExportStatus status);

    @Modifying
    @Transactional
    @Query("UPDATE TtsExportTaskEntity t SET t.status = :newStatus WHERE t.status = :oldStatus")
    int updateStatusByStatus(TtsExportStatus oldStatus, TtsExportStatus newStatus);
}
