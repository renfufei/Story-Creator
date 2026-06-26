package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.TtsReplacementTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TtsReplacementTemplateRepository extends JpaRepository<TtsReplacementTemplateEntity, Long> {

    List<TtsReplacementTemplateEntity> findAllByOrderBySortOrder();

    List<TtsReplacementTemplateEntity> findByEnabledTrueOrderBySortOrder();
}
