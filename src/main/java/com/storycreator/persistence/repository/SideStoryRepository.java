package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.SideStoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SideStoryRepository extends JpaRepository<SideStoryEntity, Long> {

    List<SideStoryEntity> findByProjectIdOrderBySortOrder(Long projectId);

    List<SideStoryEntity> findByProjectId(Long projectId);

    @Modifying
    @Transactional
    void deleteByProjectId(Long projectId);

    @Modifying
    @Transactional
    @Query("UPDATE SideStoryEntity s SET s.status = 'DRAFT' WHERE s.status = 'OUTLINE_READY' AND s.id NOT IN (SELECT DISTINCT c.sideStoryId FROM SideStoryChapterEntity c)")
    int cleanOrphanStatuses();
}
