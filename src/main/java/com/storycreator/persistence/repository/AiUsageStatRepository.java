package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.AiUsageStatEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiUsageStatRepository extends JpaRepository<AiUsageStatEntity, Long> {

    List<AiUsageStatEntity> findByProjectIdOrderByTotalDurationMsDesc(Long projectId);

    Optional<AiUsageStatEntity> findByProjectIdAndModelId(Long projectId, String modelId);
}
