package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.WritingRulesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

public interface WritingRulesRepository extends JpaRepository<WritingRulesEntity, Long> {
    Optional<WritingRulesEntity> findByProjectId(Long projectId);

    @Modifying
    @Transactional
    void deleteByProjectId(Long projectId);
}
