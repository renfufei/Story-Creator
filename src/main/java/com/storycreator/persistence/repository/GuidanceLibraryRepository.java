package com.storycreator.persistence.repository;

import com.storycreator.core.domain.GuidanceLibraryStep;
import com.storycreator.persistence.entity.GuidanceLibraryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GuidanceLibraryRepository extends JpaRepository<GuidanceLibraryEntity, Long> {

    List<GuidanceLibraryEntity> findAllByOrderByUpdatedAtDesc();

    List<GuidanceLibraryEntity> findByStepOrderByUpdatedAtDesc(GuidanceLibraryStep step);
}
