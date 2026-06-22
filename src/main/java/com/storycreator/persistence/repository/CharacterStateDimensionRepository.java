package com.storycreator.persistence.repository;

import com.storycreator.core.domain.CharacterStateDimension;
import com.storycreator.persistence.entity.CharacterStateDimensionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CharacterStateDimensionRepository extends JpaRepository<CharacterStateDimensionEntity, Long> {

    List<CharacterStateDimensionEntity> findByProjectIdOrderBySortOrder(Long projectId);

    Optional<CharacterStateDimensionEntity> findByProjectIdAndDimKey(Long projectId, CharacterStateDimension dimKey);

    void deleteByProjectId(Long projectId);
}
