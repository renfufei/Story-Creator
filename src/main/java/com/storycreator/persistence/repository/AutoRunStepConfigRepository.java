package com.storycreator.persistence.repository;

import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.AutoRunStepConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AutoRunStepConfigRepository extends JpaRepository<AutoRunStepConfigEntity, Long> {

    List<AutoRunStepConfigEntity> findByProjectId(Long projectId);

    Optional<AutoRunStepConfigEntity> findByProjectIdAndStep(Long projectId, WorkflowStep step);
}
