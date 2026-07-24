package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.SideStoryChapterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface SideStoryChapterRepository extends JpaRepository<SideStoryChapterEntity, Long> {

    List<SideStoryChapterEntity> findBySideStoryIdOrderByChapterNumber(Long sideStoryId);

    Optional<SideStoryChapterEntity> findBySideStoryIdAndChapterNumber(Long sideStoryId, int chapterNumber);

    List<SideStoryChapterEntity> findByProjectIdOrderByChapterNumber(Long projectId);

    @Modifying
    @Transactional
    void deleteBySideStoryId(Long sideStoryId);

    @Modifying
    @Transactional
    void deleteByProjectId(Long projectId);

    @Modifying
    @Transactional
    @Query("UPDATE SideStoryChapterEntity c SET c.status = 'NOT_STARTED' WHERE c.status = 'GENERATING'")
    int resetGeneratingStatuses();

    int countBySideStoryId(Long sideStoryId);
}
