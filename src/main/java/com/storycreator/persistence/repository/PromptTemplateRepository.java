package com.storycreator.persistence.repository;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.PromptTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplateEntity, Long> {
    List<PromptTemplateEntity> findByStep(WorkflowStep step);
    Optional<PromptTemplateEntity> findByStepAndGenreAndIsDefaultTrue(WorkflowStep step, Genre genre);
    Optional<PromptTemplateEntity> findByStepAndGenreIsNullAndIsDefaultTrue(WorkflowStep step);

    Optional<PromptTemplateEntity> findByStepAndSubStepAndGenreAndIsDefaultTrue(WorkflowStep step, PromptSubStep subStep, Genre genre);
    Optional<PromptTemplateEntity> findByStepAndSubStepAndGenreIsNullAndIsDefaultTrue(WorkflowStep step, PromptSubStep subStep);
}
