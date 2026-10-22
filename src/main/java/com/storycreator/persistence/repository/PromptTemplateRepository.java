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

    /**
     * 某一步骤下所有「默认」模板。
     * <p>一步骤可能有**多条**默认 —— 每个子步骤各一条（例如 {@code CHAPTER_WRITING} 同时有
     * {@code CHAPTER_WRITING_PRIMARY} 与 {@code CHAPTER_EXPANSION} 两条默认）。所以这里返回 List：
     * 按步骤用 {@code Optional} 查询的方法一旦碰上两条就会抛
     * {@code NonUniqueResultException}，整个模板列表接口 500，正文生成也会跟着崩。
     */
    List<PromptTemplateEntity> findAllByStepAndIsDefaultTrue(WorkflowStep step);

    // 注意：不要加「按 step 找唯一默认」的 Optional 方法 —— 一步骤本来就可能有多条默认
    // （每个子步骤一条），单条查询必抛 NonUniqueResultException。要按步骤取默认请用上面的 List 版。

    Optional<PromptTemplateEntity> findByStepAndSubStepAndGenreAndIsDefaultTrue(WorkflowStep step, PromptSubStep subStep, Genre genre);
    Optional<PromptTemplateEntity> findByStepAndSubStepAndGenreIsNullAndIsDefaultTrue(WorkflowStep step, PromptSubStep subStep);
}
