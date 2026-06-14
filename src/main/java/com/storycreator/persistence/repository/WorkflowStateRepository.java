package com.storycreator.persistence.repository;

import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.WorkflowStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

public interface WorkflowStateRepository extends JpaRepository<WorkflowStateEntity, Long> {
    Optional<WorkflowStateEntity> findByProjectIdAndStep(Long projectId, WorkflowStep step);
    List<WorkflowStateEntity> findByProjectId(Long projectId);

    @Modifying
    @Query("UPDATE WorkflowStateEntity w SET w.status = :newStatus WHERE w.status = :oldStatus")
    int updateStatusByStatus(StepStatus oldStatus, StepStatus newStatus);

    @Modifying
    @Transactional
    void deleteByProjectId(Long projectId);
}
