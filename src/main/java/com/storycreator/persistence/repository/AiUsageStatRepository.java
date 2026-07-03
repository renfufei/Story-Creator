package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.AiUsageStatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AiUsageStatRepository extends JpaRepository<AiUsageStatEntity, Long> {

    List<AiUsageStatEntity> findByProjectIdOrderByTotalDurationMsDesc(Long projectId);

    Optional<AiUsageStatEntity> findByProjectIdAndModelId(Long projectId, String modelId);

    void deleteByProjectId(Long projectId);

    /**
     * Atomically increment duration for an existing row.
     * Returns the number of rows updated (0 if no matching row exists).
     */
    @Modifying
    @Query("UPDATE AiUsageStatEntity a SET a.totalDurationMs = a.totalDurationMs + :durationMs " +
            "WHERE a.projectId = :projectId AND a.modelId = :modelId")
    int incrementDuration(@Param("projectId") Long projectId,
                          @Param("modelId") String modelId,
                          @Param("durationMs") long durationMs);
}
