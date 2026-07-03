package com.storycreator.persistence.repository;

import com.storycreator.core.domain.MaterialCategory;
import com.storycreator.persistence.entity.MaterialLibraryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialLibraryRepository extends JpaRepository<MaterialLibraryEntity, Long> {

    List<MaterialLibraryEntity> findAllByOrderByUpdatedAtDesc();

    List<MaterialLibraryEntity> findByCategoryOrderByUpdatedAtDesc(MaterialCategory category);

    List<MaterialLibraryEntity> findByIdIn(List<Long> ids);
}
