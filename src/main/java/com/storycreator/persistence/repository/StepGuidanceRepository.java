package com.storycreator.persistence.repository;

import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.StepGuidanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

public interface StepGuidanceRepository extends JpaRepository<StepGuidanceEntity, Long> {
    Optional<StepGuidanceEntity> findByProjectIdAndStep(Long projectId, WorkflowStep step);
    List<StepGuidanceEntity> findByProjectId(Long projectId);

    @Modifying
    @Transactional
    void deleteByProjectId(Long projectId);
}
