package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.WorldSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

public interface WorldSettingRepository extends JpaRepository<WorldSettingEntity, Long> {
    Optional<WorldSettingEntity> findByProjectId(Long projectId);

    @Modifying
    @Transactional
    void deleteByProjectId(Long projectId);
}
