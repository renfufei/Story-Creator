package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.ChapterOutlineEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ChapterOutlineRepository extends JpaRepository<ChapterOutlineEntity, Long> {
    List<ChapterOutlineEntity> findByProjectIdOrderByChapterNumber(Long projectId);
    Optional<ChapterOutlineEntity> findByProjectIdAndChapterNumber(Long projectId, int chapterNumber);

    @Modifying
    @Transactional
    void deleteByProjectId(Long projectId);
}
