package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.CharacterImageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CharacterImageRepository extends JpaRepository<CharacterImageEntity, Long> {
    List<CharacterImageEntity> findByCharacterIdOrderByCreatedAtDesc(Long characterId);
    List<CharacterImageEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    void deleteByCharacterId(Long characterId);
}
