package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.VolumeOutlineEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface VolumeOutlineRepository extends JpaRepository<VolumeOutlineEntity, Long> {
    List<VolumeOutlineEntity> findByProjectIdOrderByVolumeNumber(Long projectId);
    Optional<VolumeOutlineEntity> findByProjectIdAndVolumeNumber(Long projectId, int volumeNumber);

    @Modifying
    @Transactional
    void deleteByProjectId(Long projectId);
}
