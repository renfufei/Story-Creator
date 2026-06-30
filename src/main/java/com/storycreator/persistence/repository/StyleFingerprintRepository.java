package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.StyleFingerprintEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

public interface StyleFingerprintRepository extends JpaRepository<StyleFingerprintEntity, Long> {
    Optional<StyleFingerprintEntity> findByProjectId(Long projectId);

    @Modifying
    @Transactional
    void deleteByProjectId(Long projectId);
}
