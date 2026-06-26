package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.TtsTextReplacementEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TtsTextReplacementRepository extends JpaRepository<TtsTextReplacementEntity, Long> {

    List<TtsTextReplacementEntity> findByEnabledTrueOrderBySortOrder();
}
