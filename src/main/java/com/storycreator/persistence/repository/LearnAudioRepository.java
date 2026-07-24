package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.LearnAudioEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LearnAudioRepository extends JpaRepository<LearnAudioEntity, Long> {

    List<LearnAudioEntity> findByModuleOrderByItemKey(String module);

    Optional<LearnAudioEntity> findByModuleAndItemKey(String module, String itemKey);

    List<LearnAudioEntity> findByModuleAndStatus(String module, String status);
}
