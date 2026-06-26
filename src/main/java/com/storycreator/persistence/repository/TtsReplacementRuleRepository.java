package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.TtsReplacementRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TtsReplacementRuleRepository extends JpaRepository<TtsReplacementRuleEntity, Long> {

    List<TtsReplacementRuleEntity> findByTemplateIdOrderBySortOrder(Long templateId);
}
