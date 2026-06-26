package com.storycreator.persistence.repository;

import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.StepModelConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

public interface StepModelConfigRepository extends JpaRepository<StepModelConfigEntity, Long> {
    Optional<StepModelConfigEntity> findByProjectIdAndStep(Long projectId, WorkflowStep step);
    List<StepModelConfigEntity> findByProjectId(Long projectId);

    @Modifying
    @Transactional
    void deleteByProjectId(Long projectId);
}
