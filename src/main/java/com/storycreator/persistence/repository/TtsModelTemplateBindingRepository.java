package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.TtsModelTemplateBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TtsModelTemplateBindingRepository extends JpaRepository<TtsModelTemplateBindingEntity, Long> {

    List<TtsModelTemplateBindingEntity> findByModelConfigIdOrderBySortOrder(Long modelConfigId);

    Optional<TtsModelTemplateBindingEntity> findByModelConfigIdAndTemplateRef(Long modelConfigId, String templateRef);

    void deleteByModelConfigIdAndTemplateRef(Long modelConfigId, String templateRef);

    void deleteByTemplateRef(String templateRef);
}
