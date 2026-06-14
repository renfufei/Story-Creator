package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.CharacterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CharacterRepository extends JpaRepository<CharacterEntity, Long> {
    List<CharacterEntity> findByProjectIdOrderBySortOrder(Long projectId);
    List<CharacterEntity> findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(Long projectId, int sortOrder);
    void deleteByProjectId(Long projectId);
}
