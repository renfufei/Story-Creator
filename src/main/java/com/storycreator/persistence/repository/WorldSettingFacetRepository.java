package com.storycreator.persistence.repository;

import com.storycreator.core.domain.WorldFacetKey;
import com.storycreator.persistence.entity.WorldSettingFacetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorldSettingFacetRepository extends JpaRepository<WorldSettingFacetEntity, Long> {
    Optional<WorldSettingFacetEntity> findByProjectIdAndFacetKey(Long projectId, WorldFacetKey facetKey);
    List<WorldSettingFacetEntity> findByProjectId(Long projectId);
    void deleteByProjectId(Long projectId);
}
