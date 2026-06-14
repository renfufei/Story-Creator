package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.StoryOutlineEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

public interface StoryOutlineRepository extends JpaRepository<StoryOutlineEntity, Long> {
    Optional<StoryOutlineEntity> findByProjectId(Long projectId);

    @Modifying
    @Transactional
    void deleteByProjectId(Long projectId);
}
